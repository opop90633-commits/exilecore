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

    /** 七種有專屬傳奇的地圖事件（2026-10-18：＋深淵裂縫）。 */
    private static final List<String> EVENTS = List.of("shrine", "strongbox", "breach", "exile", "ritual", "delirium", "abyss");

    @Test
    void poolIsBossAndNamedUniquesOnly() {
        // 2026-10 PoE2 改版：舊的傳奇退役，換成新的一批（地圖事件各五件）
        final List<UniqueItem> pool = UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 100);
        final Set<String> got = ids(pool);
        final Set<String> expected = new HashSet<>();
        for (final UniqueItem unique : items.uniques()) {
            if (unique.droppedBy("map_boss")) {
                expected.add(unique.id());
            }
        }
        // 2026-10-26 照 PoE2 0.5：＋30 件原作有、我們以前沒有的一般掉落傳奇（這次之前 45）
        assertEquals(221, expected.size(), "2026-10-31：＋信徒之錘，這次之前 220；2026-10-30：＋拉薇安之泉，這次之前 219；2026-10-29：始源冠冕、劈世巨鎚改成只有世界 Boss 掉（−2）；＋血之屏障，這次之前 220；＋百目冠，這次之前 219；地圖 Boss 池 200 件（2026-10-28 照 PoE2：＋41 件原作沒有掉落限制、以前是內容專屬的，＋7 件新傳奇，－8 件改成 Boss 限定或禁止掉落，這次之前 160；2026-10-28：＋黯炎；2026-10-27：＋84 件照 PoE2 的第二批一般傳奇；2026-10-26：＋30 件；2026-10-24：＋6 件戰星傳奇；2026-10：＋德魯伊的獸魂符 4 件；2026-10-15 ＋6 件、＋3 件圖騰；2026-10-17 ＋棘壘）");
        for (final String event : EVENTS) {
            for (final UniqueItem unique : items.uniques()) {
                // 2026-10-28 照 PoE2：原作沒有掉落限制的（以前是地圖事件專屬）也是全域掉落，換得到；限定的照舊換不到
                if (unique.droppedBy(event) && !tw.exilecore.item.UniqueRarity.global(unique)) {
                    assertFalse(got.contains(unique.id()), unique.id() + "：地圖事件的專屬換不到（2026-10-17，跟賭博一樣）");
                }
            }
        }
        expected.addAll(Set.of("gravewarden_maul", "astrolabe_wand"));
        // 2026-10-28 照 PoE2：極境的三件（長冬之戒、冥河之衛、行灰之履）原作沒有掉落限制，地圖 Boss 也會掉，但三換一照舊換不到（protectedUnique）
        expected.removeIf(id -> UniqueRecipe.protectedUnique(items.unique(id)));
        assertEquals(expected, got, "地圖 Boss 池＋兩個具名 Boss");
        assertEquals(218, pool.size()); // 2026-10-31：＋忠誠之錘（這次之前 217）；2026-10-30：＋拉維安加之泉（這次之前 216）；2026-10-29：始源冠冕、劈世巨鎚只有世界 Boss 掉（−2）；＋鮮血屏障（這次之前 217）；＋百目冠（這次之前 216）； 2026-10-28 照 PoE2：全域掉落多了（見上面，這次之前 162）；2026-10-28：＋黯炎（這次之前 161）；2026-10-27 照 PoE2 0.5 第二批：＋84 件一般掉落傳奇（這次之前 77）；2026-10-26：＋30 件（這次之前 47）
        // 2026-10-28 照 PoE2：天樞墜、隕砧、霜環、熔鑄壁鎧、始源冠冕、劈世巨鎚原作沒有掉落限制，改成全域掉落（換得到），從這裡拿掉
        for (final String never : List.of("trophy_girdle", "hollow_diadem", "colossus_heart", "horizon_cleaver", "solar_sceptre",
                "winterbound_ring", "styxguard", "ashwalker_boots", "bone_cinch", "roamers_boots", "goldleaf_hood", "abyss_maul",
                "spiteforge_greaves", "tailwind_quiver", "hallowed_fire", "empress_sneer", "flameclasp_belt", "veiled_tiara")) {
            assertFalse(got.contains(never), never + " 不會換到（追逐級、極境專屬、終局自己的、只能賭的、劫盜、Boss 限定、禁止掉落）");
        }
        for (final String worldOnly : List.of("genesis_crown", "worldsplitter")) {
            assertFalse(got.contains(worldOnly), worldOnly + "：2026-10-29 照站長只有世界 Boss 掉，換不到");
        }
        for (final String now : List.of("celestial_pivot", "meteor_anvil", "rime_halo", "molten_aegis", "sanctum_helm")) {
            assertTrue(got.contains(now), now + "：2026-10-28 照 PoE2 是全域掉落，換得到");
        }
        for (final UniqueItem unique : pool) {
            assertFalse(items.retiredUnique(unique.id()), "退役的傳奇換不到：" + unique.id());
        }
    }

    @Test
    void chaseAndUberNeverComeOutEvenIfListed() {
        final UniqueRecipe.Settings greedy = new UniqueRecipe.Settings(true, 3,
                List.of("chase", "uber_sky_crucible", "uber_bone_throne", "final"), true);
        final Set<String> got = ids(UniqueRecipe.outcomes(items.uniques(), greedy, List.of(), 100));
        assertEquals(Set.of("celestial_pivot", "meteor_anvil", "spider_bulwark", "spiteforge_greaves", "tailwind_quiver", "hallowed_fire",
                "empress_sneer"), got, "寫了 chase、uber_… 也不會換到；final 寫了就會（2026-10-15：＋蛛巢壁壘；2026-10-26：恆時珠寶英雄末路是 Boss 固定 1% 掉，換不到；2026-10-28 照 PoE2：＋原作 Boss 限定、我們放終局 Boss 的四件）");
    }

    @Test
    void inputsAreExcludedUnlessNothingElseIsLeft() {
        final List<UniqueItem> pool = UniqueRecipe.outcomes(items.uniques(), config.recipe(),
                List.of("keymasters_ring", "keymasters_ring", "undertow_band"), 100);
        assertEquals(216, pool.size(), "2026-10-31：＋信徒之錘，這次之前 215；2026-10-30：＋拉薇安之泉，這次之前 214；2026-10-29：始源冠冕、劈世巨鎚改成只有世界 Boss 掉（−2）；＋血之屏障，這次之前 215；＋百目冠，這次之前 214；（2026-10-28 照 PoE2：全域掉落多了，這次之前 161；2026-10-28：＋黯炎，這次之前 160；2026-10-27：＋84 件第二批一般掉落傳奇，這次之前 76；2026-10-26：＋30 件新的一般掉落傳奇，這次之前 46）放進去的不會換到（2026-10-17：事件的專屬本來就不在池子裡，只少了一種）");
        assertFalse(ids(pool).contains("keymasters_ring"));
        assertFalse(ids(pool).contains("undertow_band"));
        // 池子只剩放進去的那種：照樣換（不然永遠換不了）
        final UniqueRecipe.Settings onlyObservatory = new UniqueRecipe.Settings(true, 3, List.of("observatory"), true);
        assertEquals(Set.of("astrolabe_wand"), ids(UniqueRecipe.outcomes(items.uniques(), onlyObservatory, List.of("astrolabe_wand"), 100)));
        // 不排除：放進去的也可能換到
        final UniqueRecipe.Settings keep = new UniqueRecipe.Settings(true, 3, UniqueRecipe.DEFAULT_SOURCES, false);
        assertEquals(218, /* 2026-10-31：＋忠誠之錘，這次之前 217；2026-10-30：＋拉維安加之泉，這次之前 216；2026-10-29：始源冠冕、劈世巨鎚只有世界 Boss 掉（−2）；＋鮮血屏障，這次之前 217；＋百目冠，這次之前 216； 2026-10-28 照 PoE2：全域掉落多了，這次之前 162；2026-10-28：＋黯炎，這次之前 161；2026-10-27：＋84 件第二批一般傳奇，這次之前 77；2026-10-26：＋30 件新的一般掉落傳奇，這次之前 47 */ UniqueRecipe.outcomes(items.uniques(), keep, List.of("keymasters_ring"), 100).size());
    }

    @Test
    void itemLevelIsTheHighestInputAndGatesDropLevel() {
        assertEquals(84, UniqueRecipe.itemLevel(List.of(unique("undertow_band", 62), unique("crimson_coil", 84), unique("skypierce", 70))));
        final List<UniqueItem> at60 = UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 60);
        assertEquals(201, at60.size(), "2026-10-31：＋信徒之錘（33 級），這次之前 200；2026-10-30：＋拉薇安之泉（49 級），這次之前 199；2026-10-29：＋血之屏障（16 級），這次之前 198；＋百目冠（45 級），這次之前 197；60 級（2026-10-28 照 PoE2：全域掉落多了，這次之前 150；2026-10-28：＋黯炎（20 級），這次之前 149；2026-10-27：＋78 件 60 級以內的第二批傳奇，這次之前 71；2026-10-26：＋28 件 60 級以內的新傳奇，這次之前 43；2026-10-24：＋6 件戰星傳奇都在 60 級以內；2026-10-17：事件的專屬拿掉了）：60 級以上的還換不到");
        for (final UniqueItem unique : at60) {
            assertTrue(unique.dropLevel() <= 60, unique.id());
        }
        // 2026-10-26 照 PoE2 0.5：新加的一般掉落傳奇照原作的需求等級，有 1 級的（荊脈長棍、巨力腰索…），1 級起就換得到
        // （這次之前最低是戰星傳奇的 5 級，4 級以下換不到）
        assertFalse(UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 1).isEmpty(), "1 級起換得到");
        for (final UniqueItem unique : UniqueRecipe.outcomes(items.uniques(), config.recipe(), List.of(), 4)) {
            assertTrue(unique.dropLevel() <= 4, unique.id());
        }
    }

    @Test
    void whatCanBePutIn() {
        assertNull(UniqueRecipe.problem(unique("undertow_band", 70), items.unique("undertow_band")), "一般的傳奇可以放");
        assertNull(UniqueRecipe.problem(unique("rime_halo", 87), items.unique("rime_halo")), "終局自己的可以放（換不到而已）");
        assertNull(UniqueRecipe.problem(unique("roamers_boots", 30), items.unique("roamers_boots")), "只能賭的也可以放");
        final ItemData rare = new ItemData("jring_16", Rarity.RARE, 70);
        assertEquals("只能放傳奇", UniqueRecipe.problem(rare, null));
        final ItemData unidentified = unique("undertow_band", 70);
        unidentified.setIdentified(false);
        assertTrue(UniqueRecipe.problem(unidentified, items.unique("undertow_band")).contains("鑑定"));
        final ItemData locked = unique("undertow_band", 70);
        locked.setLocked(true);
        assertTrue(UniqueRecipe.problem(locked, items.unique("undertow_band")).contains("鎖定"));
        assertNull(UniqueRecipe.problem(unique("trophy_girdle", 80), items.unique("trophy_girdle")), "追逐級可以放（2026-10：換追逐級）");
        assertNull(UniqueRecipe.problem(unique("styxguard", 91), items.unique("styxguard")), "極境專屬可以放（2026-10：換一般賭博的傳奇）");
        assertTrue(UniqueRecipe.uberExclusive(items.unique("styxguard")));
        assertFalse(UniqueRecipe.uberExclusive(items.unique("undertow_band")));
        assertFalse(UniqueRecipe.uberExclusive(items.unique("trophy_girdle")), "追逐級不算極境專屬");
        assertTrue(UniqueRecipe.problem(unique("undertow_band", 70), null).contains("沒有了"), "uniques.yml 已經拿掉的");
        final ItemData noUuid = new ItemData();
        noUuid.setRarity(Rarity.UNIQUE);
        noUuid.setUnique("undertow_band");
        assertTrue(UniqueRecipe.problem(noUuid, items.unique("undertow_band")).contains("不完整"), "沒有 UUID 的（舊資料）不收");
        for (final UniqueItem unique : items.uniques()) {
            // 2026-10-26：永恆珠寶（Boss 固定 1% 掉）也不會換到
            assertEquals(unique.chase() || unique.sources().stream().anyMatch(source -> source.startsWith("uber"))
                            || unique.base().equals("timeless_jewel"),
                    UniqueRecipe.protectedUnique(unique), unique.id());
        }
    }

    @Test
    void uberInputsGiveGambleUniques() {
        final List<UniqueItem> pool = UniqueRecipe.gambleOutcomes(items.uniques(), config.recipe(), List.of("styxguard"), 100);
        assertFalse(pool.isEmpty());
        for (final UniqueItem unique : pool) {
            assertTrue(unique.droppedBy(UniqueRecipe.GAMBLE_SOURCE), unique.id() + " 賭得到");
            assertFalse(UniqueRecipe.protectedUnique(unique), unique.id() + " 不是追逐級、極境專屬");
        }
        assertTrue(ids(pool).contains("roamers_boots"), "只能賭的也會換到");
    }

    @Test
    void threeChaseAlwaysGiveARandomChase() {
        final Set<String> chase = new HashSet<>();
        for (final UniqueItem unique : items.uniques()) {
            if (unique.chase() && unique.weight() > 0) {
                chase.add(unique.id());
            }
        }
        assertFalse(chase.isEmpty());
        for (final String id : chase) {
            assertFalse(items.retiredUnique(id));
        }
        assertEquals(20, chase.size(), "追逐級 20 件（2026-10-27：＋7 件照 PoE2 的第二批；2026-10-15 加了九件）");
        assertEquals(chase, ids(UniqueRecipe.chaseOutcomes(items.uniques(), 100)), "全部的追逐級都可能換到（包括放進去的那種），沒有別的");
        assertTrue(UniqueRecipe.chaseOutcomes(items.uniques(), 74).isEmpty(), "追逐級 75 級起");
        assertNull(UniqueRecipe.mixProblem(true, true));
        assertNull(UniqueRecipe.mixProblem(false, false));
        assertTrue(UniqueRecipe.mixProblem(true, false).contains("追逐級"), "不能混放");
        assertTrue(UniqueRecipe.mixProblem(false, true).contains("追逐級"));
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
