package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：時間泡泡讓身上的減益晚結束（凍結、暈眩、電擊也算）。 */
class TemporalBubbleTest {

    @Test
    void debuffsLastLongerInsideTheBubble() {
        final AilmentState state = new AilmentState();
        state.addIgnite(10, 100, null);
        state.applyChill(0.3, 100, 0);
        state.maim(100);
        state.addCorruptedBlood(5, 100, 0);
        state.extendDebuffs(5, 50);
        assertEquals(105, state.maimUntil());
        assertEquals(105, state.corruptedBloodUntil());
        assertTrue(state.ignite(102) != null, "點燃延長了");
        assertTrue(state.chill(102) > 0, "冰緩延長了");
        // 2026-10-23：凍結也延長（照 PoE2）
        state.freeze(50, 30, 60);
        state.extendDebuffs(5, 60);
        assertEquals(85, state.frozenUntil());
        assertEquals(110, state.maimUntil(), "第二次泡泡再延長一次");
        // 已經結束的不會復活
        state.extendDebuffs(5, 200);
        assertEquals(110, state.maimUntil());
        assertEquals(85, state.frozenUntil());
    }
}
