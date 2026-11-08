package tw.exilecore.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;

/** 2026-11-04 照站長「這幾個好像都沒固定效果」：裂隙指環的品質上限、淵印指環的深淵消耗；空座指環多一格光環格；攫握指環、日冕墜飾 1 個符文孔。 */
class SpecialRingImplicitTest {

    static ItemRepository items;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        final Path data = Path.of("src/main/resources/data");
        items = new ItemRepository();
        items.load(data);
        generator = new ItemGenerator(items);
        currencies = new CurrencyRepository();
        currencies.load(data);
    }

    @Test
    void breachRingsRaiseQualityCap() {
        assertEquals(20, items.maxQualityBonus("jring_breach"));
        assertEquals(25, items.maxQualityBonus("jring_breach_refined"));
        assertEquals(0, items.maxQualityBonus("jring_dusk"));
        final ItemData ring = generator.generate(items.base("jring_breach"), Rarity.NORMAL, 60, new SplittableRandom(3));
        ring.setIdentified(true);
        final CurrencyType resist = currencies.get("catalyst_fire"); // 2026-11-08：抗性催化劑（舊）刪掉了
        final int max = Crafting.catalystMax(items, ring);
        assertEquals(40, max);
        for (int i = 0; i < 20; i++) {
            Crafting.applyCatalyst(resist, ring, max);
        }
        assertEquals(40, ring.quality(), "普通 +5 一顆，打到 40% 為止");
        assertTrue(Crafting.problem(resist, ring, generator) != null, "到上限就不能再打");
    }

    @Test
    void basesHaveImplicits() {
        assertNotNull(items.base("jring_breach").implicit());
        assertNotNull(items.base("jring_breach_refined").implicit());
        assertTrue(items.base("jring_abyssal").implicit().mods().stream()
                .anyMatch(mod -> mod.stat().equals("abyssal_wasting.on_hit")), "淵印指環：擊中時施加深淵消耗");
        assertTrue(items.auraSlotBase("jring_unset") && !items.retiredBase("jring_unset") && !items.heistOnlyBase("jring_unset"),
                "空座指環：光環格、全域掉落");
        for (final String id : new String[] {"jring_grasping", "jamulet_corona"}) {
            assertEquals(1, tw.exilecore.item.Runes.slots(items, items.base(id), generator.generate(items.base(id), Rarity.MAGIC, 60, new SplittableRandom(1))),
                    id + "：1 個符文孔");
            assertTrue(items.heistOnlyBase(id), id + "：劫盜專屬");
        }
        assertEquals(0, tw.exilecore.item.Runes.slots(items, items.base("jring_dusk"), null), "一般戒指沒有符文孔");
        // 只加那件裝備的護甲、閃避、能量護盾的符文嵌在飾品上沒效果：擋下來
        final ItemData grasping = generator.generate(items.base("jring_grasping"), Rarity.MAGIC, 60, new SplittableRandom(2));
        grasping.setIdentified(true);
        assertTrue(Crafting.problem(currencies.get("rune_iron_lesser"), grasping, generator) != null, "鐵骨符文擋下來");
        assertEquals(null, Crafting.problem(currencies.get("rune_body_perfect"), grasping, generator), "體魄符文（生命）可以");
    }
}
