package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-02 照站長：通貨交易所原生畫面的資料（插件 tw.exilecore.market.ExchangeFeed 的狀態）與算法（排序、搜尋、比例的寫法）。
 * 純 Java（測試用）。這次之前交易所只有箱子選單。
 */
public final class ExchangeModel {

    /** 一種通貨：代號、名稱、圖示（原版物品＋自訂模型）、分類、你有幾個、最近換過的順序（-1 = 沒有）。 */
    public record Currency(String id, String name, String item, String model, String group, int have, int recent) {
    }

    /** 深度表的一個價位：lotGive 個「我給的」換 lotWant 個「我要的」、還有多少、幾張單、有沒有你的。 */
    public record Level(int lotGive, int lotWant, int stock, int orders, boolean mine) {
    }

    /** 你的一張掛單。 */
    public record Order(String id, String give, String want, int giveTotal, int wantTotal, int taken, int remaining, boolean confirmed,
                        String ago) {
    }

    /** 照現在的數量下單會怎樣（伺服器模擬的）。 */
    public record Preview(int received, int paid, int restGive, int restWant, int refund, boolean resting, long fee) {
    }

    /** 最近的成交：last 是 1 個 a 換幾個 b（inverse = a 是我給的、b 是我要的；不然 a 是我要的）。 */
    public record Market(double last, Double change24, long vol24, int count24, boolean inverse) {
    }

    public final List<Currency> currencies;
    public final Map<String, Currency> byId;
    public final String give;
    public final String want;
    public final int giveAmount;
    public final int wantAmount;
    public final String source;
    public final int max;
    public final int maxOrders;
    public final long gold;
    public final List<Level> asks;
    public final List<Level> bids;
    /** 參考價：{我要的值, 我給的值}（金幣；1 個我要的 ≈ ref[0]/ref[1] 個我給的；null = 沒有）。 */
    public final long[] ref;
    public final Market market;
    public final Preview preview;
    public final List<Order> orders;
    public final String message;
    public final boolean open;

    public ExchangeModel(final JsonObject root) {
        final List<Currency> list = new ArrayList<>();
        for (final JsonElement element : array(root, "currencies")) {
            final JsonObject c = element.getAsJsonObject();
            list.add(new Currency(string(c, "id", ""), string(c, "n", ""), string(c, "i", "minecraft:paper"), c.has("m") ? string(c, "m", null) : null,
                    string(c, "g", ""), integer(c, "have", 0), integer(c, "r", -1)));
        }
        currencies = List.copyOf(list);
        final Map<String, Currency> map = new HashMap<>();
        for (final Currency currency : currencies) {
            map.put(currency.id(), currency);
        }
        byId = Map.copyOf(map);
        give = root.has("give") ? string(root, "give", null) : null;
        want = root.has("want") ? string(root, "want", null) : null;
        giveAmount = integer(root, "giveAmount", 1);
        wantAmount = integer(root, "wantAmount", 1);
        source = string(root, "source", "");
        max = integer(root, "max", 10000);
        maxOrders = integer(root, "maxOrders", 0);
        gold = root.has("gold") ? root.get("gold").getAsLong() : 0;
        asks = levels(root, "asks");
        bids = levels(root, "bids");
        if (root.has("ref") && root.get("ref").isJsonArray() && root.getAsJsonArray("ref").size() == 2) {
            ref = new long[] {root.getAsJsonArray("ref").get(0).getAsLong(), root.getAsJsonArray("ref").get(1).getAsLong()};
        } else {
            ref = null;
        }
        if (root.has("market") && root.get("market").isJsonObject()) {
            final JsonObject m = root.getAsJsonObject("market");
            market = new Market(m.has("last") ? m.get("last").getAsDouble() : 0, m.has("change24") ? m.get("change24").getAsDouble() : null,
                    m.has("vol24") ? m.get("vol24").getAsLong() : 0, integer(m, "count24", 0), m.has("inverse") && m.get("inverse").getAsBoolean());
        } else {
            market = null;
        }
        if (root.has("preview") && root.get("preview").isJsonObject()) {
            final JsonObject p = root.getAsJsonObject("preview");
            preview = new Preview(integer(p, "received", 0), integer(p, "paid", 0), integer(p, "restGive", 0), integer(p, "restWant", 0),
                    integer(p, "refund", 0), p.has("resting") && p.get("resting").getAsBoolean(), p.has("fee") ? p.get("fee").getAsLong() : 0);
        } else {
            preview = null;
        }
        final List<Order> mine = new ArrayList<>();
        for (final JsonElement element : array(root, "orders")) {
            final JsonObject o = element.getAsJsonObject();
            mine.add(new Order(string(o, "id", ""), string(o, "give", ""), string(o, "want", ""), integer(o, "giveTotal", 0),
                    integer(o, "wantTotal", 0), integer(o, "taken", 0), integer(o, "remaining", 0),
                    !o.has("confirmed") || o.get("confirmed").getAsBoolean(), string(o, "ago", "")));
        }
        orders = List.copyOf(mine);
        message = string(root, "msg", "");
        open = root.has("open") && root.get("open").getAsBoolean();
    }

    public boolean ready() {
        return give != null && want != null && !give.equals(want);
    }

    public String name(final String id) {
        final Currency currency = id == null ? null : byId.get(id);
        return currency == null ? String.valueOf(id) : currency.name();
    }

    public int have(final String id) {
        final Currency currency = id == null ? null : byId.get(id);
        return currency == null ? 0 : currency.have();
    }

    /**
     * 選通貨的清單：照搜尋字過濾（名稱或代號，不分大小寫），排序是最近換過的、你有的（多的先）、其他照原本的順序（設定檔的順序）。
     */
    public List<Currency> picker(final String search) {
        final String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final List<Currency> out = new ArrayList<>();
        for (final Currency currency : currencies) {
            if (query.isEmpty() || currency.name().toLowerCase(Locale.ROOT).contains(query) || currency.id().toLowerCase(Locale.ROOT).contains(query)) {
                out.add(currency);
            }
        }
        final Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < currencies.size(); i++) {
            order.put(currencies.get(i).id(), i);
        }
        out.sort(Comparator.<Currency>comparingInt(c -> c.recent() >= 0 ? 0 : c.have() > 0 ? 1 : 2)
                .thenComparingInt(c -> c.recent() >= 0 ? c.recent() : 0)
                .thenComparingInt(c -> c.recent() < 0 && c.have() > 0 ? -c.have() : 0)
                .thenComparingInt(c -> order.getOrDefault(c.id(), 0)));
        return out;
    }

    /** 「1 A ≈ x B」：每 den 個 A 換 num 個 B；小於 1 時反過來寫（「x A ≈ 1 B」），比較好讀。跟箱子選單一樣。 */
    public static String ratio(final String a, final long num, final String b, final long den) {
        if (den <= 0) {
            return "—";
        }
        final double value = (double) num / den;
        if (value >= 1 || value <= 0) {
            return "1 " + a + " ≈ " + number(value) + " " + b;
        }
        return number(1 / value) + " " + a + " ≈ 1 " + b;
    }

    /** 數字：100 以上整數（千分位）、10 以上一位小數、其他兩位小數（去掉多的 0）。 */
    public static String number(final double value) {
        if (value >= 100) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    /** 深度表一個價位的寫法：「1 我要 ≈ x 我給」。 */
    public String levelText(final Level level) {
        return ratio(name(want), level.lotGive(), name(give), level.lotWant());
    }

    /** 最近成交的寫法（「1 我要 ≈ x 我給」，24 小時漲跌）；沒有成交回傳空字串。 */
    public String marketText() {
        if (market == null || market.last() <= 0) {
            return "";
        }
        final double perWant = market.inverse() ? 1 / market.last() : market.last(); // 1 個我要的換幾個我給的
        final String change = market.change24() == null ? "" : String.format(Locale.ROOT, "（24 小時 %s%.1f%%）",
                market.change24() >= 0 ? "+" : "", market.change24());
        return "1 " + name(want) + " ≈ " + number(perWant) + " " + name(give) + change;
    }

    /** 可以馬上換到的總數。 */
    public int askTotal() {
        int total = 0;
        for (final Level level : asks) {
            total += level.stock();
        }
        return total;
    }

    /** 數字框裡打的字 → 1～max；打壞了回傳 -1。 */
    public static int parseAmount(final String text, final int max) {
        try {
            final long value = Long.parseLong(text == null ? "" : text.trim().replace(",", ""));
            return (int) Math.max(1, Math.min(max, value));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static List<Level> levels(final JsonObject root, final String key) {
        final List<Level> out = new ArrayList<>();
        for (final JsonElement element : array(root, key)) {
            final JsonObject o = element.getAsJsonObject();
            out.add(new Level(integer(o, "lg", 1), integer(o, "lw", 1), integer(o, "stock", 0), integer(o, "orders", 0),
                    o.has("mine") && o.get("mine").getAsBoolean()));
        }
        return List.copyOf(out);
    }

    private static JsonArray array(final JsonObject root, final String key) {
        return root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(final JsonObject root, final String key, final String fallback) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : fallback;
    }

    private static int integer(final JsonObject root, final String key, final int fallback) {
        try {
            return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsInt() : fallback;
        } catch (final RuntimeException e) {
            return fallback;
        }
    }
}
