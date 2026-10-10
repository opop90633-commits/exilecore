package tw.exilecore.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 交易所一鍵帶入的換算：捨去、進位，超出範圍時兩邊一起調整（價格不偏掉）。 */
class ExchangeMathTest {

    @Test
    void simpleRates() {
        // 每 1 個我給的換 3 個我要的
        assertEquals(new ExchangeMath.Amounts(10, 30), ExchangeMath.fromGive(10, 3, 1, 9999));
        assertEquals(new ExchangeMath.Amounts(4, 10), ExchangeMath.fromWant(10, 3, 1, 9999), "要 10 個：給 4 個（進位）");
        // 參考價：一個值 120 金幣換一個值 40 金幣的
        assertEquals(new ExchangeMath.Amounts(10, 30), ExchangeMath.fromGive(10, 120, 40, 9999));
        // 每 3 個換 1 個：10 個換 3 個（捨去）
        assertEquals(new ExchangeMath.Amounts(10, 3), ExchangeMath.fromGive(10, 1, 3, 9999));
        assertEquals(new ExchangeMath.Amounts(30, 10), ExchangeMath.fromWant(10, 1, 3, 9999));
    }

    @Test
    void outOfRangeAdjustsBothSides() {
        // 市價 100 個換 1 個：給 1 個連 1 個都換不到 → 改成給 100 換 1，不會變成「1 換 1」
        assertEquals(new ExchangeMath.Amounts(100, 1), ExchangeMath.fromGive(1, 1, 100, 5000));
        // 要 100 個要給 10000 個，超過上限 5000 → 給 5000 換 50
        assertEquals(new ExchangeMath.Amounts(5000, 50), ExchangeMath.fromWant(100, 1, 100, 5000));
        // 換到的超過上限 → 只要上限那麼多，少給一點
        assertEquals(new ExchangeMath.Amounts(3333, 9999), ExchangeMath.fromGive(5000, 3, 1, 9999));
    }

    @Test
    void neverWorseThanTheQuotedPrice() {
        // 照算出來的數量下單：每個我要的付的 ≥ 報價（跟那張單合得來）
        for (int amount = 1; amount < 400; amount += 7) {
            for (final long[] price : new long[][] {{3, 1}, {1, 3}, {7, 5}, {1, 100}, {250, 1}}) {
                final ExchangeMath.Amounts a = ExchangeMath.fromGive(amount, price[0], price[1], 5000);
                final ExchangeMath.Amounts b = ExchangeMath.fromWant(amount, price[0], price[1], 5000);
                for (final ExchangeMath.Amounts x : new ExchangeMath.Amounts[] {a, b}) {
                    assertTrue(x.give() >= 1 && x.want() >= 1 && x.give() <= 5000 && x.want() <= 5000, x.toString());
                    // want / give ≤ num / den
                    assertTrue(x.want() * price[1] <= x.give() * price[0], x + " 價格 " + price[0] + "/" + price[1]);
                }
            }
        }
    }
}
