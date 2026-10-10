package tw.exilecore.stash;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 整理與收進倉庫的規則：排序、放哪一頁、全部頁重新分頁（東西不會少）。 */
class StashLayoutTest {

    static StashLayout.Entry<String> entry(final String id, final StashCategory category, final int rank, final int level) {
        return new StashLayout.Entry<>(id, category, rank, level, id, 1);
    }

    static List<StashLayout.Entry<String>> many(final String prefix, final StashCategory category, final int count) {
        final List<StashLayout.Entry<String>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(entry(prefix + i, category, i % 4, i));
        }
        return list;
    }

    @Test
    void sortsByCategoryThenRarityThenLevel() {
        final List<StashLayout.Entry<String>> sorted = StashLayout.sorted(List.of(
                entry("gem", StashCategory.GEM, 0, 20),
                entry("normal sword", StashCategory.WEAPON, 0, 50),
                entry("rare boots", StashCategory.ARMOUR, 2, 10),
                entry("rare sword lv10", StashCategory.WEAPON, 2, 10),
                entry("rare sword lv60", StashCategory.WEAPON, 2, 60),
                entry("orb", StashCategory.CURRENCY, 0, 0),
                entry("rock", StashCategory.OTHER, 0, 0)));
        assertEquals(List.of("rare sword lv60", "rare sword lv10", "normal sword", "rare boots", "gem", "orb", "rock"),
                sorted.stream().map(StashLayout.Entry::item).toList());
    }

    @Test
    void depositGoesToMatchingTabThenAll() {
        final List<StashCategory> tabs = List.of(StashCategory.WEAPON, StashCategory.ALL, StashCategory.ARMOUR, StashCategory.ALL);
        assertEquals(List.of(0, 1, 3), StashLayout.depositOrder(StashCategory.WEAPON, tabs));
        assertEquals(List.of(2, 1, 3), StashLayout.depositOrder(StashCategory.ARMOUR, tabs));
        assertEquals(List.of(1, 3), StashLayout.depositOrder(StashCategory.GEM, tabs), "沒有寶石頁：放「全部」的頁");
        assertTrue(StashLayout.depositOrder(StashCategory.GEM,
                List.of(StashCategory.WEAPON, StashCategory.ARMOUR, StashCategory.MAP, StashCategory.FLASK)).isEmpty(),
                "沒有對應的頁、也沒有「全部」的頁：不放");
    }

    @Test
    void distributeFollowsTabsAndKeepsEverything() {
        final List<StashCategory> tabs = List.of(StashCategory.WEAPON, StashCategory.ALL, StashCategory.ARMOUR, StashCategory.GEM);
        final List<StashLayout.Entry<String>> entries = new ArrayList<>();
        entries.addAll(many("w", StashCategory.WEAPON, 50)); // 武器頁放 45 件，5 件放「全部」
        entries.addAll(many("a", StashCategory.ARMOUR, 10));
        entries.addAll(many("g", StashCategory.GEM, 3));
        entries.addAll(many("m", StashCategory.MAP, 4)); // 沒有地圖頁：放「全部」
        final List<List<StashLayout.Entry<String>>> pages = StashLayout.distribute(entries, tabs, 45);
        assertEquals(45, pages.get(0).size());
        assertTrue(pages.get(0).stream().allMatch(e -> e.category() == StashCategory.WEAPON));
        assertEquals(10, pages.get(2).size());
        assertTrue(pages.get(2).stream().allMatch(e -> e.category() == StashCategory.ARMOUR));
        assertEquals(3, pages.get(3).size());
        assertEquals(9, pages.get(1).size(), "5 件武器＋4 張地圖");
        // 每頁照規則排好，武器在地圖前面
        assertEquals(StashCategory.WEAPON, pages.get(1).getFirst().category());
        assertEquals(StashCategory.MAP, pages.get(1).getLast().category());
        // 一件都沒少、也沒有重複
        final Set<String> seen = new HashSet<>();
        for (final List<StashLayout.Entry<String>> page : pages) {
            for (final StashLayout.Entry<String> entry : page) {
                assertTrue(seen.add(entry.item()));
            }
        }
        assertEquals(entries.size(), seen.size());
    }

    @Test
    void distributeOverflowsWhenNoPageFits() {
        // 沒有「全部」的頁：放不下的武器擠到其他有空位的頁，東西不會不見
        final List<StashCategory> tabs = List.of(StashCategory.WEAPON, StashCategory.ARMOUR, StashCategory.MAP, StashCategory.GEM);
        final List<StashLayout.Entry<String>> entries = new ArrayList<>(many("w", StashCategory.WEAPON, 60));
        entries.addAll(many("a", StashCategory.ARMOUR, 40));
        final List<List<StashLayout.Entry<String>>> pages = StashLayout.distribute(entries, tabs, 45);
        int total = 0;
        for (final List<StashLayout.Entry<String>> page : pages) {
            assertTrue(page.size() <= 45);
            total += page.size();
        }
        assertEquals(100, total);
        assertEquals(45, pages.get(0).size());
        assertEquals(45, pages.get(1).size(), "防具頁：40 件防具＋5 件放不下的武器");
        assertEquals(10, pages.get(2).size());
        assertThrows(IllegalArgumentException.class, () -> StashLayout.distribute(many("x", StashCategory.OTHER, 181), tabs, 45));
    }

    @Test
    void tabNamesAndCategoriesAreCleaned() {
        assertEquals("裝備", StashLayout.cleanTabName("  裝備\n ", 12));
        assertEquals("一二三四五六七八九十一二", StashLayout.cleanTabName("一二三四五六七八九十一二三四", 12));
        assertEquals("", StashLayout.cleanTabName(null, 12));
        assertEquals(StashCategory.WEAPON, StashCategory.parseTab("weapon"));
        assertEquals(StashCategory.ALL, StashCategory.parseTab("CURRENCY"), "通貨有自己的頁，不能當頁籤分類");
        assertEquals(StashCategory.ALL, StashCategory.parseTab("亂打"));
        assertTrue(!StashCategory.tabChoices().contains(StashCategory.CURRENCY));
    }
}
