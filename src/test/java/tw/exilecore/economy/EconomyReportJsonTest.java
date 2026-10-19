package tw.exilecore.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 市場報告網頁的資料（/economy/data.json）：每天的產出、消耗、存量、參考價值，來源與去處分 7 天、30 天，沒有個人資料。 */
class EconomyReportJsonTest {

    @Test
    void webJsonHasDailySeriesAndPeriodTotals() {
        final List<String> days = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            days.add(String.format("2026-09-%02d", i));
        }
        final List<EconomyReportJson.CurrencyMeta> currencies = List.of(
                new EconomyReportJson.CurrencyMeta("glimmer_orb", "微光石", 10),
                new EconomyReportJson.CurrencyMeta("zenith_orb", "至臻石", 1500),
                new EconomyReportJson.CurrencyMeta("star_mirror", "映星鏡", 0, false));
        final List<EconomyRepository.Flow> flows = List.of(
                new EconomyRepository.Flow("2026-09-30", "glimmer_orb", EconomySources.DROP, 40),
                new EconomyRepository.Flow("2026-09-30", "glimmer_orb", EconomySources.SALVAGE, 5),
                new EconomyRepository.Flow("2026-09-30", "glimmer_orb", EconomySources.CRAFT, -15),
                new EconomyRepository.Flow("2026-09-01", "glimmer_orb", EconomySources.DROP, 100),
                new EconomyRepository.Flow("2026-08-31", "glimmer_orb", EconomySources.DROP, 999),
                new EconomyRepository.Flow("2026-09-29", EconomyRepository.GOLD, EconomySources.DROP, 5000),
                new EconomyRepository.Flow("2026-09-29", EconomyRepository.GOLD, EconomySources.GAMBLE, -1200),
                new EconomyRepository.Flow("2026-09-29", "unknown_orb", EconomySources.DROP, 7),
                new EconomyRepository.Flow("2026-09-30", "star_mirror", EconomySources.DROP, 1));
        final List<EconomyRepository.DayValue> stock = List.of(
                new EconomyRepository.DayValue("2026-09-30", "glimmer_orb", 300),
                new EconomyRepository.DayValue("2026-09-30", EconomyRepository.GOLD, 80_000));
        final List<EconomyRepository.DayValue> values = List.of(new EconomyRepository.DayValue("2026-09-30", "glimmer_orb", 12));
        final byte[] bytes = EconomyReportJson.build(days, currencies, flows, stock, values, Map.of("2026-09-30", 4), true, "zenith_orb",
                "2026-09-30 12:00");
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(root.get("follow").getAsBoolean());
        assertEquals("zenith_orb", root.get("anchor").getAsString());
        assertEquals("2026-09-30 12:00", root.get("generated").getAsString());
        assertEquals(30, root.getAsJsonArray("days").size());
        assertEquals("2026-09-01", root.getAsJsonArray("days").get(0).getAsString());

        final JsonArray currencyArray = root.getAsJsonArray("currencies");
        assertEquals(3, currencyArray.size(), "只有傳進來的通貨（照傳進來的順序）");
        final JsonObject mirror = currencyArray.get(2).getAsJsonObject();
        assertFalse(mirror.get("priced").getAsBoolean(), "沒有參考價值的通貨也列（2026-10），標成沒標價");
        assertEquals(1, mirror.getAsJsonArray("produced").get(29).getAsLong());
        assertTrue(currencyArray.get(0).getAsJsonObject().get("priced").getAsBoolean());
        final JsonObject glimmer = currencyArray.get(0).getAsJsonObject();
        assertEquals("glimmer_orb", glimmer.get("id").getAsString());
        assertEquals("微光石", glimmer.get("name").getAsString());
        assertEquals(10, glimmer.get("configured").getAsLong());
        assertEquals(30, glimmer.getAsJsonArray("produced").size());
        assertEquals(45, glimmer.getAsJsonArray("produced").get(29).getAsLong(), "掉落 40 ＋ 拆解 5");
        assertEquals(100, glimmer.getAsJsonArray("produced").get(0).getAsLong());
        assertEquals(0, glimmer.getAsJsonArray("produced").get(15).getAsLong(), "沒有紀錄的那天是 0");
        assertEquals(15, glimmer.getAsJsonArray("consumed").get(29).getAsLong(), "消耗是正數");
        assertTrue(glimmer.getAsJsonArray("stock").get(0).isJsonNull(), "還沒算過存量的那天是 null（不是 0）");
        assertEquals(300, glimmer.getAsJsonArray("stock").get(29).getAsLong());
        assertTrue(glimmer.getAsJsonArray("value").get(28).isJsonNull());
        assertEquals(12, glimmer.getAsJsonArray("value").get(29).getAsLong());
        final JsonObject sources = glimmer.getAsJsonObject("sources");
        assertEquals(40, sources.getAsJsonObject("7").get(EconomySources.DROP).getAsLong(), "7 天：只有 9/24 以後的");
        assertEquals(140, sources.getAsJsonObject("30").get(EconomySources.DROP).getAsLong(), "30 天：9/1 也算，8/31 不算");
        assertEquals(5, sources.getAsJsonObject("7").get(EconomySources.SALVAGE).getAsLong());
        assertEquals(15, glimmer.getAsJsonObject("sinks").getAsJsonObject("7").get(EconomySources.CRAFT).getAsLong());
        assertFalse(glimmer.getAsJsonObject("sinks").getAsJsonObject("7").has(EconomySources.DROP), "產出不會跑到去處");

        final JsonObject zenith = currencyArray.get(1).getAsJsonObject();
        assertEquals(0, zenith.getAsJsonArray("produced").get(29).getAsLong());
        assertEquals(0, zenith.getAsJsonObject("sources").getAsJsonObject("7").size());

        final JsonObject gold = root.getAsJsonObject("gold");
        assertEquals(5000, gold.getAsJsonArray("produced").get(28).getAsLong());
        assertEquals(1200, gold.getAsJsonArray("consumed").get(28).getAsLong());
        assertEquals(80_000, gold.getAsJsonArray("stock").get(29).getAsLong());
        assertEquals(1200, gold.getAsJsonObject("sinks").getAsJsonObject("30").get(EconomySources.GAMBLE).getAsLong());

        assertEquals(4, root.getAsJsonArray("active").get(29).getAsInt());
        assertEquals(0, root.getAsJsonArray("active").get(0).getAsInt());
        final JsonObject names = root.getAsJsonObject("sourceNames");
        assertEquals(EconomySources.sourceName(EconomySources.GAMBLE), names.get(EconomySources.GAMBLE).getAsString());
        for (final String source : names.keySet()) {
            assertFalse(names.get(source).getAsString().isBlank(), source);
        }
    }

    @Test
    void emptyDatabaseStillGivesAFullShape() {
        final byte[] bytes = EconomyReportJson.build(List.of("2026-10-01", "2026-10-02"),
                List.of(new EconomyReportJson.CurrencyMeta("glimmer_orb", "微光石", 10)), List.of(), List.of(), List.of(), Map.of(), false,
                "zenith_orb", "2026-10-02 00:00");
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(root.get("follow").getAsBoolean());
        final JsonObject glimmer = root.getAsJsonArray("currencies").get(0).getAsJsonObject();
        assertEquals(2, glimmer.getAsJsonArray("produced").size());
        assertEquals(2, glimmer.getAsJsonArray("stock").size());
        assertTrue(glimmer.getAsJsonArray("stock").get(1).isJsonNull());
        assertEquals(2, root.getAsJsonObject("gold").getAsJsonArray("consumed").size());
        assertEquals(2, root.getAsJsonArray("active").size());
        assertTrue(root.getAsJsonObject("gold").getAsJsonObject("sources").has("7"));
        assertTrue(root.getAsJsonObject("gold").getAsJsonObject("sources").has("30"));
    }

    /** 2026-10-19：管理員發放、扣除不列在市場報告（不算產出、消耗，也不在來源、用途裡）。 */
    @Test
    void adminFlowsAreHidden() {
        final List<String> days = List.of("2026-10-19");
        final List<EconomyReportJson.CurrencyMeta> currencies = List.of(new EconomyReportJson.CurrencyMeta("card_star_chaser", "追星者", 0, false));
        final List<EconomyRepository.Flow> flows = List.of(
                new EconomyRepository.Flow("2026-10-19", "card_star_chaser", EconomySources.DROP, 5),
                new EconomyRepository.Flow("2026-10-19", "card_star_chaser", EconomySources.ADMIN, 100),
                new EconomyRepository.Flow("2026-10-19", "card_star_chaser", EconomySources.ADMIN_TAKE, -5));
        final byte[] bytes = EconomyReportJson.build(days, currencies, flows, List.of(), List.of(), Map.of(), false, "zenith_orb", "2026-10-19 21:00");
        final JsonObject card = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("currencies").get(0).getAsJsonObject();
        assertEquals(5, card.getAsJsonArray("produced").get(0).getAsLong(), "只算掉落的 5，管理員發的 100 不算");
        assertEquals(0, card.getAsJsonArray("consumed").get(0).getAsLong(), "管理員扣除不算消耗");
        assertFalse(card.getAsJsonObject("sources").getAsJsonObject("7").has(EconomySources.ADMIN));
        assertFalse(card.getAsJsonObject("sinks").getAsJsonObject("7").has(EconomySources.ADMIN_TAKE));
    }
}
