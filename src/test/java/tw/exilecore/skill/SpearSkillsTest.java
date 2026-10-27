package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照 PoE2 0.5 的矛技能：擲矛（每把矛賦予）、炎爆矛插地、血嗅印的血爆、血宣的爆炸。 */
class SpearSkillsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void spearThrowIsGrantedByEverySpear() {
        final SkillDefinition throwing = config.skill("spear_throw");
        assertNotNull(throwing);
        assertEquals("weapon", throwing.source());
        assertFalse(throwing.engravable(), "擲矛沒有寶石，雕不出來");
        assertFalse(throwing.hidden());
        assertTrue(throwing.isAttack());
        assertTrue(throwing.weapons().contains(Tags.SPEAR));
        assertEquals(0, throwing.mana().last(), "武器賦予的不耗魔力");
        assertEquals("spear_throw", config.signature("spear_throw"));
        // poe2db Spear Throw：125% → 390%（20 級），怒星爆炸 232% → 724%
        final SkillBuild one = SkillBuild.of(config, throwing, 1, 100, false, List.of(), Set.of(Tags.SPEAR), new StatSheet());
        final SkillBuild twenty = SkillBuild.of(config, throwing, 20, 100, false, List.of(), Set.of(Tags.SPEAR), new StatSheet());
        assertEquals(1.25, one.profile().effectiveness(), 1e-9);
        assertEquals(3.9, twenty.profile().effectiveness(), 1e-9);
        assertEquals(7.24 / 3.9, config.parentShare("spear_throw_blast", throwing, 20), 1e-9);
        assertTrue(config.skill("spear_throw_blast").hidden());
    }

    @Test
    void explosiveSpearLodgesAndExplodesLater() {
        final SkillDefinition spear = config.skill("explosive_spear");
        final SkillBuild build = SkillBuild.of(config, spear, 20, 100, false, List.of(), Set.of(Tags.SPEAR), new StatSheet());
        assertEquals(0, build.explode(), 1e-9, "打中不再直接炸開，落地插著");
        assertEquals(20, build.pierce(), "貫穿全部");
        assertEquals("explosive_spear", config.signature("explosive_spear"));
        assertTrue(spear.tags().contains(Tags.DURATION));
        // poe2db：爆炸 489%、怒星 724%（20 級），長矛本身 156%
        assertEquals(4.89 / 1.56, config.parentShare("explosive_spear_blast", spear, 20), 1e-9);
        assertEquals(7.24 / 1.56, config.parentShare("explosive_spear_infused", spear, 20), 1e-9);
        assertEquals(40, SpearMechanics.EXPLOSIVE_LIMIT.length);
        assertEquals(1, SpearMechanics.EXPLOSIVE_LIMIT[0]);
        assertEquals(3, SpearMechanics.EXPLOSIVE_LIMIT[19]);
        assertEquals(5, SpearMechanics.EXPLOSIVE_LIMIT[39]);
        // 2026-10-27 照 PoE2：飛到準心指的點（不再是拋物線）
        assertEquals(0, spear.params().gravity(), 1e-9, "落點在準心，不是拋物線");
    }

    /** 2026-10-27 照 PoE2 Explosive Spear 的 Frenzy-Charged：十字形（每一臂 4 → 5.9 → 7.9 格、寬 1.5 格）＋燃燒地面，拿掉替代用的一定點燃。 */
    @Test
    void frenzyExplosionIsACrossWithIgnitedGround() {
        final SkillDefinition infused = config.skill("explosive_spear_infused");
        assertEquals(4.0, infused.params().radius().at(1), 1e-9);
        assertEquals(5.9, infused.params().radius().at(20), 1e-9);
        assertEquals(7.9, infused.params().radius().at(40), 1e-9);
        assertEquals(1.5, SpearMechanics.CROSS_WIDTH, 1e-9);
        assertEquals(2.0, SpearMechanics.GROUND_RADIUS, 1e-9);
        assertEquals(8.0, SpearMechanics.GROUND_SECONDS, 1e-9);
        assertFalse(infused.modifiers().stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.IGNITE_CHANCE)), "原作沒有一定點燃");
        assertTrue(infused.modifiers().stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.IGNITE_MAGNITUDE)
                && m.type() == tw.exilecore.stats.ModType.INCREASED && Math.abs(m.value() - 1.0) < 1e-9), "點燃效果增加 100%");
        // 十字：沿著矛飛的方向（+x）一條、垂直一條；每一臂 5.9、半寬 0.75
        assertTrue(SpearMechanics.inCross(5.5, 0.5, 1, 0, 5.9, 0.75), "前方那一臂上");
        assertTrue(SpearMechanics.inCross(-5.5, -0.5, 1, 0, 5.9, 0.75), "後方那一臂上");
        assertTrue(SpearMechanics.inCross(0.3, 5.8, 1, 0, 5.9, 0.75), "側面那一臂上");
        assertFalse(SpearMechanics.inCross(2, 2, 1, 0, 5.9, 0.75), "兩臂之間的斜角打不到");
        assertFalse(SpearMechanics.inCross(6.5, 0, 1, 0, 5.9, 0.75), "超過臂長");
        // 方向轉 45 度：十字跟著轉
        final double d = Math.sqrt(0.5);
        assertTrue(SpearMechanics.inCross(3, 3, d, d, 5.9, 0.75));
        assertFalse(SpearMechanics.inCross(4, 0, d, d, 5.9, 0.75));
    }

    /** 2026-10-27 照 PoE2：落點離你最近 1 格、最遠 range。 */
    @Test
    void landingPointIsClampedToMinAndMaxDistance() {
        final org.bukkit.Location from = new org.bukkit.Location(null, 0, 64, 0);
        final org.bukkit.Location far = SpearMechanics.clampTarget(from, new org.bukkit.Location(null, 40, 64, 0), 1, 28);
        assertEquals(28, far.getX(), 1e-9);
        final org.bukkit.Location near = SpearMechanics.clampTarget(from, new org.bukkit.Location(null, 0, 64, 0.5), 1, 28);
        assertEquals(1, near.getZ(), 1e-9);
        final org.bukkit.Location ok = SpearMechanics.clampTarget(from, new org.bukkit.Location(null, 3, 63, 4), 1, 28);
        assertEquals(3, ok.getX(), 1e-9);
        assertEquals(63, ok.getY(), 1e-9);
    }

    @Test
    void bloodhoundsMarkOnlyTriggersTheBloodExplosion() {
        final SkillDefinition mark = config.skill("bloodhounds_mark");
        assertEquals(0, mark.params().mark().taken(), 1e-9, "原作沒有受到傷害增加");
        assertTrue(mark.params().mark().effects().isEmpty(), "原作沒有降護甲");
        final SkillDefinition blast = config.skill("bloodhound_blast");
        assertTrue(blast.hidden());
        assertTrue(blast.damage() != null, "不吃武器：自己的傷害表");
        // 2026-10-27 照 PoE2：不用武器的攻擊（基礎 196～294、傷害倍率 408%）；這次之前是法術、表裡已經乘過倍率（800）
        assertTrue(blast.isAttack() && blast.weaponless());
        final SkillBuild twenty = SkillBuild.of(config, blast, 20, 100, false, List.of(), Set.of(Tags.SPEAR), new StatSheet());
        assertEquals(196, twenty.profile().spellDamage().get(tw.exilecore.stats.DamageType.PHYSICAL), 1e-9);
        assertEquals(4.08, twenty.profile().effectiveness(), 1e-9);
        assertFalse(twenty.profile().tags().contains(Tags.SPEAR), "不帶武器的標籤");
        final tw.exilecore.stats.StatSheet sheet = weaponSheet();
        assertEquals((196 + 294) / 2.0 * 4.08, tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, twenty.profile())
                .get(tw.exilecore.stats.DamageType.PHYSICAL), 1e-6, "不吃武器傷害、不吃法術的增加");
    }

    /** 拿著一把 100 物理的武器、法術傷害增加 100%。 */
    private static tw.exilecore.stats.StatSheet weaponSheet() {
        return new StatSheet()
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.WEAPON_MIN, tw.exilecore.stats.ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"))
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.WEAPON_MAX, tw.exilecore.stats.ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"))
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.WEAPON_CRIT, tw.exilecore.stats.ModType.FLAT, 30, Set.of(), "武器"))
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, 1.0, Set.of(Tags.SPELL), "法術"));
    }

    /** 2026-10-27 照 PoE2：不用武器的攻擊吃攻擊的加成與附加傷害、暴擊率 5%（不看武器）；一般的攻擊照舊用武器。 */
    @Test
    void weaponlessAttacksUseAttackModifiersButNotTheWeapon() {
        final SkillDefinition blast = config.skill("bloodhound_blast");
        final SkillBuild one = SkillBuild.of(config, blast, 1, 100, false, List.of(), Set.of(Tags.SPEAR), new StatSheet());
        final tw.exilecore.stats.StatSheet sheet = weaponSheet()
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, 0.5, Set.of(Tags.ATTACK), "攻擊"))
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE_ADDED_MIN, tw.exilecore.stats.ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "附加"))
                .add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE_ADDED_MAX, tw.exilecore.stats.ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "附加"));
        // (7～11 + 10) × 100% × (1 + 50%)
        assertEquals((9 + 10) * 1.5, tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, one.profile()).get(tw.exilecore.stats.DamageType.PHYSICAL), 1e-6);
        assertEquals(0.05, tw.exilecore.stats.DamageCalculator.critChance(sheet, one.profile()), 1e-9, "基礎暴擊率 5%，不看武器的 30%");
        // 一般的攻擊（炎爆矛）照舊用武器
        final SkillBuild spear = SkillBuild.of(config, config.skill("explosive_spear"), 1, 100, false, List.of(), Set.of(Tags.SPEAR), new StatSheet());
        assertEquals(0.3, tw.exilecore.stats.DamageCalculator.critChance(sheet, spear.profile()), 1e-9);
        assertTrue(spear.profile().tags().contains(Tags.SPEAR));
        // 混合物（PoE2 一樣是不看武器的攻擊）改用同一套：20 級 314～582 × 185%
        final SkillDefinition concoction = config.skill("bleeding_concoction");
        assertTrue(concoction.weaponless());
        final SkillBuild brew = SkillBuild.of(config, concoction, 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertEquals((314 + 582) / 2.0 * 1.85, tw.exilecore.stats.DamageCalculator.expectedOutgoing(weaponSheet(), brew.profile())
                .get(tw.exilecore.stats.DamageType.PHYSICAL), 1e-6);
    }

    @Test
    void heraldOfBloodExplosionHasItsOwnDamage() {
        final SkillDefinition burst = config.skill("herald_blood_burst");
        assertTrue(burst.isAttack() && burst.weaponless(), "2026-10-27 照 PoE2：不用武器的攻擊；這次之前是法術");
        assertTrue(config.skill("herald_of_blood").tags().contains(Tags.ATTACK));
        assertTrue(burst.modifiers().stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.FLAG_ALWAYS_HIT)), "Can't be Evaded");
        final SkillBuild twenty = SkillBuild.of(config, burst, 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertEquals(107, twenty.profile().spellDamage().get(tw.exilecore.stats.DamageType.PHYSICAL), 1, "poe2db：20 級 107～160");
    }
}
