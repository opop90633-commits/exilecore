package tw.exilecore.currency;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-03 照站長：催化至臻籤只能掛在戒指、項鍊上；以前掛錯地方的不生效、也不擋至臻石。 */
class OmenBaseTest {

    @Test
    void catalysingOmenOnlyFitsRingsAndAmulets() throws IOException {
        final Path data = Path.of("src/main/resources/data");
        final ItemRepository items = new ItemRepository();
        items.load(data);
        final ItemGenerator generator = new ItemGenerator(items);
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(data);
        final CurrencyType catalysing = currencies.get("omen_catalysing_exaltation");
        assertNotNull(catalysing);
        final ItemData leggings = new ItemData("body_str_int_20", Rarity.RARE, 80);
        assertNotNull(Crafting.problem(catalysing, leggings, generator), "胸甲、護腿這種不能掛");
        final ItemData ring = new ItemData("jring_08", Rarity.RARE, 80);
        assertNull(Crafting.problem(catalysing, ring, generator), "戒指可以掛");
        final ItemData amulet = new ItemData("jamulet_09", Rarity.RARE, 80);
        assertNull(Crafting.problem(catalysing, amulet, generator), "項鍊可以掛");
        // 以前掛錯的：至臻石不會觸發它（不會被擋住）
        final CurrencyType exalt = currencies.all().stream()
                .filter(t -> t.operation() == CurrencyOperation.ADD_AFFIX_RARE && t.minModLevel() == 0).findFirst().orElseThrow();
        assertTrue(Crafting.triggers(catalysing, exalt));
        assertFalse(Crafting.triggers(catalysing, exalt, items.base("body_str_int_20")));
        assertTrue(Crafting.triggers(catalysing, exalt, items.base("jring_08")));
    }
}
