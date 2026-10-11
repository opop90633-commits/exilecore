package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.map.MapStats;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 詞綴補正（2026-10）：詞綴後來多了變數，舊物品缺的補上（不然一讀就丟「缺少變數」：倉庫打不開、屬性算不出來）。 */
class AffixUpgradeTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
    }

    @Test
    void fillAddsOnlyTheMissingVariablesAtTheLowEndOfTheRange() {
        final Map<String, int[]> ranges = new LinkedHashMap<>();
        ranges.put("value", new int[] {70, 79});
        ranges.put("stun", new int[] {60, 69});
        assertNull(AffixUpgrade.fill(Map.of("value", 75, "stun", 61), ranges), "沒有缺的不動");
        final Map<String, Integer> filled = AffixUpgrade.fill(Map.of("value", 95), ranges);
        assertEquals(Map.of("value", 95, "stun", 60), filled, "擲過的不動（就算已經不在新範圍裡），缺的補最低值");
        assertEquals(Map.of("value", 70, "stun", 60), AffixUpgrade.fill(null, ranges));
        assertEquals(Map.of("value", 70, "stun", 60), AffixUpgrade.fill(Map.of(), ranges));
        assertEquals(Map.of("a", 3), AffixUpgrade.fill(Map.of(), Map.of("a", new int[] {9, 3})), "範圍寫反的也取小的");
        assertNull(AffixUpgrade.fill(Map.of("extra", 1), Map.of()), "定義裡沒有的變數留著不管");
    }

    @Test
    void oldItemsWithReworkedAffixesNoLongerThrow() {
        // 今天改過、多了變數的三條：地圖詞綴的怪物暴擊（multi）、怪物異常門檻（stun）、揭露珠寶詞綴的戰吼（b）
        for (final String[] one : new String[][] {{"map_monster_crit", "multi"}, {"map_monster_ailment_threshold", "stun"},
                {"unveiled_j_warcry", "b"}}) {
            final Affix affix = repository.affix(one[0]);
            assertNotNull(affix, one[0]);
            final int tier = affix.tiers().getLast().tier();
            final RolledAffix old = new RolledAffix(one[0], tier, Map.of("value", 5)); // 舊物品：只有 value
            assertThrows(IllegalStateException.class, () -> {
                for (final AffixMod mod : affix.mods()) {
                    mod.toModifier(old.vars(), "x");
                }
            }, one[0] + "：沒補之前會丟「缺少變數」");
            final ItemData data = new ItemData("no_such_base", Rarity.RARE, 80);
            data.affixes().add(old);
            assertTrue(AffixUpgrade.apply(repository, data), one[0]);
            assertEquals(5, old.vars().get("value"), "擲過的不動");
            final int[] range = affix.ranges(tier).get(one[1]);
            assertEquals(Math.min(range[0], range[1]), old.vars().get(one[1]), one[0] + "：補這一階的最低值");
            assertDoesNotThrow(() -> {
                for (final AffixMod mod : affix.mods()) {
                    mod.toModifier(old.vars(), "x");
                }
            });
            assertFalse(affix.render(old.vars()).contains("{"), "說明文字裡沒有沒換掉的變數：" + affix.render(old.vars()));
            assertFalse(AffixUpgrade.apply(repository, data), "補過一次就不用再補");
        }
    }

    @Test
    void removedTiersFallBackToTheWeakestTier() {
        final Affix affix = repository.affix("map_monster_crit");
        final RolledAffix old = new RolledAffix("map_monster_crit", 99, Map.of("value", 5)); // 這一階已經沒有了
        final ItemData data = new ItemData("no_such_base", Rarity.RARE, 80);
        data.affixes().add(old);
        assertTrue(AffixUpgrade.apply(repository, data));
        AffixTier weakest = affix.tiers().getFirst();
        for (final AffixTier tier : affix.tiers()) {
            weakest = tier.tier() > weakest.tier() ? tier : weakest;
        }
        assertEquals(Math.min(weakest.vars().get("multi")[0], weakest.vars().get("multi")[1]), old.vars().get("multi"));
        // 不認得的詞綴、沒有詞綴的物品：不動、不丟錯
        final ItemData other = new ItemData("no_such_base", Rarity.RARE, 80);
        other.affixes().add(new RolledAffix("no_such_affix", 1, Map.of()));
        assertFalse(AffixUpgrade.apply(repository, other));
        assertFalse(AffixUpgrade.apply(repository, new ItemData("no_such_base", Rarity.NORMAL, 1)));
    }

    @Test
    void everyShippedAffixCanBeHealedFromEmpty() {
        // 每一條詞綴、每一階：就算舊物品一個變數都沒有，補完之後每條修飾器都算得出來
        for (final Affix affix : repository.affixes()) {
            for (final AffixTier tier : affix.tiers()) {
                final Map<String, Integer> filled = AffixUpgrade.fill(Map.of(), AffixUpgrade.ranges(affix, tier.tier()));
                final Map<String, Integer> vars = filled == null ? Map.of() : filled;
                for (final AffixMod mod : affix.mods()) {
                    assertDoesNotThrow(() -> mod.toModifier(vars, affix.id()), affix.id() + " T" + tier.tier());
                }
            }
        }
        assertNotNull(List.of(MapStats.class)); // 地圖詞綴走 MapStats.of → toModifier，同一條路
    }
}
