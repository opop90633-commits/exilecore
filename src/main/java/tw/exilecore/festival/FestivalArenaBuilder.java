package tw.exilecore.festival;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 幽燈墓園（2026-10 萬燈夜）：活動世界（虛空）裡的萬聖節場地。
 * <ul>
 *   <li>地板：灰化土、粗泥、靈魂土混著鋪，四條碎石小路通到中央；中央是石磚的圓台，一圈柵欄頂著南瓜燈（萬燈）。</li>
 *   <li>墓碑一排一排、幾棵枯樹掛著蜘蛛網、地上散著南瓜；外圈一圈點燈用的南瓜燈柱（{@link #lanternSpots}）。</li>
 *   <li>外圍是青苔石磚矮牆加鐵欄杆，上面一路到 {@value #CLEAR} 格是看不見的屏障（掉不出去）。</li>
 * </ul>
 * 每 tick 放一點方塊，不會卡伺服器。
 */
final class FestivalArenaBuilder {

    static final int CLEAR = 20;
    private static final int BASE = 4;
    private static final int PER_TICK = 6000;
    /** 地板上（腳下那格）在 y = FLOOR_Y - 1。 */
    static final int FLOOR_Y = 100;

    private record Placement(int x, int y, int z, BlockData data) {
    }

    private final ExileCore plugin;
    private final CommandSender sender;
    private final World world;
    private final int cx;
    private final int cy;
    private final int cz;
    private final int radius;
    private final int lanternCount;
    private final int lanternRing;
    private final Runnable done;

    FestivalArenaBuilder(final ExileCore plugin, final CommandSender sender, final World world, final FestivalConfig config,
                         final Runnable done) {
        this.plugin = plugin;
        this.sender = sender;
        this.world = world;
        this.cx = config.arena().x();
        this.cy = FLOOR_Y - 1;
        this.cz = config.arena().z();
        this.radius = config.arena().radius();
        this.lanternCount = config.lanterns().count();
        this.lanternRing = config.lanterns().ring();
        this.done = done;
    }

    /** 點燈用的南瓜燈的位置（南瓜那一格；下面是柵欄）：從正北開始順時針平均分。 */
    static List<int[]> lanternSpots(final FestivalConfig config) {
        final List<int[]> list = new ArrayList<>();
        final int count = config.lanterns().count();
        for (int i = 0; i < count; i++) {
            final double angle = Math.PI * 2 * i / count;
            final int x = config.arena().x() + (int) Math.round(Math.sin(angle) * config.lanterns().ring());
            final int z = config.arena().z() - (int) Math.round(Math.cos(angle) * config.lanterns().ring());
            list.add(new int[] {x, FLOOR_Y + 1, z});
        }
        return list;
    }

    void start() {
        final List<Placement> list = plan();
        sender.sendMessage(plugin.text().prefixed(String.format(java.util.Locale.ROOT,
                "<gray>開始蓋幽燈墓園：半徑 %d 格，%,d 個方塊", radius, list.size())));
        place(list, 0);
    }

    private BlockData data(final String text) {
        return plugin.getServer().createBlockData(text);
    }

    private BlockData data(final Material material) {
        return material.createBlockData();
    }

    /** 固定的雜湊（每次蓋出來都一樣）。 */
    private static int hash(final int x, final int z, final int salt) {
        int h = x * 73_856_093 ^ z * 19_349_663 ^ salt * 83_492_791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    private boolean path(final int dx, final int dz, final double d) {
        // 四條小路（寬 3）＋點燈那一圈的小路
        return (Math.abs(dx) <= 1 || Math.abs(dz) <= 1) && d > 8 || Math.abs(d - lanternRing) < 1.2;
    }

    private List<Placement> plan() {
        final List<Placement> list = new ArrayList<>();
        final Set<Long> taken = new HashSet<>(); // 地面上已經放了東西的格子
        final int outer = radius + 3;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = cx + dx;
                final int z = cz + dz;
                if (d <= radius + 0.5) {
                    list.add(new Placement(x, cy, z, data(floor(dx, dz, d))));
                    for (int y = 1; y <= BASE; y++) {
                        list.add(new Placement(x, cy - y, z, data(Material.DEEPSLATE)));
                    }
                    for (int y = 1; y <= CLEAR; y++) {
                        list.add(new Placement(x, cy + y, z, data(Material.AIR)));
                    }
                } else if (d <= radius + 2.0) {
                    list.add(new Placement(x, cy, z, data(Material.MOSSY_STONE_BRICKS)));
                    for (int y = 1; y <= BASE; y++) {
                        list.add(new Placement(x, cy - y, z, data(Material.DEEPSLATE)));
                    }
                    list.add(new Placement(x, cy + 1, z, data(hash(x, z, 1) % 3 == 0 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS)));
                    list.add(new Placement(x, cy + 2, z, data(Material.IRON_BARS)));
                    list.add(new Placement(x, cy + 3, z, data(Material.IRON_BARS)));
                    for (int y = 4; y <= CLEAR; y++) {
                        list.add(new Placement(x, cy + y, z, data(Material.BARRIER)));
                    }
                }
            }
        }
        // 牆頭每 20° 一盞靈魂燈籠
        for (int i = 0; i < 18; i++) {
            final double angle = Math.toRadians(i * 20 + 10);
            final int x = cx + (int) Math.round(Math.cos(angle) * (radius + 1.4));
            final int z = cz + (int) Math.round(Math.sin(angle) * (radius + 1.4));
            list.add(new Placement(x, cy + 2, z, data(Material.MOSSY_STONE_BRICK_WALL)));
            list.add(new Placement(x, cy + 3, z, data(Material.SOUL_LANTERN)));
        }
        // 中央：一圈柵欄頂著南瓜燈（萬燈），再外面一圈靈魂燈籠
        for (int i = 0; i < 8; i++) {
            final double angle = Math.PI * 2 * i / 8 + Math.PI / 8;
            final int x = cx + (int) Math.round(Math.cos(angle) * 6.5);
            final int z = cz + (int) Math.round(Math.sin(angle) * 6.5);
            list.add(new Placement(x, cy + 1, z, data(Material.DARK_OAK_FENCE)));
            list.add(new Placement(x, cy + 2, z, data(Material.DARK_OAK_FENCE)));
            list.add(new Placement(x, cy + 3, z, data("minecraft:jack_o_lantern[facing=" + facing(cx - x, cz - z) + "]")));
            taken.add(key(x, z));
        }
        // 點燈的燈柱（柵欄＋還沒點的雕刻南瓜，臉朝中央）
        for (int i = 0; i < lanternCount; i++) {
            final double angle = Math.PI * 2 * i / lanternCount;
            final int x = cx + (int) Math.round(Math.sin(angle) * lanternRing);
            final int z = cz - (int) Math.round(Math.cos(angle) * lanternRing);
            list.add(new Placement(x, cy + 1, z, data(Material.DARK_OAK_FENCE)));
            list.add(new Placement(x, cy + 2, z, data("minecraft:carved_pumpkin[facing=" + facing(cx - x, cz - z) + "]")));
            taken.add(key(x, z));
            for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                list.add(new Placement(x + o[0], cy, z + o[1], data(Material.MOSSY_COBBLESTONE)));
                taken.add(key(x + o[0], z + o[1]));
            }
        }
        // 墓碑：一排一排（避開小路、中央、點燈那一圈）
        for (int dx = -radius + 3; dx <= radius - 3; dx += 3) {
            for (int dz = -radius + 3; dz <= radius - 3; dz += 4) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 11 || d > radius - 3 || path(dx, dz, d) || Math.abs(d - lanternRing) < 3 || hash(dx, dz, 2) % 10 < 3) {
                    continue;
                }
                final int x = cx + dx;
                final int z = cz + dz;
                if (taken.contains(key(x, z))) {
                    continue;
                }
                final int style = hash(dx, dz, 3) % 4;
                switch (style) {
                    case 0 -> list.add(new Placement(x, cy + 1, z, data(Material.MOSSY_STONE_BRICK_WALL)));
                    case 1 -> list.add(new Placement(x, cy + 1, z, data(Material.STONE_BRICK_WALL)));
                    case 2 -> {
                        list.add(new Placement(x, cy + 1, z, data(Material.COBBLESTONE_WALL)));
                        list.add(new Placement(x, cy + 2, z, data(Material.COBBLESTONE_WALL)));
                    }
                    default -> list.add(new Placement(x, cy + 1, z, data("minecraft:mossy_stone_brick_slab[type=top]")));
                }
                // 墳前一小塊土
                list.add(new Placement(x, cy, z + 1, data(Material.ROOTED_DIRT)));
                taken.add(key(x, z));
                if (hash(dx, dz, 4) % 6 == 0) {
                    list.add(new Placement(x, cy + 1, z + 1, data(Material.COBWEB)));
                    taken.add(key(x, z + 1));
                } else if (hash(dx, dz, 4) % 6 == 1) {
                    list.add(new Placement(x, cy + 1, z + 1, data("minecraft:candle[candles=3,lit=true]")));
                    taken.add(key(x, z + 1));
                }
            }
        }
        // 枯樹
        final int[][] trees = {{-24, -14}, {22, -22}, {-15, 24}, {26, 12}, {-34, 4}, {6, -36}, {12, 34}, {-28, -28}};
        for (final int[] tree : trees) {
            final int x = cx + tree[0];
            final int z = cz + tree[1];
            if (Math.sqrt(tree[0] * tree[0] + tree[1] * tree[1]) > radius - 3) {
                continue;
            }
            final int height = 5 + hash(x, z, 5) % 3;
            for (int y = 1; y <= height; y++) {
                list.add(new Placement(x, cy + y, z, data(Material.DARK_OAK_LOG)));
            }
            final int[][] branches = {{1, 0, height - 1}, {-1, 0, height - 2}, {0, 1, height}, {2, 0, height}, {-2, 0, height - 1}, {0, -1, height - 3}};
            for (final int[] b : branches) {
                final String axis = b[0] != 0 ? "x" : "z";
                list.add(new Placement(x + b[0], cy + b[2], z + b[1], data("minecraft:dark_oak_log[axis=" + axis + "]")));
            }
            list.add(new Placement(x + 2, cy + height - 1, z, data(Material.COBWEB)));
            list.add(new Placement(x - 1, cy + height - 3, z, data(Material.COBWEB)));
            list.add(new Placement(x + 1, cy + height - 2, z, data("minecraft:soul_lantern[hanging=true]")));
            list.add(new Placement(x, cy, z, data(Material.ROOTED_DIRT)));
            taken.add(key(x, z));
        }
        // 地上散著南瓜
        for (int dx = -radius + 2; dx <= radius - 2; dx++) {
            for (int dz = -radius + 2; dz <= radius - 2; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 9 || d > radius - 2 || path(dx, dz, d) || hash(dx, dz, 6) % 70 != 0) {
                    continue;
                }
                final int x = cx + dx;
                final int z = cz + dz;
                if (taken.contains(key(x, z)) || taken.contains(key(x, z - 1))) {
                    continue;
                }
                final int kind = hash(dx, dz, 7) % 3;
                list.add(new Placement(x, cy + 1, z, kind == 0 ? data(Material.PUMPKIN)
                        : data((kind == 1 ? "minecraft:carved_pumpkin" : "minecraft:jack_o_lantern") + "[facing=" + facing(-dx, -dz) + "]")));
                taken.add(key(x, z));
            }
        }
        return list;
    }

    private static long key(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** 朝 (dx, dz) 那個方向的 facing。 */
    static String facing(final int dx, final int dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz > 0 ? "south" : "north";
    }

    private Material floor(final int dx, final int dz, final double d) {
        if (d <= 5.5) {
            return d <= 1.5 ? Material.CHISELED_STONE_BRICKS : hash(dx, dz, 8) % 4 == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
        }
        if (d <= 8.5) {
            return hash(dx, dz, 9) % 3 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
        }
        if (path(dx, dz, d)) {
            final int r = hash(dx, dz, 10) % 5;
            return r == 0 ? Material.MOSSY_COBBLESTONE : r == 1 ? Material.GRAVEL : Material.COBBLESTONE;
        }
        final int r = hash(dx, dz, 11) % 20;
        if (r < 8) {
            return Material.PODZOL;
        }
        if (r < 13) {
            return Material.COARSE_DIRT;
        }
        if (r < 16) {
            return Material.SOUL_SOIL;
        }
        if (r < 18) {
            return Material.ROOTED_DIRT;
        }
        return Material.MUD;
    }

    private void place(final List<Placement> list, final int from) {
        final int to = Math.min(list.size(), from + PER_TICK);
        for (int i = from; i < to; i++) {
            final Placement p = list.get(i);
            final Block block = world.getBlockAt(p.x(), p.y(), p.z());
            if (!block.getBlockData().equals(p.data())) {
                block.setBlockData(p.data(), false);
            }
        }
        if (to < list.size()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> place(list, to), 1);
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<green>幽燈墓園蓋好了：中心（" + cx + ", " + (cy + 1) + ", " + cz + "），半徑 " + radius + " 格"));
        done.run();
    }

    static Location center(final World world, final FestivalConfig config) {
        return new Location(world, config.arena().x() + 0.5, FLOOR_Y, config.arena().z() + 0.5);
    }
}
