package tw.exilecore.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通貨交易所的掛單簿：整批成交、舊單的價格、便宜的先、剩下的照自己的價格掛（不會更貴）。 */
class ExchangeBookTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private static ExchangeBook.Order order(final String id, final UUID who, final String give, final int giveTotal,
                                            final String want, final int wantTotal, final long at) {
        return new ExchangeBook.Order(id, who, who.toString().substring(0, 5), give, giveTotal, want, wantTotal, at, giveTotal, true, null);
    }

    @Test
    void lotsUseTheSimplestRatio() {
        final ExchangeBook.Order order = order("o", ALICE, "zenith", 10, "bounty", 30, 0);
        assertEquals(1, order.lotGive());
        assertEquals(3, order.lotWant());
        assertEquals(10, order.lots());
        assertEquals(30, order.remainingWant());
        final ExchangeBook.Order odd = order("p", ALICE, "zenith", 7, "bounty", 2, 0);
        assertEquals(7, odd.lotGive(), "7:2 沒辦法再約分，只能整批 7 換 2");
        assertEquals(2, odd.lotWant());
        assertEquals(1, odd.lots());
    }

    @Test
    void takerGetsTheMakersPriceAndTheRestRests() {
        final ExchangeBook book = new ExchangeBook();
        // Alice 賣 10 個至臻石，每個要 3 個豐饒石
        book.add(order("alice", ALICE, "zenith", 10, "bounty", 30, 0));
        // Bob 願意用 14 個豐饒石換 4 個至臻石（每個最多 3.5）
        final ExchangeBook.Result result = book.match("bounty", 14, "zenith", 4, BOB);
        assertEquals(1, result.fills().size());
        assertEquals(4, result.received(), "照 Alice 的價格（3）買到 4 個");
        assertEquals(12, result.paid());
        assertFalse(result.resting(), "要的都買到了，不用掛");
        assertEquals(2, result.refund(), "比預算便宜，剩下的不拿");
        final ExchangeBook.Order alice = book.get("alice");
        assertEquals(6, alice.remaining());
        assertEquals(4, alice.taken());
        assertEquals(4, result.fills().getFirst().makerGave());
        assertEquals(12, result.fills().getFirst().makerGot());
    }

    @Test
    void cheaperMakersFirstThenOlder() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("expensive", ALICE, "zenith", 5, "bounty", 20, 0));   // 4 / 個
        book.add(order("cheap-new", CAROL, "zenith", 2, "bounty", 6, 20));    // 3 / 個，晚掛
        book.add(order("cheap-old", CAROL, "zenith", 2, "bounty", 6, 10));    // 3 / 個，早掛
        final List<ExchangeBook.Order> makers = book.makersFor("bounty", "zenith", BOB);
        assertEquals(List.of("cheap-old", "cheap-new", "expensive"), makers.stream().map(ExchangeBook.Order::id).toList());
        final ExchangeBook.Result result = book.match("bounty", 100, "zenith", 5, BOB);
        assertEquals(5, result.received());
        assertEquals(2 * 3 + 2 * 3 + 1 * 4, result.paid(), "先買便宜的 4 個，再買貴的 1 個");
        assertNull(book.get("cheap-old"), "全部成交的舊單移除");
        assertNull(book.get("cheap-new"));
        assertEquals(4, book.get("expensive").remaining());
    }

    @Test
    void tooExpensiveMakersAreNotTaken() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("alice", ALICE, "zenith", 1, "bounty", 5, 0)); // 每個要 5
        final ExchangeBook.Result result = book.match("bounty", 12, "zenith", 4, BOB); // 每個最多 3
        assertTrue(result.fills().isEmpty());
        assertTrue(result.resting());
        assertEquals(12, result.restGive());
        assertEquals(4, result.restWant());
        assertEquals(0, result.refund());
        assertEquals(1, book.get("alice").remaining(), "沒成交的舊單不動");
    }

    @Test
    void restingNeverPaysMoreThanTheLimit() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("alice", ALICE, "zenith", 1, "bounty", 3, 0)); // 1 個，每個 3
        // Bob：7 個豐饒石換 2 個至臻石（每個最多 3.5）。先用 3 買 1 個，剩下想要 1 個，最多押 floor(1 × 7 / 2) = 3
        final ExchangeBook.Result result = book.match("bounty", 7, "zenith", 2, BOB);
        assertEquals(1, result.received());
        assertEquals(3, result.paid());
        assertEquals(3, result.restGive(), "剩下的照原本的價格算，無條件捨去");
        assertEquals(1, result.restWant());
        assertEquals(1, result.refund(), "用不到的 1 個不拿");
        assertEquals(7, result.paid() + result.restGive() + result.refund());
    }

    @Test
    void largeLotsAreSkippedForSmallerOnes() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("big", ALICE, "zenith", 10, "bounty", 30, 0));    // 一批 1:3（約分後）…
        book.add(order("chunky", CAROL, "zenith", 7, "bounty", 21, 5)); // 也是 1:3
        book.add(order("odd", ALICE, "zenith", 2, "bounty", 5, 1));     // 一批 2 個要 5（2.5 / 個，最便宜）
        // Bob 只有 4 個豐饒石、想要 1 個：odd 那批要 5 個付不起，跳過它改買 big 的 1 個（3 個）
        final ExchangeBook.Result result = book.match("bounty", 4, "zenith", 1, BOB);
        assertEquals(1, result.received());
        assertEquals(3, result.paid());
        assertEquals(2, book.get("odd").remaining(), "買不起一整批的單不動");
    }

    @Test
    void ownAndUnconfirmedOrdersAreNotMatched() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("mine", BOB, "zenith", 5, "bounty", 5, 0));
        book.add(new ExchangeBook.Order("pending", ALICE, "alice", "zenith", 5, "bounty", 5, 1, 5, false, "op-1"));
        assertTrue(book.makersFor("bounty", "zenith", BOB).isEmpty(), "自己的單、還沒寫進資料庫的單都不能成交");
        book.confirm("op-1");
        assertEquals(1, book.makersFor("bounty", "zenith", BOB).size(), "寫進去之後就可以了");
    }

    @Test
    void simulateDoesNotChangeTheBook() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("alice", ALICE, "zenith", 3, "bounty", 9, 0));
        final ExchangeBook.Result preview = book.simulate("bounty", 9, "zenith", 3, BOB);
        assertEquals(3, preview.received());
        assertEquals(3, book.get("alice").remaining(), "試算不改單");
        final ExchangeBook.Result real = book.match("bounty", 9, "zenith", 3, BOB);
        assertEquals(preview.received(), real.received());
        assertEquals(preview.paid(), real.paid());
        assertNull(book.get("alice"));
    }

    @Test
    void lowballOrdersStillRestAtTheirOwnPrice() {
        final ExchangeBook book = new ExchangeBook();
        // 沒有可以成交的單：整張照自己開的價格掛著（1 個換 7 個也可以掛，只是沒人會跟）
        final ExchangeBook.Result result = book.simulate("bounty", 1, "zenith", 7, BOB);
        assertTrue(result.resting());
        assertEquals(1, result.restGive());
        assertEquals(7, result.restWant());
        assertEquals(0, result.refund());
    }

    @Test
    void ordersOfAnAccount() {
        final ExchangeBook book = new ExchangeBook();
        book.add(order("a1", ALICE, "zenith", 1, "bounty", 3, 0));
        book.add(order("b1", BOB, "zenith", 1, "bounty", 3, 1));
        book.add(order("a2", ALICE, "bounty", 3, "zenith", 1, 2));
        assertEquals(List.of("a1", "a2"), book.of(ALICE).stream().map(ExchangeBook.Order::id).toList());
        assertEquals(3, book.all().size());
        book.remove("a1");
        assertEquals(1, book.of(ALICE).size());
    }
}
