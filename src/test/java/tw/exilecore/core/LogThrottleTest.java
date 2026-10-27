package tw.exilecore.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 2026-10-27：每 tick 出錯時，同一個錯一分鐘只寫一次完整堆疊。 */
class LogThrottleTest {

    private static RuntimeException boom() {
        return new IllegalStateException("壞了");
    }

    @Test
    void sameErrorIsWrittenOncePerMinute() {
        final long[] now = {0};
        final LogThrottle throttle = new LogThrottle(() -> now[0]);
        final RuntimeException error = boom();
        assertEquals(0, throttle.allow("召喚物更新出錯", error), "第一次寫");
        for (int tick = 0; tick < 1199; tick++) { // 接下來一分鐘每 tick 都出錯
            now[0] += 50_000_000L;
            assertEquals(-1, throttle.allow("召喚物更新出錯", error));
        }
        now[0] += LogThrottle.QUIET_NANOS;
        assertEquals(1199, throttle.allow("召喚物更新出錯", error), "一分鐘後再寫一次，報上略過的次數");
    }

    @Test
    void differentErrorsAreSeparate() {
        final LogThrottle throttle = new LogThrottle(() -> 0L);
        assertEquals(0, throttle.allow("A", boom()));
        assertEquals(0, throttle.allow("B", boom()), "訊息不同各寫一次");
        assertEquals(0, throttle.allow("A", new NullPointerException()), "例外種類不同也各寫一次");
        assertNotEquals(LogThrottle.key("A", boom()), LogThrottle.key("A", new NullPointerException()));
    }
}
