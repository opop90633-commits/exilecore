package tw.exilecore.currency;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.Runes;
import tw.exilecore.item.UniquePicks;
import tw.exilecore.item.Veiled;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長：裂痕、譫妄、深淵、極恐星魄（PoE2 的腐化精髓）。 */
class CorruptedEssencesTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        generator = new ItemGenerator(items);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    @AfterAll
    static void reset() {
        UniquePicks.setKeystones(null, null); // 別的測試自己接
    }

    private static ItemData rare(final String base, final int seed) {
        final ItemData data = generator.generate(items.base(base), Rarity.RARE, 80, new SplittableRandom(seed));
        data.setIdentified(true);
        return data;
    }

    @Test
    void breachRaisesQualityCap() {
        final CurrencyType breach = currencies.get("essence_breach");
        final ItemData ring = rare("jring_13", 1);
        assertEquals(20, Crafting.catalystMax(items, ring));
        assertNull(Crafting.problem(breach, ring, generator));
        Crafting.applyEssence(breach, ring, generator, new SplittableRandom(2), null);
        assertTrue(ring.affixes().stream().anyMatch(a -> a.id().equals("ess_breach_quality")));
        assertEquals(40, Crafting.catalystMax(items, ring), "+20% 最大品質");
        assertNotNull(Crafting.problem(breach, rare("body_str_10", 3), generator), "胸甲不行");
    }

    @Test
    void abyssSwapsAnAffixForAVeiledOne() {
        final CurrencyType abyss = currencies.get("essence_abyss");
        final ItemData body = rare("body_str_10", 4);
        final int before = body.affixes().size();
        assertNull(Crafting.problem(abyss, body, generator));
        Crafting.applyEssence(abyss, body, generator, new SplittableRandom(5), null);
        assertEquals(before, body.affixes().size(), "拿掉一條、換上一條");
        assertEquals(1, Veiled.veiledOn(body).size());
        assertNotNull(Crafting.problem(abyss, body, generator), "已經有隱匿詞綴就不能再用");
    }

    @Test
    void deliriumPicksAKeystone() {
        UniquePicks.setKeystones(new UniquePicks.Pool(List.of("ks_a", "ks_b", "ks_c"), id -> id), id -> List.of());
        final CurrencyType delirium = currencies.get("essence_delirium");
        final ItemData body = rare("body_str_10", 6);
        assertNull(Crafting.problem(delirium, body, generator));
        Crafting.applyEssence(delirium, body, generator, new SplittableRandom(7), null);
        final RolledAffix rolled = body.affixes().stream().filter(a -> a.id().equals("ess_delirium_keystone")).findFirst().orElseThrow();
        final String expected = UniquePicks.keystoneAt(rolled.vars().get(UniquePicks.KEYSTONE_SEED));
        assertEquals(List.of(expected), UniquePicks.affixKeystones(items, body));
        assertNotNull(Crafting.problem(delirium, rare("boots_str_01", 8), generator), "鞋子不行");
    }

    @Test
    void horrorBoostsRunes() {
        final CurrencyType horror = currencies.get("essence_horror");
        final ItemData boots = rare("boots_str_01", 9);
        final double before = Runes.effect(items, boots, null);
        Crafting.applyEssence(horror, boots, generator, new SplittableRandom(10), null);
        assertEquals(before + 0.6, Runes.effect(items, boots, null), 1e-9, "符文效果 +60%");
    }
}
