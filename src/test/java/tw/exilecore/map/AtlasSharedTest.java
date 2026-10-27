package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：星圖帳號共用——哪些鍵算星圖、轉檔挑哪個角色的星圖。 */
class AtlasSharedTest {

    static MapConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new MapConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void atlasKeysAreTheGridAndTreeOnly() {
        for (final String key : List.of("atlasseed", "atlascells", "atlascells2", "atlaschosen", "atlaslastmap", "atlaslasttower",
                "citadel.sky_forge", "tablet.1:2", "tabletuses.1:2", "towerhide.1:2", "celltry.0:1", "towertry.3:-1",
                "citadelfree.sky_forge", "citadeltry.4:4", "atlasfaith", "atlastree", "atlastree_v", "atlasset.0.0", "atlassetactive")) {
            assertTrue(MapService.isAtlasKey(key), key);
        }
        // 角色自己的首殺、最高階、極境、試煉，以及帳號那幾筆總表，都不是「搬過去的星圖」
        for (final String key : List.of("atlas.forest", "mapbest", "fkpts.forest", "uber.sky_forge", "bossmaps.peaks", "trial.one",
                MapService.SHARED_MARK, MapService.ACCOUNT_FINAL_PREFIX + "x", MapService.ACCOUNT_UBER_PREFIX + "x", MapService.ACCOUNT_BEST_KEY)) {
            assertFalse(MapService.isAtlasKey(key), key);
        }
    }

    @Test
    void migrationTakesTheMostCompletedAtlasAndUnionsEndgame() {
        final MapConfig.MapDef normalFinal = config.finals().stream().filter(map -> !config.isEventPinnacle(map.id())).findFirst().orElseThrow();
        final MapConfig.MapDef eventFinal = config.finals().stream().filter(map -> config.isEventPinnacle(map.id())).findFirst().orElse(null);

        final Map<String, String> small = new HashMap<>();
        small.put(AtlasProgress.SEED_KEY, "11");
        small.put(AtlasProgress.CELLS_KEY, "1:0");
        small.put(AtlasTree.PROGRESS_KEY, "small_tree");
        small.put(Atlas.BEST_KEY, "5");
        small.put(Atlas.key(normalFinal.id()), "100");
        small.put(MapService.uberKey(normalFinal.id()), "200");

        final Map<String, String> big = new HashMap<>();
        big.put(AtlasProgress.SEED_KEY, "22");
        big.put(AtlasProgress.CELLS_KEY, "1:0,1:1,2:0");
        big.put(MapService.FAITH_KEY, "EXILE");
        big.put(Atlas.BEST_KEY, "12");
        big.put("atlas.forest", "300");
        if (eventFinal != null) {
            big.put(Atlas.key(eventFinal.id()), "400");
        }

        final Map<String, String> noSeed = new HashMap<>();
        noSeed.put(AtlasProgress.CELLS_KEY, "1:0,1:1,2:0,3:0,3:1"); // 沒有種子的格子沒有意義，不挑

        final Map<String, String> values = MapService.sharedAtlas(config, List.of(small, big, noSeed));
        assertEquals("22", values.get(AtlasProgress.SEED_KEY));
        assertEquals("1:0,1:1,2:0", values.get(AtlasProgress.CELLS_KEY));
        assertEquals("EXILE", values.get(MapService.FAITH_KEY));
        assertNull(values.get(AtlasTree.PROGRESS_KEY), "星圖天賦跟著挑到的那個角色，不混");
        assertEquals("100", values.get(MapService.ACCOUNT_FINAL_PREFIX + normalFinal.id()));
        assertEquals("200", values.get(MapService.ACCOUNT_UBER_PREFIX + normalFinal.id()));
        assertEquals("12", values.get(MapService.ACCOUNT_BEST_KEY));
        assertFalse(values.containsKey("atlas.forest"), "首殺留在角色上");
        assertFalse(values.containsKey(Atlas.BEST_KEY));
        if (eventFinal != null) {
            assertNull(values.get(MapService.ACCOUNT_FINAL_PREFIX + eventFinal.id()), "事件巔峰不算星圖的終局");
        }
    }

    @Test
    void accountWithoutAnySeedGetsNoGrid() {
        final Map<String, String> legacy = new HashMap<>();
        legacy.put("atlas.forest", "1");
        final Map<String, String> values = MapService.sharedAtlas(config, List.of(legacy));
        assertNull(values.get(AtlasProgress.SEED_KEY), "沒有種子：第一次用到星圖時照舊產生（並把舊的完成地圖轉成格子）");
        assertNotNull(values);
    }
}
