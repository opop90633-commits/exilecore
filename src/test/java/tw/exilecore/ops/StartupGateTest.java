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
}
