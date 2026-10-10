package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 地圖 Boss 換階段：一次一個、至少隔 3 秒（這段時間打不動）、護盾還在時不換。 */
class PhaseStepTest {

    @Test
    void phasesAdvanceOneAtATime() {
        assertEquals(PhaseStep.Step.ENTER, PhaseStep.step(0.66, 0.5, 1000, PhaseStep.NEVER, false), "血量到了、還沒換過");
        assertEquals(PhaseStep.Step.NONE, PhaseStep.step(0.66, 0.7, 1000, PhaseStep.NEVER, false), "血量還沒到");
        assertEquals(PhaseStep.Step.ENTER, PhaseStep.step(0.66, 0.66, 1000, PhaseStep.NEVER, false), "剛好在門檻上也算");
        assertEquals(PhaseStep.Step.WAIT, PhaseStep.step(0.33, 0.2, 1000, 1000 - PhaseStep.GAP_TICKS + 1, false),
                "剛換過：等 3 秒才換下一個（這段時間打不動）");
        assertEquals(PhaseStep.Step.ENTER, PhaseStep.step(0.33, 0.2, 1000, 1000 - PhaseStep.GAP_TICKS, false));
        assertEquals(PhaseStep.Step.NONE, PhaseStep.step(0.33, 0.2, 5000, PhaseStep.NEVER, true), "上一個階段的護盾還在：護盾自己處理");
        assertEquals(PhaseStep.Step.NONE, PhaseStep.step(0.33, 0.5, 1010, 1000, false), "剛換過但血還沒到下一個：照常打");
        assertEquals(PhaseStep.Step.ENTER, PhaseStep.step(0.33, 0.2, 0, PhaseStep.NEVER, false), "伺服器剛開（tick 很小）也不會溢位");
    }

    @Test
    void burstingThroughTwoThresholdsEntersThemThreeSecondsApart() {
        // 一下從 70% 打到 20%（MapBosses 每 10 tick 檢查一次）：以前兩個階段在同一個瞬間，現在隔 3 秒，中間打不動
        final double[] thresholds = {0.66, 0.33};
        int phase = 0;
        long last = PhaseStep.NEVER;
        final List<Long> entered = new ArrayList<>();
        final List<Long> held = new ArrayList<>();
        for (long tick = 0; tick <= 200; tick += 10) {
            if (phase >= thresholds.length) {
                break;
            }
            switch (PhaseStep.step(thresholds[phase], 0.2, tick, last, false)) {
                case ENTER -> {
                    phase++;
                    last = tick;
                    entered.add(tick);
                }
                case WAIT -> held.add(tick);
                case NONE -> {
                }
            }
        }
        assertEquals(List.of(0L, PhaseStep.GAP_TICKS), entered);
        assertEquals(List.of(10L, 20L, 30L, 40L, 50L), held, "等第二個階段的這 3 秒 Boss 打不動");
    }
}
