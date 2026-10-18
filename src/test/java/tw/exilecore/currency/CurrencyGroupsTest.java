package tw.exilecore.currency;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通貨袋、倉庫通貨頁的分組：每一組放得進一頁（45 格）。 */
class CurrencyGroupsTest {

    @Test
    void shippedCurrenciesFitInPages() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(Path.of("src/main/resources/data"));
        int total = 0;
        for (final CurrencyGroups.Group group : CurrencyGroups.Group.values()) {
            final List<CurrencyType> in = CurrencyGroups.in(currencies.all(), group);
            assertTrue(in.size() <= CurrencyGroups.PAGE_CAPACITY, group + " 超過一頁：" + in.size());
            assertTrue(!in.isEmpty(), group + " 是空的");
            total += in.size();
        }
        assertEquals(currencies.size(), total, "每種通貨剛好在一組裡");
        assertTrue(CurrencyGroups.overfull(currencies.all()).isEmpty());
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.of(currencies.get("glimmer_orb")));
        assertEquals(CurrencyGroups.Group.ESSENCE, CurrencyGroups.of(currencies.get("essence_body")));
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.of(currencies.get("catalyst_resist")));
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.of(currencies.get("omen_sinistral")));
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.of(currencies.get("distilled_ire")));
        // 未切割寶石（2026-10 算通貨）：技能寶石 1–21 級、輔助寶石 1–3 階各一種
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.of(currencies.get("uncut_skill_1")));
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.of(currencies.get("uncut_support_3")));
        assertEquals(24, CurrencyGroups.in(currencies.all(), CurrencyGroups.Group.GEM).size());
        // 2026-10-18：Boss 門票：事件入場券、巔峰碎片、天穹碎片、深淵星核、金庫之主的鑰印；共鳴石算一般通貨
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.of(currencies.get("resonance_orb")));
        for (final String id : List.of("ticket_shrine", "fragment_crucible", "sky_fragment", "abyss_core", "heist_finale_key")) {
            assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.of(currencies.get(id)), id);
        }
        assertEquals(7 + 4 + 3, CurrencyGroups.in(currencies.all(), CurrencyGroups.Group.KEYS).size());
        // 一打開先看有東西的那組；都沒有看一般通貨
        final Map<String, Integer> held = Map.of("distilled_guilt", 3);
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.first(currencies.all(), type -> held.getOrDefault(type.id(), 0)));
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.first(currencies.all(), type -> 0));
        final int[] counts = CurrencyGroups.kindsHeld(currencies.all(), type -> held.getOrDefault(type.id(), 0));
        assertEquals("一般通貨 0 種・未切割寶石 0 種・星魄 0 種・催化劑・星籤・蒸餾情緒 1 種・Boss 門票 0 種", CurrencyGroups.summary(counts));
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.Group.BASIC.next());
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.Group.BASIC.previous()); // 2026-10-18：Boss 門票在最後
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.Group.KEYS.previous());
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.Group.MECHANIC.next());
    }
}
