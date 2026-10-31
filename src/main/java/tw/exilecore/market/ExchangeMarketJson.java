package tw.exilecore.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 2026-10-31 照站長：通貨交易所的行情網頁（/market）的資料。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>2026-11-01 照站長：不用參考價值換算，價格全照實際成交——X 用 Y 換的，就記成「1 個 X ＝ 幾個 Y」；
 *       一筆成交兩邊都算（X 用 Y 計價、Y 用 X 計價）。</li>
 *   <li>每種通貨的「主要計價通貨」＝最近 7 天換手最多的那種對面通貨（7 天沒成交看全部）；列表照它顯示。</li>
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
    /** 主要計價通貨看最近幾天的量。 */
    static final int MAIN_DAYS = 7;

    private ExchangeMarketJson() {
    }

    /** 加總用：對面通貨的個數、這種通貨的個數、筆數。 */
    static final class Sum {
        double counter;
        long units;
        int count;

        void add(final int counterAmount, final int amount) {
            counter += counterAmount;
            units += amount;
            count++;
        }

        Double vwap() {
            return units <= 0 ? null : counter / units;
        }
    }

    /** 一個交易對（X 用 Y 計價）的統計。 */
    static final class Pair {
        final TreeMap<String, Sum> daily = new TreeMap<>();
        final TreeMap<Long, Sum> hourly = new TreeMap<>();
        final Sum last24 = new Sum();
        final Sum prev24 = new Sum();
        final Sum recent = new Sum(); // 最近 MAIN_DAYS 天
        final Sum all = new Sum();
        double lastPrice;
        long lastAt = Long.MIN_VALUE;
    }

    /** 統計全部成交：通貨 → 對面通貨 → 交易對。 */
    static Map<String, Map<String, Pair>> pairs(final List<TradeRow> trades, final ZoneId zone, final Instant now) {
        final Map<String, Map<String, Pair>> out = new LinkedHashMap<>();
        final Instant dayAgo = now.minus(24, ChronoUnit.HOURS);
        final Instant twoDaysAgo = now.minus(48, ChronoUnit.HOURS);
        final Instant hourlyFrom = now.minus(HOURLY_DAYS, ChronoUnit.DAYS);
        final Instant mainFrom = now.minus(MAIN_DAYS, ChronoUnit.DAYS);
        for (final TradeRow trade : trades) {
            if (trade.giveAmount() <= 0 || trade.wantAmount() <= 0 || trade.give().equals(trade.want())) {
                continue;
            }
            final String day = trade.at().atZone(zone).toLocalDate().toString();
            final long hour = trade.at().truncatedTo(ChronoUnit.HOURS).toEpochMilli();
            final long at = trade.at().toEpochMilli();
            for (int side = 0; side < 2; side++) {
                final String x = side == 0 ? trade.give() : trade.want();
                final String y = side == 0 ? trade.want() : trade.give();
                final int amount = side == 0 ? trade.giveAmount() : trade.wantAmount();
                final int counter = side == 0 ? trade.wantAmount() : trade.giveAmount();
                final Pair pair = out.computeIfAbsent(x, k -> new LinkedHashMap<>()).computeIfAbsent(y, k -> new Pair());
                pair.daily.computeIfAbsent(day, k -> new Sum()).add(counter, amount);
                if (!trade.at().isBefore(hourlyFrom)) {
                    pair.hourly.computeIfAbsent(hour, k -> new Sum()).add(counter, amount);
                }
                if (!trade.at().isBefore(dayAgo)) {
                    pair.last24.add(counter, amount);
                } else if (!trade.at().isBefore(twoDaysAgo)) {
                    pair.prev24.add(counter, amount);
                }
                if (!trade.at().isBefore(mainFrom)) {
                    pair.recent.add(counter, amount);
                }
                pair.all.add(counter, amount);
                if (at >= pair.lastAt) {
                    pair.lastAt = at;
                    pair.lastPrice = counter / (double) amount;
                }
            }
        }
        return out;
    }

    /** 主要計價通貨：最近 7 天換手最多的對面通貨；7 天沒成交看全部。沒成交過回傳 null。 */
    static String mainCounter(final Map<String, Pair> counters) {
        if (counters == null || counters.isEmpty()) {
            return null;
        }
        String best = null;
        long bestUnits = 0;
        for (final Map.Entry<String, Pair> e : counters.entrySet()) {
            if (e.getValue().recent.units > bestUnits) {
                best = e.getKey();
                bestUnits = e.getValue().recent.units;
            }
        }
        if (best != null) {
            return best;
        }
        for (final Map.Entry<String, Pair> e : counters.entrySet()) {
            if (e.getValue().all.units > bestUnits) {
                best = e.getKey();
                bestUnits = e.getValue().all.units;
            }
        }
        return best;
    }

    private static void stats(final JsonObject o, final Pair pair) {
        o.addProperty("last", round(pair.lastPrice));
        o.addProperty("lastAt", pair.lastAt);
        o.addProperty("vol24", pair.last24.units);
        o.addProperty("count24", pair.last24.count);
        o.addProperty("count", pair.all.count);
        if (pair.last24.vwap() != null && pair.prev24.vwap() != null && pair.prev24.vwap() > 0) {
            o.addProperty("change24", round((pair.last24.vwap() / pair.prev24.vwap() - 1) * 100));
        }
    }

    /**
     * 做出網頁的資料。
     *
     * @param stamp 資料時間（給人看）
     */
    public static byte[] build(final List<Meta> currencies, final List<TradeRow> trades, final List<OrderView> orders,
                               final ZoneId zone, final Instant now, final String stamp) {
        final Map<String, Map<String, Pair>> all = pairs(trades, zone, now);
        final JsonObject root = new JsonObject();
        root.addProperty("stamp", stamp);
        root.addProperty("trades", trades.size());
        final JsonArray list = new JsonArray();
        for (final Meta meta : currencies) {
            final JsonObject c = new JsonObject();
            c.addProperty("id", meta.id());
            c.addProperty("name", meta.name());
            c.addProperty("group", meta.group());
            c.addProperty("icon", meta.icon());
            final Map<String, Pair> counters = all.get(meta.id());
            final String main = mainCounter(counters);
            if (main != null) {
                c.addProperty("main", main);
                stats(c, counters.get(main));
            }
            list.add(c);
        }
        root.add("currencies", list);
        final JsonObject pairs = new JsonObject();
        for (final Map.Entry<String, Map<String, Pair>> entry : all.entrySet()) {
            final JsonObject byCounter = new JsonObject();
            for (final Map.Entry<String, Pair> e : entry.getValue().entrySet()) {
                final Pair pair = e.getValue();
                final JsonObject p = new JsonObject();
                stats(p, pair);
                final JsonArray days = new JsonArray();
                for (final Map.Entry<String, Sum> row : pair.daily.entrySet()) {
                    final JsonArray r = new JsonArray();
                    r.add(row.getKey());
                    r.add(round(row.getValue().vwap()));
                    r.add(row.getValue().units);
                    r.add(row.getValue().count);
                    days.add(r);
                }
                p.add("daily", days);
                final JsonArray hours = new JsonArray();
                for (final Map.Entry<Long, Sum> row : pair.hourly.entrySet()) {
                    final JsonArray r = new JsonArray();
                    r.add(row.getKey());
                    r.add(round(row.getValue().vwap()));
                    r.add(row.getValue().units);
                    hours.add(r);
                }
                p.add("hourly", hours);
                byCounter.add(e.getKey(), p);
            }
            pairs.add(entry.getKey(), byCounter);
        }
        root.add("pairs", pairs);
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

    /** 測試、除錯用：每天的 VWAP（通貨 → 對面通貨 → 日期 → 價格）。 */
    static Map<String, Map<String, Map<String, Double>>> dailyPrices(final List<TradeRow> trades, final ZoneId zone, final Instant now) {
        final Map<String, Map<String, Map<String, Double>>> out = new TreeMap<>();
        pairs(trades, zone, now).forEach((x, counters) -> counters.forEach((y, pair) -> {
            final Map<String, Double> days = new TreeMap<>();
            pair.daily.forEach((day, sum) -> days.put(day, sum.vwap()));
            out.computeIfAbsent(x, k -> new TreeMap<>()).put(y, days);
        }));
        return out;
    }
}
