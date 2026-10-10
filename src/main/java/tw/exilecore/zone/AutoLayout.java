package tw.exilecore.zone;

import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.npc.NpcType;
import tw.exilecore.world.VoidSpawn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * /ec zone autolayout：以管理員的位置為營地中心，一鍵產生整套測試版面。
 * <pre>
 * 營地：64×64，四個 NPC（鑑定師、倉庫、拆解師、傳送師）
 * 戰鬥區域：從營地東邊開始往東排，每個 320 格長、192 格寬（z 方向 ±96），全高度
 *   入口（x0+8）→ 中途（x0+150）→ Boss 前（x0+255）→ Boss 場地（x0+290）
 * </pre>
 * 地形照世界原本的樣子，檢查點放在地表；之後可以用 /ec zone 的其他指令或直接改 zone-layout.yml 微調。
 * <p>
 * 虛空地圖（這些點底下整欄都是空的）：先在管理員腳下的高度鋪測試用的地面（{@link Ground}），點再放在地面上。
 */
final class AutoLayout {

    static final int CAMP_HALF = 32;
    static final int ZONE_LENGTH = 320;
    static final int ZONE_HALF_WIDTH = 96;
    static final int ENTRY_OFFSET = 8;
    static final int MIDWAY_OFFSET = 150;
    static final int BOSS_FRONT_OFFSET = 255;
    static final int ARENA_OFFSET = 290;

    /** 虛空地圖鋪的地面：營地中心 9×9、通道 7 格寬、Boss 場地 25×25。 */
    static final int CAMP_FLOOR_RADIUS = 4;
    static final int PATH_HALF_WIDTH = 3;
    static final int ARENA_FLOOR_RADIUS = 12;

    private AutoLayout() {
    }

    static void run(final ExileCore plugin, final ZoneService zones, final Player admin) {
        final Location origin = admin.getLocation().clone();
        final World world = origin.getWorld();
        final int bx = origin.getBlockX();
        final int bz = origin.getBlockZ();
        final Optional<Zone> camp = zones.zones().stream().filter(Zone::town).findFirst();
        final List<Zone> combat = zones.combatZones();
        final List<int[]> points = pointColumns(bx, bz, combat.size());

        // 需要地表高度的點所在的區塊先非同步載入（產生），完成後回主執行緒擺放
        final Set<Long> chunks = new LinkedHashSet<>();
        for (final int[] column : points) {
            chunks.add(chunkKey(column[0] >> 4, column[1] >> 4));
        }
        admin.sendMessage(plugin.text().prefixed("<gray>載入 " + chunks.size() + " 個區塊中（第一次會產生地形，要等一下）……"));
        load(plugin, admin, world, chunks, () -> {
            if (!anyEmpty(world, points)) {
                place(plugin, zones, admin, origin, camp.orElse(null), combat, 0);
                return;
            }
            // 虛空地圖：先鋪地面，點才有地方放
            final Ground ground = new Ground(world, origin, combat);
            final Set<Long> groundChunks = ground.chunks();
            admin.sendMessage(plugin.text().prefixed("<gray>這裡是虛空，先鋪測試用的地面：再載入 " + groundChunks.size() + " 個區塊……"));
            load(plugin, admin, world, groundChunks, () -> place(plugin, zones, admin, origin, camp.orElse(null), combat, ground.build()));
        });
    }

    /** 非同步載入（產生）區塊，全部好了回主執行緒執行 then。 */
    private static void load(final ExileCore plugin, final Player admin, final World world, final Set<Long> chunks, final Runnable then) {
        final List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (final long key : chunks) {
            futures.add(world.getChunkAtAsync((int) (key >> 32), (int) key));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        admin.sendMessage(plugin.text().prefixed("<red>載入區塊失敗：" + error.getMessage()));
                        return;
                    }
                    then.run();
                }));
    }

    private static void place(final ExileCore plugin, final ZoneService zones, final Player admin, final Location origin,
                              final Zone camp, final List<Zone> combat, final int groundBlocks) {
        final World world = origin.getWorld();
        final String worldName = world.getName();
        final int bx = origin.getBlockX();
        final int bz = origin.getBlockZ();
        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight() - 1;
        final ZoneLayout layout = zones.layout();

        if (camp != null) {
            final ZoneLayout.Entry entry = layout.getOrCreate(camp.id());
            entry.setBox(ZoneLayout.Box.of(worldName, bx - CAMP_HALF, minY, bz - CAMP_HALF, bx + CAMP_HALF - 1, maxY, bz + CAMP_HALF - 1));
            entry.setEntry(ZoneService.toPoint(origin));
        }
        for (int i = 0; i < combat.size(); i++) {
            final Zone zone = combat.get(i);
            final int x0 = zoneStart(bx, i);
            final ZoneLayout.Entry entry = layout.getOrCreate(zone.id());
            entry.setBox(ZoneLayout.Box.of(worldName, x0, minY, bz - ZONE_HALF_WIDTH, x0 + ZONE_LENGTH - 1, maxY, bz + ZONE_HALF_WIDTH - 1));
            entry.setEntry(surface(world, x0 + ENTRY_OFFSET, bz, -90f));
            entry.clearCheckpoints();
            entry.putCheckpoint(new ZoneLayout.Checkpoint("entrance", "入口", surface(world, x0 + ENTRY_OFFSET, bz, -90f)));
            entry.putCheckpoint(new ZoneLayout.Checkpoint("midway", "中途", surface(world, x0 + MIDWAY_OFFSET, bz, -90f)));
            entry.putCheckpoint(new ZoneLayout.Checkpoint("boss", "Boss 前", surface(world, x0 + BOSS_FRONT_OFFSET, bz, -90f)));
            entry.setArena(zone.boss() == null ? null : surface(world, x0 + ARENA_OFFSET, bz, 90f));
        }
        zones.rebuild();
        try {
            zones.saveLayout();
        } catch (final IOException e) {
            admin.sendMessage(plugin.text().prefixed("<red>存檔失敗：" + e.getMessage()));
            return;
        }

        // 營地的 NPC：同種類 48 格內已經有就不放
        final NpcType[] types = {NpcType.IDENTIFIER, NpcType.STASH, NpcType.SALVAGE, NpcType.WAYPOINT};
        final List<int[]> spots = npcSpots(bx, bz);
        int placedNpcs = 0;
        for (int i = 0; i < types.length; i++) {
            if (hasNpcNear(plugin, types[i], origin, 48)) {
                continue;
            }
            final int[] spot = spots.get(i);
            final ZoneLayout.Point point = surface(world, spot[0], spot[1], 0f);
            final Location location = new Location(world, point.x(), point.y(), point.z());
            // 面向營地中心
            location.setDirection(origin.toVector().subtract(location.toVector()).setY(0));
            location.setPitch(0f);
            plugin.npcs().spawn(location, types[i]);
            placedNpcs++;
        }

        admin.sendMessage(plugin.text().prefixed("<green>版面完成：營地在你腳下，" + combat.size()
                + " 個戰鬥區域往東排（每個 " + ZONE_LENGTH + " 格），放了 " + placedNpcs + " 個 NPC"));
        if (groundBlocks > 0) {
            admin.sendMessage(plugin.text().parse("<gray>  虛空地圖：在你腳下的高度鋪了 " + groundBlocks
                    + " 格測試地面（營地中心 9×9、往東穿過所有區域的 7 格寬通道、Boss 場地 25×25），只鋪在整欄都是空的地方"));
        }
        admin.sendMessage(plugin.text().parse("<gray>  往東走就會進入" + (combat.isEmpty() ? "" : combat.get(0).name())
                + "；/ec zone list 看版面，/ec zone tp <區域> 直接過去"));
    }

    private static int zoneStart(final int bx, final int index) {
        return bx + CAMP_HALF + index * ZONE_LENGTH;
    }

    /** 需要地表高度的位置：每個區域的入口、中途、Boss 前、Boss 場地，和營地的四個 NPC。 */
    private static List<int[]> pointColumns(final int bx, final int bz, final int zoneCount) {
        final List<int[]> columns = new ArrayList<>();
        for (int i = 0; i < zoneCount; i++) {
            final int x0 = zoneStart(bx, i);
            for (final int offset : new int[] {ENTRY_OFFSET, MIDWAY_OFFSET, BOSS_FRONT_OFFSET, ARENA_OFFSET}) {
                columns.add(new int[] {x0 + offset, bz});
            }
        }
        columns.addAll(npcSpots(bx, bz));
        return columns;
    }

    private static boolean anyEmpty(final World world, final List<int[]> columns) {
        for (final int[] column : columns) {
            if (VoidSpawn.emptyColumn(world, column[0], column[1])) {
                return true;
            }
        }
        return false;
    }

    /** 四個 NPC 的位置（營地中心的四個角落，離 4 格）。 */
    private static List<int[]> npcSpots(final int bx, final int bz) {
        return List.of(new int[] {bx + 4, bz - 4}, new int[] {bx + 4, bz + 4}, new int[] {bx - 4, bz + 4}, new int[] {bx - 4, bz - 4});
    }

    private static boolean hasNpcNear(final ExileCore plugin, final NpcType type, final Location center, final double radius) {
        for (final Entity npc : plugin.npcs().loaded()) {
            if (npc.getWorld().equals(center.getWorld()) && npc.getLocation().distanceSquared(center) <= radius * radius
                    && plugin.npcs().type(npc).orElse(null) == type) {
                return true;
            }
        }
        return false;
    }

    /** 地表上的點（最高的會擋路方塊上面一格，不算樹葉）。 */
    private static ZoneLayout.Point surface(final World world, final int x, final int z, final float yaw) {
        final int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new ZoneLayout.Point(world.getName(), x + 0.5, y, z + 0.5, yaw);
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /** 虛空地圖鋪地面用的方塊：營地是草地，各區域不同（不能用沙子、礫石這種會掉下去的）。 */
    static Material floorMaterial(final Zone zone) {
        if (zone == null || zone.town()) {
            return Material.GRASS_BLOCK;
        }
        return switch (zone.id()) {
            case "coast" -> Material.SMOOTH_SANDSTONE;
            case "forest" -> Material.MOSS_BLOCK;
            case "ruins" -> Material.STONE_BRICKS;
            case "swamp" -> Material.MUD;
            case "mountain" -> Material.SNOW_BLOCK;
            default -> Material.STONE;
        };
    }

    /**
     * 虛空地圖的測試地面，高度是管理員腳下那一格：營地中心 9×9（NPC 站的地方）、
     * 從營地往東穿過所有區域的 7 格寬通道（怪物會刷在通道上）、每個 Boss 場地 25×25。
     * 只鋪在整欄都是空的地方，不會蓋掉任何已經有的東西；再跑一次 autolayout 也不會重複鋪。
     */
    private static final class Ground {

        private final World world;
        private final int bx;
        private final int bz;
        private final int y;
        private final List<Zone> combat;

        Ground(final World world, final Location origin, final List<Zone> combat) {
            this.world = world;
            this.bx = origin.getBlockX();
            this.bz = origin.getBlockZ();
            this.y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 3, origin.getBlockY() - 1));
            this.combat = combat;
        }

        /** 要鋪的範圍，每個是 {minX, minZ, maxX, maxZ}。 */
        private List<int[]> areas() {
            final List<int[]> areas = new ArrayList<>();
            areas.add(new int[] {bx - CAMP_FLOOR_RADIUS, bz - CAMP_FLOOR_RADIUS, bx + CAMP_FLOOR_RADIUS, bz + CAMP_FLOOR_RADIUS});
            if (!combat.isEmpty()) {
                areas.add(new int[] {bx + 1, bz - PATH_HALF_WIDTH, zoneStart(bx, combat.size() - 1) + ARENA_OFFSET, bz + PATH_HALF_WIDTH});
            }
            for (int i = 0; i < combat.size(); i++) {
                if (combat.get(i).boss() != null) {
                    final int ax = zoneStart(bx, i) + ARENA_OFFSET;
                    areas.add(new int[] {ax - ARENA_FLOOR_RADIUS, bz - ARENA_FLOOR_RADIUS, ax + ARENA_FLOOR_RADIUS, bz + ARENA_FLOOR_RADIUS});
                }
            }
            return areas;
        }

        Set<Long> chunks() {
            final Set<Long> chunks = new LinkedHashSet<>();
            for (final int[] area : areas()) {
                for (int cx = area[0] >> 4; cx <= area[2] >> 4; cx++) {
                    for (int cz = area[1] >> 4; cz <= area[3] >> 4; cz++) {
                        chunks.add(chunkKey(cx, cz));
                    }
                }
            }
            return chunks;
        }

        /** @return 鋪了幾格 */
        int build() {
            int placed = 0;
            for (final int[] area : areas()) {
                for (int x = area[0]; x <= area[2]; x++) {
                    final Material material = floorMaterial(zoneAt(x));
                    for (int z = area[1]; z <= area[3]; z++) {
                        if (VoidSpawn.emptyColumn(world, x, z)) {
                            world.getBlockAt(x, y, z).setType(material, false);
                            placed++;
                        }
                    }
                }
            }
            return placed;
        }

        /** 通道上這個 x 屬於哪個戰鬥區域（營地回傳 null）。 */
        private Zone zoneAt(final int x) {
            final int index = Math.floorDiv(x - zoneStart(bx, 0), ZONE_LENGTH);
            return index < 0 || index >= combat.size() ? null : combat.get(index);
        }
    }
}
