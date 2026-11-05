package tw.exilecore.summer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemRepository;

/** 2026-11-05 照站長「夏日活動補上各 Boss 的入場券，並且新增兩件專屬的傳奇」。 */
class SummerTicketsUniquesTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shopHasEveryBossKeyAndBothUniques() throws Exception {
        final SummerConfig config = new SummerConfig();
        config.load(DATA);
        final ItemRepository items = new ItemRepository();
        items.load(DATA);
        final Set<String> currencies = config.shop().stream().filter(item -> item.kind() == SummerConfig.Kind.CURRENCY)
                .map(SummerConfig.ShopItem::currency).collect(Collectors.toSet());
        for (final String key : Set.of("ticket_shrine", "ticket_strongbox", "ticket_breach", "ticket_exile", "ticket_ritual", "ticket_delirium",
                "ticket_abyss", "fragment_crucible", "fragment_frost", "fragment_bone", "fragment_ember", "sky_fragment", "abyss_core",
                "heist_finale_key", "fog_splinter")) {
            assertTrue(currencies.contains(key), "兌換有 " + key);
        }
        final Set<String> uniques = config.shop().stream().filter(item -> item.kind() == SummerConfig.Kind.UNIQUE)
                .map(SummerConfig.ShopItem::unique).collect(Collectors.toSet());
        assertEquals(Set.of("coral_throat_spear", "tide_pearl_heart"), uniques);
        assertEquals(Set.copyOf(config.boss().uniques()), uniques, "海妖掉的跟兌換的是同兩件");
        assertEquals(0.02, config.boss().uniqueChance(), 1e-9);
        for (final String id : uniques) {
            assertNotNull(items.unique(id), id);
            assertEquals(Set.of("world_boss"), Set.copyOf(items.unique(id).sources()), id + "：只有活動 Boss 給");
        }
    }
}
