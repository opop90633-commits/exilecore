package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三部分（三）召喚物：設定、召喚物的屬性表只吃主人的「召喚物…」、輔助寶石、數量上限（純邏輯的部分）。 */
class MinionTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
    }

    private static SkillBuild build(final String skill, final int gemLevel, final StatSheet sheet, final SupportDefinition... supports) {
        return SkillBuild.of(config, config.skill(skill), gemLevel, 100, false, List.of(supports), Set.of(Tags.WAND), sheet);
    }

    private static MinionSheets.Base base(final int level) {
        final double[] damage = monsters.damageAt(level);
        return new MinionSheets.Base(monsters.lifeAt(level), monsters.accuracyAt(level), monsters.evasionAt(level),
                monsters.armourAt(level), damage[0], damage[1], monsters.critChance());
    }

    @Test
    void summonSkillsParse() {
        // 2026-10 PoE2：喚屍改成 20 秒的暫時召喚（最多 8–10 隻）；骸骨兵改成精魂召喚（永久）
        final SkillDefinition zombie = config.skill("raise_zombie");
        assertEquals(Mechanic.SUMMON, zombie.mechanic());
        assertNotNull(zombie.summon());
        assertEquals("ZOMBIE", zombie.summon().kind());
        assertTrue(zombie.summon().corpse(), "殭屍要屍體");
        assertFalse(zombie.summon().permanent());
        assertEquals(20, zombie.summon().duration(), 1e-9);
        assertEquals(10, zombie.summon().max().intAt(1), "2026-10-26 照 PoE2：一律最多 10 隻");
        assertEquals(10, zombie.summon().max().intAt(20));
        assertTrue(zombie.tags().contains(Tags.MINION));
        assertTrue(zombie.tags().contains(Tags.MELEE), "召喚物用近戰打：近戰的輔助可以掛");
        assertTrue(zombie.tags().contains(Tags.DURATION), "有持續時間：持久輔助可以掛");
        assertFalse(zombie.dealsDamage(), "技能自己不造成傷害");
        assertNull(zombie.damage());

        final SkillDefinition skeletons = config.skill("summon_skeletons");
        assertEquals(2, skeletons.summon().count());
        assertTrue(skeletons.summon().permanent());
        assertFalse(skeletons.tags().contains(Tags.DURATION), "永久的沒有持續時間標籤");
        assertEquals("IRON_SWORD", skeletons.summon().weapon());

        final SkillDefinition spirits = config.skill("raging_spirits");
        assertTrue(spirits.summon().flying());
        assertEquals(0.7, spirits.conversion().get(DamageType.FIRE), 1e-9, "焚魂七成轉火焰");
        assertTrue(spirits.summon().resists().isEmpty(), "2026-10-26 照 PoE2：焚魂沒有抗性");

        // 魔像拿掉了（2026-10）：固定隻數、會嘲諷的換成狼伴，打中連周圍一起打的是骸骨重卒
        final SkillDefinition wolf = config.skill("beast_companion");
        assertFalse(wolf.summon().fixedMax(), "2026-10-17：狼伴吃 +x 召喚物（每隻保留 30 精魂）");
        // 2026-10-26 照 PoE2：狼伴、骸骨重卒沒有吼叫引怪，重卒的普攻只打一個（範圍是碎骨的震波）；這次之前 taunt 6、radius 2.0
        assertEquals(0, wolf.summon().taunt(), 1e-9);
        assertEquals("狼", wolf.summon().kindName());
        assertEquals(0, config.skill("skeletal_brute").summon().radius(), 1e-9);
        // 有一隻在的時候加在主人身上的 buff：現在的技能沒有用到，參數還在
        final SkillDefinition golem = SkillDefinition.parse("test", tw.exilecore.core.config.YamlNode.load("測試", new java.io.StringReader("""
                name: 測試
                type: spell
                mechanic: summon
                summon: { kind: IRON_GOLEM, max: 1, fixed_max: true, buff: [ { stat: damage, type: INCREASED, value: 20 } ] }
                visual: flame_golem
                """)), SkillConfig.VISUALS);
        assertEquals(1, golem.summon().buff().size());
        assertEquals(StatKeys.DAMAGE, golem.summon().buff().getFirst().stat());
        assertEquals(0.2, golem.summon().buff().getFirst().value(), 1e-9);
        assertEquals("魔像", golem.summon().kindName());
        assertNull(config.skill("fireball").summon());
    }

    @Test
    void monsterLevelFollowsGemRequirement() {
        assertEquals(1, MinionSheets.monsterLevel(config, 1));
        assertEquals(config.requiredLevel(10), MinionSheets.monsterLevel(config, 10));
        assertEquals(90, MinionSheets.monsterLevel(config, 20), "2026-10-26：需求等級照 PoE2，20 級要 90 級");
        assertEquals(90 + 4 * 3, MinionSheets.monsterLevel(config, 23), "20 級以上每級加 4");
    }

    @Test
    void minionSheetOnlyTakesMinionModifiersFromTheOwner() {
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.50, Set.of(), "主人的通用傷害"));
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.40, Set.of(Tags.SPELL), "主人的法術傷害"));
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.30, Set.of(Tags.MINION), "召喚物傷害"));
        owner.add(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 0.20, Set.of(Tags.MINION), "召喚物生命"));
        owner.add(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 1.00, Set.of(), "主人的生命"));
        owner.add(new Modifier(StatKeys.ATTACK_SPEED, ModType.INCREASED, 0.10, Set.of(Tags.MINION), "召喚物攻速"));
        final List<Modifier> copied = MinionSheets.ownerModifiers(owner);
        assertEquals(3, copied.size(), "只有帶 minion 標籤的");

        final SkillBuild zombie = build("raise_zombie", 10, owner);
        final SummonSpec spec = zombie.definition().summon();
        final int level = MinionSheets.monsterLevel(config, zombie.level());
        final MinionSheets.Base base = base(level);
        final StatSheet sheet = MinionSheets.build(spec, base, copied);
        final SkillProfile profile = MinionSheets.profile(zombie);
        final StatSheet effective = profile.effective(sheet);

        assertEquals(base.life() * spec.life() * 1.2, MinionSheets.life(effective), 1e-6, "生命 = 怪物生命 × 倍率 ×（1 + 召喚物生命 20%）");
        assertEquals("raise_zombie.minion", profile.id());
        assertTrue(profile.isAttack());
        assertFalse(profile.isSpell());
        assertTrue(profile.tags().containsAll(Set.of(Tags.MINION, Tags.MELEE, Tags.PHYSICAL)));
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, profile);
        final double baseAverage = (base.damageMin() + base.damageMax()) / 2 * spec.damage();
        assertEquals(baseAverage * 1.3, expected.get(DamageType.PHYSICAL), 1e-6, "傷害只吃「召喚物傷害 30%」，不吃主人的 50%、法術 40%");
        assertEquals(spec.attackSpeed() * 1.1, MinionSheets.attacksPerSecond(effective, profile), 1e-9);
        assertEquals(Math.round(20 / (spec.attackSpeed() * 1.1)), MinionSheets.attackTicks(MinionSheets.attacksPerSecond(effective, profile)));
        assertEquals(1.0, MinionSheets.movementScale(effective), 1e-9);
        assertEquals(0, MinionSheets.regenPercent(effective), 1e-9);
        assertEquals(base.accuracy() * MinionSheets.ACCURACY_BONUS, sheet.get(StatKeys.ACCURACY), 1e-9);
    }

    @Test
    void supportsStackOnTheMinionNotTheOwner() {
        final StatSheet owner = new StatSheet();
        final SupportDefinition damage = config.support("minion_damage");
        final SupportDefinition life = config.support("minion_life");
        final SupportDefinition speed = config.support("minion_speed");
        final SupportDefinition rage = config.support("rage");
        final SupportDefinition echo = config.support("spell_echo");
        final SupportDefinition totem = new SupportDefinition("spell_totem", "圖騰施法", "", "NAUTILUS_SHELL", 2, Set.of(Tags.SPELL),
                Set.of(Tags.MOVEMENT, Tags.MARK, Tags.CORPSE, Tags.TOTEM, Tags.BELL, Tags.TRAP, Tags.MINION, Tags.AURA, Tags.WARCRY, Tags.PERSISTENT), 1.5,
                List.of(Modifier.flat(StatKeys.FLAG_SPELL_TOTEM, 1, "圖騰施法")), List.of()); // 2026-10：圖騰施法改成主要寶石，開關還在
        final SupportDefinition duration = config.support("prolonged_duration"); // 2026-10-27 持久併入長留（只留最高階）
        assertTrue(damage.supports(config.skill("raise_zombie")));
        assertFalse(damage.supports(config.skill("fireball")), "兇猛召喚只能掛召喚技能");
        assertFalse(rage.supports(config.skill("raise_zombie")), "近戰的輔助排除召喚物的不行（蠻力 2026-10 拿掉了）");
        assertFalse(echo.supports(config.skill("raise_zombie")), "雙詠不能掛召喚");
        assertFalse(totem.supports(config.skill("raise_zombie")), "圖騰施法不能掛召喚");
        assertFalse(duration.supports(config.skill("summon_skeletons")), "永久的召喚物沒有持續時間");
        assertTrue(duration.supports(config.skill("raise_zombie")));

        final SkillBuild plain = build("raise_zombie", 10, owner);
        final SkillBuild boosted = build("raise_zombie", 10, owner, damage, life, speed, totem, echo);
        assertEquals(3, boosted.supports().size(), "圖騰施法、雙詠不算");
        assertNull(boosted.deploy());
        assertFalse(boosted.echo());
        final SummonSpec spec = boosted.definition().summon();
        final MinionSheets.Base base = base(MinionSheets.monsterLevel(config, boosted.level()));
        final SkillProfile plainProfile = MinionSheets.profile(plain);
        final SkillProfile boostedProfile = MinionSheets.profile(boosted);
        final StatSheet plainSheet = MinionSheets.build(spec, base, List.of());
        final StatSheet boostedSheet = MinionSheets.build(spec, base, List.of());
        final double plainDamage = DamageCalculator.expectedOutgoing(plainSheet, plainProfile).total();
        final double boostedDamage = DamageCalculator.expectedOutgoing(boostedSheet, boostedProfile).total();
        assertEquals(plainDamage * 1.3 * 0.8, boostedDamage, 1e-6, "2026-10-26 照 PoE2 基準：兇猛召喚更多 30%、堅韌召喚更少 20%");
        final StatSheet effective = boostedProfile.effective(boostedSheet);
        assertEquals(MinionSheets.life(plainProfile.effective(plainSheet)) * 1.4 * 0.85, MinionSheets.life(effective), 1e-6, "2026-10-26：堅韌召喚生命更多 40%、兇猛召喚更少 15%");
        assertEquals(1.0, MinionSheets.regenPercent(effective), 1e-9, "2026-10-26：回復 1.5% → 1%");
        assertEquals(spec.attackSpeed() * 1.2, MinionSheets.attacksPerSecond(effective, boostedProfile), 1e-9, "2026-10-26：迅捷召喚改成增加 20%");
        assertEquals(1.25, MinionSheets.movementScale(effective), 1e-9, "2026-10-26：移動速度增加 25%");
        assertTrue(boosted.manaCost() >= plain.manaCost(), "魔力倍率照掛（2026-10-26 照 PoE2 這三顆都是 100%）");
        // 主人自己的屬性表不受影響（輔助只在技能描述的 local）
        assertEquals(0, owner.allModifiers().size());
    }

    @Test
    void minionCapAndDurationScale() {
        final StatSheet owner = new StatSheet();
        assertEquals(10, build("raise_zombie", 1, owner).minions());
        assertEquals(10, build("raise_zombie", 20, owner).minions());
        assertEquals(10, build("raging_spirits", 20, owner).minions());
        assertEquals(1, build("beast_companion", 20, owner).minions());
        owner.add(new Modifier(StatKeys.SKILL_MINIONS, ModType.FLAT, 1, Set.of(Tags.MINION), "不死軍團"));
        assertEquals(11, build("raise_zombie", 20, owner).minions(), "+1 召喚物數量");
        assertEquals(2, build("beast_companion", 20, owner).minions(), "狼伴也吃 +1 召喚物（2026-10-17 拿掉固定隻數）");
        assertEquals(0, build("fireball", 20, owner).minions());
        final SkillBuild skeletons = build("raise_zombie", 20, owner, config.support("prolonged_duration")); // 2026-10-27 持久併入長留
        assertEquals(Math.round(20 * 20 * skeletons.durationScale()), skeletons.durationTicks(skeletons.definition().summon().duration()));
        assertTrue(skeletons.durationScale() > 1.0);
    }

    @Test
    void summaryListsOnlyMinionModifiers() {
        final StatSheet owner = new StatSheet();
        assertTrue(MinionSheets.summary(owner).isEmpty());
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.5, Set.of(), "主人的"));
        assertTrue(MinionSheets.summary(owner).isEmpty(), "沒有召喚物的加成就不列");
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.3, Set.of(Tags.MINION), "召喚物傷害"));
        owner.add(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 0.2, Set.of(Tags.MINION), "召喚物生命"));
        owner.add(new Modifier(StatKeys.SKILL_MINIONS, ModType.FLAT, 1, Set.of(Tags.MINION), "不死軍團"));
        final List<String> lines = MinionSheets.summary(owner);
        assertEquals(List.of("召喚物傷害：增加 30%", "召喚物最大生命：增加 20%", "召喚物數量上限 +1"), lines);
    }

    @Test
    void modifierTextKnowsMinionStats() {
        assertEquals("35% 更多召喚物傷害", ModifierText.describe(new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.35, Set.of(Tags.MINION), "")));
        assertEquals("40% 更多召喚物最大生命", ModifierText.describe(new Modifier(StatKeys.LIFE_MAX, ModType.MORE, 0.4, Set.of(Tags.MINION), "")));
        assertEquals("25% 更多召喚物攻擊速度", ModifierText.describe(new Modifier(StatKeys.ATTACK_SPEED, ModType.MORE, 0.25, Set.of(Tags.MINION), "")));
        assertEquals("增加 30% 召喚物移動速度", ModifierText.describe(new Modifier(StatKeys.MOVEMENT_SPEED, ModType.INCREASED, 0.3, Set.of(Tags.MINION), "")));
        assertEquals("召喚物每秒回復 1.5% 最大生命", ModifierText.describe(new Modifier(StatKeys.LIFE_REGEN_PERCENT, ModType.FLAT, 1.5, Set.of(Tags.MINION), "")));
        assertEquals("同時最多多 1 隻召喚物", ModifierText.describe(new Modifier(StatKeys.SKILL_MINIONS, ModType.FLAT, 1, Set.of(Tags.MINION), "")));
        assertEquals("增加 10% 攻擊速度", ModifierText.describe(new Modifier(StatKeys.ATTACK_SPEED, ModType.INCREASED, 0.1, Set.of(), "")), "沒標籤的照舊");
    }

    @Test
    void minionHitsRollAccuracyAgainstMonsters() {
        final StatSheet owner = new StatSheet();
        final SkillBuild zombie = build("raise_zombie", 20, owner);
        final int level = MinionSheets.monsterLevel(config, zombie.level());
        final StatSheet sheet = MinionSheets.build(zombie.definition().summon(), base(level), List.of());
        final SkillProfile profile = MinionSheets.profile(zombie);
        final StatSheet monster = new StatSheet();
        monster.setBase(StatKeys.EVASION, monsters.evasionAt(level));
        monster.setBase(StatKeys.ARMOUR, monsters.armourAt(level));
        monster.setBase(StatKeys.RESIST_MAX, 75);
        int hits = 0;
        double dealt = 0;
        final Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            final var result = DamageCalculator.calculate(sheet, profile, monster, 0, random);
            if (result.hit()) {
                hits++;
                dealt += result.total();
            }
        }
        // 2026-10 PoE2 命中公式（命中 × 1.25 ÷ (命中 + 閃避 × 0.3)）：打同等級普通怪幾乎都打得中
        final double chance = tw.exilecore.stats.Formulas.hitChance(sheet.get(StatKeys.ACCURACY), monster.get(StatKeys.EVASION));
        assertTrue(chance > 0.95, "命中率：" + chance);
        assertTrue(hits > 1900, "幾乎都打得中：" + hits);
        // 2026-10-26：召喚物照 PoE2（ally 表）之後跟怪物曲線脫鉤，這裡只確認打得出傷害、不超過同等級怪物一下的上限
        assertTrue(dealt / hits > 0 && dealt / hits < monsters.damageAt(level)[1], "每下：" + dealt / hits);
    }

    /** 2026-10-26：召喚物照 PoE2——召喚物等級 = 寶石等級 × 2，用 PoE2 的召喚物表，換算成我們的怪物時「挨幾下、幾下打死」跟 PoE2 一樣。 */
    @Test
    void minionsFollowPoe2AllyTables() {
        assertEquals(2, MinionSheets.allyLevel(1));
        assertEquals(40, MinionSheets.allyLevel(20));
        assertEquals(40, MinionSheets.allyLevel(23), "超過 20 級不加召喚物等級（照 extraScale）");
        final SummonSpec warrior = config.skill("summon_skeletons").summon();
        assertTrue(warrior.ally());
        // 20 級寶石：區域 90 級；PoE2 骸骨戰士 2490 × 0.88 生命、204.04 × 0.70 傷害，90 級怪 41748 生命、每下 443.79
        // （2026-10-27 照 PoE2 逐級表；這次之前 PoE2 的表只到 83 級，拿 83 級的 33945、364 比，生命、傷害多了兩成多）
        final MinionSheets.Base base = MinionSheets.base(config, warrior, 20, 0, monsters::lifeAt, monsters::damageAt,
                monsters::accuracyAt, monsters::evasionAt, monsters::armourAt, monsters.critChance());
        final StatSheet sheet = MinionSheets.build(warrior, base, List.of());
        final double[] hit = monsters.damageAt(90);
        final double monsterHit = (hit[0] + hit[1]) / 2;
        assertEquals(2490 * 0.88 / 443.79, MinionSheets.life(sheet) / monsterHit, 0.01, "挨幾下同等級普通怪會死：跟 PoE2 一樣");
        assertEquals(2490 * 0.88, MinionSheets.life(sheet), 2490 * 0.88 * 0.02, "怪物表照 PoE2，生命就是原作的絕對值（差不到 2%）");
        final double average = (base.damageMin() + base.damageMax()) / 2 * warrior.damage();
        assertEquals(41748 / (204.04 * 0.70), monsters.lifeAt(90) / average, 0.1, "幾下打死一隻同等級普通怪：跟 PoE2 一樣");
        assertEquals(30, sheet.get(StatKeys.BLOCK_CHANCE), 1e-9, "骸骨戰士 30% 格擋");
        assertTrue(StatKeys.flag(sheet, StatKeys.FLAG_ALWAYS_HIT), "PoE2 的召喚物一定命中");
        assertEquals(MinionSheets.ALLY_CRIT, base.critChance(), 1e-9);
        assertTrue(sheet.get(StatKeys.ARMOUR) > 0, "骸骨戰士有護甲（0.5 倍）");
        final StatSheet sniper = MinionSheets.build(config.skill("skeletal_archers").summon(), base, List.of());
        assertEquals(0, sniper.get(StatKeys.ARMOUR), 1e-9, "PoE2 沒寫護甲倍率的召喚物沒有護甲");
        assertEquals(50, MinionSheets.build(config.skill("skeletal_pyromancer").summon(), base, List.of()).get(DamageType.FIRE.mitigationStat()), 1e-9);
        // 每一隻的倍率（Path of Building 的 Minions.lua）：攻擊速度 = 1 ÷ 攻擊時間
        assertEquals(2.0, config.skill("skeletal_brute").summon().damage(), 1e-9);
        assertEquals(1 / 1.75, config.skill("skeletal_brute").summon().attackSpeed(), 0.001);
        assertEquals(0.25, config.skill("raging_spirits").summon().life(), 1e-9);
        assertEquals(1.4, config.skill("raging_spirits").summon().attackSpeed(), 1e-9, "焚魂攻擊速度更多 40%");
        // 精魂（poe2db 逐級表）、上限、持續時間
        final StatSheet owner = new StatSheet();
        assertEquals(60, config.skill("summon_skeletons").aura().perMinion().at(1), 1e-9);
        assertEquals(22, config.skill("summon_skeletons").aura().perMinion().at(10), 1e-9);
        assertEquals(48, config.skill("skeletal_brute").aura().perMinion().at(20), 1e-9);
        assertEquals(60, config.skill("wolf_pack").aura().reserve(), 1e-9, "灰狼隨整群 60 精魂");
        assertEquals(3, build("wolf_pack", 1, owner).minions());
        assertEquals(6, build("wolf_pack", 20, owner).minions());
        // 2026-10-27 照 PoE2 Unearth：掘骸（bone_raise）併進 bone_spikes，骨構體是隱藏的 unearth_construct
        assertEquals(8, build("unearth_construct", 1, owner).minions());
        assertEquals(20, build("unearth_construct", 20, owner).minions());
        assertEquals(15, config.skill("unearth_construct").summon().duration(), 1e-9);
        assertNull(config.skill("bone_raise"));
        final SkillBuild swarm = build("ravenous_swarm", 20, owner);
        assertEquals(3, swarm.minions());
        assertEquals(6, swarm.definition().summon().interval(), 1e-9);
        assertEquals(9.8, swarm.definition().summon().duration() * swarm.durationScale(), 0.01, "每群 20 級活 9.8 秒");
        assertEquals(30, config.skill("ravenous_swarm").aura().reserve(), 1e-9);
        // 強化殭屍：擊中傷害更多 100% → 253%
        assertEquals(2.53, MinionService.empowered(20).get(1).value(), 1e-9);
        assertEquals(1.0, MinionService.empowered(1).get(1).value(), 1e-9);
    }

    /** 2026-10-26 照 PoE2 第二輪：能量護盾、附魂術與夥伴的精魂、狼伴的更多傷害、指令、祭司、激怒、暴擊加成。 */
    @Test
    void minionsFollowPoe2SkillsAndCommands() {
        // 能量護盾：生命的一部分移到護盾（焰術士 10%、霜術士／雷術士／祭司 15%）
        assertEquals(0.1, config.skill("skeletal_pyromancer").summon().es(), 1e-9);
        assertEquals(0.15, config.skill("skeletal_frostcaller").summon().es(), 1e-9);
        assertEquals(0.15, config.skill("skeletal_stormcaller").summon().es(), 1e-9);
        assertEquals(0.15, config.skill("skeletal_cleric").summon().es(), 1e-9);
        assertEquals(0, config.skill("summon_skeletons").summon().es(), 1e-9);
        final SummonSpec frost = config.skill("skeletal_frostcaller").summon();
        final MinionSheets.Base base = MinionSheets.base(config, frost, 20, 0, monsters::lifeAt, monsters::damageAt,
                monsters::accuracyAt, monsters::evasionAt, monsters::armourAt, monsters.critChance());
        final StatSheet sheet = MinionSheets.build(frost, base, List.of());
        final double total = base.life() * frost.life();
        assertEquals(total * 0.85, MinionSheets.life(sheet), 1e-6);
        assertEquals(total * 0.15, MinionSheets.energyShield(sheet), 1e-6);
        assertEquals(0, MinionSheets.energyShield(MinionSheets.build(config.skill("summon_skeletons").summon(), base, List.of())), 1e-9);
        assertEquals(0.125, MinionAbilities.ES_RECHARGE_PER_SECOND, 1e-9);
        assertEquals(80, MinionAbilities.ES_DELAY_TICKS);
        // 召喚物暴擊傷害加成 +100%（怪物 30 + 召喚物 70）
        assertEquals(170, sheet.get(StatKeys.CRIT_MULTI), 1e-9); // 2026-10-27 照 PoE2 PlayerMinionIntrinsicStats：暴擊加成 +70%（這次之前 200）
        // 附魂術：精魂召喚、每隻 floor(50 × P^0.75)、傷害 × 1.25、沒有數量上限
        final SkillDefinition spectre = config.skill("bound_spectre");
        assertEquals(Mechanic.AURA, spectre.mechanic());
        assertEquals(50, spectre.aura().perMinion().at(1), 1e-9);
        assertTrue(spectre.summon().spectre());
        assertFalse(spectre.summon().corpse(), "附身之後一直叫，不用每次一具屍體");
        assertEquals(1.25, spectre.summon().damage(), 1e-9);
        assertEquals(40, spectre.summon().max().intAt(20));
        assertEquals(38, MinionAbilities.spectreSpirit(0.7));
        assertEquals(50, MinionAbilities.spectreSpirit(1.0));
        assertEquals(67, MinionAbilities.spectreSpirit(1.5));
        assertEquals(84, MinionAbilities.spectreSpirit(2.0));
        assertEquals(105, MinionAbilities.spectreSpirit(2.7));
        final SummonSpec ravager = spectre.summon().withMultipliers(4.0, 1.25 * 1.8);
        assertEquals(4.0, ravager.life(), 1e-9);
        assertEquals(2.25, ravager.damage(), 1e-9);
        // 荒原狼伴：最大精魂的 floor(100 × √P) × 0.3 %、傷害 × 1.25、更多 8 + 4 ×（等級 − 1）%
        assertEquals(30, MinionAbilities.companionSpiritPercent(1.0), 1e-9);
        assertEquals(36.6, MinionAbilities.companionSpiritPercent(1.5), 1e-9);
        assertEquals(31.2, MinionAbilities.companionSpiritPercent(1.1), 1e-9);
        assertEquals(0.875, config.skill("beast_companion").summon().damage(), 1e-9);
        final StatSheet owner = new StatSheet();
        final SkillBuild beast20 = build("beast_companion", 20, owner);
        final SkillBuild beast1 = build("beast_companion", 1, owner);
        final Set<String> minion = Set.of(Tags.MINION, Tags.ATTACK, Tags.MELEE);
        final StatSheet effective20 = new StatSheet().addAll(MinionSheets.stripMinion(MinionSheets.profile(beast20).local()));
        final StatSheet effective1 = new StatSheet().addAll(MinionSheets.stripMinion(MinionSheets.profile(beast1).local()));
        assertEquals(1.84, effective20.more(StatKeys.DAMAGE, minion), 1e-9, "20 級更多 84%");
        assertEquals(1.08, effective1.more(StatKeys.DAMAGE, minion), 1e-9, "1 級更多 8%");
        // 指令：冷卻、魔力
        assertEquals(8, MinionAbilities.command("skeletal_brute").cooldown(), 1e-9);
        assertEquals(83, MinionAbilities.command("skeletal_brute").manaAt(20));
        assertEquals(6, MinionAbilities.command("skeletal_archers").cooldown(), 1e-9);
        assertEquals(3, MinionAbilities.command("skeletal_pyromancer").cooldown(), 1e-9);
        assertEquals(5, MinionAbilities.command("skeletal_stormcaller").cooldown(), 1e-9);
        assertEquals(155, MinionAbilities.command("skeletal_stormcaller").manaAt(20));
        assertEquals(173, MinionAbilities.command("skeletal_frostcaller").manaAt(25), "超過 20 級照 20 級");
        assertEquals(4, MinionAbilities.command("skeletal_reaver").cooldown(), 1e-9);
        assertEquals(10, MinionAbilities.command("spiraling_conspiracy").cooldown(), 1e-9);
        assertNull(MinionAbilities.command("summon_skeletons"), "骸骨戰士沒有指令");
        assertNull(MinionAbilities.command("skeletal_cleric"), "祭司沒有指令");
        assertEquals(2.0, config.skill("skeletal_stormcaller").castTime(), 1e-9, "按鍵下指令用指令的施放時間");
        // 法術的傷害表、祭司的回復（固定值）
        assertEquals(551, MinionAbilities.range(MinionAbilities.STORM_LIGHTNING, 20)[0], 1e-9);
        assertEquals(3121, MinionAbilities.range(MinionAbilities.STORM_LIGHTNING, 20)[1], 1e-9);
        assertEquals(408, MinionAbilities.range(MinionAbilities.ARC_LIGHTNING, 40)[1], 1e-9);
        assertEquals(27243, MinionAbilities.HEAL_PER_MINUTE[MinionAbilities.row(20)]);
        assertEquals(0, config.skill("skeletal_cleric").summon().heal(), 1e-9, "不再是每秒回最大生命的百分比");
        final double[] factors = MinionSheets.poe2Factors(config, 20, 0, monsters::lifeAt, monsters::damageAt);
        assertEquals((base.damageMin() + base.damageMax()) / 2, MinionSheets.poe2AllyDamage(40) * factors[0], 1e-6,
                "法術傷害跟召喚物的武器傷害同一套換算");
        // 激怒：1% × 1.2^經過的秒數
        assertEquals(0.01, MinionAbilities.enrageDegen(0.5), 1e-12);
        assertEquals(0.012, MinionAbilities.enrageDegen(1), 1e-12);
        assertEquals(0.01 * Math.pow(1.2, 9), MinionAbilities.enrageDegen(9.9), 1e-12);
        // 蟲群擊中一定中毒、焰術士 70% 轉火焰 1.6 公尺、焚魂沒有點燃機率
        assertTrue(config.skill("ravenous_swarm").modifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.POISON_CHANCE) && m.value() == 100));
        assertEquals(0.7, config.skill("skeletal_pyromancer").conversion().get(DamageType.FIRE), 1e-9);
        assertEquals(1.6, config.skill("skeletal_pyromancer").summon().radius(), 1e-9);
        assertTrue(config.skill("raging_spirits").modifiers().stream().noneMatch(m -> m.stat().equals(StatKeys.IGNITE_CHANCE)));
        // 復活時間：骷髏、附身、狼伴 12 秒，灰狼隨 7.5 秒
        assertEquals(240, AuraService.respawnTicks("bound_spectre"));
        assertEquals(240, AuraService.respawnTicks("beast_companion"));
        assertEquals(150, AuraService.respawnTicks("wolf_pack"));
        assertEquals(240, AuraService.respawnTicks("skeletal_cleric"));
        // 感電地面（死亡風暴）
        assertNotNull(config.skill(MinionService.SHOCKED_GROUND));
    }

    @Test
    void brokenSummonBlocksAreRejected() throws IOException {
        final Path dir = Files.createTempDirectory("exilecore-minion");
        Files.copy(DATA.resolve("supports.yml"), dir.resolve("supports.yml"));
        final String original = Files.readString(DATA.resolve("skills.yml"));
        assertRejected(dir, original.replace("kind: ZOMBIE", "kind: CREEPER"), "kind");
        assertRejected(dir, original.replaceFirst("(?s)(  raise_zombie:.*?)mechanic: summon", "$1mechanic: nova"), "summon");
        assertRejected(dir, original.replace("    cast_time: 0.6\n    mana: [6, 18]\n    visual: raise_zombie",
                "    damage: [1, 2]\n    damage_types: { physical: 1.0 }\n    cast_time: 0.6\n    mana: [6, 18]\n    visual: raise_zombie"), "damage");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        Files.writeString(dir.resolve("skills.yml"), content);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }

    @Test
    void minionLimitClampedToPerPlayerRoom() {
        // 2026-10-27：單一技能上限（最多 50）夾在一個人的總上限（40）剩下的空間；這次之前超過會一直叫新的擠掉舊的
        assertEquals(MinionService.MAX_PER_PLAYER, MinionService.clampToRoom(SkillBuild.MAX_MINIONS, 0));
        assertEquals(15, MinionService.clampToRoom(20, 25), "其他技能占了 25 隻：只剩 15");
        assertEquals(6, MinionService.clampToRoom(6, 10));
        assertEquals(0, MinionService.clampToRoom(5, MinionService.MAX_PER_PLAYER), "滿了就不再叫");
    }

    @Test
    void companionRoomIsReservedInOrder() {
        // 2026-10-27：精魂召喚照開的順序分總上限：先開的預留它要的隻數，後開的不能補進先開的那種死掉留下的空位
        assertEquals(30, AuraService.companionLimit(30, 40, 0));
        assertEquals(10, AuraService.companionLimit(20, 40, 30), "先開的要 30 隻：後開的最多 10 隻（就算現在有空位）");
        assertEquals(5, AuraService.companionLimit(20, 5, 30), "活著的別的召喚物還沒收掉：先照現在的空間");
        assertEquals(0, AuraService.companionLimit(20, 40, 45));
    }

    @Test
    void mountCannotJumpWalls() {
        // 2026-10-27：坐騎跳躍力、自動踏上高度跟玩家自己差不多（這次之前 0.75、1.5，能跳過 3 格牆）
        assertTrue(Mounts.JUMP_STRENGTH <= 0.5);
        assertTrue(Mounts.STEP_HEIGHT <= 1.0);
    }
}
