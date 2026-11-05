package tw.exilecore.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-05 照站長「拉霸機新增歷史損益」：存檔的格式讀寫、換日歸零今天的。 */
class SlotsHistoryTest {

    @Test
    void encodesAndRollsOverDays() {
        final SlotsService.History history = new SlotsService.History();
        history.roll(100);
        history.spins = 12;
        history.wins = 3;
        history.wagered = 12_000;
        history.returned = 9_500;
        history.best = 5_000;
        history.jackpots = 1;
        history.dayWagered = 2_000;
        history.dayReturned = 3_000;
        history.dayBest = 3_000;
        final SlotsService.History back = SlotsService.History.decode(history.encode());
        assertEquals(history.encode(), back.encode());
        assertEquals(-2_500, back.net());
        assertEquals(1_000, back.dayNet());
        back.roll(101);
        assertEquals(0, back.dayWagered, "換日：今天的歸零");
        assertEquals(12_000, back.wagered, "累計留著");
        assertEquals(0, SlotsService.History.decode(null).spins);
        assertEquals(0, SlotsService.History.decode("壞掉:x").spins);
    }
}
