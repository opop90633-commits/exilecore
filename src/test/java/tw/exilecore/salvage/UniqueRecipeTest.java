package tw.exilecore.salvage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 傳奇三換一：能放哪些、換得到哪些（用出貨的 uniques.yml 與 salvage.yml）。 */
class UniqueRecipeTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static SalvageConfig config;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        config = new SalvageConfig();
        config.load(DATA);
    }

    private static Set<String> ids(final List<UniqueItem> uniques) {
        return uniques.stream().map(UniqueItem::id).collect(Collectors.toSet());
    }

    private static ItemData unique(final String id, final int ilvl) {
        final UniqueItem definition = items.unique(id);
        final ItemData data = new ItemData(definition.base(), Rarity.UNIQUE, ilvl);
        data.setUnique(id);
        data.setName(definition.name());
        data.setIdentified(true);
        return data;
    }

    @Test
    void shippedSettingsMatchTheDefaults() {
        assertEquals(UniqueRecipe.Settings.defaults(), config.recipe(), "程式的預設值跟出貨的 salvage.yml 一樣");
        assertTrue(config.recipe().enabled());
        assertEquals(3, config.recipe().inputs());
        assertEquals("傳奇三換一", config.recipe().title());
    }

    @Test
    void poolIsBossEventAndNamedUniquesOnly() {
        final List<UniqueItem> pool = UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 100);
        final Set<String> got = ids(pool);
        final Set<String> expected = new HashSet<>();
        for (final UniqueItem unique : items.uniques()) {
            if (unique.droppedBy("map_boss")) {
                expected.add(unique.id());
            }
        }
        assertEquals(14, expected.size(), "地圖 Boss 池 14 件");
        expected.addAll(Set.of("shrine_lamp", "thousand_locks", "rift_sceptre", "exile_oath", "blood_rite", "mist_mirror"));
        expected.addAll(Set.of("tomb_oath", "star_chart"));
        assertEquals(expected, got, "地圖 Boss 池＋六種事件＋兩個具名 Boss");
        assertEquals(22, pool.size());
        for (final String never : List.of("star_chaser", "emperor_crown", "starfall_heart", "sky_rift", "blazing_sun", "eternal_winter",
                "styx_ward", "skyburn_greaves", "sky_heart", "star_forge", "frost_crown", "ferryman_toll", "ember_bulwark", "wanderer",
                "gilded_brim")) {
            assertFalse(got.contains(never), never + " 不會換到（追逐級、極境專屬、終局自己的、只能賭的）");
        }
    }

    @Test
    void chaseAndUberNeverComeOutEvenIfListed() {
        final UniqueRecipe.Settings greedy = new UniqueRecipe.Settings(true, 3,
                List.of("chase", "uber_sky_crucible", "uber_bone_throne", "final"), true);
        final Set<String> got = ids(UniqueRecipe.outcomes(items.uniques(), greedy, List.of(), 100));
        assertEquals(Set.of("sky_heart", "star_forge"), got, "寫了 chase、uber_… 也不會換到；final 寫了就會");
    }

    @Test
    void inputsAreExcludedUnlessNothingElseIsLeft() {
        final List<UniqueItem> pool = UniqueRecipe.outcomes(items.uniques(), config.recipe(),
                List.of("thousand_locks", "thousand_locks", "tide_scar"), 100);
        assertEquals(20, pool.size(), "放進去的兩種不會換到");
        assertFalse(ids(pool).contains("thousand_locks"));
        assertFalse(ids(pool).contains("tide_scar"));
        // 池子只剩放進去的那種：照樣換（不然永遠換不了）
        final UniqueRecipe.Settings onlyBoxes = new UniqueRecipe.Settings(true, 3, List.of("strongbox"), true);
        assertEquals(Set.of("thousand_locks"), ids(UniqueRecipe.outcomes(items.uniques(), onlyBoxes, List.of("thousand_locks"), 100)));
        // 不排除：放進去的也可能換到
        final UniqueRecipe.Settings keep = new UniqueRecipe.Settings(true, 3, UniqueRecipe.DEFAULT_SOURCES, false);
        assertEquals(22, UniqueRecipe.outcomes(items.uniques(), keep, List.of("thousand_locks"), 100).size());
    }

    @Test
    void itemLevelIsTheHighestInputAndGatesDropLevel() {
        assertEquals(84, UniqueRecipe.itemLevel(List.of(unique("tide_scar", 62), unique("iron_vow", 84), unique("blood_edge", 70))));
        final List<UniqueItem> at60 = UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 60);
        assertEquals(19, at60.size(), "60 級：焚城、空殼、不屈（68）還換不到");
        for (final UniqueItem unique : at60) {
            assertTrue(unique.dropLevel() <= 60, unique.id());
        }
        assertTrue(UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 59).isEmpty(), "59 級以下換不到東西");
    }

    @Test
    void whatCanBePutIn() {
        assertNull(UniqueRecipe.problem(unique("tide_scar", 70), items.unique("tide_scar")), "一般的傳奇可以放");
        assertNull(UniqueRecipe.problem(unique("ferryman_toll", 87), items.unique("ferryman_toll")), "終局自己的可以放（換不到而已）");
        assertNull(UniqueRecipe.problem(unique("wanderer", 30), items.unique("wanderer")), "只能賭的也可以放");
        final ItemData rare = new ItemData("iron_ring", Rarity.RARE, 70);
        assertEquals("只能放傳奇", UniqueRecipe.problem(rare, null));
        final ItemData unidentified = unique("tide_scar", 70);
        unidentified.setIdentified(false);
        assertTrue(UniqueRecipe.problem(unidentified, items.unique("tide_scar")).contains("鑑定"));
        final ItemData locked = unique("tide_scar", 70);
        locked.setLocked(true);
        assertTrue(UniqueRecipe.problem(locked, items.unique("tide_scar")).contains("鎖定"));
        assertTrue(UniqueRecipe.problem(unique("star_chaser", 80), items.unique("star_chaser")).contains("追逐級"));
        assertTrue(UniqueRecipe.problem(unique("styx_ward", 91), items.unique("styx_ward")).contains("極境"));
        assertTrue(UniqueRecipe.problem(unique("tide_scar", 70), null).contains("沒有了"), "uniques.yml 已經拿掉的");
        final ItemData noUuid = new ItemData();
        noUuid.setRarity(Rarity.UNIQUE);
        noUuid.setUnique("tide_scar");
        assertTrue(UniqueRecipe.problem(noUuid, items.unique("tide_scar")).contains("不完整"), "沒有 UUID 的（舊資料）不收");
        for (final UniqueItem unique : items.uniques()) {
            assertEquals(unique.chase() || unique.sources().stream().anyMatch(source -> source.startsWith("uber")),
                    UniqueRecipe.protectedUnique(unique), unique.id());
        }
    }

    @Test
    void brokenRecipeKeepsTheOldSettings(@org.junit.jupiter.api.io.TempDir final Path dir) throws IOException {
        java.nio.file.Files.writeString(dir.resolve("salvage.yml"), "unique_recipe:\n  enabled: false\n");
        final SalvageConfig custom = new SalvageConfig();
        custom.load(dir);
        assertFalse(custom.recipe().enabled());
        java.nio.file.Files.writeString(dir.resolve("salvage.yml"), "unique_recipe:\n  enabled: false\n  inputs: 9\n");
        assertThrows(IllegalArgumentException.class, () -> custom.load(dir));
        assertFalse(custom.recipe().enabled(), "寫錯了：原本的設定（關掉）不動，不會變回開著");
        java.nio.file.Files.writeString(dir.resolve("salvage.yml"), "gold:\n  base: 4\n");
        custom.load(dir);
        assertEquals(UniqueRecipe.Settings.defaults(), custom.recipe(), "沒寫 unique_recipe：預設（開著）");
    }

    @Test
    void settingsParse() {
        final UniqueRecipe.Settings custom = UniqueRecipe.Settings.parse(YamlNode.load("test.yml", new StringReader("""
                enabled: false
                inputs: 4
                sources: [Map_Boss, map_boss, ritual]
                exclude_inputs: false
                """)));
        assertFalse(custom.enabled());
        assertEquals(4, custom.inputs());
        assertEquals(List.of("map_boss", "ritual"), custom.sources(), "轉小寫、去掉重複");
        assertFalse(custom.excludeInputs());
        assertEquals("傳奇四換一", custom.title());
        assertEquals(UniqueRecipe.DEFAULT_SOURCES, UniqueRecipe.Settings.parse(YamlNode.load("test.yml", new StringReader("inputs: 3\n"))).sources(),
                "沒寫 sources：預設的池子");
        assertThrows(IllegalArgumentException.class, () -> UniqueRecipe.Settings.parse(YamlNode.load("test.yml", new StringReader("inputs: 1\n"))));
        assertThrows(IllegalArgumentException.class, () -> UniqueRecipe.Settings.parse(YamlNode.load("test.yml", new StringReader("inputs: 9\n"))));
        assertThrows(IllegalArgumentException.class, () -> UniqueRecipe.Settings.parse(YamlNode.load("test.yml", new StringReader("sources: []\n"))));
    }
}
