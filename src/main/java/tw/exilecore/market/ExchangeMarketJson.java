package tw.exilecore.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 2026-10-31 照站長：通貨交易所的行情網頁（/market）的資料。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>價格都換成「基準通貨」（economy.yml 的 reference.anchor，預設至臻石）幾個：
 *       跟基準直接換的成交照實際比例；跟別的通貨換的，照那一天的參考價值換算對面那種通貨值多少基準。</li>
 *   <li>每天、每小時（最近 7 天）照成交量加權平均（VWAP）；量是那種通貨換手的個數。</li>
 *   <li>掛單只給價格與數量（沒有名字、帳號）。</li>
 * </ul>
 */
public final class ExchangeMarketJson {

    /** 一種通貨（group：分類的顯示名稱；icon：有沒有圖示）。 */
    public record Meta(String id, String name, String group, boolean icon) {
    }

    /** 一筆成交：giveAmount 個 give 換 wantAmount 個 want。 */
    public record TradeRow(Instant at, String give, int giveAmount, String want, int wantAmount) {
    }

    /** 掛著的單（同價合併）：一批是 lotGive 個 give 換 lotWant 個 want，還有 lots 批。 */
    public record OrderView(String give, String want, int lotGive, int lotWant, int lots) {
    }

    /** 每小時的走勢看最近幾天。 */
    static final int HOURLY_DAYS = 7;

    private ExchangeMarketJson() {
    }

    /** 加總用：基準等值、個數、筆數。 */
    static final class Sum {
        double anchor;
        long units;
        int count;

        void add(final double equivalent, final int amount) {
            anchor += equivalent;
            units += amount;
            count++;
        }

        Double vwap() {
            return units <= 0 ? null : anchor / units;
        }
    }

    /**
     * 一筆成交裡 side 那種通貨每個值多少基準（另一邊換算成基準）。換算不出來回傳 null。
     *
     * @param values 那一天的參考價值（通貨 → 金幣）
     */
    static Double priceOf(final String side, final TradeRow trade, final String anchor, final Map<String, Long> values) {
        final boolean give = side.equals(trade.give());
        final int amount = give ? trade.giveAmount() : trade.wantAmount();
        final String other = give ? trade.want() : trade.give();
        final int otherAmount = give ? trade.wantAmount() : trade.giveAmount();
        if (amount <= 0 || otherAmount <= 0) {
            return null;
        }
        if (other.equals(anchor)) {
            return otherAmount / (double) amount;
        }
        final Long otherValue = values.get(other);
        final Long anchorValue = values.get(anchor);
        if (otherValue == null || anchorValue == null || otherValue <= 0 || anchorValue <= 0) {
            return null;
        }
        return otherAmount * (otherValue / (double) anchorValue) / amount;
    }

    /**
     * 做出網頁的資料。
     *
     * @param current   現在的參考價值（通貨 → 金幣）
     * @param dayValues 每天的參考價值（yyyy-MM-dd → 通貨 → 金幣）；那天沒有的照 current
     * @param stamp     資料時間（給人看）
     */
    public static byte[] build(final List<Meta> currencies, final String anchor, final Map<String, Long> current,
                               final Map<String, Map<String, Long>> dayValues, final List<TradeRow> trades,
                               final List<OrderView> orders, final ZoneId zone, final Instant now, final String stamp) {
        final Map<String, TreeMap<String, Sum>> daily = new HashMap<>();
        final Map<String, TreeMap<Long, Sum>> hourly = new HashMap<>();
        final Map<String, Sum> last24 = new HashMap<>();
        final Map<String, Sum> prev24 = new HashMap<>();
        final Map<String, double[]> lastTrade = new HashMap<>(); // 通貨 → {價格, 時間毫秒}
        final Instant dayAgo = now.minus(24, ChronoUnit.HOURS);
        final Instant twoDaysAgo = now.minus(48, ChronoUnit.HOURS);
        final Instant hourlyFrom = now.minus(HOURLY_DAYS, ChronoUnit.DAYS);
        for (final TradeRow trade : trades) {
            final String day = trade.at().atZone(zone).toLocalDate().toString();
            final Map<String, Long> values = new HashMap<>(current);
            final Map<String, Long> thatDay = dayValues.get(day);
            if (thatDay != null) {
                values.putAll(thatDay);
            }
            for (final String side : new String[] {trade.give(), trade.want()}) {
                if (side.equals(anchor)) {
                    continue; // 基準自己永遠是 1
                }
                final Double price = priceOf(side, trade, anchor, values);
                if (price == null) {
                    continue;
                }
                final int amount = side.equals(trade.give()) ? trade.giveAmount() : trade.wantAmount();
                final double equivalent = price * amount;
                daily.computeIfAbsent(side, k -> new TreeMap<>()).computeIfAbsent(day, k -> new Sum()).add(equivalent, amount);
                if (!trade.at().isBefore(hourlyFrom)) {
                    final long hour = trade.at().truncatedTo(ChronoUnit.HOURS).toEpochMilli();
                    hourly.computeIfAbsent(side, k -> new TreeMap<>()).computeIfAbsent(hour, k -> new Sum()).add(equivalent, amount);
                }
                if (!trade.at().isBefore(dayAgo)) {
                    last24.computeIfAbsent(side, k -> new Sum()).add(equivalent, amount);
                } else if (!trade.at().isBefore(twoDaysAgo)) {
                    prev24.computeIfAbsent(side, k -> new Sum()).add(equivalent, amount);
                }
                final double[] seen = lastTrade.get(side);
                if (seen == null || trade.at().toEpochMilli() >= seen[1]) {
                    lastTrade.put(side, new double[] {price, trade.at().toEpochMilli()});
                }
            }
        }
        final JsonObject root = new JsonObject();
        root.addProperty("stamp", stamp);
        root.addProperty("anchor", anchor);
        root.addProperty("trades", trades.size());
        final JsonArray list = new JsonArray();
        final Long anchorValue = current.get(anchor);
        for (final Meta meta : currencies) {
            final JsonObject c = new JsonObject();
            c.addProperty("id", meta.id());
            c.addProperty("name", meta.name());
            c.addProperty("group", meta.group());
            c.addProperty("icon", meta.icon());
            final Long value = current.get(meta.id());
            if (value != null && value > 0 && anchorValue != null && anchorValue > 0) {
                c.addProperty("ref", round(value / (double) anchorValue));
            }
            final double[] seen = lastTrade.get(meta.id());
            if (seen != null) {
                c.addProperty("last", round(seen[0]));
                c.addProperty("lastAt", (long) seen[1]);
            }
            final Sum day = last24.get(meta.id());
            final Sum before = prev24.get(meta.id());
            c.addProperty("vol24", day == null ? 0 : day.units);
            c.addProperty("count24", day == null ? 0 : day.count);
            if (day != null && before != null && day.vwap() != null && before.vwap() != null && before.vwap() > 0) {
                c.addProperty("change24", round((day.vwap() / before.vwap() - 1) * 100));
            }
            list.add(c);
        }
        root.add("currencies", list);
        final JsonObject days = new JsonObject();
        for (final Map.Entry<String, TreeMap<String, Sum>> entry : daily.entrySet()) {
            final JsonArray rows = new JsonArray();
            for (final Map.Entry<String, Sum> row : entry.getValue().entrySet()) {
                final JsonArray r = new JsonArray();
                r.add(row.getKey());
                r.add(round(row.getValue().vwap()));
                r.add(row.getValue().units);
                r.add(row.getValue().count);
                rows.add(r);
            }
            days.add(entry.getKey(), rows);
        }
        root.add("daily", days);
        final JsonObject hours = new JsonObject();
        for (final Map.Entry<String, TreeMap<Long, Sum>> entry : hourly.entrySet()) {
            final JsonArray rows = new JsonArray();
            for (final Map.Entry<Long, Sum> row : entry.getValue().entrySet()) {
                final JsonArray r = new JsonArray();
                r.add(row.getKey());
                r.add(round(row.getValue().vwap()));
                r.add(row.getValue().units);
                rows.add(r);
            }
            hours.add(entry.getKey(), rows);
        }
        root.add("hourly", hours);
        // 掛單：同一個方向、同一個價格合併
        final Map<String, int[]> merged = new LinkedHashMap<>();
        for (final OrderView order : orders) {
            if (order.lots() <= 0 || order.lotGive() <= 0 || order.lotWant() <= 0) {
                continue;
            }
            final String key = order.give() + "|" + order.want() + "|" + order.lotGive() + "|" + order.lotWant();
            merged.computeIfAbsent(key, k -> new int[2])[0] += order.lots();
            merged.get(key)[1]++;
        }
        final JsonArray book = new JsonArray();
        for (final Map.Entry<String, int[]> entry : merged.entrySet()) {
            final String[] parts = entry.getKey().split("\\|");
            final JsonArray o = new JsonArray();
            o.add(parts[0]);
            o.add(parts[1]);
            o.add(Integer.parseInt(parts[2]));
            o.add(Integer.parseInt(parts[3]));
            o.add(entry.getValue()[0]); // 還有幾批
            o.add(entry.getValue()[1]); // 幾張單
            book.add(o);
        }
        root.add("orders", book);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    static double round(final Double value) {
        if (value == null) {
            return 0;
        }
        final double abs = Math.abs(value);
        final double scale = abs >= 100 ? 10 : abs >= 1 ? 1000 : 1_000_000;
        return Math.round(value * scale) / scale;
    }

    /** 測試、除錯用：每天的 VWAP（通貨 → 日期 → 價格）。 */
    static Map<String, Map<String, Double>> dailyPrices(final List<TradeRow> trades, final String anchor, final Map<String, Long> current,
                                                        final ZoneId zone) {
        final Map<String, Map<String, Sum>> sums = new TreeMap<>();
        for (final TradeRow trade : trades) {
            final String day = trade.at().atZone(zone).toLocalDate().toString();
            for (final String side : new String[] {trade.give(), trade.want()}) {
                if (side.equals(anchor)) {
                    continue;
                }
                final Double price = priceOf(side, trade, anchor, current);
                if (price != null) {
                    final int amount = side.equals(trade.give()) ? trade.giveAmount() : trade.wantAmount();
                    sums.computeIfAbsent(side, k -> new TreeMap<>()).computeIfAbsent(day, k -> new Sum()).add(price * amount, amount);
                }
            }
        }
        final Map<String, Map<String, Double>> out = new TreeMap<>();
        for (final Map.Entry<String, Map<String, Sum>> entry : sums.entrySet()) {
            final Map<String, Double> days = new TreeMap<>();
            entry.getValue().forEach((day, sum) -> days.put(day, sum.vwap()));
            out.put(entry.getKey(), days);
        }
        return out;
    }
}
