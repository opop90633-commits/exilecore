package tw.exilecore.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02 照站長：市場報告的追逐級傳奇（帳本 chase:<代號>）：每種出了幾件、從哪裡來；名單裡沒出過的也列，不混進通貨。 */
class ChaseReportTest {

    @Test
    void chaseSectionCountsDropsBySource() {
        final List<String> days = List.of("2026-11-01", "2026-11-02");
        final List<EconomyRepository.Flow> flows = List.of(
                new EconomyRepository.Flow("2026-11-01", "chase:headhunter", EconomySources.DROP, 2),
                new EconomyRepository.Flow("2026-11-02", "chase:headhunter", EconomySources.GAMBLE, 1),
                new EconomyRepository.Flow("2026-11-02", "glimmer_orb", EconomySources.DROP, 9));
        final Map<String, String> names = new LinkedHashMap<>();
        names.put("headhunter", "獵首");
        names.put("mageblood", "法血");
        final byte[] bytes = EconomyReportJson.build(days, List.of(new EconomyReportJson.CurrencyMeta("glimmer_orb", "微光石", 10)), flows,
                List.of(), List.of(), Map.of(), false, "glimmer_orb", "now", names, Map.of("headhunter", 4L, "starforge", 1L));
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.getAsJsonArray("currencies").size(), "追逐級不混進通貨");
        final JsonArray chase = root.getAsJsonArray("chase");
        assertEquals(3, chase.size(), "沒出過的也列；名單外但全服有的也列");
        final JsonObject hh = chase.get(0).getAsJsonObject();
        assertEquals("獵首", hh.get("name").getAsString());
        assertEquals(4, hh.get("held").getAsLong(), "全服現有");
        assertEquals(2, hh.getAsJsonArray("produced").get(0).getAsLong());
        assertEquals(1, hh.getAsJsonArray("produced").get(1).getAsLong());
        assertEquals(1, hh.getAsJsonObject("sources").getAsJsonObject("7").get(EconomySources.GAMBLE).getAsLong());
        assertEquals(0, chase.get(1).getAsJsonObject().getAsJsonArray("produced").get(1).getAsLong());
    }
}
