package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-29 照 PoE2 Dark Effigy：數身上混沌持續傷害的減益（每層中毒、技能的混沌減益、混沌點燃各一個）。 */
class ChaosDotsTest {

    @Test
    void countsEveryChaosDamageOverTime() {
        final AilmentState state = new AilmentState();
        assertEquals(0, state.chaosDots(0));
        state.addPoison(10, 100, UUID.randomUUID(), 10);
        state.addPoison(10, 100, UUID.randomUUID(), 10);
        state.addDebuff("debuff.essence_drain", new AilmentState.Debuff(5, 100, null, false, 0, 5, 100, 0));
        assertEquals(3, state.chaosDots(0), "兩層中毒＋汲魂彈");
        state.addIgnite(5, 100, null, false);
        assertEquals(3, state.chaosDots(0), "一般的點燃不算");
        state.addIgnite(50, 100, null, true);
        assertEquals(4, state.chaosDots(0), "黯炎的混沌點燃算");
        assertEquals(0, state.chaosDots(200), "都過期了");
    }
}
