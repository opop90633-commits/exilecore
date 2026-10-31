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
    @Test
    void pricesUseTheActualCounterCurrencyAndAreVolumeWeighted() {
        final Instant now = Instant.parse("2026-10-31T12:00:00Z");
        final List<ExchangeMarketJson.TradeRow> trades = List.of(
                // 4 個混沌換 1 個至臻 → 混沌用至臻計價每個 0.25；至臻用混沌計價每個 4
                new ExchangeMarketJson.TradeRow(now.minusSeconds(3600), "chaos_orb", 4, "zenith_orb", 1),
                // 1 換 1；加權後混沌 (1 + 1) / (4 + 1) = 0.4
                new ExchangeMarketJson.TradeRow(now.minusSeconds(1800), "zenith_orb", 1, "chaos_orb", 1),
                // 10 個富豪換 2 個混沌：富豪用混沌計價每個 0.2，不換算成至臻
                new ExchangeMarketJson.TradeRow(now.minusSeconds(900), "regal_orb", 10, "chaos_orb", 2));
        final Map<String, Map<String, Map<String, Double>>> daily = ExchangeMarketJson.dailyPrices(trades, ZONE, now);
        assertEquals(0.4, daily.get("chaos_orb").get("zenith_orb").get("2026-10-31"), 1e-9);
        assertEquals(2.5, daily.get("zenith_orb").get("chaos_orb").get("2026-10-31"), 1e-9); // 5 個混沌 / 2 個至臻
        assertEquals(0.2, daily.get("regal_orb").get("chaos_orb").get("2026-10-31"), 1e-9);
        assertEquals(5.0, daily.get("chaos_orb").get("regal_orb").get("2026-10-31"), 1e-9);
        assertFalse(daily.get("regal_orb").containsKey("zenith_orb"), "沒直接換過的不算");
    }

    @Test
    void jsonHasMainCounterPairsAndMergedOrdersWithoutNames() {
        final Instant now = Instant.parse("2026-10-31T12:00:00Z");
        final List<ExchangeMarketJson.TradeRow> trades = List.of(
                new ExchangeMarketJson.TradeRow(now.minusSeconds(30 * 3600), "chaos_orb", 2, "zenith_orb", 1),
                new ExchangeMarketJson.TradeRow(now.minusSeconds(3600), "chaos_orb", 1, "zenith_orb", 1),
                new ExchangeMarketJson.TradeRow(now.minusSeconds(600), "chaos_orb", 1, "regal_orb", 3));
        final List<ExchangeMarketJson.OrderView> orders = List.of(
                new ExchangeMarketJson.OrderView("chaos_orb", "zenith_orb", 2, 1, 5),
                new ExchangeMarketJson.OrderView("chaos_orb", "zenith_orb", 2, 1, 3),
                new ExchangeMarketJson.OrderView("zenith_orb", "chaos_orb", 1, 3, 0));
        final byte[] bytes = ExchangeMarketJson.build(List.of(new ExchangeMarketJson.Meta("zenith_orb", "至臻石", "一般通貨", true),
                        new ExchangeMarketJson.Meta("chaos_orb", "混沌石", "一般通貨", true),
                        new ExchangeMarketJson.Meta("exalted_orb", "崇高石", "一般通貨", true)), trades, orders,
                ZONE, now, "2026-10-31 20:00");
        final String text = new String(bytes, StandardCharsets.UTF_8);
        final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        final JsonObject chaos = root.getAsJsonArray("currencies").get(1).getAsJsonObject();
        assertEquals("zenith_orb", chaos.get("main").getAsString(), "7 天內跟至臻換了 3 個、跟富豪 1 個");
        assertEquals(1, chaos.get("last").getAsDouble(), 1e-9);
        assertEquals(100, chaos.get("change24").getAsDouble(), 1e-6, "0.5 → 1：漲 100%");
        assertFalse(chaos.has("ref"), "沒有參考價");
        assertFalse(root.getAsJsonArray("currencies").get(2).getAsJsonObject().has("main"), "沒成交過");
        final JsonObject regal = root.getAsJsonObject("pairs").getAsJsonObject("chaos_orb").getAsJsonObject("regal_orb");
        assertEquals(3, regal.get("last").getAsDouble(), 1e-9, "用富豪換的就用富豪計價");
        assertEquals(2, root.getAsJsonObject("pairs").getAsJsonObject("chaos_orb").getAsJsonObject("zenith_orb")
                .getAsJsonArray("daily").size(), "台北時間跨了一天：兩天");
        assertEquals(1, root.getAsJsonArray("orders").size(), "同價合併、沒有剩的不列");
        assertEquals(8, root.getAsJsonArray("orders").get(0).getAsJsonArray().get(4).getAsInt());
        assertTrue(!text.contains("account") && !text.contains("player"));
    }
}
