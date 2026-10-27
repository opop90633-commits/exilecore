package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 效能：裝備比較只在背包內容或身上的裝備變了才重算。 */
class CompareGateTest {

    @Test
    void periodicCheckSkipsWhenNothingChanged() {
        final CompareGate gate = new CompareGate();
        final UUID player = UUID.randomUUID();
        assertTrue(gate.changed(player, 42), "還沒比過");
        gate.applied(player, 42);
        assertFalse(gate.changed(player, 42), "增益、充能讓屬性表重算，但背包沒變：不重比");
        assertTrue(gate.changed(player, 43), "背包變了（撿到、換手上那格）：重比");
    }

    @Test
    void gearChangeForcesRecompare() {
        final CompareGate gate = new CompareGate();
        final UUID player = UUID.randomUUID();
        gate.applied(player, 42);
        gate.force(player);
        assertTrue(gate.changed(player, 42), "身上的裝備換了：背包一樣也要重比");
    }
}
