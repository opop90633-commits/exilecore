package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-27：伺服器忙的時候施工少花一點時間。 */
class MapWorksBudgetTest {

    @Test
    void budgetShrinksWhenServerIsBusy() {
        assertEquals(MapWorks.NANOS_PER_TICK, MapWorks.budgetNanos(20));
        assertEquals(MapWorks.NANOS_PER_TICK, MapWorks.budgetNanos(40));
        assertEquals(MapWorks.BUSY_NANOS_PER_TICK, MapWorks.budgetNanos(45));
    }
}
