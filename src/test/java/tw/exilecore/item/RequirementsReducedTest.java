package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-21：PoE2 的「減少 x% 屬性需求」（local.requirements）：只改屬性需求，等級需求不變，武器、防具都能出。 */
class RequirementsReducedTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        generator = new ItemGenerator(items);
    }

    @Test
    void reducesAttributeRequirementsOnly() {
        final SplittableRandom random = new SplittableRandom(3);
        for (final String baseId : new String[] {"plate_body", "plate_helmet"}) {
            final ItemBase base = items.base(baseId);
            final ItemData data = generator.generate(base, Rarity.RARE, 80, random);
            data.affixes().removeIf(rolled -> items.affix(rolled.id()).type() == AffixType.SUFFIX);
            final ItemBase.Requirements before = ItemStats.requirements(items, base, data);
            assertNull(generator.forceAffix(data, "requirements_reduced", 1, random), baseId + " 要能放減少屬性需求");
            final ItemBase.Requirements after = ItemStats.requirements(items, base, data);
            assertEquals(before.level(), after.level(), "等級需求不變");
            assertTrue(before.str() > 0, baseId + " 有力量需求");
            assertEquals(Math.round(before.str() * 0.65), after.str(), "T1 是 35%");
            assertEquals(ItemStats.scaleRequirement(before.dex(), 0.65), after.dex());
            // 不進屬性表（本地的）
            assertTrue(ItemStats.modifiers(items, data).stream().noneMatch(modifier -> modifier.stat().startsWith("local.")));
        }
    }

    @Test
    void scaleNeverDropsBelowOne() {
        assertEquals(0, ItemStats.scaleRequirement(0, 0.65));
        assertEquals(1, ItemStats.scaleRequirement(1, 0.65));
        assertEquals(13, ItemStats.scaleRequirement(20, 0.65));
    }
}
