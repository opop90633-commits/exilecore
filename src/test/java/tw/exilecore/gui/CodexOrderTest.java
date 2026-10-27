package tw.exilecore.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：圖鑑的基底、詞綴順序（武器照類型分好、不混在一起），珠寶、護符的圖示分得出來。 */
class CodexOrderTest {

    private static ItemRepository repository() throws Exception {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        return repository;
    }

    @Test
    void weaponsAreGroupedByTypeInFixedOrder() throws Exception {
        final List<ItemBase> bases = CodexMenu.codexBases(repository());
        final List<Integer> ranks = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        int previous = -1;
        for (final ItemBase base : bases) {
            if (base.slot() != ItemSlot.MAIN_HAND) {
                continue;
            }
            final int rank = CodexOrder.weaponRank(base);
            assertTrue(rank >= previous, "主手武器要照類型排好：" + base.id());
            if (rank != previous) {
                assertTrue(seen.add(rank), "同一種武器要連在一起：" + base.id());
                ranks.add(rank);
            }
            previous = rank;
        }
        assertTrue(ranks.size() >= 10, "武器類型應該有十幾種：" + ranks);
        // 部位的大順序不變；同一種裡照需要等級
        for (int i = 1; i < bases.size(); i++) {
            final ItemBase a = bases.get(i - 1);
            final ItemBase b = bases.get(i);
            assertTrue(a.slot().ordinal() <= b.slot().ordinal(), "部位順序：" + a.id() + " → " + b.id());
            if (a.slot() == b.slot() && CodexOrder.kindRank(a) == CodexOrder.kindRank(b)) {
                assertTrue(a.requirements().level() <= b.requirements().level(), "同一種照需要等級：" + a.id() + " → " + b.id());
            }
        }
        // 排序穩定：再排一次一樣
        final List<ItemBase> again = new ArrayList<>(bases);
        java.util.Collections.reverse(again);
        again.sort(CodexOrder.BASES);
        assertEquals(bases, again);
    }

    @Test
    void affixesPrefixFirstThenStable() throws Exception {
        final List<Affix> all = new ArrayList<>(repository().affixes());
        all.sort(CodexOrder.AFFIXES);
        boolean suffix = false;
        for (final Affix affix : all) {
            if (affix.type() == AffixType.SUFFIX) {
                suffix = true;
            } else {
                assertTrue(!suffix, "前綴要在後綴前面：" + affix.id());
            }
        }
        final List<Affix> again = new ArrayList<>(all);
        java.util.Collections.reverse(again);
        again.sort(CodexOrder.AFFIXES);
        assertEquals(all, again);
    }

    @Test
    void jewelAndCharmIconsAreDistinct() throws Exception {
        final ItemRepository repository = repository();
        final Map<Material, String> used = new HashMap<>();
        for (final ItemBase base : CodexMenu.codexBases(repository)) {
            if (!base.isJewel() && !base.isCharm()) {
                continue;
            }
            final Material icon = CodexOrder.icon(base);
            assertNotNull(icon, base.id());
            assertTrue(icon != Material.POTION, "護符不要跟藥劑一樣：" + base.id());
            final String other = used.put(icon, base.id());
            assertTrue(other == null, "圖鑑圖示重複：" + other + " 和 " + base.id() + " 都是 " + icon);
        }
        assertEquals(Material.REDSTONE, CodexOrder.icon(repository.base("ruby_jewel")));
        assertEquals(Material.REDSTONE_BLOCK, CodexOrder.icon(repository.base("time_lost_ruby")));
        assertEquals(Material.NETHER_STAR, CodexOrder.icon(repository.base("timeless_jewel")));
    }
}
