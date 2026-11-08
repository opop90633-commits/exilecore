package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.Runes;

import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長：晶淵鑿——武器、頭、胸、褲、鞋多一個符文孔，腐化的也能用，一件只能用一次。 */
class BonusRuneSocketTest {

    static ItemRepository items;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws Exception {
        items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(items);
    }

    private static ItemData item(final String base) {
        final ItemData data = generator.generate(items.base(base), Rarity.RARE, 80, new SplittableRandom(3));
        data.setIdentified(true);
        return data;
    }

    @Test
    void addsOneSocketOncePerItem() {
        final ItemData body = item("body_str_10");
        final int before = Runes.slots(items, items.base(body.base()), body);
        assertTrue(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(body, generator));
        CurrencyOperation.BONUS_RUNE_SOCKET.apply(body, generator, new SplittableRandom(1));
        assertEquals(before + 1, Runes.slots(items, items.base(body.base()), body));
        assertFalse(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(body, generator), "一件只能用一次");
        assertEquals(1, body.copy().bonusSockets(), "複製時要帶著");
    }

    @Test
    void worksOnCorruptedAndOnlyOnTheFiveSlots() {
        final ItemData boots = item("boots_str_01");
        boots.setCorrupted(true);
        assertTrue(CurrencyOperation.BONUS_RUNE_SOCKET.allowedOn(boots, items) && CurrencyOperation.BONUS_RUNE_SOCKET.canApply(boots, generator), "腐化的也能用");
        assertTrue(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(item("spear_01"), generator), "武器");
        assertFalse(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(item("jring_13"), generator), "戒指不行");
    }

    @Test
    void worksOnSanctifiedAndDoubleCorrupted() {
        // 2026-11-08 照站長「已聖化跟雙重腐化的也能用 1 次」
        final ItemData chest = item("body_str_10");
        chest.setSanctified(true);
        assertTrue(CurrencyOperation.BONUS_RUNE_SOCKET.allowedOnSanctified());
        assertEquals(null, Crafting.problem(currency(), chest, generator));
        final ItemData helm = item("boots_str_01");
        helm.setCorrupted(true);
        helm.setDoubleCorrupted(true);
        assertTrue(CurrencyOperation.BONUS_RUNE_SOCKET.allowedOn(helm, items) && CurrencyOperation.BONUS_RUNE_SOCKET.canApply(helm, generator));
        CurrencyOperation.BONUS_RUNE_SOCKET.apply(helm, generator, new SplittableRandom(1));
        assertFalse(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(helm, generator), "還是一件一次");
    }

    private static CurrencyType currency() {
        try {
            final CurrencyRepository currencies = new CurrencyRepository();
            currencies.load(Path.of("src/main/resources/data"));
            return currencies.get("delve_socket_chisel");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void notOnUniques() {
        final ItemData unique = generator.generateUnique(items.unique("abyss_throne_belt"), 80, new SplittableRandom(1));
        final ItemData uniqueBody = item("body_str_10");
        uniqueBody.setRarity(tw.exilecore.item.Rarity.UNIQUE);
        assertFalse(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(uniqueBody, generator), "2026-11-08 照站長：傳奇裝不能用");
        assertFalse(CurrencyOperation.BONUS_RUNE_SOCKET.canApply(unique, generator));
    }
}
