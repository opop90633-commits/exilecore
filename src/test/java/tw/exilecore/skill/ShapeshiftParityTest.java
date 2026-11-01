package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：熾息、噬咬、鼓翼風、掠影撲補上的機制（ShapeshiftParity、AilmentState 點燃疊加）。 */
class ShapeshiftParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void knockbackScalesWithDistance() {
        // 鼓翼風：0 格更多 200%（×3）、6 格以外更少 90%（×0.1）、中間照比例
        assertEquals(3.0, ShapeshiftParity.knockbackFactor(0, 6, 200, -90), 1e-9);
        assertEquals(0.1, ShapeshiftParity.knockbackFactor(6, 6, 200, -90), 1e-9);
        assertEquals(0.1, ShapeshiftParity.knockbackFactor(20, 6, 200, -90), 1e-9);
        assertEquals(1 + (200 - 145) / 100.0, ShapeshiftParity.knockbackFactor(3, 6, 200, -90), 1e-9);
        assertEquals(1, ShapeshiftParity.knockbackFactor(3, 0, 200, -90), 1e-9, "沒寫 limit 不隨距離");
        // 照 skills.yml
        assertEquals(3.0, ShapeshiftParity.knockbackFactor(config.skill("wingbeat_gust"), 0), 1e-9);
        assertEquals(0.1, ShapeshiftParity.knockbackFactor(config.skill("wingbeat_gust"), 7), 1e-9);
        assertEquals(0.5, ShapeshiftParity.knockbackFactor(config.skill("gnawing_bite"), 0), 1e-9);
        assertEquals(1, ShapeshiftParity.knockbackFactor(config.skill("pounce"), 1), 1e-9);
    }

    @Test
    void devourTimingFollowsDistance() {
        final double[] near = ShapeshiftParity.devourTiming(1.0);
        assertEquals(-15, near[0], 1e-9);
        assertEquals(0, near[1], 1e-9);
        assertEquals(0, near[2], 1e-9);
        final double[] far = ShapeshiftParity.devourTiming(10);
        assertEquals(20, far[0], 1e-9);
        assertEquals(0.5, far[1], 1e-9, "衝滿 4 格 +0.5 秒");
        assertEquals(4, far[2], 1e-9);
        final double[] mid = ShapeshiftParity.devourTiming(2.75);
        assertEquals(2, mid[2], 1e-9);
        assertEquals(0.25, mid[1], 1e-9);
        assertEquals(5.5, ShapeshiftParity.devourLockRange(), 1e-9);
    }

    @Test
    void pickAlongRayPicksNearestOnLine() {
        final double[] eye = {0, 1.6, 0};
        final double[] dir = {0, 0, 1};
        final List<double[]> points = List.of(new double[] {3, 1.6, 4}, new double[] {0.5, 1.2, 5}, new double[] {0, 1.6, 2},
                new double[] {0, 1.6, -2});
        assertEquals(2, ShapeshiftParity.pickAlongRay(eye, dir, points, 6, 1.2));
        assertEquals(1, ShapeshiftParity.pickAlongRay(eye, dir, points.subList(0, 2), 6, 1.2));
        assertEquals(-1, ShapeshiftParity.pickAlongRay(eye, dir, List.of(new double[] {0, 1.6, -2}), 6, 1.2), "身後的不算");
    }

    @Test
    void startDelayAndEnergy() {
        assertEquals(10, ShapeshiftParity.startDelayTicks(4, 2.5));
        assertEquals(0, ShapeshiftParity.startDelayTicks(0, 2.5));
        assertEquals(0.02, ShapeshiftParity.energyScale(config.skill("flame_breath")), 1e-9);
        assertEquals(1, ShapeshiftParity.energyScale(config.skill("wingbeat_gust")), 1e-9);
        assertEquals(4, ShapeshiftParity.flat(config.skill("flame_breath").modifiers(), ShapeshiftParity.START_ATTACKS, 0), 1e-9);
    }

    @Test
    void flagsOnlyOnTheirSkills() {
        assertTrue(ShapeshiftParity.flag(config.skill("flame_breath"), ShapeshiftParity.FLAG_COMPOUND_IGNITE));
        assertTrue(ShapeshiftParity.flag(config.skill("pounce"), ShapeshiftParity.FLAG_LEAP_LOCK));
        assertFalse(ShapeshiftParity.flag(config.skill("soaring_smash"), ShapeshiftParity.FLAG_LEAP_LOCK), "別的跳躍照舊");
        assertFalse(config.skill("gnawing_bite").modifiers().stream().anyMatch(m -> m.stat().equals("charge.event.kill.power")),
                "噬咬改成每吃一個給銳星，不是咬死給");
    }

    @Test
    void compoundingIgnitesStackWithinOneUse() {
        final AilmentState state = new AilmentState();
        final UUID me = UUID.randomUUID();
        assertEquals(1, state.addCompoundingIgnite(10, 100, me, false, 5, 0));
        assertEquals(2, state.addCompoundingIgnite(10, 110, me, false, 5, 10));
        assertEquals(3, state.addCompoundingIgnite(10, 120, me, false, 5, 20));
        assertEquals(30, state.ignite(20).dps(), 1e-9, "同一次使用的 3 次點燃加在一起");
        // 換了一次使用：從 1 重新算（上一次的點燃留著）
        assertEquals(1, state.addCompoundingIgnite(10, 130, me, false, 6, 30));
        assertEquals(30, state.ignite(30).dps(), 1e-9);
        // 舊的已經結束：從 1 重新算
        assertEquals(1, state.addCompoundingIgnite(10, 300, me, false, 6, 200));
    }
}
