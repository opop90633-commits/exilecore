package tw.exilecore.market;

/**
 * 交易所的「一鍵帶入」：照一個價格（每 den 個我給的換 num 個我要的）從一邊的數量算出另一邊。純邏輯。
 * <p>
 * 算出來的那一邊超出範圍（少於 1、多於上限）時，連另一邊一起調整，價格才不會偏掉
 * （例如市價是 100 個換 1 個，我給 1 個不會變成「1 換 1」的單，而是改成給 100 個換 1 個）。
 * 我要的一律無條件捨去、我給的一律無條件進位：照這個價格下單，一定不比市價差（跟那張單合得來）。
 */
final class ExchangeMath {

    /** 一鍵帶入的結果。 */
    record Amounts(int give, int want) {
    }

    private ExchangeMath() {
    }

    /** 我給 give 個，照每 den 個換 num 個，能換到幾個（連 give 一起調整到合理範圍）。 */
    static Amounts fromGive(final int give, final long num, final long den, final int max) {
        if (num <= 0 || den <= 0 || max <= 0) {
            return new Amounts(clamp(give, max), 1);
        }
        long g = clamp(give, max);
        long w = g * num / den;
        if (w < 1) {
            // 給太少，連 1 個都換不到：給到剛好換 1 個
            g = Math.min(max, ceilDiv(den, num));
            w = Math.max(1, g * num / den);
        }
        if (w > max) {
            // 換到的超過上限：只要上限那麼多，少給一點
            w = max;
            g = Math.max(1, Math.min(g, w * den / num));
            w = Math.max(1, Math.min(max, g * num / den));
        }
        return new Amounts((int) g, (int) w);
    }

    /** 我要 want 個，照每 den 個換 num 個，要給幾個（連 want 一起調整到合理範圍）。 */
    static Amounts fromWant(final int want, final long num, final long den, final int max) {
        if (num <= 0 || den <= 0 || max <= 0) {
            return new Amounts(1, clamp(want, max));
        }
        long w = clamp(want, max);
        long g = ceilDiv(w * den, num);
        if (g > max) {
            // 要給的超過上限：給上限那麼多，能換多少換多少
            g = max;
            w = Math.max(1, g * num / den);
        }
        return new Amounts((int) Math.max(1, g), (int) w);
    }

    private static long ceilDiv(final long a, final long b) {
        return (a + b - 1) / b;
    }

    private static int clamp(final long value, final int max) {
        return (int) Math.max(1, Math.min(Math.max(1, max), value));
    }
}
