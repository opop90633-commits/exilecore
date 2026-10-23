package tw.exilecore.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupGateTest {

    @Test
    void secondsLeftRoundsUpAndOpensAfterTheDelay() {
        assertEquals(0, StartupGate.secondsLeft(0, 1_000_000));
        assertEquals(25, StartupGate.secondsLeft(1_025_000, 1_000_000));
        assertEquals(1, StartupGate.secondsLeft(1_000_001, 1_000_000), "不足一秒算一秒");
        assertEquals(0, StartupGate.secondsLeft(1_000_000, 1_000_000));
    }

    @Test
    void delayIsClamped() {
        assertEquals(0, StartupGate.clampDelay(-5));
        assertEquals(25, StartupGate.clampDelay(25));
        assertEquals(300, StartupGate.clampDelay(9999));
    }

    @org.junit.jupiter.api.Test
    void playersAreStaggeredAfterOpening() {
        final long open = 1_015_000;
        long last = 0;
        last = StartupGate.slot(open, last);
        assertEquals(open, last, "第一個人開放就進");
        last = StartupGate.slot(open, last);
        assertEquals(open + StartupGate.STAGGER_MILLIS, last);
        last = StartupGate.slot(open, last);
        assertEquals(open + 2 * StartupGate.STAGGER_MILLIS, last);
        // 很久以後才來的：照開放時間（不會被前面的人拖到）
        assertEquals(open, StartupGate.slot(open, open - 10_000));
    }
}
