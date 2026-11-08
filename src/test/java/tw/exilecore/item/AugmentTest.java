package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.VaalCrafting;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「增幅系統」：增幅（沿用符文孔）、解鎖的專屬詞綴、索魯德的詞綴大小、淵座束帶的視為部位。 */
class AugmentTest {

    static ItemRepository items;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(items);
    }

    private static ItemData rare(final String base, final int seed) {
        final ItemData data = generator.generate(items.base(base), Rarity.RARE, 82, new SplittableRandom(seed));
        data.setIdentified(true);
        return data;
    }

    @Test
    void augmentsOnlyFitTheirSlot() {
        final Runes.Rune medved = items.rune("augment_medved");
        assertNotNull(medved);
        assertTrue(medved.isAugment());
        final ItemData body = rare("body_str_10", 1);
        final ItemData boots = rare("boots_str_01", 2);
        assertNull(Runes.socketProblem(items, items.base(body.base()), body, medved));
        assertNotNull(Runes.socketProblem(items, items.base(boots.base()), boots, medved));
        final ItemData spear = rare("spear_01", 3);
        assertNull(Runes.socketProblem(items, items.base(spear.base()), spear, items.rune("augment_thrud")));
        assertNotNull(Runes.socketProblem(items, items.base(body.base()), body, items.rune("augment_thrud")));
        assertTrue(VaalCrafting.socketBound("augment_kolr"), "嵌死了，換掉不還");
    }

    @Test
    void socketedAugmentUnlocksItsAffixes() {
        final ItemData body = rare("body_str_10", 4);
        body.affixes().clear();
        final boolean before = generator.eligible(body, AffixType.PREFIX).stream().anyMatch(a -> a.spawnTags().contains("augment_medved"));
        assertFalse(before, "沒嵌增幅擲不到");
        body.addRune("augment_medved");
        assertTrue(generator.eligible(body, AffixType.PREFIX).stream().anyMatch(a -> a.spawnTags().contains("augment_medved")), "嵌了就擲得到");
        assertTrue(generator.eligible(body, AffixType.PREFIX).stream().noneMatch(a -> a.spawnTags().contains("augment_vorana")), "別的增幅的不會");
    }

    @Test
    void thrudScalesThatKindOfModifier() {
        final Affix physical = items.affixes().stream()
                .filter(a -> a.spawnTags().contains("augment_thrud") && a.mods().stream().anyMatch(m -> m.stat().equals(ItemStats.MOD_MAGNITUDE + "physical")))
                .findFirst().orElseThrow();
        final ItemData spear = rare("spear_01", 5);
        spear.affixes().clear();
        spear.affixes().add(new RolledAffix("phys_inc", 1, Map.of("value", 100)));
        final double before = LocalMods.of(items, items.base(spear.base()), spear).physical();
        final AffixTier tier = physical.tiers().getFirst();
        final String var = tier.vars().keySet().iterator().next();
        spear.affixes().add(new RolledAffix(physical.id(), tier.tier(), Map.of(var, 15)));
        assertEquals(before * 1.15, LocalMods.of(items, items.base(spear.base()), spear).physical(), 1e-9, "物理詞綴 ×1.15");
        assertEquals(1.0, ItemStats.magnitudeScale(ItemStats.magnitudes(items, spear), Modifier.flat(StatKeys.LIFE_MAX, 10, "")), 1e-9, "別類不吃");
    }

    @Test
    void throneBeltIsTreatedAsTheRolledSlot() {
        final UniqueItem belt = items.unique("abyss_throne_belt");
        for (int seed = 0; seed < 30; seed++) {
            final ItemData data = generator.generateUnique(belt, 80, new SplittableRandom(seed));
            data.setIdentified(true);
            final String as = Runes.treatedAs(items, data);
            assertNotNull(as, "五個版本一定有一個");
            assertTrue(Runes.RUNE_AS_SLOTS.contains(as));
            final String fits = switch (as) {
                case "chest" -> "augment_medved";
                case "head" -> "augment_vorana";
                case "legs" -> "augment_kolr";
                case "feet" -> "augment_uhtred";
                default -> null;
            };
            if (fits != null) {
                assertNull(Runes.socketProblem(items, items.base(data.base()), data, items.rune(fits)), as + " 的增幅嵌得進去");
            }
            assertNotNull(Runes.socketProblem(items, items.base(data.base()), data, items.rune("augment_thrud")), "武器的不行");
        }
        final List<String> kinds = List.of("chest", "head", "legs", "feet", "shield");
        assertEquals(kinds, Runes.RUNE_AS_SLOTS);
    }
}
