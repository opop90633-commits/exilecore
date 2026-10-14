package tw.exilecore.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：交易所照市價算數量、市場深度、價格微調。 */
class ExchangeDepthTest {

    private static final UUID ME = UUID.randomUUID();
    private static int seq;

    private static ExchangeBook.Order order(final UUID who, final String give, final int giveTotal, final String want, final int wantTotal) {
        return new ExchangeBook.Order("o" + seq++, who, "p", give, giveTotal, want, wantTotal, seq, giveTotal, true, "op");
    }

    /** 別人賣崇高（divine）收混沌（chaos）：10 個 1:100、10 個 1:120。 */
    private static ExchangeBook book() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order(UUID.randomUUID(), "divine", 10, "chaos", 1200));
        book.add(order(UUID.randomUUID(), "divine", 10, "chaos", 1000));
        book.add(order(UUID.randomUUID(), "divine", 5, "chaos", 500)); // 同價位
        return book;
    }

    private static ExchangeMath.Simulator sim(final ExchangeBook book) {
        return (give, want) -> book.simulate("chaos", give, "divine", want, ME).received();
    }

    @Test
    void wantAtMarketEatsCheapestFirst() {
        final ExchangeBook book = book();
        assertEquals(new ExchangeMath.Amounts(100, 1), ExchangeMath.cheapestFor(sim(book), 1, 99999));
        // 15 個在 100、再 2 個在 120：限價要開到 120（17 × 120），實際只付 1500 + 240，多的退回
        final ExchangeMath.Amounts amounts = ExchangeMath.cheapestFor(sim(book), 17, 99999);
        assertEquals(new ExchangeMath.Amounts(17 * 120, 17), amounts);
        final ExchangeBook.Result result = book.simulate("chaos", amounts.give(), "divine", amounts.want(), ME);
        assertEquals(17, result.received());
        assertEquals(1500 + 240, result.paid());
        assertNull(ExchangeMath.cheapestFor(sim(book), 26, 99999), "市場上只有 25 個");
    }

    @Test
    void giveAtMarketBuysAsManyAsItCan() {
        final ExchangeBook book = book();
        assertEquals(new ExchangeMath.Amounts(1000, 10), ExchangeMath.mostFor(sim(book), 1000, 99999));
        assertNull(ExchangeMath.mostFor(sim(book), 50, 99999), "給太少：一個都換不到");
        final ExchangeMath.Amounts big = ExchangeMath.mostFor(sim(book), 1800, 99999);
        assertEquals(15, big.want(), "限價單：要吃到 120 的價位，整張單的價格就得開到 120（17 × 120 > 1800），所以只買得到 15 個");
        final ExchangeBook.Result result = book.simulate("chaos", big.give(), "divine", big.want(), ME);
        assertEquals(15, result.received());
        assertTrue(!result.resting(), "照市價算的不留掛單");
    }

    @Test
    void levelsMergeSamePrice() {
        final List<ExchangeMath.Level> levels = ExchangeMath.levels(book().makersFor("chaos", "divine", ME), ME);
        assertEquals(2, levels.size());
        assertEquals(15, levels.get(0).stock());
        assertEquals(2, levels.get(0).orders());
        assertEquals(100, levels.get(0).lotWant() / levels.get(0).lotGive());
        assertEquals(10, levels.get(1).stock());
    }

    @Test
    void competitorsBestBidFirstAndMarksMine() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order(UUID.randomUUID(), "chaos", 900, "divine", 10));
        book.add(order(ME, "chaos", 950, "divine", 10));
        final List<ExchangeBook.Order> bids = book.competitors("chaos", "divine");
        assertEquals(ME, bids.getFirst().account(), "出價高的在前");
        assertTrue(ExchangeMath.levels(bids, ME).getFirst().mine());
    }

    @Test
    void nudgeMovesTheBiggerSide() {
        assertEquals(new ExchangeMath.Amounts(105, 1), ExchangeMath.nudge(100, 1, true, 9999));
        assertEquals(new ExchangeMath.Amounts(95, 1), ExchangeMath.nudge(100, 1, false, 9999));
        assertEquals(new ExchangeMath.Amounts(1, 95), ExchangeMath.nudge(1, 100, true, 9999), "給 1 換 100：出價高 = 少要一點");
        assertEquals(new ExchangeMath.Amounts(2, 1), ExchangeMath.nudge(1, 1, true, 9999), "至少動 1 個");
        assertEquals(new ExchangeMath.Amounts(1, 1), ExchangeMath.nudge(1, 1, false, 9999), "不會低於 1");
    }
}
