package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 增益的總長：第一次看到、重新放了時重算。 */
class BuffTotalsTest {

    @Test
    void totals() {
        final BuffTotals totals = new BuffTotals();
        assertEquals(100, totals.observe("a", 100));
        totals.endRound();
        assertEquals(100, totals.observe("a", 97));
        totals.endRound();
        assertEquals(200, totals.observe("a", 200), "重新放了（剩的變多）");
        totals.endRound();
        totals.endRound(); // 這一輪沒看到：忘掉
        assertEquals(50, totals.observe("a", 50));
    }
}
