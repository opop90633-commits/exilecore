package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;

import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「瓦爾催化灌注器照原作」：品質達到「這件的」上限才能用，最多超過上限 10%。 */
class VaalInfuserMaxTest {

    static ItemRepository items;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws Exception {
        items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(items);
    }

    private static ItemData ring(final String base, final int quality) {
        final ItemData data = generator.generate(items.base(base), Rarity.RARE, 80, new SplittableRandom(1));
        data.setIdentified(true);
        data.setCatalyst("fire");
        data.setQualityUpTo(quality, 100);
        return data;
    }

    @Test
    void normalRingGoesUpToThirty() {
        assertFalse(CurrencyOperation.VAAL_INFUSE_JEWELLERY.canApply(ring("jring_13", 19), generator), "還沒滿");
        final ItemData full = ring("jring_13", 20);
        assertTrue(CurrencyOperation.VAAL_INFUSE_JEWELLERY.canApply(full, generator));
        for (int i = 0; i < 20; i++) {
            CurrencyOperation.vaalInfuse(full, generator, new SplittableRandom(i));
        }
        assertEquals(30, full.quality());
        assertFalse(CurrencyOperation.VAAL_INFUSE_JEWELLERY.canApply(full, generator), "到 30% 就不能再用");
    }

    @Test
    void breachRingUsesItsOwnCap() {
        final int max = Crafting.catalystMax(items, ring("jring_breach_refined", 0));
        assertTrue(max > 20, "精煉裂隙指環上限比較高");
        assertFalse(CurrencyOperation.VAAL_INFUSE_JEWELLERY.canApply(ring("jring_breach_refined", 20), generator), "20% 還沒到它的上限");
        final ItemData full = ring("jring_breach_refined", max);
        assertTrue(CurrencyOperation.VAAL_INFUSE_JEWELLERY.canApply(full, generator));
        for (int i = 0; i < 20; i++) {
            CurrencyOperation.vaalInfuse(full, generator, new SplittableRandom(i));
        }
        assertEquals(max + 10, full.quality());
    }
}
