package tw.exilecore.currency;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-06 照站長＋PoE2：蝕變石的「多一個孔」不會出在腰帶、戒指、項鍊——本身帶 2 孔的淵座束帶（Darkness Enthroned）也一樣；
 * 這次之前看「這件有沒有孔」，淵座束帶會腐化出第 3 孔。
 */
class CorruptBeltSocketTest {

    @Test
    void abyssThroneBeltNeverGainsASocket() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(11);
        for (int i = 0; i < 400; i++) {
            final ItemData data = generator.generateUnique(items.unique("abyss_throne_belt"), 84, random);
            CurrencyOperation.corruptAlways(data, generator, random);
            assertTrue(data.corrupted());
            assertEquals(0, data.corruptSockets(), "第 " + i + " 次：腰帶不會多孔");
        }
        assertTrue(CurrencyOperation.noCorruptSocket(items.base("jbelt_fine")));
    }
}
