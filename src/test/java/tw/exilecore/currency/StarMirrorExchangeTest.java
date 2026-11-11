package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11 照站長「映星鏡新增可自選追逐級傳奇」：換得到全部現役的追逐級傳奇、做出來是正常的傳奇。 */
class StarMirrorExchangeTest {

    static ItemRepository repository;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(repository);
    }

    @Test
    void listsEveryActiveChaseUnique() {
        final List<UniqueItem> list = StarMirrorExchangeMenu.candidates(generator, repository);
        final long expected = repository.uniques().stream().filter(u -> u.chase() && u.weight() > 0).count();
        assertEquals(expected, list.size());
        assertTrue(list.size() >= 20, "追逐級傳奇：" + list.size());
        assertTrue(list.stream().allMatch(UniqueItem::chase));
    }

    @Test
    void madeItemIsANormalUnique() {
        for (final UniqueItem unique : StarMirrorExchangeMenu.candidates(generator, repository)) {
            final int level = StarMirrorExchangeMenu.itemLevel(unique);
            assertTrue(level >= StarMirrorExchangeMenu.ITEM_LEVEL && level >= unique.dropLevel());
            final ItemData data = generator.generateUnique(unique, level, new Random(1));
            assertEquals(Rarity.UNIQUE, data.rarity());
            assertEquals(unique.id(), data.unique());
            assertFalse(data.mirrored(), unique.id());
        }
    }
}
