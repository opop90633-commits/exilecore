package tw.exilecore.worldgen;

import org.junit.jupiter.api.Test;
import tw.exilecore.world.CampaignSetup;
import tw.exilecore.zone.ZoneLayout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignWorldgenTest {

    /** 站得住的判定用：這些方塊可以穿過（腳、頭的位置）。 */
    private static final Set<String> PASSABLE = Set.of("air", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "short_dry_grass", "bush", "brown_mushroom", "red_mushroom", "moss_carpet", "leaf_litter", "snow", "brown_carpet", "gray_carpet");

    /** 擋不住路的其他方塊（{@link WebReach} 的「擋路」判斷用；PASSABLE 以外的）。 */
    private static final Set<String> NOT_SOLID = Set.of("water", "lava", "cobweb", "torch", "lantern", "soul_lantern", "vine");

    /** 會拉住腳的方塊（踩進去減速；甜莓叢還會扣血）：劇情世界不放。 */
    private static final Set<String> SLOWING = Set.of("sweet_berry_bush", "powder_snow", "soul_sand", "honey_block");

    @Test
    void sameChunkTwiceGivesSameBlocks() {
        for (final int[] chunk : new int[][] {{0, 0}, {-7, 0}, {22, 1}, {63, -2}, {100, 0}}) {
            final BufferSink first = generate(chunk[0], chunk[1]);
            final BufferSink second = generate(chunk[0], chunk[1]);
            assertEquals(first.size(), second.size());
            for (int i = 0; i < first.size(); i++) {
                assertEquals(first.block(i), second.block(i));
                assertEquals(first.y(i), second.y(i));
            }
        }
    }

    @Test
    void chunkSinkOnlyReceivesItsOwnColumns() {
        final BufferSink sink = generate(3, -1);
        for (int i = 0; i < sink.size(); i++) {
            assertEquals(3, sink.x(i) >> 4);
            assertEquals(-1, sink.z(i) >> 4);
        }
    }

    @Test
    void checkpointsArenasNpcsAndGatesAreStandable() {
        for (int zone = 0; zone < CampaignLayout.ZONE_COUNT; zone++) {
            for (int which = 0; which < 3; which++) {
                assertStandable("區域 " + zone + " 檢查點 " + which, CampaignLayout.checkpoint(zone, which));
            }
            assertStandable("區域 " + zone + " Boss 場地", CampaignLayout.arena(zone));
        }
        for (final CampaignStructures.Npc npc : CampaignStructures.Npc.values()) {
            assertStandable("NPC " + npc, CampaignStructures.npcSpot(npc));
        }
        for (int gate = 0; gate < CampaignStructures.GATE_COUNT; gate++) {
            assertStandable("傳送門 " + gate, CampaignStructures.gate(gate));
        }
        assertStandable("營地落點", CampaignLayout.campSpawn());
        assertStandable("地圖裝置前", CampaignStructures.deviceReturn());
    }

    @Test
    void roadClimbsAtMostOneBlockPerStep() {
        for (int x = -30; x < CampaignLayout.WORLD_END_X - 60; x++) {
            final int step = Math.abs(CampaignLayout.roadTop(x + 1.5) - CampaignLayout.roadTop(x + 0.5));
            assertTrue(step <= 1, "路在 x=" + x + " 一步高了 " + step + " 格");
        }
    }

    @Test
    void walkingAlongTheRoadNeverNeedsMoreThanAOneBlockStep() {
        // 照生成出來的方塊走（含 Boss 場地的地板、祭壇、拱門、木棧道），不是只看路面公式
        final java.util.Map<Long, BufferSink> chunks = new java.util.HashMap<>();
        int previous = Integer.MIN_VALUE;
        for (int x = -30; x < CampaignLayout.STAR_X - 16; x++) {
            final int z = (int) Math.round(CampaignLayout.roadZ(x + 0.5));
            final long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
            final int cx = x >> 4;
            final int cz = z >> 4;
            final BufferSink sink = chunks.computeIfAbsent(key, k -> generate(cx, cz));
            final int stand = standHeight(sink, x, z, CampaignLayout.roadTop(x + 0.5));
            assertTrue(stand != Integer.MIN_VALUE, "x=" + x + " z=" + z + " 找不到站的地方");
            if (previous != Integer.MIN_VALUE) {
                assertTrue(stand - previous <= 1, "x=" + x + " z=" + z + " 要往上跳 " + (stand - previous) + " 格");
            }
            previous = stand;
        }
    }

    /** 路面附近站得住的高度：腳下實心、腳與頭可以通過（找離路面公式最近的那一個）。 */
    private static int standHeight(final BufferSink sink, final int x, final int z, final int roadTop) {
        for (int dy = 0; dy <= 5; dy++) {
            for (final int y : new int[] {roadTop + 1 + dy, roadTop + 1 - dy}) {
                final int below = sink.blockAt(x, y - 1, z);
                final int feet = sink.blockAt(x, y, z);
                final int head = sink.blockAt(x, y + 1, z);
                if (below > 0 && !PASSABLE.contains(Palette.blockName(below)) && !"water".equals(Palette.blockName(below))
                        && (feet <= 0 || PASSABLE.contains(Palette.blockName(feet)))
                        && (head <= 0 || PASSABLE.contains(Palette.blockName(head)))) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void roadGuidesSitOnTheRoadSurfaceAndPointEast() {
        final List<RoadGuides.Cell> cells = RoadGuides.cells();
        assertEquals(RoadGuides.cells(), cells, "每次算都一樣（新舊世界對得上）");
        assertEquals(0, cells.size() % 9, "每個箭頭 9 格");
        final int arrows = cells.size() / 9;
        assertTrue(arrows >= 30, "路標太少：" + arrows);
        // 每個區域都有
        final java.util.Map<Integer, Integer> perZone = new java.util.HashMap<>();
        for (int i = 0; i < cells.size(); i += 9) {
            perZone.merge(CampaignLayout.zoneIndex(cells.get(i).x() + 0.5), 1, Integer::sum);
        }
        for (int zone = 0; zone < CampaignLayout.ZONE_COUNT; zone++) {
            assertTrue(perZone.getOrDefault(zone, 0) >= 3, "區域 " + zone + " 的路標太少：" + perZone);
        }
        final java.util.Map<Long, BufferSink> chunks = new java.util.HashMap<>();
        final Set<Long> seen = new HashSet<>();
        for (final RoadGuides.Cell cell : cells) {
            assertTrue(seen.add(((long) cell.x() << 32) | (cell.z() & 0xffffffffL)), "路標重疊 " + cell);
            assertTrue(CampaignTerrain.isRoad(cell.x(), cell.z()), "不在路上 " + cell);
            final int cx = cell.x() >> 4;
            final int cz = cell.z() >> 4;
            final BufferSink sink = chunks.computeIfAbsent(RoadGuides.chunkKey(cx, cz), key -> generate(cx, cz));
            final int surface = sink.blockAt(cell.x(), cell.y(), cell.z());
            final int above = sink.blockAt(cell.x(), cell.y() + 1, cell.z());
            assertTrue(surface > 0 && !PASSABLE.contains(Palette.blockName(surface)) && !"water".equals(Palette.blockName(surface)),
                    "路標要換掉的不是路面：" + cell + " 是 " + (surface < 0 ? "沒有" : Palette.state(surface)));
            assertTrue(above <= 0 || PASSABLE.contains(Palette.blockName(above)), "路標上面被擋住：" + cell + " 是 " + Palette.state(Math.max(0, above)));
        }
        // byChunk 只是照區塊分好
        int total = 0;
        for (final java.util.Map.Entry<Long, List<RoadGuides.Cell>> entry : RoadGuides.byChunk().entrySet()) {
            for (final RoadGuides.Cell cell : entry.getValue()) {
                assertEquals(RoadGuides.chunkKey(cell.x() >> 4, cell.z() >> 4), entry.getKey());
                total++;
            }
        }
        assertEquals(cells.size(), total);
    }

    @Test
    void storyNpcsStandOnSolidGround() throws Exception {
        // 跟任務模組擺劇情人物同一套算法：營地落點／區域檢查點＋相對位置，高度照錨點
        final tw.exilecore.quest.QuestConfig quests = new tw.exilecore.quest.QuestConfig();
        quests.load(java.nio.file.Path.of("src/main/resources/data"));
        final tw.exilecore.zone.ZoneConfig zones = new tw.exilecore.zone.ZoneConfig();
        zones.load(java.nio.file.Path.of("src/main/resources/data"));
        final List<String> combat = new java.util.ArrayList<>();
        for (final tw.exilecore.zone.ZoneConfig.ZoneDefinition zone : zones.combatZones()) {
            combat.add(zone.id());
        }
        final List<String> cps = List.of("entrance", "midway", "boss");
        int checked = 0;
        for (final tw.exilecore.quest.QuestConfig.Npc npc : quests.npcs().values()) {
            final tw.exilecore.quest.QuestConfig.Placement place = npc.placement();
            final CampaignLayout.Spot anchor = tw.exilecore.quest.QuestConfig.CAMP.equals(place.zone())
                    ? CampaignLayout.campSpawn()
                    : CampaignLayout.checkpoint(combat.indexOf(place.zone()), cps.indexOf(place.checkpoint()));
            final CampaignLayout.Spot spot = new CampaignLayout.Spot(anchor.x() + place.x(), anchor.y(), anchor.z() + place.z(), place.yaw());
            assertStandable("劇情人物 " + npc.id(), spot);
            // 不擋路：不在路面上（營地除外）
            if (!tw.exilecore.quest.QuestConfig.CAMP.equals(place.zone())) {
                assertFalse(CampaignTerrain.isRoad((int) Math.floor(spot.x()), (int) Math.floor(spot.z())), npc.id() + " 站在路上");
            }
            checked++;
        }
        assertEquals(8, checked);
    }

    @Test
    void cliffsAreWallsNotRamps() {
        // 走廊邊緣：崖頂至少比旁邊的走廊高 3 格（垂直的崖壁，爬不上去）
        int edges = 0;
        for (int x = 60; x < CampaignLayout.STAR_X; x += 7) {
            for (int z = -120; z < 120; z++) {
                final double here = CampaignLayout.cliffWeight(x + 0.5, z + 0.5);
                final double next = CampaignLayout.cliffWeight(x + 0.5, z + 1.5);
                if ((here < 0.5) == (next < 0.5) || CampaignLayout.coastDistance(x + 0.5, z + 0.5) < 20) {
                    continue;
                }
                final int low = here < 0.5 ? CampaignTerrain.top(x, z) : CampaignTerrain.top(x, z + 1);
                final int high = here < 0.5 ? CampaignTerrain.top(x, z + 1) : CampaignTerrain.top(x, z);
                assertTrue(high - low >= 3, "x=" + x + " z=" + z + " 的崖壁只有 " + (high - low) + " 格");
                edges++;
            }
        }
        assertTrue(edges > 100, "找不到崖壁");
    }

    @Test
    void setupPutsPointsInsideTheirZones() {
        final ZoneLayout layout = new ZoneLayout();
        final List<String> combat = List.of("coast", "forest", "ruins", "swamp", "mountain", "extra");
        assertEquals(5, CampaignSetup.apply(layout, "exilecore_campaign", "camp", combat));
        assertTrue(CampaignSetup.applied(layout, "exilecore_campaign", "camp"));
        assertFalse(CampaignSetup.applied(layout, "world", "camp"));
        final ZoneLayout.Entry camp = layout.get("camp");
        assertTrue(camp.box().contains("exilecore_campaign", camp.entry().x(), camp.entry().y(), camp.entry().z()));
        // 船與碼頭也在營地（安全區）裡
        assertTrue(camp.box().contains("exilecore_campaign", -120, 65, 0));
        for (int i = 0; i < 5; i++) {
            final ZoneLayout.Entry entry = layout.get(combat.get(i));
            assertEquals(3, entry.checkpoints().size());
            for (final ZoneLayout.Checkpoint checkpoint : entry.checkpoints()) {
                assertTrue(entry.box().contains("exilecore_campaign", checkpoint.point().x(), checkpoint.point().y(), checkpoint.point().z()),
                        combat.get(i) + " 的 " + checkpoint.id() + " 不在範圍裡");
            }
            assertTrue(entry.box().contains("exilecore_campaign", entry.arena().x(), entry.arena().y(), entry.arena().z()));
        }
        assertEquals(null, layout.get("extra"));
        // 相鄰區域的範圍不重疊、中間沒有縫
        for (int i = 0; i + 1 < 5; i++) {
            assertEquals(layout.get(combat.get(i)).box().maxX() + 1, layout.get(combat.get(i + 1)).box().minX());
        }
        assertEquals(CampaignLayout.CAMP_BOX_MAX_X + 1, layout.get("coast").box().minX());
    }

    @Test
    void paletteIdsAreUniqueAndNamed() {
        final Set<String> states = new HashSet<>();
        for (int id = 0; id < Palette.size(); id++) {
            assertTrue(states.add(Palette.state(id)), "重複的方塊狀態 " + Palette.state(id));
            assertFalse(Palette.blockName(id).isBlank());
        }
        assertEquals("oak_log", Palette.blockName(B.OAK_LOG));
        assertEquals(B.OAK_LOG, Palette.id("minecraft:oak_log[axis=y]"));
        assertEquals("minecraft:air", Palette.state(B.AIR));
    }

    @Test
    void biomesFollowThemes() {
        assertEquals("plains", Campaign.biome(0, 0));
        assertEquals("ocean", Campaign.biome(-75, 0));
        assertEquals("deep_ocean", Campaign.biome(-130, 0));
        assertEquals("dark_forest", Campaign.biome(500, 0));
        assertEquals("savanna", Campaign.biome(830, 0));
        assertEquals("mangrove_swamp", Campaign.biome(1150, 0));
        assertEquals("snowy_slopes", Campaign.biome(1500, 0));
    }

    /**
     * 走路的高度沒有會拉住人的方塊（2026-10，「跑步會拉腳」）：甜莓叢、粉雪、靈魂沙、蜂蜜塊一個都沒有；
     * 蜘蛛網只能是樹上垂下來的：這一格與周圍八格的地面往上 5 格以上（跳起來頭頂大約到地面往上 4 格多）。
     * 抽查整個劇情世界（每 4×3 個區塊抽一個）與五個 Boss 場地。森林裡每個區塊的蜘蛛網見 {@link #hangingWebsAreOutOfReach}。
     */
    @Test
    void nothingSticksToYourFeet() {
        final Set<Long> chunks = new java.util.LinkedHashSet<>();
        for (int cx = CampaignLayout.CAMP_BOX_MIN_X >> 4; cx <= CampaignLayout.WORLD_END_X >> 4; cx += 4) {
            for (int cz = CampaignLayout.ZONE_MIN_Z >> 4; cz <= CampaignLayout.ZONE_MAX_Z >> 4; cz += 3) {
                chunks.add(chunkKey(cx, cz));
            }
        }
        for (int zone = 0; zone < CampaignLayout.ZONE_COUNT; zone++) {
            final CampaignLayout.Spot arena = CampaignLayout.arena(zone);
            final int ax = (int) Math.floor(arena.x()) >> 4;
            final int az = (int) Math.floor(arena.z()) >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunks.add(chunkKey(ax + dx, az + dz));
                }
            }
        }
        int webs = 0;
        for (final long key : chunks) {
            final int cx = (int) (key >> 32);
            final int cz = (int) key;
            final BufferSink sink = generate(cx, cz);
            for (int i = 0; i < sink.size(); i++) {
                final String name = Palette.blockName(sink.block(i));
                if (!SLOWING.contains(name) && !"cobweb".equals(name)) {
                    continue;
                }
                final int x = sink.x(i);
                final int y = sink.y(i);
                final int z = sink.z(i);
                if (sink.blockAt(x, y, z) != sink.block(i)) {
                    continue; // 後來被蓋掉了
                }
                assertFalse(SLOWING.contains(name), name + " 在 " + x + "," + y + "," + z + "（踩進去會被拉住）");
                webs++;
                assertWebAboveGround(x, y, z);
            }
        }
        assertTrue(webs > 0, "樹上垂下來的蜘蛛網還在（林影織母的地盤）");
    }

    /**
     * 森林（幽語森林與兩邊的交界）每個區塊的蜘蛛網：都在這一格與周圍八格的地面往上 5 格以上；
     * 地形這邊看不到的大石頭、倒木、別棵樹的東西讓它碰得到的（區塊載入時照 {@link WebReach} 清掉，
     * world.WalkableCleanup），只能是少數。
     */
    @Test
    void hangingWebsAreOutOfReach() {
        int webs = 0;
        int nearObstacles = 0;
        for (int zone = 0; zone < CampaignLayout.ZONE_COUNT; zone++) {
            if (CampaignLayout.zoneTheme(zone) != CampaignLayout.Theme.FOREST) {
                continue;
            }
            for (int cx = (CampaignLayout.zoneStart(zone) - 32) >> 4; cx <= (CampaignLayout.zoneEnd(zone) + 32) >> 4; cx++) {
                for (int cz = CampaignLayout.ZONE_MIN_Z >> 4; cz <= CampaignLayout.ZONE_MAX_Z >> 4; cz++) {
                    final BufferSink sink = generate(cx, cz);
                    WebReach.Blocks blocks = null;
                    for (int i = 0; i < sink.size(); i++) {
                        if (!"cobweb".equals(Palette.blockName(sink.block(i)))) {
                            continue;
                        }
                        final int x = sink.x(i);
                        final int y = sink.y(i);
                        final int z = sink.z(i);
                        if (sink.blockAt(x, y, z) != sink.block(i)) {
                            continue; // 後來被蓋掉了
                        }
                        webs++;
                        assertWebAboveGround(x, y, z);
                        if (blocks == null) {
                            blocks = blocks(sink);
                        }
                        if (WebReach.reachable(blocks, x, y, z)) {
                            nearObstacles++; // 載入時會被清掉
                        }
                    }
                }
            }
        }
        System.out.println("forest webs " + webs + ", reachable via boulders etc. " + nearObstacles);
        assertTrue(webs >= 50, "森林裡樹上垂下來的蜘蛛網還在（林影織母的地盤）：" + webs);
        assertTrue(nearObstacles * 20 <= webs, "載入時才清掉的蜘蛛網太多：" + nearObstacles + " / " + webs);
    }

    /** 地面是 top（實心的那格），腳在 top+1；跳到最高頭頂大約在 top+4 多。站在隔壁格子的邊上身體會伸進這一格：周圍八格也算。 */
    private static void assertWebAboveGround(final int x, final int y, final int z) {
        final int ground = CampaignTerrain.highestAround(x, z);
        assertTrue(y >= ground + CampaignTerrain.WEB_CLEARANCE,
                "蜘蛛網跳起來碰得到：" + x + "," + y + "," + z + "（周圍的地面最高在 " + ground + "）");
    }

    /** 給 {@link WebReach} 查一個區塊產生出來的方塊；區塊外的只看地形高度（跟 WalkableCleanup 一樣）。 */
    private static WebReach.Blocks blocks(final BufferSink sink) {
        final Map<Long, Integer> cells = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (int i = 0; i < sink.size(); i++) {
            cells.put(cell(sink.x(i), sink.y(i), sink.z(i)), sink.block(i));
            minX = Math.min(minX, sink.x(i));
            minZ = Math.min(minZ, sink.z(i));
        }
        final int baseX = minX & ~15;
        final int baseZ = minZ & ~15;
        return new WebReach.Blocks() {
            @Override
            public boolean solid(final int x, final int y, final int z) {
                if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) {
                    return y <= CampaignTerrain.top(x, z);
                }
                final int block = cells.getOrDefault(cell(x, y, z), Palette.AIR);
                final String name = Palette.blockName(block);
                return block > 0 && !PASSABLE.contains(name) && !NOT_SOLID.contains(name);
            }

            @Override
            public boolean leaves(final int x, final int y, final int z) {
                if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) {
                    return false;
                }
                return Palette.blockName(cells.getOrDefault(cell(x, y, z), Palette.AIR)).endsWith("_leaves");
            }
        };
    }

    private static long cell(final int x, final int y, final int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    private static long chunkKey(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static BufferSink generate(final int chunkX, final int chunkZ) {
        final BufferSink sink = new BufferSink(chunkX << 4, chunkZ << 4, (chunkX << 4) + 15, (chunkZ << 4) + 15);
        Campaign.generateChunk(chunkX, chunkZ, sink);
        return sink;
    }

    private static void assertStandable(final String label, final CampaignLayout.Spot spot) {
        final int x = (int) Math.floor(spot.x());
        final int y = (int) Math.round(spot.y());
        final int z = (int) Math.floor(spot.z());
        final BufferSink sink = generate(x >> 4, z >> 4);
        final int below = sink.blockAt(x, y - 1, z);
        final int feet = sink.blockAt(x, y, z);
        final int head = sink.blockAt(x, y + 1, z);
        assertTrue(below > 0 && !PASSABLE.contains(Palette.blockName(below)) && !"water".equals(Palette.blockName(below)),
                label + "：腳下不是實心方塊（" + (below < 0 ? "沒有" : Palette.state(below)) + "）");
        assertTrue(feet <= 0 || PASSABLE.contains(Palette.blockName(feet)), label + "：腳的位置被 " + Palette.state(Math.max(0, feet)) + " 擋住");
        assertTrue(head <= 0 || PASSABLE.contains(Palette.blockName(head)), label + "：頭的位置被 " + Palette.state(Math.max(0, head)) + " 擋住");
    }
}
