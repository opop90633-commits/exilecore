package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Falling Thunder（驚雷劈）：猛擊之後用掉所有的銳星，從落點往前射出閃電投射物。 */
class FallingThunderTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final Set<String> STAFF = Set.of(Tags.QUARTERSTAFF);
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
    }

    private static SkillBuild build(final int level, final List<SupportDefinition> supports) {
        return SkillBuild.of(config, config.skill("startle_thunder_chop"), level, 90, false, supports, STAFF, new StatSheet());
    }

    private static double more(final SkillProfile profile) {
        double total = 1;
        for (final Modifier m : profile.local()) {
            if (m.stat().equals(StatKeys.DAMAGE) && m.type() == tw.exilecore.stats.ModType.MORE) {
                total *= 1 + m.value();
            }
        }
        return total;
    }

    @Test
    void tagsAndStageFollowRef() {
        final SkillDefinition thunder = config.skill("startle_thunder_chop");
        assertEquals(Set.of(Tags.ATTACK, Tags.AREA, Tags.MELEE, Tags.SLAM, Tags.PROJECTILE, "lightning"), Set.copyOf(thunder.tags()));
        // 猛擊那一下不是投射物（不吃投射物的加成），還是近戰、猛擊
        final SkillBuild slam = build(20, List.of());
        assertFalse(slam.profile().tags().contains(Tags.PROJECTILE));
        assertTrue(slam.profile().tags().contains(Tags.MELEE) && slam.profile().tags().contains(Tags.SLAM));
        assertEquals(Map.of(DamageType.LIGHTNING, 0.6), thunder.conversion());
        // 投射物那一段：沒有自己的逐級表（RePoE FallingThunderProjectilePlayer），跟猛擊同一張；每顆 3 發；物理全部轉閃電
        final SkillStages.Stage stage = SkillStages.get("startle_thunder_chop", SkillExecutor.THUNDER);
        assertNotNull(stage);
        for (final int level : new int[] {1, 20, 40}) {
            assertEquals(thunder.effectiveness().at(level), stage.effectiveness().at(level), 1e-9);
        }
        assertEquals(4.37, stage.effectiveness().at(20), 1e-9);
        assertEquals(3, stage.count().intAt(20));
        assertEquals(Map.of(DamageType.LIGHTNING, 1.0), stage.conversion());
        final SkillDefinition bolt = config.skill(SkillExecutor.THUNDER_BOLT);
        assertTrue(bolt.hidden());
        assertEquals(Mechanic.PROJECTILE, bolt.mechanic());
        assertEquals(20, bolt.params().pierce(), "穿透所有目標");
        assertEquals(0.8, bolt.params().speed(), 1e-9, "16 公尺／秒");
        assertTrue(SupportDefinition.consumes(thunder, "power"), "本身會消耗銳星（恆星、倍星有用；綿咒、蔓寒不能掛）");
    }

    @Test
    void damageAndCountPerPowerCharge() {
        assertEquals(0, SkillExecutor.thunderMore(0, 50), 1e-9);
        assertEquals(3.0, SkillExecutor.thunderMore(1, 50), 1e-9, "1 顆：(1 + 50%) × 2");
        assertEquals(5.0, SkillExecutor.thunderMore(3, 50), 1e-9, "3 顆：(1 + 150%) × 2");
        assertEquals(6.2, SkillExecutor.thunderMore(3, 70), 1e-9, "品質 20%：每顆 70%");
        assertEquals(0, SkillExecutor.thunderCount(0, 3, 2), "沒有銳星不射（多射的輔助也一樣）");
        assertEquals(3, SkillExecutor.thunderCount(1, 3, 0));
        assertEquals(11, SkillExecutor.thunderCount(3, 3, 2));
        assertEquals(SkillBuild.MAX_PROJECTILES, SkillExecutor.thunderCount(10, 3, 0));
        assertEquals(1, SkillExecutor.thunderCount(1, 3, -5));
    }

    @Test
    void boltBuildUsesTheSlamDamageAndSupports() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild slam = build(20, List.of());
        assertNull(SkillExecutor.thunderBuild(config, slam, 0, 90, STAFF, sheet), "沒有銳星就沒有投射物");
        final SkillBuild bolt = SkillExecutor.thunderBuild(config, slam, 3, 90, STAFF, sheet);
        assertNotNull(bolt);
        assertEquals(9, bolt.projectiles(), "每顆 3 發");
        assertEquals(20, bolt.pierce());
        final Set<String> tags = bolt.profile().tags();
        assertTrue(tags.contains(Tags.PROJECTILE) && tags.contains(Tags.ATTACK) && tags.contains(Tags.QUARTERSTAFF), tags.toString());
        assertFalse(tags.contains(Tags.MELEE) || tags.contains(Tags.SLAM) || tags.contains(Tags.AREA), tags.toString());
        assertEquals(Map.of(DamageType.LIGHTNING, 1.0), bolt.profile().conversion());
        assertEquals(slam.profile().effectiveness(), bolt.profile().effectiveness(), 1e-9, "照猛擊那一下的傷害");
        assertEquals(5.0, more(bolt.profile()) / more(slam.profile()), 1e-9, "3 顆：更多 150% × 更多 100%");
        assertEquals(SkillExecutor.THUNDER_SPEED_VARIATION, SkillStages.mark(bolt.profile(), ProjectileEngine.SPEED_VARIATION), 1e-9);
        // 多射的輔助（分光 +2）掛在驚雷劈上：投射物再 +2
        final SkillBuild multi = build(20, List.of(config.support("light_split")));
        assertEquals(1, multi.supports().size());
        assertTrue(config.support("light_split").supports(config.skill("startle_thunder_chop")), "投射物的輔助掛得上");
        assertEquals(2 * 3 + 2, SkillExecutor.thunderBuild(config, multi, 2, 90, STAFF, sheet).projectiles());
        assertEquals(0, SkillExecutor.thunderCount(0, 3, 2));
        // 分裂的輔助：投射物會分裂
        final SkillBuild fork = build(20, List.of(config.support("split_shot")));
        assertTrue(config.support("split_shot").supports(config.skill("startle_thunder_chop")));
        assertEquals(1, SkillExecutor.thunderBuild(config, fork, 1, 90, STAFF, sheet).forks());
        assertTrue(config.support("pierce").supports(config.skill("startle_thunder_chop")));
        assertFalse(config.support("pierce").supports(config.skill("ice_ridge_rift")), "沒有投射物那一段的扇形技能照舊不能掛");
    }

    @Test
    void qualityFollowsRef() {
        final SkillDefinition thunder = config.skill("startle_thunder_chop");
        final List<Modifier> mods = SkillService.qualityModifiers(thunder, 20);
        assertEquals(20, mods.stream().filter(m -> m.stat().equals(SkillExecutor.THUNDER_QUALITY)).mapToDouble(Modifier::value).sum(), 1e-9);
        assertEquals(0, mods.stream().filter(m -> m.stat().equals(ChargeService.SUP_KEEP)).mapToDouble(Modifier::value).sum(), 1e-9,
                "機率不用掉戰星是額外的品質效果（高階奇術）");
        assertEquals(20, GemQuality.advancedModifiers("startle_thunder_chop", 20, "q").stream()
                .filter(m -> m.stat().equals(ChargeService.SUP_KEEP)).mapToDouble(Modifier::value).sum(), 1e-9);
        assertEquals("品質 +20%：每用掉一顆銳星，猛擊與投射物各更多 20% 傷害（投射物的加在 50% 上）", SkillService.qualityText(thunder, 20));
        // 20% 品質：每顆 70%
        final StatSheet sheet = new StatSheet();
        final SkillBuild slam = build(20, List.of());
        final SkillBuild quality = slam.withProfile(slam.profile().withLocal(mods));
        final SkillBuild bolt = SkillExecutor.thunderBuild(config, quality, 3, 90, STAFF, sheet);
        assertEquals(6.2, more(bolt.profile()) / more(quality.profile()), 1e-9);
    }

    @Test
    void speedVariationSpreadsTheVolley() {
        assertEquals(1.0, ProjectileEngine.speedFactor(0, 0.9), 1e-9);
        assertEquals(0.5, ProjectileEngine.speedFactor(50, 0), 1e-9);
        assertEquals(1.0, ProjectileEngine.speedFactor(50, 0.5), 1e-9);
        assertEquals(1.5, ProjectileEngine.speedFactor(50, 1), 1e-9);
    }
}
