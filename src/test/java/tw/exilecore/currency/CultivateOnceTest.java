package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.UniqueItem;

import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「蝕培石原作不能對同一個物品一直重複使用」。 */
class CultivateOnceTest {

    static ItemRepository items;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws Exception {
        items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(items);
    }

    @Test
    void vaalUniqueCanBeCultivatedOnlyOnce() {
        final UniqueItem vaal = items.uniques().stream().filter(u -> !items.vaalMods(u.id()).isEmpty()).findFirst().orElseThrow();
        final ItemData data = generator.generateUnique(vaal, 82, new SplittableRandom(5));
        data.setIdentified(true);
        data.setCorrupted(true);
        assertNull(generator.cultivateProblem(data), vaal.id());
        assertTrue(CurrencyOperation.CULTIVATE.canApply(data, generator));
        CurrencyOperation.CULTIVATE.apply(data, generator, new SplittableRandom(6));
        assertFalse(data.cultivatedOut().isEmpty(), "換過瓦爾詞綴");
        assertNotNull(generator.cultivateProblem(data));
        assertFalse(CurrencyOperation.CULTIVATE.canApply(data, generator), "第二次不行");
    }
}
