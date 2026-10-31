package tw.exilecore.currency;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-31：必變籤＋蝕變石一定看得出變化（只有一條 10～15% 數值的傳奇，重擲數值可能擲回原數字）。 */
class CorruptAlwaysTest {

    @Test
    void omenOfCorruptionAlwaysChangesSomethingVisible() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 400; i++) {
            final ItemData data = generator.generateUnique(items.unique("genesis_crown"), 82, random);
            final ItemData before = data.copy();
            CurrencyOperation.corruptAlways(data, generator, random);
            assertTrue(data.corrupted());
            final boolean changed = data.quality() != before.quality() || data.corruptSockets() != before.corruptSockets()
                    || !String.valueOf(data.affixes().stream().map(a -> a.id() + a.vars()).toList())
                    .equals(String.valueOf(before.affixes().stream().map(a -> a.id() + a.vars()).toList()));
            assertTrue(changed, "第 " + i + " 次沒變化");
        }
        assertNotEquals(null, items.unique("genesis_crown"));
    }
}
