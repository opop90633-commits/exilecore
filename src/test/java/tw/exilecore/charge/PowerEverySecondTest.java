package tw.exilecore.charge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：最近沒有失去銳星時每秒獲得一顆。 */
class PowerEverySecondTest {
    @Test
    void readyOnlyWhenNotLostRecently() {
        assertTrue(ChargeService.powerReady(null, 100), "沒失去過");
        assertFalse(ChargeService.powerReady(100L, 150), "最近失去過");
        assertFalse(ChargeService.powerReady(100L, 180));
        assertTrue(ChargeService.powerReady(100L, 181), "超過 4 秒");
    }
}
