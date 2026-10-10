package tw.exilecore.map;

import org.junit.jupiter.api.Test;
import tw.exilecore.worldgen.BufferSink;
import tw.exilecore.worldgen.GuideArrow;
import tw.exilecore.worldgen.Palette;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地圖版面的樣式（廳堂、曠野、洞窟、群島、城區）：每一種都走得通（入口到 Boss、每一群怪、每個房間、回程門）、
 * 封得起來（地板、液體外面一定是牆或液體）、路標在主路上、同一個種子同一張圖，各自的特色真的有出現。
 */
class MapLayoutStyleTest {

    static final int SEEDS = 40;

    @Test
    void everyStyleIsConnectedSealedAndDeterministic() {
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                final int rooms = 6 + (int) (seed % 6);
                final MapLayout layout = MapLayout.generate(seed * 7919, rooms, style);
                final MapLayout again = MapLayout.generate(seed * 7919, rooms, style);
                final String where = style + " seed " + seed;
                assertEquals(style, layout.style());
                assertEquals(layout.rooms(), again.rooms(), where);
                assertEquals(layout.packSpots(), again.packSpots(), where);
                assertEquals(layout.guides(), again.guides(), where);
                assertTrue(layout.roomCount() >= 5, where + "：只有 " + layout.roomCount() + " 間");
                assertFalse(layout.packSpots().isEmpty(), where);
                final boolean[][] seen = reachable(layout);
                assertTrue(seen[layout.boss().cx()][layout.boss().cz()], where + "：走不到 Boss 房");
                assertTrue(seen[layout.exitPortal().x()][layout.exitPortal().z()], where + "：走不到回程門");
                for (final MapLayout.Spot spot : layout.packSpots()) {
                    assertTrue(seen[spot.x()][spot.z()], where + "：走不到怪物群 " + spot);
                }
                for (final MapLayout.Room room : layout.rooms()) {
                    assertTrue(seen[room.cx()][room.cz()], where + "：走不到 " + room.kind() + " 房間的中心");
                }
                for (int x = 0; x < MapLayout.SIZE; x++) {
                    for (int z = 0; z < MapLayout.SIZE; z++) {
                        if (layout.isFloor(x, z)) {
                            assertTrue(seen[x][z], where + "：有走不到的地板 " + x + "," + z);
                            assertFalse(layout.isLiquid(x, z) || layout.isWall(x, z), where);
                        }
                        if (layout.isFloor(x, z) || layout.isLiquid(x, z)) {
                            // 地板、液體旁邊（東南西北）一定是地板、液體或牆：不會走出去、水不會流出去
                            assertTrue(x > 0 && z > 0 && x < MapLayout.SIZE - 1 && z < MapLayout.SIZE - 1, where + "：貼到格子邊");
                            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                                final int nx = x + d[0];
                                final int nz = z + d[1];
                                assertTrue(layout.isFloor(nx, nz) || layout.isLiquid(nx, nz) || layout.isWall(nx, nz),
                                        where + "：" + x + "," + z + " 旁邊是空的");
                            }
                            assertTrue(x >= layout.minX() && x <= layout.maxX() && z >= layout.minZ() && z <= layout.maxZ(),
                                    where + "：蓋的範圍沒包到 " + x + "," + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void guidesFollowTheMainPathInEveryStyle() {
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            int links = 0;
            for (long seed = 1; seed <= 60; seed++) {
                final MapLayout layout = MapLayout.generate(seed * 104_729, 6 + (int) (seed % 6), style);
                final String where = style + " seed " + seed;
                final Set<Long> cells = new HashSet<>();
                for (final MapLayout.Guide guide : layout.guides()) {
                    for (final int[] cell : GuideArrow.cells(guide.x(), guide.z(), guide.dx(), guide.dz())) {
                        assertTrue(layout.isFloor(cell[0], cell[1]), where + "：箭頭蓋到牆或水 " + guide);
                        assertTrue(cells.add(((long) cell[0] << 32) | (cell[1] & 0xffffffffL)), where + "：箭頭重疊 " + guide);
                    }
                    boolean onMainPath = false;
                    for (final MapLayout.Link link : layout.links()) {
                        for (final int[] leg : MapLayout.legs(link)) {
                            onMainPath |= onLeg(guide, leg);
                        }
                    }
                    assertTrue(onMainPath, where + "：箭頭不在主路上 " + guide);
                }
                for (final MapLayout.Link link : layout.links()) {
                    links++;
                    boolean guided = false;
                    for (final MapLayout.Guide guide : layout.guides()) {
                        for (final int[] leg : MapLayout.legs(link)) {
                            guided |= onLeg(guide, leg);
                        }
                    }
                    assertTrue(guided, where + "：" + link.from().kind() + " → " + link.to().kind() + " 這段沒有箭頭");
                    // 每一段都是直的
                    for (final int[] leg : MapLayout.legs(link)) {
                        assertTrue(leg[0] == leg[2] || leg[1] == leg[3], where + "：斜的一段");
                    }
                }
                assertEquals(MapLayout.Kind.BOSS, layout.links().getLast().to().kind(), where);
            }
            assertTrue(links > 300, style + "：測到的走道太少 " + links);
        }
    }

    @Test
    void stylesLookDifferent() {
        final Map<MapLayout.Style, long[]> totals = new HashMap<>();
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            long floor = 0;
            long liquid = 0;
            long pillars = 0;
            long withLiquid = 0;
            long loops = 0;
            long winding = 0;
            for (long seed = 1; seed <= SEEDS; seed++) {
                final MapLayout layout = MapLayout.generate(seed * 31, 9, style);
                long liquidHere = 0;
                for (int x = 3; x < MapLayout.SIZE - 3; x++) {
                    for (int z = 3; z < MapLayout.SIZE - 3; z++) {
                        if (layout.isFloor(x, z)) {
                            floor++;
                        }
                        if (layout.isLiquid(x, z)) {
                            liquidHere++;
                        }
                        // 空地中間的一團牆（岩石、樹叢）：四個方向三格外都是地板
                        if (layout.isWall(x, z) && layout.isFloor(x + 3, z) && layout.isFloor(x - 3, z) && layout.isFloor(x, z + 3)
                                && layout.isFloor(x, z - 3)) {
                            pillars++;
                        }
                    }
                }
                liquid += liquidHere;
                if (liquidHere > 0) {
                    withLiquid++;
                }
                loops += layout.pathCount() - layout.links().size();
                for (final MapLayout.Link link : layout.links()) {
                    if (link.points().size() > 3) {
                        winding++;
                    }
                }
            }
            totals.put(style, new long[] {floor, liquid, pillars, withLiquid, loops, winding});
        }
        final long[] halls = totals.get(MapLayout.Style.HALLS);
        final long[] open = totals.get(MapLayout.Style.OPEN);
        final long[] cavern = totals.get(MapLayout.Style.CAVERN);
        final long[] islands = totals.get(MapLayout.Style.ISLANDS);
        final long[] city = totals.get(MapLayout.Style.CITY);
        assertEquals(0, halls[1], "廳堂沒有水");
        assertTrue(open[0] > halls[0] * 1.3, "曠野的空地比廳堂大很多：" + open[0] + " vs " + halls[0]);
        assertTrue(open[2] > SEEDS * 3, "曠野有岩石、樹叢擋路：" + open[2]);
        assertTrue(cavern[3] >= SEEDS * 0.6, "洞窟大多有地下水池：" + cavern[3] + "/" + SEEDS);
        assertTrue(cavern[5] > SEEDS * 4, "洞窟的通道是彎的：" + cavern[5]);
        assertEquals(0, halls[5], "廳堂的走道是 L 形");
        assertTrue(islands[1] > islands[0], "群島的水比地板多：水 " + islands[1] + "、地板 " + islands[0]);
        assertTrue(islands[3] == SEEDS, "每一張群島都有水");
        assertTrue(city[4] > halls[4] + SEEDS / 2, "城區常常有繞路：" + city[4] + " vs 廳堂的岔路 " + halls[4]);
    }

    @Test
    void unknownLayoutInMapsYmlIsRejected() throws java.io.IOException {
        final java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("maps");
        java.nio.file.Files.writeString(folder.resolve("maps.yml"), """
                maps:
                  test_map:
                    base: map_wreck_bay
                    theme: desert
                    layout: maze
                    atlas: [0, 0]
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 測試, type: ZOMBIE }
                """);
        final IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MapConfig().load(folder));
        assertTrue(error.getMessage().contains("不認得的版面樣式 maze"), error.getMessage());
        assertTrue(error.getMessage().contains("islands"), "列出能用的樣式：" + error.getMessage());
        // 沒寫 layout 是廳堂；寫了就照寫的
        java.nio.file.Files.writeString(folder.resolve("maps.yml"), """
                maps:
                  test_map:
                    base: map_wreck_bay
                    theme: volcano
                    atlas: [0, 0]
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 測試, type: ZOMBIE }
                  other_map:
                    base: map_moss_grove
                    theme: fungal
                    layout: CITY
                    atlas: [2, 0]
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 測試, type: ZOMBIE }
                """);
        final MapConfig config = new MapConfig();
        config.load(folder);
        assertEquals(MapLayout.Style.HALLS, config.find("test_map").layout());
        assertEquals(MapLayout.Style.CITY, config.find("other_map").layout());
    }

    @Test
    void stylesParseFromConfigText() {
        assertEquals(MapLayout.Style.OPEN, MapLayout.Style.parse("open"));
        assertEquals(MapLayout.Style.ISLANDS, MapLayout.Style.parse(" Islands "));
        assertNull(MapLayout.Style.parse("maze"));
        assertNull(MapLayout.Style.parse(null));
        assertEquals("cavern", MapLayout.Style.CAVERN.id());
        assertEquals(MapLayout.Style.HALLS, MapLayout.generate(5, 8).style(), "沒寫樣式是廳堂");
        assertEquals(MapLayout.Style.HALLS, MapLayout.generate(5, 8, null).style());
    }

    @Test
    void builderSealsLiquidsAndWallsInEveryStyleAndTheme() {
        final int barrier = Palette.id("barrier");
        final int ceiling = MapLayout.FLOOR_Y + MapBuilder.CEILING;
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            final MapLayout layout = MapLayout.generate(77, 9, style);
            for (final String theme : MapConfig.THEMES) {
                final BufferSink sink = new BufferSink();
                MapBuilder.build(layout, theme, 1024, 2048, sink);
                final Map<Long, Integer> blocks = new HashMap<>();
                for (int i = 0; i < sink.size(); i++) {
                    assertTrue(sink.y(i) >= MapLayout.FLOOR_Y - 4 && sink.y(i) <= ceiling, style + "/" + theme + "：蓋到範圍外 y=" + sink.y(i));
                    assertTrue(sink.x(i) >= 1024 && sink.z(i) >= 2048 && sink.x(i) < 1024 + MapLayout.SIZE && sink.z(i) < 2048 + MapLayout.SIZE);
                    blocks.put(cell(sink.x(i), sink.y(i), sink.z(i)), sink.block(i));
                }
                int liquids = 0;
                for (int x = layout.minX(); x <= layout.maxX(); x++) {
                    for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                        final String where = style + "/" + theme + " " + x + "," + z;
                        if (layout.isFloor(x, z)) {
                            assertEquals(barrier, blocks.getOrDefault(cell(1024 + x, ceiling, 2048 + z), Palette.AIR), where + "：沒有天花板");
                            for (int y = MapLayout.FLOOR_Y + 1; y < ceiling; y++) {
                                assertTrue(blocks.getOrDefault(cell(1024 + x, y, 2048 + z), Palette.AIR) != barrier, where + "：地板上面有屏障 y=" + y);
                            }
                            assertTrue(blocks.getOrDefault(cell(1024 + x, MapLayout.FLOOR_Y, 2048 + z), Palette.AIR) != Palette.AIR, where + "：沒有地板");
                        } else if (layout.isLiquid(x, z)) {
                            liquids++;
                            final String name = Palette.blockName(blocks.getOrDefault(cell(1024 + x, MapLayout.FLOOR_Y, 2048 + z), Palette.AIR));
                            assertEquals("volcano".equals(theme) ? "lava" : "water", name, where + "：液體");
                            for (int y = MapLayout.FLOOR_Y + 1; y <= ceiling; y++) {
                                assertEquals(barrier, blocks.getOrDefault(cell(1024 + x, y, 2048 + z), Palette.AIR), where + "：液體上面要是屏障 y=" + y);
                            }
                            assertTrue(blocks.getOrDefault(cell(1024 + x, MapLayout.FLOOR_Y - 4, 2048 + z), Palette.AIR) != Palette.AIR, where + "：液體底下是空的");
                        } else if (layout.isWall(x, z)) {
                            for (int y = MapLayout.FLOOR_Y; y <= ceiling; y++) {
                                assertTrue(blocks.getOrDefault(cell(1024 + x, y, 2048 + z), Palette.AIR) != Palette.AIR, where + "：牆有缺口 y=" + y);
                            }
                        }
                    }
                }
                if (style == MapLayout.Style.ISLANDS) {
                    assertTrue(liquids > 1000, style + "/" + theme + "：水太少 " + liquids);
                }
                // 路標都是發光方塊，上面沒有東西（曠野、洞窟的零星植物不會蓋到路標）
                for (final MapLayout.Guide guide : layout.guides()) {
                    for (final int[] c : GuideArrow.cells(guide.x(), guide.z(), guide.dx(), guide.dz())) {
                        assertEquals("ochre_froglight", Palette.blockName(blocks.getOrDefault(cell(1024 + c[0], MapLayout.FLOOR_Y, 2048 + c[1]), Palette.AIR)),
                                style + "/" + theme + "：路標不是發光方塊 " + guide);
                        if (layout.roomAt(c[0], c[1]) == null) {
                            assertTrue(blocks.getOrDefault(cell(1024 + c[0], MapLayout.FLOOR_Y + 1, 2048 + c[1]), Palette.AIR) <= 0,
                                    style + "/" + theme + "：路標上面被擋住 " + guide);
                        }
                    }
                }
                // 回程門
                final MapLayout.Spot exit = layout.exitPortal();
                assertEquals("crying_obsidian", Palette.blockName(blocks.getOrDefault(cell(1024 + exit.x(), MapLayout.FLOOR_Y, 2048 + exit.z()), Palette.AIR)));
            }
        }
    }

    @Test
    void spawnAndEventSpotsAreNotBuriedUnderPillars() {
        final Set<String> passable = Set.of("air", "fern", "bush", "moss_carpet", "short_dry_grass", "dead_bush", "snow", "glow_lichen",
                "brown_mushroom", "red_mushroom", "amethyst_cluster", "gray_carpet");
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            for (long seed = 1; seed <= 25; seed++) {
                final MapLayout layout = MapLayout.generate(seed * 613, 9, style);
                for (final String theme : MapConfig.THEMES) {
                    final BufferSink sink = new BufferSink();
                    MapBuilder.build(layout, theme, 0, 0, sink);
                    final Map<Long, Integer> blocks = new HashMap<>();
                    for (int i = 0; i < sink.size(); i++) {
                        blocks.put(cell(sink.x(i), sink.y(i), sink.z(i)), sink.block(i));
                    }
                    for (final MapLayout.Spot spot : layout.packSpots()) {
                        for (int dy = 1; dy <= 2; dy++) {
                            final String name = Palette.blockName(blocks.getOrDefault(cell(spot.x(), MapLayout.FLOOR_Y + dy, spot.z()), Palette.AIR));
                            assertTrue(passable.contains(name), style + "/" + theme + " seed " + seed + "：怪物群的出生點上面是 " + name);
                        }
                    }
                    for (final MapLayout.Room room : layout.rooms()) {
                        if (room.kind() != MapLayout.Kind.NORMAL && room.kind() != MapLayout.Kind.SIDE) {
                            continue;
                        }
                        for (final int[] offset : MapLayout.EVENT_OFFSETS) {
                            final String name = Palette.blockName(blocks.getOrDefault(
                                    cell(room.cx() + offset[0], MapLayout.FLOOR_Y + 1, room.cz() + offset[1]), Palette.AIR));
                            assertTrue(passable.contains(name), style + "/" + theme + " seed " + seed + "：事件的位置上面是 " + name);
                        }
                    }
                }
            }
        }
    }

    /**
     * 地圖裡沒有會拉住腳的方塊（2026-10，「跑步會拉腳」）：蜘蛛網（踩進去速度剩四分之一）、甜莓叢、粉雪、靈魂沙、蜂蜜塊。
     * 每種版面、每個主題都看。
     */
    @Test
    void nothingSticksToYourFeet() {
        final Set<String> slowing = Set.of("cobweb", "sweet_berry_bush", "powder_snow", "soul_sand", "honey_block");
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            for (long seed = 1; seed <= 8; seed++) {
                final MapLayout layout = MapLayout.generate(seed * 911, 9, style);
                for (final String theme : MapConfig.THEMES) {
                    final BufferSink sink = new BufferSink();
                    MapBuilder.build(layout, theme, 0, 0, sink);
                    for (int i = 0; i < sink.size(); i++) {
                        final String name = Palette.blockName(sink.block(i));
                        assertFalse(slowing.contains(name), style + "/" + theme + " seed " + seed + "：" + name + " 在 "
                                + sink.x(i) + "," + sink.y(i) + "," + sink.z(i));
                    }
                }
            }
        }
    }

    @Test
    void newThemesUseTheirOwnBlocks() {
        final MapLayout layout = MapLayout.generate(9, 9, MapLayout.Style.OPEN);
        for (final String theme : new String[] {"desert", "volcano", "fungal"}) {
            final MapBuilder.Theme palette = MapBuilder.theme(theme);
            assertNotNull(palette);
            assertFalse(java.util.Arrays.equals(palette.floor(), MapBuilder.theme("ruins").floor()), theme + "：跟廢墟一樣");
            final String floor = Palette.blockName(palette.floor()[0]);
            assertTrue(Set.of("red_sand", "blackstone", "mycelium").contains(floor), theme + "：" + floor);
            // 火山的地板沒有岩漿塊（站上去會燙）
            if ("volcano".equals(theme)) {
                for (final int block : palette.floor()) {
                    assertFalse(Palette.blockName(block).contains("magma"), "火山地板有岩漿塊");
                }
                assertFalse(Palette.blockName(palette.accent()).contains("magma"), "Boss 房的地板紋路也不能是岩漿塊");
                assertEquals("lava", Palette.blockName(palette.liquid()));
            } else {
                assertEquals("water", Palette.blockName(palette.liquid()));
            }
            final BufferSink sink = new BufferSink();
            MapBuilder.build(layout, theme, 0, 0, sink);
            assertTrue(sink.size() > 10_000, theme);
        }
    }

    private static boolean[][] reachable(final MapLayout layout) {
        final boolean[][] seen = new boolean[MapLayout.SIZE][MapLayout.SIZE];
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        final MapLayout.Spot entrance = layout.entrance();
        assertTrue(layout.isFloor(entrance.x(), entrance.z()));
        queue.add(new int[] {entrance.x(), entrance.z()});
        seen[entrance.x()][entrance.z()] = true;
        while (!queue.isEmpty()) {
            final int[] p = queue.poll();
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int x = p[0] + d[0];
                final int z = p[1] + d[1];
                if (layout.isFloor(x, z) && !seen[x][z]) {
                    seen[x][z] = true;
                    queue.add(new int[] {x, z});
                }
            }
        }
        return seen;
    }

    /** 箭頭的中心在這一段上，而且指著同一個方向。 */
    private static boolean onLeg(final MapLayout.Guide guide, final int[] leg) {
        final int dx = Integer.signum(leg[2] - leg[0]);
        final int dz = Integer.signum(leg[3] - leg[1]);
        if ((dx == 0 && dz == 0) || guide.dx() != dx || guide.dz() != dz) {
            return false;
        }
        return guide.x() >= Math.min(leg[0], leg[2]) && guide.x() <= Math.max(leg[0], leg[2])
                && guide.z() >= Math.min(leg[1], leg[3]) && guide.z() <= Math.max(leg[1], leg[3]);
    }

    private static long cell(final int x, final int y, final int z) {
        return ((long) x * 4096L + y) * 65536L + z;
    }
}
