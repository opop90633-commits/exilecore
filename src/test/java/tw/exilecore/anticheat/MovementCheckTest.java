package tw.exilecore.anticheat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 移動檢查的純邏輯（2026-10）：正常跑不會抓、兩倍速會抓、懸空會抓、傳送 reset 後不抓。 */
final class MovementCheckTest {

    private static final double ALLOWED = 7.3; // 原版衝刺跳躍一秒約 7.1 格（還沒乘 tolerance 1.25 → 9.1）

    @Test
    void sprintJumpingIsFine() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        for (int i = 0; i < 200; i++) {
            final boolean ground = i % 12 < 6;
            final double vertical = ground ? 0 : (i % 12 < 9 ? 0.4 : -0.4);
            assertEquals(MovementCheck.Verdict.OK, check.tick(0.355, vertical, ground, ALLOWED, true), "刻 " + i);
        }
        assertTrue(check.violation() < 0.01);
    }

    @Test
    void doubleSpeedIsCaught() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        MovementCheck.Verdict caught = MovementCheck.Verdict.OK;
        for (int i = 0; i < 60 && caught == MovementCheck.Verdict.OK; i++) {
            caught = check.tick(0.71, 0, true, ALLOWED, true); // 一秒 14.2 格
        }
        assertEquals(MovementCheck.Verdict.SPEED, caught);
    }

    @Test
    void slightlyFastIsTolerated() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        for (int i = 0; i < 200; i++) {
            assertEquals(MovementCheck.Verdict.OK, check.tick(0.42, 0, true, ALLOWED, true)); // 一秒 8.4 格，在放寬裡
        }
    }

    @Test
    void hoveringIsCaught() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        MovementCheck.Verdict caught = MovementCheck.Verdict.OK;
        for (int i = 0; i < 80 && caught == MovementCheck.Verdict.OK; i++) {
            caught = check.tick(0.1, 0, false, ALLOWED, true); // 在空中不掉
        }
        assertEquals(MovementCheck.Verdict.FLY, caught);
    }

    @Test
    void normalJumpIsFine() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        final double[] jump = {0.42, 0.33, 0.25, 0.17, 0.09, 0.01, -0.07, -0.15, -0.22, -0.30, -0.37, -0.45};
        for (int round = 0; round < 10; round++) {
            for (final double vertical : jump) {
                assertEquals(MovementCheck.Verdict.OK, check.tick(0.3, vertical, false, ALLOWED, true));
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(MovementCheck.Verdict.OK, check.tick(0.3, 0, true, ALLOWED, true));
            }
        }
    }

    @Test
    void fallingIsNotFlying() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        double vertical = -0.1;
        for (int i = 0; i < 100; i++) {
            vertical = Math.max(-3.9, vertical - 0.08);
            assertEquals(MovementCheck.Verdict.OK, check.tick(0.2, vertical, false, ALLOWED, true));
        }
    }

    @Test
    void flightCheckOffNeverFlags() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        for (int i = 0; i < 100; i++) {
            assertEquals(MovementCheck.Verdict.OK, check.tick(0.1, 0.1, false, ALLOWED, false));
        }
    }

    @Test
    void resetForgetsTheBurst() {
        final MovementCheck check = new MovementCheck(MovementCheck.Config.DEFAULT);
        for (int i = 0; i < 10; i++) {
            check.tick(0.71, 0, true, ALLOWED, true);
        }
        check.reset();
        assertEquals(0, check.violation());
        assertEquals(0, check.windowSum());
        for (int i = 0; i < 40; i++) {
            assertEquals(MovementCheck.Verdict.OK, check.tick(0.3, 0, true, ALLOWED, true));
        }
    }
}
