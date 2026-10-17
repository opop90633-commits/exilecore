package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-17：重複的揭露詞綴，第二條換回隱匿詞綴。 */
class VeiledDedupeTest {

    @Test
    void secondCopyBecomesVeiledAgain() throws Exception {
        final ItemRepository items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        String id = null;
        for (final Affix affix : items.affixes()) {
            if (Veiled.isUnveiled(affix.id()) && affix.type() == AffixType.PREFIX) {
                id = affix.id();
                break;
            }
        }
        final ItemData data = new ItemData("plate_body", Rarity.RARE, 84);
        data.affixes().add(new RolledAffix(id, 1, Map.of("value", 10)));
        data.affixes().add(new RolledAffix(id, 1, Map.of("value", 12)));
        assertTrue(Veiled.dedupe(data, items, new java.util.SplittableRandom(1)));
        assertEquals(id, data.affixes().get(0).id());
        assertEquals(Veiled.VEILED_PREFIX, data.affixes().get(1).id());
        assertFalse(Veiled.dedupe(data, items, new java.util.SplittableRandom(1)));
    }
}
