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
        // 2026-10-26：星籤自己一組（照 PoE2 每種通貨分開之後一頁放不下）；這次之前跟催化劑同一組
        assertEquals(CurrencyGroups.Group.OMEN, CurrencyGroups.of(currencies.get("omen_sinistral")));
        assertEquals(CurrencyGroups.Group.ESSENCE, CurrencyGroups.of(currencies.get("perfect_essence_body")));
        assertEquals(CurrencyGroups.Group.ESSENCE_ATTACK, CurrencyGroups.of(currencies.get("lesser_essence_blaze")));
        assertEquals(CurrencyGroups.Group.ESSENCE_CASTER, CurrencyGroups.of(currencies.get("essence_hysteria")));
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.of(currencies.get("perfect_zenith_orb")));
        // 2026-10-26：符文、星魄、星籤拆組後有 11 組，最上面一排 9 格放不下：兩端 ◀ ▶、中間 7 格跟著目前那組捲，每一組都看得到也點得到
        //   （這次之前驗「分頁按鈕最多 9 個」）
        final int count = CurrencyGroups.Group.values().length;
        for (int current = 0; current < count; current++) {
            final int[] row = CurrencyGroups.tabRow(count, current);
            assertEquals(CurrencyGroups.TAB_ROW, row.length);
            final int now = current;
            assertTrue(java.util.Arrays.stream(row).anyMatch(g -> g == now), "目前那組 " + current + " 的頁籤在畫面上");
            if (count > CurrencyGroups.TAB_ROW) {
                assertEquals(CurrencyGroups.TAB_PREVIOUS, row[0]);
                assertEquals(CurrencyGroups.TAB_NEXT, row[CurrencyGroups.TAB_ROW - 1]);
            }
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), java.util.Arrays.stream(CurrencyGroups.tabRow(9, 0)).boxed().toList(), "9 組以下照舊一組一格");
        assertEquals(List.of(-1, -1, 0, -1, 1, -1, 2, -1, -1), java.util.Arrays.stream(CurrencyGroups.tabRow(3, 0)).boxed().toList());
        assertEquals(List.of(-2, 4, 5, 6, 7, 8, 9, 10, -3), java.util.Arrays.stream(CurrencyGroups.tabRow(11, 10)).boxed().toList(), "捲到最後");
        assertEquals(List.of(-2, 0, 1, 2, 3, 4, 5, 6, -3), java.util.Arrays.stream(CurrencyGroups.tabRow(11, 2)).boxed().toList());
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.of(currencies.get("distilled_ire")));
        // 2026-10-26 照 PoE2：符文 52 種分兩組（次級與普通、高階與完美）
        assertEquals(CurrencyGroups.Group.RUNES, CurrencyGroups.of(currencies.get("rune_iron")));
        assertEquals(CurrencyGroups.Group.RUNES, CurrencyGroups.of(currencies.get("rune_iron_lesser")));
        assertEquals(CurrencyGroups.Group.RUNES_HIGH, CurrencyGroups.of(currencies.get("rune_vision_perfect")));
        assertEquals(26, CurrencyGroups.in(currencies.all(), CurrencyGroups.Group.RUNES).size());
        assertEquals(26, CurrencyGroups.in(currencies.all(), CurrencyGroups.Group.RUNES_HIGH).size());
        // 未切割寶石（2026-10 算通貨）：技能寶石 1–21 級、輔助寶石 1–3 階各一種
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.of(currencies.get("uncut_skill_1")));
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.of(currencies.get("uncut_support_3")));
        assertEquals(24, CurrencyGroups.in(currencies.all(), CurrencyGroups.Group.GEM).size());
        // 2026-10-18：Boss 門票：事件入場券、巔峰碎片、天穹碎片、深淵星核、金庫之主的鑰印；共鳴石算一般通貨
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.of(currencies.get("resonance_orb")));
        for (final String id : List.of("ticket_shrine", "fragment_crucible", "sky_fragment", "abyss_core", "heist_finale_key")) {
            assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.of(currencies.get(id)), id);
        }
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.of(currencies.get("tablet_shrine")), "2026-10-21：星圖石板跟門票同一組");
        // 2026-10-26：活動素材 4 種、劫盜合約與藍圖、揭露石、傳奇兌換券也放這組（一般通貨那組加了高階／完美通貨）
        assertEquals(7 + 4 + 3 + 9 + 4 + 4, CurrencyGroups.in(currencies.all(), CurrencyGroups.Group.KEYS).size());
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.of(currencies.get("event_candy")));
        // 一打開先看有東西的那組；都沒有看一般通貨
        final Map<String, Integer> held = Map.of("distilled_guilt", 3);
        assertEquals(CurrencyGroups.Group.MECHANIC, CurrencyGroups.first(currencies.all(), type -> held.getOrDefault(type.id(), 0)));
        assertEquals(CurrencyGroups.Group.BASIC, CurrencyGroups.first(currencies.all(), type -> 0));
        final int[] counts = CurrencyGroups.kindsHeld(currencies.all(), type -> held.getOrDefault(type.id(), 0));
        // 2026-10-26：星魄分三組、星籤自己一組、符文兩組
        assertEquals("一般通貨 0 種・未切割寶石 0 種・星魄 0 種・星魄・攻擊 0 種・星魄・法術與腐化 0 種・催化劑・蒸餾情緒 1 種・星籤 0 種・符文 0 種・高階符文 0 種・門票・劫盜・活動 0 種・星諭卡 0 種",
                CurrencyGroups.summary(counts));
        assertEquals(CurrencyGroups.Group.GEM, CurrencyGroups.Group.BASIC.next());
        assertEquals(CurrencyGroups.Group.CARDS, CurrencyGroups.Group.BASIC.previous()); // 2026-10-19：星諭卡在最後
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.Group.CARDS.previous());
        assertEquals(CurrencyGroups.Group.CARDS, CurrencyGroups.of(currencies.get("card_tide_mark")));
        assertEquals(CurrencyGroups.Group.CARDS, CurrencyGroups.of(currencies.get("stacked_deck")));
        assertEquals(CurrencyGroups.Group.RUNES_HIGH, CurrencyGroups.Group.KEYS.previous()); // 2026-10-26：符文兩組在門票前面
        assertEquals(CurrencyGroups.Group.OMEN, CurrencyGroups.Group.MECHANIC.next());
    }
}
