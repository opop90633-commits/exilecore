package tw.exilecore.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeMarketJsonTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final Map<String, Long> VALUES = Map.of("zenith_orb", 100L, "chaos_orb", 50L, "regal_orb", 10L);

    @Test
    void pricesAreInAnchorUnitsAndVolumeWeighted() {
        final Instant now = Instant.parse("2026-10-31T12:00:00Z");
        final List<ExchangeMarketJson.TradeRow> trades = List.of(
                // 直接跟基準換：4 個混沌換 1 個至臻 → 每個 0.25
                new ExchangeMarketJson.TradeRow(now.minusSeconds(3600), "chaos_orb", 4, "zenith_orb", 1),
                // 1 個混沌換 1 個至臻 → 每個 1；加權後 (1 + 1) / (4 + 1) = 0.4
                new ExchangeMarketJson.TradeRow(now.minusSeconds(1800), "zenith_orb", 1, "chaos_orb", 1),
                // 跟別的通貨換：10 個富豪換 2 個混沌（混沌照參考價 0.5 個至臻）→ 每個富豪 0.1；混沌那邊照富豪的參考價也是每個 0.5
                new ExchangeMarketJson.TradeRow(now.minusSeconds(900), "regal_orb", 10, "chaos_orb", 2));
        final Map<String, Map<String, Double>> daily = ExchangeMarketJson.dailyPrices(trades, "zenith_orb", VALUES, ZONE);
        assertEquals(3.0 / 7, daily.get("chaos_orb").get("2026-10-31"), 1e-9); // (1 + 1 + 1) / (4 + 1 + 2)
        assertEquals(0.1, daily.get("regal_orb").get("2026-10-31"), 1e-9);
        assertFalse(daily.containsKey("zenith_orb"), "基準自己不算");
    }

    @Test
    void jsonHasCurrenciesHistoryAndMergedOrdersWithoutNames() {
        final Instant now = Instant.parse("2026-10-31T12:00:00Z");
        final List<ExchangeMarketJson.TradeRow> trades = List.of(
                new ExchangeMarketJson.TradeRow(now.minusSeconds(30 * 3600), "chaos_orb", 2, "zenith_orb", 1),
                new ExchangeMarketJson.TradeRow(now.minusSeconds(3600), "chaos_orb", 1, "zenith_orb", 1));
        final List<ExchangeMarketJson.OrderView> orders = List.of(
                new ExchangeMarketJson.OrderView("chaos_orb", "zenith_orb", 2, 1, 5),
                new ExchangeMarketJson.OrderView("chaos_orb", "zenith_orb", 2, 1, 3),
                new ExchangeMarketJson.OrderView("zenith_orb", "chaos_orb", 1, 3, 0));
        final byte[] bytes = ExchangeMarketJson.build(List.of(new ExchangeMarketJson.Meta("zenith_orb", "至臻石", "一般通貨", true),
                        new ExchangeMarketJson.Meta("chaos_orb", "混沌石", "一般通貨", true)), "zenith_orb", VALUES, Map.of(), trades, orders,
                ZONE, now, "2026-10-31 20:00");
        final String text = new String(bytes, StandardCharsets.UTF_8);
        final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        final JsonObject chaos = root.getAsJsonArray("currencies").get(1).getAsJsonObject();
        assertEquals(0.5, chaos.get("ref").getAsDouble(), 1e-9);
        assertEquals(1, chaos.get("vol24").getAsInt());
        assertEquals(100, chaos.get("change24").getAsDouble(), 1e-6, "0.5 → 1：漲 100%");
        assertEquals(2, root.getAsJsonObject("daily").getAsJsonArray("chaos_orb").size(), "台北時間跨了一天：兩天");
        assertEquals(1, root.getAsJsonArray("orders").size(), "同價合併、沒有剩的不列");
        assertEquals(8, root.getAsJsonArray("orders").get(0).getAsJsonArray().get(4).getAsInt());
        assertTrue(!text.contains("account") && !text.contains("player"));
    }
}
