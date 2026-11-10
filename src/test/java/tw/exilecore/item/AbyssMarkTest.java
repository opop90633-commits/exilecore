package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長「深淵星魄印記揭露給較高階」。 */
class AbyssMarkTest {

    static ItemRepository items;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws Exception {
        items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(items);
    }

    private static ItemData rare(final int seed) {
        final ItemData data = generator.generate(items.base("body_str_10"), Rarity.RARE, 84, new SplittableRandom(seed));
        data.setIdentified(true);
        return data;
    }

    private static RolledAffix veiledOn(final ItemData data) {
        return Veiled.veiledOn(data).getFirst();
    }

    @Test
    void abyssMarkUnveilsAtTheBestTier() {
        final ItemData data = rare(7);
        assertTrue(Veiled.replaceWithVeiled(data, generator, new SplittableRandom(2)));
        final RolledAffix mark = veiledOn(data);
        assertTrue(Veiled.abyssMark(mark), "深淵星魄做的是深淵印記");
        assertTrue(ItemStats.affixLines(items, data).stream().anyMatch(line -> line.ranged().contains("深淵領主印記")));
        int checked = 0;
        for (int seed = 0; seed < 20; seed++) {
            final List<RolledAffix> options = Veiled.options(data, Veiled.typeOf(mark), mark, generator, new SplittableRandom(seed), Veiled.CHOICES);
            checked += options.size();
            for (final RolledAffix option : options) {
                final Affix affix = items.affix(option.id());
                int best = 0;
                int bestTier = -1;
                for (final AffixTier tier : affix.tiersFor(data.ilvl())) {
                    if (tier.ilvl() > best || bestTier < 0) {
                        best = tier.ilvl();
                        bestTier = tier.tier();
                    }
                }
                assertEquals(bestTier, option.tier(), affix.id() + "：深淵印記只出最高階");
            }
        }
        assertTrue(checked > 0, "有揭露選項");
    }

    @Test
    void unveilStoneStaysWeighted() {
        final ItemData data = new ItemData("body_str_10", Rarity.MAGIC, 84); // 沒有詞綴：一定有空位
        data.setIdentified(true);
        assertTrue(Veiled.unveilRandom(data, generator, new SplittableRandom(4)));
        assertFalse(Veiled.abyssMark(veiledOn(data)), "揭露石的不是深淵印記");
    }
}
