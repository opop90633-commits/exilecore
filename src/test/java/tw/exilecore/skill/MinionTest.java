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
        assertEquals(8, zombie.summon().max().intAt(1));
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
        assertEquals(75, spirits.summon().resists().get(DamageType.FIRE), 1e-9);

        // 魔像拿掉了（2026-10）：固定隻數、會嘲諷的換成狼伴，打中連周圍一起打的是骸骨重卒
        final SkillDefinition wolf = config.skill("beast_companion");
        assertFalse(wolf.summon().fixedMax(), "2026-10-17：狼伴吃 +x 召喚物（每隻保留 30 精魂）");
        assertEquals(6, wolf.summon().taunt(), 1e-9);
        assertEquals("狼", wolf.summon().kindName());
        assertEquals(2.0, config.skill("skeletal_brute").summon().radius(), 1e-9);
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
        assertEquals(80, MinionSheets.monsterLevel(config, 20));
        assertEquals(80 + 4 * 3, MinionSheets.monsterLevel(config, 23), "20 級以上每級加 4");
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
        final SupportDefinition duration = config.support("increased_duration");
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
        assertEquals(plainDamage * 1.35, boostedDamage, 1e-6, "召喚物傷害 35% 更多");
        final StatSheet effective = boostedProfile.effective(boostedSheet);
        assertEquals(MinionSheets.life(plainProfile.effective(plainSheet)) * 1.4, MinionSheets.life(effective), 1e-6, "召喚物生命 40% 更多");
        assertEquals(1.5, MinionSheets.regenPercent(effective), 1e-9);
        assertEquals(spec.attackSpeed() * 1.25, MinionSheets.attacksPerSecond(effective, boostedProfile), 1e-9);
        assertEquals(1.3, MinionSheets.movementScale(effective), 1e-9);
        assertTrue(boosted.manaCost() > plain.manaCost(), "魔力倍率照掛");
        // 主人自己的屬性表不受影響（輔助只在技能描述的 local）
        assertEquals(0, owner.allModifiers().size());
    }

    @Test
    void minionCapAndDurationScale() {
        final StatSheet owner = new StatSheet();
        assertEquals(8, build("raise_zombie", 1, owner).minions());
        assertEquals(10, build("raise_zombie", 20, owner).minions());
        assertEquals(10, build("raging_spirits", 20, owner).minions());
        assertEquals(1, build("beast_companion", 20, owner).minions());
        owner.add(new Modifier(StatKeys.SKILL_MINIONS, ModType.FLAT, 1, Set.of(Tags.MINION), "不死軍團"));
        assertEquals(11, build("raise_zombie", 20, owner).minions(), "+1 召喚物數量");
        assertEquals(2, build("beast_companion", 20, owner).minions(), "狼伴也吃 +1 召喚物（2026-10-17 拿掉固定隻數）");
        assertEquals(0, build("fireball", 20, owner).minions());
        final SkillBuild skeletons = build("raise_zombie", 20, owner, config.support("increased_duration"));
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
        // 2026-10 平衡：殭屍的傷害倍率 0.7 → 0.32，每下是同等級怪物一下的 0.32 倍再扣護甲
        // （PoE2 的護甲公式：護甲 ÷ (護甲 + 10 × 傷害)，對小的一下很有效，同等級的怪大約擋掉一半）
        final double scale = zombie.definition().summon().damage();
        assertTrue(dealt / hits > monsters.damageAt(level)[0] * scale * 0.5 && dealt / hits < monsters.damageAt(level)[1] * scale,
                "每下大約是同等級怪物一下的 " + scale + " 倍：" + dealt / hits + "／" + java.util.Arrays.toString(monsters.damageAt(level)));
    }

    @Test
    void brokenSummonBlocksAreRejected() throws IOException {
        final Path dir = Files.createTempDirectory("exilecore-minion");
        Files.copy(DATA.resolve("supports.yml"), dir.resolve("supports.yml"));
        final String original = Files.readString(DATA.resolve("skills.yml"));
        assertRejected(dir, original.replace("kind: ZOMBIE", "kind: CREEPER"), "kind");
        assertRejected(dir, original.replace("mechanic: summon\n    # 2026-10 平衡：群體召喚太強，damage 0.7 → 0.32\n    # 2026-10-23 弱勢補強：damage 0.32 → 0.42（90 級以上 3 人實測中位數只有 24 萬；殭屍要屍體、20 秒就消失，比骸骨兵難維持）\n    summon: { kind: ZOMBIE",
                "mechanic: nova\n    summon: { kind: ZOMBIE"), "summon");
        assertRejected(dir, original.replace("    cast_time: 0.6\n    mana: [6, 18]\n    visual: raise_zombie",
                "    damage: [1, 2]\n    damage_types: { physical: 1.0 }\n    cast_time: 0.6\n    mana: [6, 18]\n    visual: raise_zombie"), "damage");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        Files.writeString(dir.resolve("skills.yml"), content);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }
}
