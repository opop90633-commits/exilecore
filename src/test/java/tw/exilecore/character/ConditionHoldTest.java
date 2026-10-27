package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 效能：條件造成的屬性表重算每人每秒最多一次（最多晚 0.5 秒，不會明顯延遲）。 */
class ConditionHoldTest {

    @Test
    void holdsAtMostOneSecondAfterAConditionRebuild() {
        final ConditionHold hold = new ConditionHold();
        final UUID player = UUID.randomUUID();
        assertTrue(hold.ready(player, 100), "沒重算過：馬上檢查");
        hold.flipped(player, 100);
        assertFalse(hold.ready(player, 110), "10 tick 後（下一次檢查）先等一下");
        assertTrue(hold.ready(player, 120), "1 秒後一定再檢查");
        assertTrue(ConditionHold.HOLD_TICKS <= 20, "最多 1 秒");
        hold.forget(player);
        assertTrue(hold.ready(player, 101));
    }

    @Test
    void playersAreIndependent() {
        final ConditionHold hold = new ConditionHold();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        hold.flipped(a, 100);
        assertTrue(hold.ready(b, 105));
        assertFalse(hold.ready(a, 105));
    }
}
