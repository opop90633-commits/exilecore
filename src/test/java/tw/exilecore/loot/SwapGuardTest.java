package tw.exilecore.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-19 防最後一下換裝：變高要撐滿秒數才算，變低馬上算。 */
class SwapGuardTest {

    static final long HOLD = 10_000;

    @Test
    void firstValueCountsImmediately() {
        final SwapGuard.Track track = new SwapGuard.Track();
        assertEquals(1.2, track.update(1.2, 0, HOLD), 1e-9, "剛上線穿什麼都算");
    }

    @Test
    void increaseNeedsToBeHeld() {
        final SwapGuard.Track track = new SwapGuard.Track();
        track.update(0.3, 0, HOLD);
        assertEquals(0.3, track.update(1.5, 1_000, HOLD), 1e-9, "剛換上稀有度裝：還是舊的");
        assertEquals(0.3, track.update(1.5, 10_000, HOLD), 1e-9, "9 秒：還不算");
        assertEquals(1.5, track.update(1.5, 11_000, HOLD), 1e-9, "10 秒：算了");
    }

    @Test
    void decreaseCountsImmediately() {
        final SwapGuard.Track track = new SwapGuard.Track();
        track.update(1.5, 0, HOLD);
        assertEquals(0.3, track.update(0.3, 1_000, HOLD), 1e-9, "換成輸出裝：馬上掉");
        assertEquals(0.3, track.update(1.5, 2_000, HOLD), 1e-9, "最後一下換回來：不算");
    }

    @Test
    void partialDropKeepsTheClock() {
        final SwapGuard.Track track = new SwapGuard.Track();
        track.update(0.2, 0, HOLD);
        track.update(1.0, 1_000, HOLD);
        track.update(0.8, 5_000, HOLD); // 降一點但還比 0.2 高
        assertEquals(0.8, track.update(0.8, 11_000, HOLD), 1e-9, "從 1 秒起一直至少 0.8");
        assertEquals(0.8, track.update(1.4, 12_000, HOLD), 1e-9, "再換更高的：重新計時");
        assertEquals(1.4, track.update(1.4, 22_000, HOLD), 1e-9);
    }

    @Test
    void zeroHoldMeansOff() {
        final SwapGuard.Track track = new SwapGuard.Track();
        track.update(0.1, 0, 0);
        assertEquals(2.0, track.update(2.0, 1, 0), 1e-9);
    }
}
