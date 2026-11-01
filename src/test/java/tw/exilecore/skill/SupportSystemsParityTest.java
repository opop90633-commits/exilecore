package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2：召喚物那邊的輔助代價（Pierce I、Overcharge、See Red、Overextend）、召喚物法術的擊退（Knockback）、
 * 只拉長地面效果的久地（Persistent Ground I）、雙震的兩側餘震（Tectonic Slams）。
 */
class SupportSystemsParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double flat(final String support, final String stat) {
        double sum = 0;
        for (final Modifier modifier : config.support(support).modifiers()) {
            if (modifier.stat().equals(stat) && modifier.type() == ModType.FLAT) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    private static String refusal(final String support, final String skill) {
        assertNotNull(config.support(support), support);
        assertNotNull(config.skill(skill), skill);
        return config.support(support).refusal(config.skill(skill));
    }

    // ---- 1. 召喚物那邊的代價 ----

    @Test
    void minionProjectilesLoseDamageAfterPiercing() {
        assertEquals(1.0, MinionProjectiles.hitDamage(1.0, false, 0.8), 1e-9, "還沒穿透：照原本的");
        assertEquals(0.8, MinionProjectiles.hitDamage(1.0, true, 0.8), 1e-9, "穿透過敵人之後更少 20%");
        assertEquals(0.56, MinionProjectiles.hitDamage(0.7, true, 0.8), 1e-9, "分裂的 70% 再乘");
        assertEquals(0.0, MinionProjectiles.hitDamage(1.0, true, -1), 1e-9, "最少 0");
        final StatSheet pierced = new StatSheet().addAll(List.of(Modifier.flat(SupportExtraKeys.PIERCED_LESS, 20, "貫穿")));
        assertEquals(0.8, MinionTypes.piercedFactor(pierced, Set.of(Tags.PROJECTILE)), 1e-9);
        assertEquals(1.0, MinionTypes.piercedFactor(new StatSheet(), Set.of(Tags.PROJECTILE)), 1e-9);
        assertEquals(1.0, MinionTypes.piercedFactor(null, Set.of()), 1e-9);
        assertEquals(20, flat("pierce", SupportExtraKeys.PIERCED_LESS));
        assertNull(refusal("pierce", "summon_bone_bowmen"), "貫穿照舊掛得上射手（代價也接上了）");
    }

    @Test
    void downsidesStillAttachToMinionsExceptOverextend() {
        assertNull(refusal("blazing_volt", "summon_skeletons"), "熾電：召喚物的感電反射給召喚物自己");
        assertNull(refusal("blood_linger", "summon_skeletons"), "血綿：召喚物造成的流血也讓敵人加速");
        assertEquals(MinionTypes.Use.IGNORED, MinionTypes.usable(new Modifier(SupportExtraKeys.FLAG_SHOCK_REFLECT, ModType.FLAT, 1, Set.of(), ""),
                config.skill("summon_skeletons"), MinionTypes.of(config.skill("summon_skeletons"))), "代價不擋、不算好處");
        // Overextend：Path of Building 0.5 ignoreMinionTypes（Supports Attack Skills you use yourself）
        assertTrue(config.support("all_in_gamble").excludes().contains(Tags.MINION));
        assertNotNull(refusal("all_in_gamble", "summon_skeletons"), "孤注掛不上召喚技能");
        assertNull(refusal("all_in_gamble", "spine_crush"), "你自己的攻擊照舊可以");
    }

    // ---- 2. 召喚物法術的擊退 ----

    @Test
    void knockbackFitsSpellMinions() {
        assertNull(refusal("knockback", "summon_bone_thundermage"), "雷術士的電弧、死亡風暴擊中也擊退");
        assertNull(refusal("knockback", "summon_skeletons"), "攻擊的照舊");
        assertEquals("召喚物不會造成傷害", refusal("knockback", "summon_bone_priest"), "祭司不打人");
    }

    // ---- 3. 久地只拉長地面效果 ----

    @Test
    void groundScalePoolsWithSkillDuration() {
        assertEquals(1.5, SkillBuild.groundScale(0, 0.5, 1), 1e-9);
        assertEquals((1 + 0.2 + 0.5) * 1.1, SkillBuild.groundScale(0.2, 0.5, 1.1), 1e-9, "跟技能效果持續時間的增加同一池，再乘更多");
        assertEquals(0.1, SkillBuild.groundScale(-5, 0, 1), 1e-9, "最少 0.1");
    }

    @Test
    void persistentGroundOnlyLengthensGround() {
        final SkillBuild build = SkillBuild.of(config, config.skill("kerosene_grenade"), 1, 100, false, List.of(config.support("enduring_terrain")),
                Set.of(Tags.CROSSBOW), new StatSheet());
        assertEquals(1.0, build.durationScale(), 1e-9, "技能其他的持續時間不變");
        assertEquals(1.5, build.groundDurationScale(), 1e-9, "地面效果持續時間增加 50%");
        assertEquals(120, build.durationTicks(6));
        assertEquals(180, build.groundDurationTicks(6), "6 秒的油地變 9 秒");
        assertEquals(1.5, build.groundDurationRatio(), 1e-9);
        // 隱藏技能留的地面（照原技能的比例）、換持續時間倍率時地面效果跟著等比例
        final SkillBuild plain = SkillBuild.of(config, config.skill("kerosene_grenade"), 1, 100, false, List.of(), Set.of(Tags.CROSSBOW), new StatSheet());
        assertEquals(plain.durationScale(), plain.groundDurationScale(), 1e-9, "沒掛久地：一樣");
        assertEquals(1.5, plain.withGroundRatio(build.groundDurationRatio()).groundDurationScale(), 1e-9);
        assertEquals(3.0, build.withDurationScale(2.0).groundDurationScale(), 1e-9);
        assertEquals(2.0, build.withDurationScale(2.0).durationScale(), 1e-9);
        assertEquals(1.5, build.withProfile(build.profile()).groundDurationScale(), 1e-9, "其他 withX 保留地面效果倍率");
    }

    // ---- 4. 雙震 ----

    @Test
    void tectonicSlamsNumbers() {
        assertEquals(45, flat("twin_quake", TectonicSlams.DAMAGE_LESS));
        assertEquals(30, flat("twin_quake", TectonicSlams.AREA_LESS));
        assertEquals(1.0, config.parentDamage(TectonicSlams.PROC), 1e-9, "餘震＝那一下的重複，更少 45% 另外乘");
        assertEquals(0.55, TectonicSlams.damageFactor(45), 1e-9);
        assertEquals(Math.sqrt(0.7), TectonicSlams.radiusFactor(30), 1e-9, "範圍更少 30%：半徑開根號");
        assertEquals(1.0, TectonicSlams.damageFactor(0), 1e-9);
        assertEquals(1.0, TectonicSlams.radiusFactor(0), 1e-9);
        assertTrue(TectonicSlams.AFTERSHOCKS.containsAll(Set.of("proc_aftershock", "proc_tremor", TectonicSlams.PROC)));
        assertEquals("被輔助的技能的餘震更少 45% 傷害",
                ModifierText.describe(new Modifier(TectonicSlams.DAMAGE_LESS, ModType.FLAT, 45, Set.of(), "")));
        assertEquals("被輔助的技能的餘震範圍更少 30%",
                ModifierText.describe(new Modifier(TectonicSlams.AREA_LESS, ModType.FLAT, 30, Set.of(), "")));
    }

    @Test
    void sideAftershocksSitAheadOnEachSide() {
        final List<double[]> centres = TectonicSlams.sideCentres(0, 3, 2);
        assertEquals(2, centres.size());
        final double d = 2 * Math.cos(Math.toRadians(TectonicSlams.SIDE_DEGREES));
        for (final double[] c : centres) {
            assertEquals(2, Math.hypot(c[0], c[1]), 1e-9, "距離＝原本範圍的半徑");
            assertEquals(d, c[1], 1e-9, "在前方");
        }
        assertEquals(-centres.get(0)[0], centres.get(1)[0], 1e-9, "左右對稱");
        assertTrue(Math.abs(centres.get(0)[0]) > 1, "偏到兩側");
        assertEquals(2, centres.size());
    }

    @Test
    void sideAftershockCopiesTheSlamArea() {
        final SkillBuild slam = SkillBuild.of(config, config.skill("thousand_weight_smash"), 1, 100, false, List.of(), Set.of(Tags.MACE), new StatSheet());
        final TectonicSlams.Area area = TectonicSlams.initialArea(slam);
        assertEquals(slam.radius(), area.radius(), 1e-9, "新星猛擊：腳下、半徑照它");
        assertEquals(0, area.forward(), 1e-9);
        final SkillBuild sub = SkillBuild.of(config, config.skill(TectonicSlams.PROC), 1, 100, false, List.of(), Set.of(Tags.MACE), new StatSheet());
        final SkillBuild side = TectonicSlams.sideBuild(sub, area.radius(), 45, 30);
        assertEquals(slam.radius() * Math.sqrt(0.7), side.radius(), 1e-6, "範圍照原本那一下再更少 30%");
    }
}
