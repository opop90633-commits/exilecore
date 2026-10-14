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

    // ---- 2026-10：像 PoE2 一樣「打數量就照市價」與市場深度 ----

    /** 試算一張單（給 give 個、要 want 個）馬上能換到幾個；交易所用 {@link ExchangeBook#simulate}。 */
    @FunctionalInterface
    interface Simulator {
        int received(int give, int want);
    }

    /**
     * 照市價「我要 want 個」：馬上能換到 want 個的最少給多少（照掛著的單由便宜到貴吃下去）。
     * 市場上不夠 want 個：回傳 null（呼叫端照最後一個價位算剩下掛著的）。
     */
    static Amounts cheapestFor(final Simulator book, final int want, final int max) {
        final int w = clamp(want, max);
        if (book.received(max, w) < w) {
            return null;
        }
        int low = 1;
        int high = max;
        while (low < high) {
            final int mid = low + (high - low) / 2;
            if (book.received(mid, w) >= w) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return new Amounts(low, w);
    }

    /**
     * 照市價「我給 give 個」：馬上能換到最多幾個（每一個都馬上成交，不留掛單）。一個都換不到回傳 null。
     */
    static Amounts mostFor(final Simulator book, final int give, final int max) {
        final int g = clamp(give, max);
        if (book.received(g, 1) < 1) {
            return null;
        }
        int low = 1;
        int high = max;
        while (low < high) {
            final int mid = low + (high - low + 1) / 2;
            if (book.received(g, mid) >= mid) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return new Amounts(g, low);
    }

    /**
     * 價格往上（多給一點，比較快成交）或往下（少給一點，掛著等）調一檔：大約 5%，至少動 1 個。
     * 比較多的那一邊動（給 1 換 100 的單動「要」那一邊，才不會一下差 100%）。
     */
    static Amounts nudge(final int give, final int want, final boolean up, final int max) {
        if (give >= want) {
            final int step = Math.max(1, (int) Math.round(give * 0.05));
            return new Amounts(clamp(give + (up ? step : -step), max), want);
        }
        final int step = Math.max(1, (int) Math.round(want * 0.05));
        return new Amounts(give, clamp(want + (up ? -step : step), max));
    }

    /**
     * 一個價位（市場深度的一列）：每 lotGive 個（掛單拿出來的）換 lotWant 個，這個價位總共還有 stock 個、幾張單、有沒有自己的。
     */
    record Level(int lotGive, int lotWant, int stock, int orders, boolean mine) {
    }

    /** 把掛單照價位合併（保持原本的順序：第一張出現的價位在前）。 */
    static java.util.List<Level> levels(final java.util.List<ExchangeBook.Order> orders, final java.util.UUID me) {
        final java.util.Map<String, int[]> merged = new java.util.LinkedHashMap<>();
        for (final ExchangeBook.Order order : orders) {
            final int[] row = merged.computeIfAbsent(order.lotGive() + ":" + order.lotWant(),
                    key -> new int[] {order.lotGive(), order.lotWant(), 0, 0, 0});
            row[2] += order.lots() * order.lotGive();
            row[3]++;
            if (me != null && me.equals(order.account())) {
                row[4] = 1;
            }
        }
        final java.util.List<Level> list = new java.util.ArrayList<>();
        for (final int[] row : merged.values()) {
            list.add(new Level(row[0], row[1], row[2], row[3], row[4] == 1));
        }
        return list;
    }
}
