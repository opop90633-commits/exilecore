package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2 0.5：久地（Persistent Ground）能掛、我們這邊原本沒留地面的 7 個技能——
 * 刺棘身（Briarpatch）、滾雷珠（Ball Lightning）、霜星爆（Ice Nova）、焚流（Incinerate）照原作留下地面；
 * 熾息（Flame Breath）、蠻衝（Rampage）、退步凝晶（Frozen Locus）0.5 不留地面，久地不再能掛。
 */
class GroundSkillsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static String refusal(final String support, final String skill) {
        assertNotNull(config.skill(skill), skill);
        return config.support(support).refusal(config.skill(skill));
    }

    private static double flat(final List<Modifier> mods, final String stat) {
        return mods.stream().filter(m -> m.stat().equals(stat)).mapToDouble(Modifier::value).sum();
    }

    // ---- 純函式 ----

    @Test
    void trailEvery350ms() {
        assertEquals(7, GroundSkills.intervalTicks(350), "0.35 秒 = 7 tick");
        assertFalse(GroundSkills.trailDue(0, 350));
        assertFalse(GroundSkills.trailDue(6, 350));
        assertTrue(GroundSkills.trailDue(7, 350));
        assertTrue(GroundSkills.trailDue(14, 350));
        assertFalse(GroundSkills.trailDue(7, 0), "沒用掉火灌注");
    }

    @Test
    void conePointsAlongTheFlame() {
        final List<Double> points = GroundSkills.conePoints(5.8, 1.4);
        assertEquals(2, points.size());
        assertEquals(1.4, points.get(0), 1e-9, "第一灘的邊貼著自己");
        assertEquals(4.2, points.get(1), 1e-9, "之後每隔一個直徑");
        assertEquals(List.of(1.0), GroundSkills.conePoints(1.0, 2.0), "扇形比半徑短：只有一灘，中心在最遠的地方");
        assertTrue(GroundSkills.conePoints(5, 0).isEmpty());
        assertTrue(GroundSkills.merges(0.6, 1.4));
        assertFalse(GroundSkills.merges(0.8, 1.4));
    }

    @Test
    void briarpatchStoresOnePercentUpToCap() {
        assertEquals(10, GroundSkills.store(0, 1000, 1, 256), 1e-9, "1%");
        assertEquals(256, GroundSkills.store(250, 1000, 1, 256), 1e-9, "最多 256（20 級）");
        assertEquals(0, GroundSkills.store(50, 1000, 1, 0), 1e-9, "刺棘身沒開：不存");
        assertEquals(50, GroundSkills.store(50, -5, 1, 256), 1e-9);
        assertTrue(GroundSkills.canGrow(-1, 0));
        assertFalse(GroundSkills.canGrow(100, 259), "每 8 秒最多一片");
        assertTrue(GroundSkills.canGrow(100, 260));
        assertTrue(GroundSkills.moved(0.05, 0));
        assertFalse(GroundSkills.moved(0.01, 0.01), "站著不動不會被打");
        assertEquals(10, GroundSkills.BRIAR_HIT_GAP_TICKS, "每秒最多兩下");
    }

    @Test
    void groundStatsHavePlayerText() {
        for (final String stat : new String[] {GroundSkills.TRAIL_IGNITED, GroundSkills.CONE_IGNITED, GroundSkills.NOVA_CHILLED,
                GroundSkills.BRIAR_STORE, GroundSkills.BRIAR_CAP}) {
            final String text = ModifierText.describe(Modifier.flat(stat, 8, "x"));
            assertFalse(text.startsWith("特殊效果"), stat + "：" + text);
        }
        assertTrue(ModifierText.describe(Modifier.flat(GroundSkills.TRAIL_IGNITED, 350, "x")).contains("0.35 秒"));
        assertTrue(ModifierText.describe(Modifier.flat(GroundSkills.NOVA_CHILLED, 8, "x")).contains("8 秒"));
    }

    // ---- 資料 ----

    @Test
    void ballLightningFireInfusionLeavesIgnitedGround() {
        final List<Modifier> infused = config.resource("rolling_thunder_orb").infusedFor("fire");
        assertEquals(350, flat(infused, GroundSkills.TRAIL_IGNITED), 1e-9);
        assertEquals(0, flat(infused, "ignite.chance"), 1e-9, "點燃機率的平衡等效拿掉了（改成真的點燃地面）");
        final SkillDefinition ground = config.skill(GroundSkills.BALL_LIGHTNING_GROUND);
        assertTrue(ground.hidden());
        assertEquals(tw.exilecore.core.Units.metres(0.9), ground.params().zone().radius().at(1), 1e-9);
        assertEquals(4, ground.params().zone().duration(), 1e-9);
        assertTrue(ground.damageByLevel());
        assertEquals(22, ground.damage()[0]);
        assertEquals(33, ground.damage()[1]);
        assertEquals(744, ground.damage()[38], "20 級");
        assertEquals(1116, ground.damage()[39]);
        assertEquals(19478, ground.damage()[79], "40 級");
    }

    @Test
    void incinerateFireInfusionLeavesIgnitedGround() {
        final List<Modifier> infused = config.resource("burning_current").infusedFor("fire");
        assertEquals(1, flat(infused, GroundSkills.CONE_IGNITED), 1e-9);
        assertEquals(0, flat(infused, "ignite.magnitude"), 1e-9, "點燃強度的平衡等效拿掉了");
        final SkillDefinition ground = config.skill(GroundSkills.INCINERATE_GROUND);
        assertTrue(ground.hidden());
        assertEquals(tw.exilecore.core.Units.metres(1.4), ground.params().zone().radius().at(1), 1e-9);
        assertEquals(8, ground.params().zone().duration(), 1e-9);
        assertEquals(24, ground.damage()[0]);
        assertEquals(829, ground.damage()[38], "20 級");
        assertEquals(1243, ground.damage()[39]);
        assertEquals(21705, ground.damage()[79], "40 級");
    }

    @Test
    void iceNovaColdInfusionLeavesChilledGround() {
        final List<Modifier> infused = config.resource("frost_star_blast").infusedFor("cold");
        assertEquals(8, flat(infused, GroundSkills.NOVA_CHILLED), 1e-9, "Chilled Ground duration is 8 seconds");
        assertEquals(0, flat(infused, "chill.effect"), 1e-9, "冰緩效果的平衡等效拿掉了");
        final SkillBuild plain = SkillBuild.of(config, config.skill("frost_star_blast"), 1, 100, false, List.of(), Set.of(), new StatSheet());
        final SkillBuild cold = SkillService.applyInfused(plain, infused);
        assertEquals(tw.exilecore.core.Units.metres(4.8), cold.radius(), 1e-6, "爆炸與冰緩地面 4.8 公尺");
        final SkillBuild persistent = SkillBuild.of(config, config.skill("frost_star_blast"), 1, 100, false,
                List.of(config.support("enduring_terrain")), Set.of(), new StatSheet());
        assertEquals(160, plain.groundDurationTicks(8));
        assertEquals(240, persistent.groundDurationTicks(8), "久地：8 秒的冰緩地面變 12 秒");
    }

    @Test
    void briarpatchSpiritEffects() {
        final AuraSpec aura = config.skill(GroundSkills.BRIARPATCH).aura();
        assertNotNull(aura);
        final ScaledMod store = aura.effects().stream().filter(m -> m.stat().equals(GroundSkills.BRIAR_STORE)).findFirst().orElseThrow();
        final ScaledMod cap = aura.effects().stream().filter(m -> m.stat().equals(GroundSkills.BRIAR_CAP)).findFirst().orElseThrow();
        assertEquals(1, store.value().at(20), 1e-9, "存 1%");
        assertEquals(9, cap.value().at(1), 1e-9);
        assertEquals(256, cap.value().at(20), 1e-9);
        assertEquals(3424, cap.value().at(40), 1e-9);
        assertEquals(3.0, GroundSkills.BRIAR_RADIUS_M, 1e-9);
        assertEquals(6.0, GroundSkills.BRIAR_SECONDS, 1e-9);
        assertEquals(160, GroundSkills.BRIAR_GAP_TICKS, "每 8 秒一片");
    }

    @Test
    void persistentGroundAllowedListMatchesRealGrounds() {
        for (final String id : List.of("thorn_body", "rolling_thunder_orb", "frost_star_blast", "burning_current")) {
            assertNull(refusal("enduring_terrain", id), id + "：照原作會留地面");
        }
        for (final String id : List.of("flame_breath", "brute_charge", "retreat_crystal_freeze")) {
            assertNotNull(refusal("enduring_terrain", id), id + "：PoE2 0.5 不留地面");
        }
    }
}
