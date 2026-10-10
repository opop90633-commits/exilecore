package tw.exilecore.economy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 參考價值跟著市價走（純邏輯，不依賴 Bukkit）：用通貨交易所最近幾天的成交，算每種通貨值多少金幣。
 * <ul>
 *   <li>金幣不能交易，市場只看得到「通貨之間」的比例，所以要一個基準：基準通貨的價值固定（economy.yml 的 values），
 *       其他通貨照成交比例換算。</li>
 *   <li>每一筆 A 換 B 的成交，都給 A 一個「市價」＝ B 的價值 × B 的數量 ÷ A 的數量（B 也一樣）。</li>
 *   <li>同樣兩個帳號之間的成交（不管誰掛單、成交幾次）合起來只算一票（照成交金額平均）；取所有票的中位數。
 *       所以一個人跟自己的分身對倒很多次也只有一票，少數幾組奇怪的價格拉不動。</li>
 *   <li>少於 minPairs 組不同的買賣雙方就不動；一天最多變動 maxStep；不會低於設定值的 lowest 倍、高於 highest 倍。</li>
 * </ul>
 */
public final class ReferenceValues {

    /**
     * 設定（economy.yml 的 reference）。
     *
     * @param enabled  false = 一直用 values 的設定值
     * @param anchor   基準通貨（價值固定）
     * @param days     看最近幾天的成交
     * @param minPairs 少於幾組不同的買賣雙方（兩個帳號算一組）就不動
     * @param maxStep  一天最多變動多少（0.2 = 20%）
     * @param lowest   最低到設定值的幾倍
     * @param highest  最高到設定值的幾倍
     */
    public record Settings(boolean enabled, String anchor, int days, int minPairs, double maxStep, double lowest, double highest) {
        public static Settings defaults() {
            return new Settings(true, "zenith_orb", 14, 2, 0.2, 0.25, 4.0);
        }
    }

    /**
     * 一筆成交：一邊付 giveAmount 個 give、拿到 wantAmount 個 want。
     *
     * @param maker 掛單的帳號（null = 不知道：這一筆自己算一組）
     * @param taker 吃單的帳號（同上）
     */
    public record Trade(String give, int giveAmount, String want, int wantAmount, String maker, String taker) {
        /** 不知道是誰的成交（每一筆自己算一組）。 */
        public Trade(final String give, final int giveAmount, final String want, final int wantAmount) {
            this(give, giveAmount, want, wantAmount, null, null);
        }
    }

    /**
     * 算的結果。
     *
     * @param values 每種能標價的通貨的新價值（照設定檔的順序）
     * @param pairs  每種通貨有幾組不同的買賣雙方成交過（用來算的票數）
     */
    public record Result(Map<String, Long> values, Map<String, Integer> pairs) {
    }

    /** 一組買賣雙方對一種通貨的成交：成交金額加總、金額 × 市價加總（平均＝後者 ÷ 前者）。 */
    private static final class Vote {
        private double weight;
        private double weightedValue;

        private void add(final double value, final double worth) {
            weight += worth;
            weightedValue += value * worth;
        }

        private double value() {
            return weightedValue / weight;
        }
    }

    private ReferenceValues() {
    }

    /** 這一筆成交的「買賣雙方」：兩個帳號（不分誰掛單）；不知道是誰的，每一筆自己一組。 */
    private static String pairKey(final Trade trade, final int index) {
        if (trade.maker() == null || trade.taker() == null) {
            return "#" + index;
        }
        return trade.maker().compareTo(trade.taker()) <= 0 ? trade.maker() + "|" + trade.taker() : trade.taker() + "|" + trade.maker();
    }

    /**
     * 算新的參考價值。
     *
     * @param trades     最近幾天的成交
     * @param current    目前用的價值（前一天算的；沒有的用設定值）
     * @param configured 設定檔的價值（只有這裡面的通貨會算；也是上下限的基準）
     */
    public static Result compute(final List<Trade> trades, final Map<String, Long> current, final Map<String, Long> configured,
                                 final Settings settings) {
        final Map<String, Long> base = new LinkedHashMap<>();
        for (final Map.Entry<String, Long> entry : configured.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                final Long now = current.get(entry.getKey());
                base.put(entry.getKey(), now != null && now > 0 ? now : entry.getValue());
            }
        }
        // 通貨 → 買賣雙方 → 那一組的票
        final Map<String, Map<String, Vote>> votes = new LinkedHashMap<>();
        for (int i = 0; i < trades.size(); i++) {
            final Trade trade = trades.get(i);
            if (trade.giveAmount() <= 0 || trade.wantAmount() <= 0 || trade.give().equals(trade.want())) {
                continue;
            }
            final Long giveValue = base.get(trade.give());
            final Long wantValue = base.get(trade.want());
            if (giveValue == null || wantValue == null) {
                continue; // 其中一邊不能標價：沒有比例可以換算
            }
            final String pair = pairKey(trade, i);
            // 給出去的那種：值 = 拿到的那種的價值 × 拿到幾個 ÷ 給幾個；成交金額用另一邊（已知的那一邊）算
            final double wantWorth = (double) wantValue * trade.wantAmount();
            final double giveWorth = (double) giveValue * trade.giveAmount();
            votes.computeIfAbsent(trade.give(), id -> new LinkedHashMap<>()).computeIfAbsent(pair, key -> new Vote())
                    .add(wantWorth / trade.giveAmount(), wantWorth);
            votes.computeIfAbsent(trade.want(), id -> new LinkedHashMap<>()).computeIfAbsent(pair, key -> new Vote())
                    .add(giveWorth / trade.wantAmount(), giveWorth);
        }
        final Map<String, Long> values = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final Map.Entry<String, Long> entry : base.entrySet()) {
            final String id = entry.getKey();
            final List<Double> list = new ArrayList<>();
            for (final Vote vote : votes.getOrDefault(id, Map.of()).values()) {
                if (vote.weight > 0) {
                    list.add(vote.value());
                }
            }
            counts.put(id, list.size());
            final long configuredValue = configured.get(id);
            if (!settings.enabled()) {
                values.put(id, configuredValue);
                continue;
            }
            if (id.equals(settings.anchor())) {
                values.put(id, configuredValue); // 基準不動
                continue;
            }
            double value = entry.getValue();
            if (list.size() >= Math.max(1, settings.minPairs())) {
                final double target = median(list);
                final double up = 1 + Math.max(0, settings.maxStep());
                final double ratio = Math.max(1 / up, Math.min(up, target / value));
                value *= ratio;
            }
            final double low = configuredValue * Math.max(0, settings.lowest());
            final double high = configuredValue * Math.max(settings.lowest(), settings.highest());
            value = Math.max(low, Math.min(high, value));
            values.put(id, Math.max(1, Math.round(value)));
        }
        return new Result(Collections.unmodifiableMap(values), Collections.unmodifiableMap(counts));
    }

    /** 中位數（偶數個取中間兩個的平均）。 */
    static double median(final List<Double> values) {
        final List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        final int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
