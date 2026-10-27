package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 2026-10-27 效能：同一個 tick、中毒沒變就用上次算好的；加了、清了、過期了照樣更新。 */
class PoisonCacheTest {

    @Test
    void cachedWithinTickButUpdatesOnChange() {
        final AilmentState state = new AilmentState();
        final UUID player = UUID.randomUUID();
        state.addPoison(3, 40, player, 8);
        final var first = state.poisons(10);
        assertSame(first, state.poisons(10), "同一個 tick、沒變：同一份");
        state.addPoison(5, 60, null, 8);
        assertEquals(2, state.poisons(10).size(), "加了一層：重算");
        assertEquals(1, state.poisons(45).size(), "第一層過期");
        state.clear();
        assertEquals(0, state.poisons(45).size(), "清掉了");
    }
}
