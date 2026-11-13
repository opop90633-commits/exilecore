package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-12 照站長（自動量技能的榴彈幾乎都是 0）：榴彈拋到準心指的點。 */
class GrenadeLobTest {

    /** 照 ProjectileEngine 的算法逐 tick 走（先掉速度再移動），掉到 drop 以下時水平走了多遠。 */
    private static double landing(final double speed, final double drop, final double gravity) {
        final double angle = Math.toRadians(ProjectileEngine.LOB_ANGLE);
        double vx = speed * Math.cos(angle);
        double vy = speed * Math.sin(angle);
        double x = 0;
        double y = 0;
        while (y > -drop) {
            vy -= gravity;
            x += vx;
            y += vy;
        }
        return x;
    }

    @Test
    void landsNearTheAimedPoint() {
        for (final double distance : new double[] {3, 8, 15}) {
            final double speed = ProjectileEngine.lobSpeed(distance, 1.47, 0.06);
            assertEquals(distance, landing(speed, 1.47, 0.06), distance * 0.08 + 0.2);
        }
    }

    @Test
    void closerIsSlower() {
        assertTrue(ProjectileEngine.lobSpeed(3, 1.47, 0.06) < ProjectileEngine.lobSpeed(15, 1.47, 0.06));
    }
}
