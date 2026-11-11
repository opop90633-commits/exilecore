package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.Runes;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11 照站長「第一個圖片的通貨與第二張圖整個系列的符文，都放進深坑 boss」：星界合金、艾珊德菈那一系列的增幅符文。 */
class SpecialRunesTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final List<String> IDS = List.of("assandra_wisdom", "saqawal_sky", "fenumus_agony", "farrul_grace", "farrul_chase",
            "craiceann_warding", "saqawal_memory", "saqawal_erosion", "farrul_hunt", "craiceann_recovery", "mannan_cruelty",
            "grannell_mastery", "fenumus_spinning", "seske_archery", "girt_wildness", "fenumus_draining", "myrk_summer",
            "hestra_winter", "leld_spring", "greatwolf_claws", "greatwolf_willpower");
    static ItemRepository items;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        generator = new ItemGenerator(items);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    private static ItemData rare(final String base, final int seed) {
        final ItemData data = generator.generate(items.base(base), Rarity.RARE, 82, new SplittableRandom(seed));
        data.setIdentified(true);
        return data;
    }

    private static boolean fits(final String rune, final String base) {
        final ItemData data = rare(base, 1);
        return Runes.socketProblem(items, items.base(base), data, items.rune("special_" + rune)) == null;
    }

    @Test
    void wholeSeriesExistsAsReplaceableLimitedAugments() throws IOException {
        final String maps = Files.readString(DATA.resolve("maps.yml"));
        for (final String id : IDS) {
            final Runes.Rune rune = items.rune("special_" + id);
            assertNotNull(rune, id);
            assertTrue(rune.isAugment(), id);
            assertEquals(1, rune.limit(), id);
            assertEquals(50, rune.level(), id);
            assertFalse(VaalCrafting.socketBound(rune.id()), "可以被別的增幅換掉：" + id);
            final CurrencyType type = currencies.get("rune_special_" + id);
            assertNotNull(type, id);
            assertEquals("special_" + id, type.affix());
            assertTrue(maps.contains("currency: rune_special_" + id + ","), "晶淵之王掉：" + id);
        }
        assertTrue(maps.contains("currency: celestial_alloy,"));
    }

    @Test
    void slotsFollowTheOriginal() {
        assertTrue(fits("assandra_wisdom", "wand"));
        assertTrue(fits("assandra_wisdom", "staff"));
        assertFalse(fits("assandra_wisdom", "spear_01"), "法杖或長杖");
        assertFalse(fits("assandra_wisdom", "sceptre"), "權杖不算");
        assertTrue(fits("farrul_hunt", "spear_01"));
        assertFalse(fits("farrul_hunt", "wand"), "軍用武器");
        assertFalse(fits("farrul_hunt", "sceptre"), "權杖不算軍用武器");
        assertTrue(fits("saqawal_sky", "wand") && fits("saqawal_sky", "spear_01"), "任何武器");
        assertTrue(fits("seske_archery", "bow"));
        assertFalse(fits("seske_archery", "xbow_01"), "只有弓");
        assertTrue(fits("greatwolf_claws", "legs_str_01"), "手套＝褲子");
        assertFalse(fits("greatwolf_claws", "body_str_10"));
        assertTrue(fits("greatwolf_willpower", "body_str_10"));
        assertTrue(fits("farrul_chase", "boots_str_01"));
    }

    @Test
    void socketedRuneGivesItsBonus() {
        final ItemData wand = rare("wand", 2);
        wand.addRune("special_assandra_wisdom");
        final List<Modifier> mods = Runes.modifiers(items, items.base(wand.base()), wand);
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals("skill.level") && m.value() == 1 && m.tags().contains("spell")), mods.toString());
        final ItemData boots = rare("boots_str_01", 3);
        boots.addRune("special_farrul_chase");
        assertTrue(Runes.modifiers(items, items.base(boots.base()), boots).stream()
                .anyMatch(m -> m.stat().equals("movement.speed") && m.value() > 0));
    }

    @Test
    void girtGivesOnePercentPerRage() {
        // 2026-11-11 照站長「還是希望 1 點就 1%」：每 1 點怒氣一條門檻（1～50）
        final List<Runes.Line> bonded = items.rune("special_girt_wildness").bondedOn(items.base("wand"));
        assertEquals(1, bonded.size());
        assertEquals(50, bonded.getFirst().mods().size());
        assertEquals("damage@rage1", bonded.getFirst().mods().getFirst().stat());
        assertEquals("damage@rage50", bonded.getFirst().mods().getLast().stat());
    }

    @Test
    void celestialAlloyReplacesOneModWithItsPrefix() {
        final CurrencyType alloy = currencies.get("celestial_alloy");
        assertNotNull(alloy);
        final ItemData wand = rare("wand", 4);
        assertNull(Crafting.problem(alloy, wand, generator));
        final int count = wand.affixes().size();
        Crafting.applyEssence(alloy, wand, generator, new SplittableRandom(5));
        assertEquals(count, wand.affixes().size(), "移除一條、加一條");
        assertTrue(wand.affixes().stream().map(RolledAffix::id).anyMatch("celestial_caster"::equals));
        final ItemData spear = rare("spear_01", 6);
        assertNull(Crafting.problem(alloy, spear, generator));
        Crafting.applyEssence(alloy, spear, generator, new SplittableRandom(7));
        final RolledAffix martial = spear.affixes().stream().filter(a -> a.id().equals("celestial_martial")).findFirst().orElseThrow();
        assertNotNull(items.affix("celestial_martial"));
        assertTrue(martial.vars().get("value") >= 327 && martial.vars().get("value") <= 427);
        assertTrue(martial.vars().get("speed") >= 5 && martial.vars().get("speed") <= 8);
        assertNotNull(Crafting.problem(alloy, rare("body_str_10", 8), generator), "防具不能用");
    }
}
