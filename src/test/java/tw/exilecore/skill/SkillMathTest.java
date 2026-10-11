package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能的數學：間隔、扇形、散射、跳躍的初速度。 */
class SkillMathTest {

    @Test
    void intervals() {
        assertEquals(20 / 1.5, SkillMath.attackInterval(1.5, 1.0), 1e-9);
        assertEquals(20 / 1.2, SkillMath.attackInterval(1.5, 0.8), 1e-9);
        assertEquals(SkillMath.MIN_INTERVAL_TICKS, SkillMath.attackInterval(100, 1.0), 1e-9, "每秒最多 10 次");
        assertEquals(15, SkillMath.castInterval(0.75, 1.0), 1e-9);
        assertEquals(10, SkillMath.castInterval(0.75, 1.5), 1e-9);
    }

    @Test
    void nextReadyKeepsTheCadenceWhileHoldingTheKey() {
        double ready = SkillMath.nextReady(Double.NEGATIVE_INFINITY, 100, 12.9);
        assertEquals(112.9, ready, 1e-9);
        ready = SkillMath.nextReady(ready, 113, 12.9);
        assertEquals(125.8, ready, 1e-9, "連續動作接著上一次的時間");
        ready = SkillMath.nextReady(ready, 126, 12.9);
        assertEquals(138.7, ready, 1e-9);
        assertEquals(512.9, SkillMath.nextReady(ready, 500, 12.9), 1e-9, "停過一陣子就從現在算");
    }

    @Test
    void fanSpreadsHorizontallyAroundTheAim() {
        final List<double[]> single = SkillMath.fan(0, 0, 2, 1, 40);
        assertEquals(1, single.size());
        assertEquals(1.0, single.get(0)[2], 1e-9);

        final List<double[]> five = SkillMath.fan(0, 0, 1, 5, 40);
        assertEquals(5, five.size());
        assertEquals(0, five.get(2)[0], 1e-9, "中間那發沿著準心");
        assertEquals(20, Math.toDegrees(Math.acos(five.get(0)[2])), 1e-6, "最外側各偏 20°");
        assertEquals(20, Math.toDegrees(Math.acos(five.get(4)[2])), 1e-6);
        assertEquals(-five.get(0)[0], five.get(4)[0], 1e-9, "左右對稱");

        // 仰角不變
        final List<double[]> up = SkillMath.fan(0, 1, 1, 3, 30);
        for (final double[] d : up) {
            assertEquals(Math.sqrt(0.5), d[1], 1e-9);
            assertEquals(1.0, d[0] * d[0] + d[1] * d[1] + d[2] * d[2], 1e-9);
        }
    }

    @Test
    void coneCheck() {
        assertTrue(SkillMath.inCone(0, 1, 0, 5, 60), "正前方");
        assertFalse(SkillMath.inCone(0, 1, 5, 0, 60), "正側面");
        assertTrue(SkillMath.inCone(0, 1, 3, 5, 70), "31° 在 70° 扇形（±35°）裡");
        assertFalse(SkillMath.inCone(0, 1, 4, 5, 70), "39° 不在");
        assertFalse(SkillMath.inCone(0, 1, 0, -5, 150), "背後");
        assertTrue(SkillMath.inCone(0, 1, 0.3, -0.3, 10), "貼身的一律算");
        assertTrue(SkillMath.inCone(0, 1, 0, -5, 360), "360° 全部");
    }

    @Test
    void leapLandsWhereItAimed() {
        for (final boolean fromGround : new boolean[] {true, false}) {
            for (final double distance : new double[] {2, 5, 9}) {
                for (final double height : new double[] {-3, 0, 1.5}) {
                    final int ticks = SkillMath.leapTicks(distance);
                    assertTrue(ticks >= 10 && ticks <= 15);
                    final double[] velocity = SkillMath.leapVelocity(distance * 0.6, height, distance * 0.8, ticks, fromGround);
                    final double[] landed = SkillMath.simulateLeap(velocity, ticks, fromGround);
                    assertEquals(distance * 0.6, landed[0], 1e-6);
                    assertEquals(height, landed[1], 1e-6);
                    assertEquals(distance * 0.8, landed[2], 1e-6);
                }
            }
        }
        // 平地跳要往上跳（不是貼地滑過去）；從地面起跳第一 tick 有摩擦，水平初速要比較大
        final double[] ground = SkillMath.leapVelocity(0, 0, 9, SkillMath.leapTicks(9), true);
        final double[] air = SkillMath.leapVelocity(0, 0, 9, SkillMath.leapTicks(9), false);
        assertTrue(ground[1] > 0.3, "垂直初速 " + ground[1]);
        assertTrue(ground[2] > air[2] * 1.3, "地面起跳 " + ground[2] + "，空中 " + air[2]);
        final double[] wrong = SkillMath.simulateLeap(air, SkillMath.leapTicks(9), true);
        assertTrue(wrong[2] < 7, "沒算地面摩擦會跳不到（" + wrong[2] + " 格）");
    }
}
