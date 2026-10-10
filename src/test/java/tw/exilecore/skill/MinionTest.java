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
        final SkillDefinition zombie = config.skill("raise_zombie");
        assertEquals(Mechanic.SUMMON, zombie.mechanic());
        assertNotNull(zombie.summon());
        assertEquals("ZOMBIE", zombie.summon().kind());
        assertTrue(zombie.summon().corpse(), "殭屍要屍體");
        assertTrue(zombie.summon().permanent());
        assertEquals(3, zombie.summon().max().intAt(1));
        assertEquals(5, zombie.summon().max().intAt(20));
        assertTrue(zombie.tags().contains(Tags.MINION));
        assertTrue(zombie.tags().contains(Tags.MELEE), "召喚物用近戰打：近戰的輔助可以掛");
        assertFalse(zombie.tags().contains(Tags.DURATION), "永久的沒有持續時間標籤");
        assertFalse(zombie.dealsDamage(), "技能自己不造成傷害");
        assertNull(zombie.damage());

        final SkillDefinition skeletons = config.skill("summon_skeletons");
        assertEquals(2, skeletons.summon().count());
        assertEquals(20, skeletons.summon().duration(), 1e-9);
        assertTrue(skeletons.tags().contains(Tags.DURATION), "有持續時間：持久輔助可以掛");
        assertEquals("IRON_SWORD", skeletons.summon().weapon());

        final SkillDefinition spirits = config.skill("raging_spirits");
        assertTrue(spirits.summon().flying());
        assertEquals(1.0, spirits.conversion().get(DamageType.FIRE), 1e-9, "焚魂全轉火焰");
        assertEquals(75, spirits.summon().resists().get(DamageType.FIRE), 1e-9);

        final SkillDefinition golem = config.skill("flame_golem");
        assertTrue(golem.summon().fixedMax());
        assertEquals(2.0, golem.summon().radius(), 1e-9);
        assertEquals(4, golem.summon().taunt(), 1e-9);
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
        final SupportDefinition brutality = config.support("melee_physical");
        final SupportDefinition echo = config.support("spell_echo");
        final SupportDefinition totem = config.support("spell_totem");
        final SupportDefinition duration = config.support("increased_duration");
        assertTrue(damage.supports(config.skill("raise_zombie")));
        assertFalse(damage.supports(config.skill("fireball")), "兇猛召喚只能掛召喚技能");
        assertTrue(brutality.supports(config.skill("raise_zombie")), "蠻力（近戰物理）可以掛在殭屍上");
        assertFalse(echo.supports(config.skill("raise_zombie")), "雙詠不能掛召喚");
        assertFalse(totem.supports(config.skill("raise_zombie")), "圖騰施法不能掛召喚");
        assertFalse(duration.supports(config.skill("raise_zombie")), "永久的召喚物沒有持續時間");
        assertTrue(duration.supports(config.skill("summon_skeletons")));

        final SkillBuild plain = build("summon_skeletons", 10, owner);
        final SkillBuild boosted = build("summon_skeletons", 10, owner, damage, life, speed, brutality, totem, echo);
        assertEquals(4, boosted.supports().size(), "圖騰施法、雙詠不算");
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
        assertEquals(plainDamage * 1.35 * 1.4, boostedDamage, 1e-6, "召喚物傷害 35% 更多 × 蠻力的近戰物理更多 40%");
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
        assertEquals(3, build("raise_zombie", 1, owner).minions());
        assertEquals(5, build("raise_zombie", 20, owner).minions());
        assertEquals(20, build("raging_spirits", 20, owner).minions());
        assertEquals(1, build("flame_golem", 20, owner).minions());
        owner.add(new Modifier(StatKeys.SKILL_MINIONS, ModType.FLAT, 1, Set.of(Tags.MINION), "不死軍團"));
        assertEquals(6, build("raise_zombie", 20, owner).minions(), "+1 召喚物數量");
        assertEquals(1, build("flame_golem", 20, owner).minions(), "魔像固定一尊");
        assertEquals(0, build("fireball", 20, owner).minions());
        final SkillBuild skeletons = build("summon_skeletons", 20, owner, config.support("increased_duration"));
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
        assertTrue(hits > 1500 && hits < 2000, "命中率大約八到九成：" + hits);
        assertTrue(dealt / hits > monsters.damageAt(level)[0], "每下至少是同等級怪物的一下");
    }

    @Test
    void brokenSummonBlocksAreRejected() throws IOException {
        final Path dir = Files.createTempDirectory("exilecore-minion");
        Files.copy(DATA.resolve("supports.yml"), dir.resolve("supports.yml"));
        final String original = Files.readString(DATA.resolve("skills.yml"));
        assertRejected(dir, original.replace("kind: ZOMBIE", "kind: CREEPER"), "kind");
        assertRejected(dir, original.replace("mechanic: summon\n    summon: { kind: ZOMBIE", "mechanic: nova\n    summon: { kind: ZOMBIE"), "summon");
        assertRejected(dir, original.replace("    cast_time: 0.85\n    mana: [8, 24]", "    damage: [1, 2]\n    damage_types: { physical: 1.0 }\n    cast_time: 0.85\n    mana: [8, 24]"), "damage");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        Files.writeString(dir.resolve("skills.yml"), content);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }
}
