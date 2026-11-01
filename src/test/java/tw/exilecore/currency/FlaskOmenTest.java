package tw.exilecore.currency;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01：傳奇藥劑（拉薇安之泉）可以掛必變籤再用蝕變石；藥劑用不到的通貨觸發的星籤還是不行。 */
class FlaskOmenTest {

    @Test
    void omenOfCorruptionFitsUniqueFlask() throws IOException {
        final Path data = Path.of("src/main/resources/data");
        final ItemRepository items = new ItemRepository();
        items.load(data);
        final ItemGenerator generator = new ItemGenerator(items);
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(data);
        final ItemData flask = generator.generateUnique(items.unique("lavian_spring"), 82, new SplittableRandom(1));
        assertNotNull(flask);
        final CurrencyType corruption = currencies.get("omen_corruption");
        assertNull(Crafting.problem(corruption, flask, generator));
        assertTrue(corruption.canApply(flask, generator));
        final CurrencyType vaal = currencies.all().stream()
                .filter(t -> t.operation() == CurrencyOperation.CORRUPT).findFirst().orElseThrow();
        assertTrue(Crafting.triggers(corruption, vaal));
        // 只被藥劑不能用的通貨觸發的星籤（例如星火後綴籤：升稀有）還是掛不上藥劑
        final CurrencyType rareOnly = currencies.all().stream()
                .filter(t -> t.omen() != null && t.omen().triggers().stream().noneMatch(Crafting::flaskOperation))
                .findFirst().orElseThrow();
        assertNotNull(Crafting.problem(rareOnly, flask, generator));
        assertFalse(rareOnly.canApply(flask, generator));
    }
}
