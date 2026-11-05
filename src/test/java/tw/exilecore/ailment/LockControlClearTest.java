package tw.exilecore.ailment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 2026-11-05 照站長「鎖血階段完全不吃控場」：進鎖血時身上的控場清掉，傷害類異常照留。 */
class LockControlClearTest {

    @Test
    void clearsControlButKeepsDamageAilments() {
        final AilmentState state = new AilmentState();
        final long now = 100;
        state.freeze(now, 40, 0);
        state.lightStun(now + 10);
        state.addIgnite(50, now + 80, null);
        assertTrue(state.incapacitated(now));
        assertTrue(state.clearControl(now), "本來有控場");
        assertFalse(state.incapacitated(now), "凍結、輕暈清掉了");
        assertTrue(state.ignite(now) != null, "點燃照留");
        assertFalse(state.clearControl(now), "沒有控場時什麼都不做");
    }
}
