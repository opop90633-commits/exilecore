package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 程序生成的星圖（2026-10-21）：連通、間距、階級隨距離、解鎖、天賦點、存檔、舊存檔轉檔。 */
class AtlasGridTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final AtlasConfig config = new AtlasConfig();
    static final MapConfig maps = new MapConfig();
    static AtlasGrid grid;

    @BeforeAll
    static void load() throws IOException {
        config.load(DATA);
        maps.load(DATA);
        final List<AtlasGrid.Species> species = new ArrayList<>();
        for (final MapConfig.MapDef map : maps.maps()) {
            final AtlasConfig.MapRule rule = config.rule(map.id());
            species.add(new AtlasGrid.Species(map.id(), rule.minDistance() >= 0 ? rule.minDistance() : map.ring() * 2 - 1, rule.weight()));
        }
        grid = new AtlasGrid(config, 12345L, species, List.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel"));
    }

    @Test
    void shippedConfigLoadsCleanly() {
        assertTrue(config.problems().isEmpty(), config.problems().toString());
        for (final MapConfig.MapDef map : maps.maps()) {
            assertTrue(config.rule(map.id()).minDistance() >= 0, map.id() + "：atlas.yml 的 maps 要寫每一種地圖");
        }
        assertEquals(1, config.tierRange(1)[0]);
        assertEquals(16, config.tierRange(30)[1]);
    }

    @Test
    void sameSeedSameMap() {
        final AtlasGrid again = new AtlasGrid(config, 12345L, grid.species(), List.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel"));
        for (final long[] at : AtlasGrid.within(0, 0, 8)) {
            assertEquals(grid.cell((int) at[0], (int) at[1]), again.cell((int) at[0], (int) at[1]));
        }
        final AtlasGrid other = new AtlasGrid(config, 999L, grid.species(), List.of());
        int different = 0;
        for (final long[] at : AtlasGrid.within(0, 0, 8)) {
            if (!grid.cell((int) at[0], (int) at[1]).equals(other.cell((int) at[0], (int) at[1]))) {
                different++;
            }
        }
        assertTrue(different > 50, "不同的種子要不一樣：" + different);
    }

    @Test
    void emptiesNeverTouchSoTheMapIsConnected() {
        int empties = 0;
        for (final long[] at : AtlasGrid.within(0, 0, 25)) {
            final int q = (int) at[0];
            final int r = (int) at[1];
            if (!grid.isEmpty(q, r)) {
                continue;
            }
            empties++;
            assertTrue(AtlasGrid.distance(q, r) >= 2, "起點旁邊沒有空格");
            for (final int[] direction : AtlasGrid.DIRECTIONS) {
                assertFalse(grid.isEmpty(q + direction[0], r + direction[1]), "空格相鄰：" + q + "," + r);
            }
        }
        assertTrue(empties > 100, "有一些空格：" + empties);
    }

    @Test
    void kindsAndSpacing() {
        final Map<AtlasGrid.Kind, Integer> kinds = new EnumMap<>(AtlasGrid.Kind.class);
        final List<AtlasGrid.Cell> towers = new ArrayList<>();
        final List<AtlasGrid.Cell> citadels = new ArrayList<>();
        for (final long[] at : AtlasGrid.within(0, 0, 25)) {
            final AtlasGrid.Cell cell = grid.cell((int) at[0], (int) at[1]);
            kinds.merge(cell.kind(), 1, Integer::sum);
            if (cell.kind() == AtlasGrid.Kind.TOWER) {
                towers.add(cell);
                assertTrue(cell.distance() >= config.tower().minDistance());
            }
            if (cell.kind() == AtlasGrid.Kind.CITADEL) {
                citadels.add(cell);
                assertNotNull(cell.citadelFinal());
                assertTrue(cell.distance() >= config.citadels().minDistance());
            }
            if (cell.corrupted()) {
                assertTrue(cell.distance() >= config.corruption().minDistance());
                assertTrue(cell.isMap());
            }
        }
        assertTrue(towers.size() > 20, "塔：" + towers.size());
        assertTrue(citadels.size() >= 4, "城塞：" + citadels.size());
        for (final AtlasGrid.Cell a : towers) {
            for (final AtlasGrid.Cell b : towers) {
                assertTrue(a == b || AtlasGrid.distance(a.q(), a.r(), b.q(), b.r()) > 2, "塔太近");
            }
        }
        for (final AtlasGrid.Cell a : citadels) {
            for (final AtlasGrid.Cell b : citadels) {
                assertTrue(a == b || AtlasGrid.distance(a.q(), a.r(), b.q(), b.r()) > 4, "城塞太近");
            }
        }
        assertTrue(kinds.getOrDefault(AtlasGrid.Kind.BOSS, 0) > kinds.getOrDefault(AtlasGrid.Kind.HIDDEN, 0));
    }

    @Test
    void tierGrowsWithDistanceAndSpeciesRespectMinDistance() {
        for (final long[] at : AtlasGrid.within(0, 0, 25)) {
            final AtlasGrid.Cell cell = grid.cell((int) at[0], (int) at[1]);
            if (cell.isEmpty()) {
                continue;
            }
            final int[] range = config.tierRange(cell.distance());
            assertTrue(cell.tier() >= range[0] && cell.tier() <= range[1], cell.toString());
            if (cell.isMap()) {
                assertNotNull(cell.mapId());
                assertTrue(config.rule(cell.mapId()).minDistance() <= Math.max(1, cell.distance()), cell.toString());
            }
        }
        assertTrue(grid.cell(1, 0).tier() <= 2);
        assertTrue(grid.cell(12, 0).isEmpty() || grid.cell(12, 0).tier() >= 14);
    }

    @Test
    void unlockingAndPoints() {
        final AtlasProgress progress = new AtlasProgress(grid, List.of());
        assertEquals(6, progress.unlocked().size(), "起點周圍 6 格一開始解鎖");
        assertFalse(progress.unlockedSpecies().isEmpty());
        assertEquals(progress.unlockedSpecies(), progress.frontierSpecies());
        assertEquals(0, progress.cellPoints());
        final String mapId = progress.unlockedSpecies().iterator().next();
        final AtlasGrid.Cell cell = progress.defaultCell(mapId);
        assertNotNull(cell);
        assertEquals(1, cell.distance());
        assertTrue(progress.complete(cell.q(), cell.r()));
        assertFalse(progress.complete(cell.q(), cell.r()), "同一格只完成一次");
        assertTrue(progress.unlocked().size() > 6);
        assertEquals(1, progress.cellPoints());
        assertEquals(1, progress.completedMapCells());
        assertTrue(progress.completedSpecies().contains(mapId));
        assertNull(new AtlasProgress(grid, List.of()).defaultCell("observatory"), "沒解鎖的種類沒有格子");
        // 軟上限：60 格之後每 3 格 1 點
        final List<Long> many = new ArrayList<>();
        for (final long[] at : AtlasGrid.within(0, 0, 6)) {
            if (!grid.isEmpty((int) at[0], (int) at[1]) && grid.cell((int) at[0], (int) at[1]).isMap()) {
                many.add(AtlasGrid.key((int) at[0], (int) at[1]));
            }
        }
        final AtlasProgress lots = new AtlasProgress(grid, many);
        final int cells = lots.completedMapCells();
        assertTrue(cells > 60, "半徑 6 有一百多格：" + cells);
        assertEquals(60 + (cells - 60) / 3, lots.cellPoints());
        assertEquals(cells / 2, lots.capUnits());
    }

    @Test
    void storageRoundTripAndChunks() {
        final List<Long> cells = List.of(AtlasGrid.key(1, 0), AtlasGrid.key(-3, 7), AtlasGrid.key(100, -100));
        assertEquals("1:0,-3:7,100:-100", AtlasProgress.encode(cells));
        assertEquals(cells, AtlasProgress.decode("1:0, -3:7 ,100:-100,bad,1"));
        assertEquals(1, AtlasProgress.chunks(cells).size());
        final List<Long> big = new ArrayList<>();
        for (final long[] at : AtlasGrid.within(0, 0, 25)) {
            big.add(AtlasGrid.key((int) at[0], (int) at[1]));
        }
        final List<String> chunks = AtlasProgress.chunks(big);
        assertTrue(chunks.size() >= 2);
        final List<Long> back = new ArrayList<>();
        for (final String chunk : chunks) {
            assertTrue(chunk.length() <= AtlasProgress.CHUNK);
            back.addAll(AtlasProgress.decode(chunk));
        }
        assertEquals(big, back);
        assertEquals("atlascells", AtlasProgress.cellsKey(1));
        assertEquals("atlascells2", AtlasProgress.cellsKey(2));
        final long[] one = AtlasProgress.decodeOne(AtlasProgress.encodeOne(-4, 9));
        assertEquals(-4, one[0]);
        assertEquals(9, one[1]);
        assertNull(AtlasProgress.decodeOne("nope"));
    }

    @Test
    void migrationMarksNearestCellsAndThePathToThem() {
        final List<Long> cells = AtlasProgress.migrate(grid, List.of("wreck_bay", "abyss_mine", "observatory"));
        assertFalse(cells.isEmpty());
        final AtlasProgress progress = new AtlasProgress(grid, cells);
        final Set<String> species = progress.completedSpecies();
        assertTrue(species.contains("wreck_bay"), species.toString());
        assertTrue(species.contains("abyss_mine"), species.toString());
        assertTrue(species.contains("observatory"), species.toString());
        assertTrue(progress.farthest() >= 9, "天文台遺跡在 9 格以外：" + progress.farthest());
        // 路上的格子都是連著的：每一格都跟起點或另一格相鄰
        for (final long key : cells) {
            final int q = AtlasGrid.qOf(key);
            final int r = AtlasGrid.rOf(key);
            boolean linked = AtlasGrid.distance(q, r) == 1;
            for (final int[] direction : AtlasGrid.DIRECTIONS) {
                if (cells.contains(AtlasGrid.key(q + direction[0], r + direction[1]))) {
                    linked = true;
                }
            }
            assertTrue(linked, "轉檔的格子要連著：" + q + "," + r);
        }
    }

    @Test
    void viewTokens() {
        final AtlasProgress progress = new AtlasProgress(grid, List.of());
        final Set<String> view = Atlas.view(maps, Map.of(), progress);
        assertTrue(Atlas.isGrid(view));
        for (final String id : progress.unlockedSpecies()) {
            assertTrue(Atlas.speciesUnlocked(view, id));
            assertTrue(Atlas.frontier(view, id));
            assertTrue(Atlas.unlocked(maps, maps.find(id), view));
        }
        assertFalse(Atlas.unlocked(maps, maps.find("observatory"), view));
        assertFalse(Atlas.finalUnlocked(maps, view));
        assertEquals(0, Atlas.completedMaps(maps, view));
        final Set<String> breached = Atlas.view(maps, Map.of("citadel.sky_crucible", "1"), progress);
        assertTrue(Atlas.finalUnlocked(maps, breached));
        assertTrue(Atlas.citadelBreached(breached, "sky_crucible"));
        assertTrue(Atlas.unlocked(maps, maps.finalMap(), breached));
        // 舊的集合（沒有記號）照舊的規則
        assertFalse(Atlas.isGrid(Set.of("wreck_bay")));
        assertTrue(Atlas.unlocked(maps, maps.find("wreck_bay"), Set.of()), "起點地圖");
    }

    /** 2026-10-24：自己的終局還不能打的城塞順延到下一個能打、還沒攻破的終局。 */
    @Test
    void citadelRedirectsToTheNextFinalYouCanFight() {
        final List<String> finals = List.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel");
        final Set<String> fresh = Set.of();
        assertEquals("sky_crucible", MapService.citadelTarget(maps, finals, "ember_citadel", fresh), "還沒打贏天穹熔爐：先開它");
        assertEquals("sky_crucible", MapService.citadelTarget(maps, finals, "sky_crucible", fresh));
        final Set<String> breachedOnly = Set.of(Atlas.CITADEL_TOKEN + "sky_crucible");
        assertEquals("ember_citadel", MapService.citadelTarget(maps, finals, "ember_citadel", breachedOnly),
                "攻破了但還沒打贏：沒有別的能打，照它自己的（會說要先打贏）");
        final Set<String> won = Set.of(Atlas.CITADEL_TOKEN + "sky_crucible", "sky_crucible");
        assertEquals("ember_citadel", MapService.citadelTarget(maps, finals, "ember_citadel", won), "打贏天穹熔爐之後就是它自己的");
    }
}
