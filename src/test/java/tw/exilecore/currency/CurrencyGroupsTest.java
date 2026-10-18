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
        // 2026-10-18：門票／鑰匙：共鳴石與 ticket_ 開頭的事件入場券（放在最前面，一般通貨那頁快滿了）
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.of(currencies.get("resonance_orb")));
        for (final CurrencyType type : currencies.all()) {
            assertEquals(type.id().startsWith(CurrencyGroups.TICKET_PREFIX) || type.operation() == CurrencyOperation.RESONANCE,
                    CurrencyGroups.of(type) == CurrencyGroups.Group.KEYS, type.id());
        }
        // 一打開先看有東西的那組；都沒有看一般通貨
        final Map<String, Integer> held = Map.of("distilled_guilt", 3);
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.first(currencies.all(), type -> held.getOrDefault(type.id(), 0)));
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.first(currencies.all(), type -> 0));
        final int[] counts = CurrencyGroups.kindsHeld(currencies.all(), type -> held.getOrDefault(type.id(), 0));
        assertEquals("門票／鑰匙 0 種・一般通貨 0 種・未切割寶石 0 種・星魄 0 種・催化劑・星籤・蒸餾情緒 1 種", CurrencyGroups.summary(counts));
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.Group.BASIC.next());
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.Group.BASIC.previous()); // 2026-10-18
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.Group.KEYS.previous());
    }
}
