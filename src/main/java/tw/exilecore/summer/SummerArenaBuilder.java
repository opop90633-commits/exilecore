package tw.exilecore.summer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 星潮海灘（2026-11-03 照站長，夏日活動「星潮祭」）：活動世界（虛空）裡的海灘場地，跟萬燈夜的幽燈墓園分開（z 不同）。
 * <ul>
 *   <li>中央一塊圓形的競技沙地（半徑 {@value #ARENA}）給深潮海妖，邊上一圈平滑砂岩與提燈柱。</li>
 *   <li>北邊是淺海（水 1～2 格深，岸邊彎彎的）：海床上幾塊珊瑚、海草、海泡菜；一條木棧道從沙地伸進海裡。</li>
 *   <li>南邊是沙灘：棕櫚樹、遮陽傘與浴巾；一條木棧道從南邊的入口通到中央。沙灘上任何一格沙子都能挖寶（{@link #diggable}）。</li>
 *   <li>外圍是平滑砂岩矮牆，上面一路到 {@value #CLEAR} 格是看不見的屏障（掉不出去）。</li>
 * </ul>
 * 每 tick 放一點方塊，不會卡伺服器。版面是固定的雜湊算出來的：{@link #sea}、{@link #diggable} 這些 SummerService 也用（藏寶點、西瓜的落點）。
 */
final class SummerArenaBuilder {

    static final int CLEAR = 20;
    private static final int BASE = 4;
    private static final int PER_TICK = 6000;
    /** 地板上（腳下那格）在 y = FLOOR_Y - 1。 */
    static final int FLOOR_Y = 100;
    /** 中央競技沙地的半徑。 */
    static final int ARENA = 10;
    /** 棕櫚樹的位置（相對中心）。 */
    private static final int[][] TREES = {{-20, 18}, {18, 22}, {-28, 6}, {26, 4}, {-12, 30}, {10, 32}, {31, -5}, {-31, -6}, {24, 14}, {-22, 26}};
    /** 遮陽傘的位置（相對中心）與顏色。 */
    private static final int[][] UMBRELLAS = {{-8, 20}, {9, 25}, {-17, 13}, {17, 15}, {-4, 33}};
    private static final Material[] UMBRELLA_COLORS = {Material.RED_WOOL, Material.YELLOW_WOOL, Material.LIGHT_BLUE_WOOL, Material.LIME_WOOL, Material.ORANGE_WOOL};
    private static final Material[] TOWEL_COLORS = {Material.WHITE_CARPET, Material.CYAN_CARPET, Material.PINK_CARPET, Material.YELLOW_CARPET, Material.LIGHT_BLUE_CARPET};

    private record Placement(int x, int y, int z, BlockData data) {
    }

    private final ExileCore plugin;
    private final CommandSender sender;
    private final World world;
    private final int cx;
    private final int cy;
    private final int cz;
    private final int radius;
    private final Runnable done;

    SummerArenaBuilder(final ExileCore plugin, final CommandSender sender, final World world, final SummerConfig config, final Runnable done) {
        this.plugin = plugin;
        this.sender = sender;
        this.world = world;
        this.cx = config.arena().x();
        this.cy = FLOOR_Y - 1;
        this.cz = config.arena().z();
        this.radius = config.arena().radius();
        this.done = done;
    }

    // ---- 版面（純算式，Service 也用）----

    /** 岸線：這一欄（dx）往北到哪裡開始是海（dz 比這個小就是海）。 */
    static double shore(final int dx) {
        return -13 + 3 * Math.sin(dx * 0.33);
    }

    /** 這一格是海（北邊；中央的競技沙地不算）。 */
    static boolean sea(final int dx, final int dz) {
        return Math.sqrt(dx * dx + dz * dz) > ARENA + 1.5 && dz < shore(dx);
    }

    /** 海的這一格有 2 格深（離岸遠）。 */
    static boolean deep(final int dx, final int dz) {
        return dz < shore(dx) - 7;
    }

    /** 南邊的木棧道（入口通到中央）。 */
    static boolean boardwalk(final int dx, final int dz) {
        return Math.abs(dx) <= 1 && dz >= ARENA + 1;
    }

    /** 伸進海裡的木棧道（在水面上一格）。 */
    static boolean pier(final int dx, final int dz) {
        return Math.abs(dx) <= 1 && dz <= -(ARENA + 1) && dz >= -32;
    }

    private static boolean nearDeco(final int dx, final int dz) {
        for (final int[] tree : TREES) {
            if (Math.abs(tree[0] - dx) <= 1 && Math.abs(tree[1] - dz) <= 1) {
                return true;
            }
        }
        for (final int[] umbrella : UMBRELLAS) {
            if (Math.abs(umbrella[0] - dx) <= 2 && Math.abs(umbrella[1] - dz) <= 2) {
                return true;
            }
        }
        return false;
    }

    /** 沙灘上可以站、可以挖的沙子（不是海、不是棧道、不是樹與遮陽傘，也不是中央的競技沙地）。 */
    static boolean diggable(final SummerConfig config, final int dx, final int dz) {
        final double d = Math.sqrt(dx * dx + dz * dz);
        return d > ARENA + 2 && d <= config.arena().radius() - 2.5 && !sea(dx, dz) && !boardwalk(dx, dz) && !nearDeco(dx, dz);
    }

    /** 陸地上可以放東西（西瓜）的格子：沙灘、中央沙地、南邊的棧道都算。 */
    static boolean land(final SummerConfig config, final int dx, final int dz) {
        final double d = Math.sqrt(dx * dx + dz * dz);
        return d <= config.arena().radius() - 2.5 && !sea(dx, dz) && !nearDeco(dx, dz) && !pier(dx, dz);
    }

    /**
     * 今天的藏寶點（每個玩家各自一組：種子 = 玩家＋日期；不用存每個點）。
     * 彼此至少隔 5 格；回傳相對中心的 {dx, dz}。
     */
    static List<int[]> digSpots(final SummerConfig config, final long seed) {
        final RandomGenerator random = new java.util.Random(seed);
        final List<int[]> spots = new ArrayList<>();
        final int span = config.arena().radius() - 3;
        for (int i = 0; i < config.dig().spotsPerDay(); i++) {
            for (int attempt = 0; attempt < 400; attempt++) {
                final int dx = random.nextInt(span * 2 + 1) - span;
                final int dz = random.nextInt(span * 2 + 1) - span;
                if (!diggable(config, dx, dz)) {
                    continue;
                }
                boolean apart = true;
                for (final int[] other : spots) {
                    if (Math.hypot(other[0] - dx, other[1] - dz) < 5) {
                        apart = false;
                        break;
                    }
                }
                if (apart) {
                    spots.add(new int[] {dx, dz});
                    break;
                }
            }
        }
        return spots;
    }

    /** 隨機一格陸地（西瓜的落點）；找不到 null。 */
    static int[] randomLand(final SummerConfig config, final RandomGenerator random) {
        final int span = config.arena().radius() - 3;
        for (int attempt = 0; attempt < 200; attempt++) {
            final int dx = random.nextInt(span * 2 + 1) - span;
            final int dz = random.nextInt(span * 2 + 1) - span;
            if (land(config, dx, dz)) {
                return new int[] {dx, dz};
            }
        }
        return null;
    }

    // ---- 蓋 ----

    void start() {
        final List<Placement> list = plan();
        sender.sendMessage(plugin.text().prefixed(String.format(java.util.Locale.ROOT,
                "<gray>開始蓋星潮海灘：半徑 %d 格，%,d 個方塊", radius, list.size())));
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

    private List<Placement> plan() {
        final List<Placement> list = new ArrayList<>();
        final int outer = radius + 3;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = cx + dx;
                final int z = cz + dz;
                if (d <= radius + 0.5) {
                    for (int y = 1; y <= BASE; y++) {
                        list.add(new Placement(x, cy - y, z, data(Material.SANDSTONE)));
                    }
                    for (int y = 1; y <= CLEAR; y++) {
                        list.add(new Placement(x, cy + y, z, data(Material.AIR)));
                    }
                    if (sea(dx, dz)) {
                        seaColumn(list, dx, dz, x, z);
                    } else {
                        list.add(new Placement(x, cy, z, data(floor(dx, dz, d))));
                    }
                } else if (d <= radius + 2.0) {
                    for (int y = 1; y <= BASE; y++) {
                        list.add(new Placement(x, cy - y, z, data(Material.SANDSTONE)));
                    }
                    list.add(new Placement(x, cy, z, data(Material.SMOOTH_SANDSTONE)));
                    list.add(new Placement(x, cy + 1, z, data(Material.SANDSTONE_WALL)));
                    for (int y = 2; y <= CLEAR; y++) {
                        list.add(new Placement(x, cy + y, z, data(Material.BARRIER)));
                    }
                }
            }
        }
        // 牆頭每 20° 一盞提燈
        for (int i = 0; i < 18; i++) {
            final double angle = Math.toRadians(i * 20 + 10);
            final int x = cx + (int) Math.round(Math.cos(angle) * (radius + 1.4));
            final int z = cz + (int) Math.round(Math.sin(angle) * (radius + 1.4));
            list.add(new Placement(x, cy + 1, z, data(Material.SANDSTONE_WALL)));
            list.add(new Placement(x, cy + 2, z, data(Material.LANTERN)));
        }
        // 中央沙地邊上一圈提燈柱（8 根）
        for (int i = 0; i < 8; i++) {
            final double angle = Math.PI * 2 * i / 8 + Math.PI / 8;
            final int dx = (int) Math.round(Math.cos(angle) * (ARENA + 1.5));
            final int dz = (int) Math.round(Math.sin(angle) * (ARENA + 1.5));
            if (sea(dx, dz)) {
                continue;
            }
            list.add(new Placement(cx + dx, cy + 1, cz + dz, data(Material.JUNGLE_FENCE)));
            list.add(new Placement(cx + dx, cy + 2, cz + dz, data(Material.JUNGLE_FENCE)));
            list.add(new Placement(cx + dx, cy + 3, cz + dz, data(Material.LANTERN)));
        }
        // 伸進海裡的木棧道：水面上一格鋪木板，水裡柵欄撐著，兩側欄杆，盡頭一盞提燈
        for (int dz = -(ARENA + 1); dz >= -32; dz--) {
            for (int dx = -1; dx <= 1; dx++) {
                final int x = cx + dx;
                final int z = cz + dz;
                if (!sea(dx, dz)) {
                    list.add(new Placement(x, cy + 1, z, data(Material.JUNGLE_PLANKS))); // 岸上那幾格：一樣高（走上去）
                    continue;
                }
                list.add(new Placement(x, cy, z, data("minecraft:jungle_fence[waterlogged=true]")));
                list.add(new Placement(x, cy + 1, z, data(Material.JUNGLE_PLANKS)));
            }
            if (dz % 4 == 0) {
                list.add(new Placement(cx - 2, cy + 1, cz + dz, data(Material.JUNGLE_FENCE)));
                list.add(new Placement(cx + 2, cy + 1, cz + dz, data(Material.JUNGLE_FENCE)));
                list.add(new Placement(cx - 2, cy + 2, cz + dz, data(Material.JUNGLE_FENCE)));
                list.add(new Placement(cx + 2, cy + 2, cz + dz, data(Material.JUNGLE_FENCE)));
            }
        }
        list.add(new Placement(cx, cy + 2, cz - 33, data(Material.JUNGLE_FENCE)));
        list.add(new Placement(cx, cy + 3, cz - 33, data(Material.LANTERN)));
        // 棕櫚樹：叢林原木往上長、頂端歪一格，樹葉十字往下垂
        for (final int[] tree : TREES) {
            final int dx = tree[0];
            final int dz = tree[1];
            if (Math.hypot(dx, dz) > radius - 3 || sea(dx, dz)) {
                continue;
            }
            final int x = cx + dx;
            final int z = cz + dz;
            final int height = 5 + hash(x, z, 5) % 3;
            final int leanX = dx < 0 ? 1 : -1; // 往中央歪
            for (int y = 1; y <= height; y++) {
                final int lean = y >= height - 1 ? leanX : 0;
                list.add(new Placement(x + lean, cy + y, z, data(Material.JUNGLE_LOG)));
            }
            final int tx = x + leanX;
            final int top = cy + height;
            list.add(new Placement(tx, top + 1, z, data("minecraft:jungle_leaves[persistent=true]")));
            final int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (final int[] arm : arms) {
                list.add(new Placement(tx + arm[0], top + 1, z + arm[1], data("minecraft:jungle_leaves[persistent=true]")));
                list.add(new Placement(tx + arm[0] * 2, top + 1, z + arm[1] * 2, data("minecraft:jungle_leaves[persistent=true]")));
                list.add(new Placement(tx + arm[0] * 3, top, z + arm[1] * 3, data("minecraft:jungle_leaves[persistent=true]")));
            }
            list.add(new Placement(tx + 1, top + 1, z + 1, data("minecraft:jungle_leaves[persistent=true]")));
            list.add(new Placement(tx - 1, top + 1, z - 1, data("minecraft:jungle_leaves[persistent=true]")));
            if (hash(x, z, 6) % 2 == 0) {
                list.add(new Placement(tx + 1, top, z, data(Material.BROWN_MUSHROOM_BLOCK))); // 椰子
            }
        }
        // 遮陽傘（柵欄柱＋羊毛的傘面）與旁邊的浴巾（地毯）
        for (int i = 0; i < UMBRELLAS.length; i++) {
            final int dx = UMBRELLAS[i][0];
            final int dz = UMBRELLAS[i][1];
            if (Math.hypot(dx, dz) > radius - 4 || sea(dx, dz)) {
                continue;
            }
            final int x = cx + dx;
            final int z = cz + dz;
            final Material wool = UMBRELLA_COLORS[i % UMBRELLA_COLORS.length];
            list.add(new Placement(x, cy + 1, z, data(Material.JUNGLE_FENCE)));
            list.add(new Placement(x, cy + 2, z, data(Material.JUNGLE_FENCE)));
            list.add(new Placement(x, cy + 3, z, data(wool)));
            for (final int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                list.add(new Placement(x + o[0], cy + 3, z + o[1], data(i % 2 == 0 ? Material.WHITE_WOOL : wool)));
            }
            list.add(new Placement(x + 1, cy + 1, z + 1, data(TOWEL_COLORS[i % TOWEL_COLORS.length])));
            list.add(new Placement(x + 1, cy + 1, z + 2, data(TOWEL_COLORS[i % TOWEL_COLORS.length])));
        }
        return list;
    }

    /** 海的一欄：海床（沙、礫石）、水，海床上偶爾海草、海泡菜、珊瑚。 */
    private void seaColumn(final List<Placement> list, final int dx, final int dz, final int x, final int z) {
        final boolean deep = deep(dx, dz);
        final int floorY = deep ? cy - 2 : cy - 1;
        final int r = hash(dx, dz, 12) % 10;
        list.add(new Placement(x, floorY, z, data(r < 7 ? Material.SAND : r < 9 ? Material.GRAVEL : Material.CLAY)));
        for (int y = floorY + 1; y <= cy; y++) {
            list.add(new Placement(x, y, z, data(Material.WATER)));
        }
        if (pier(dx, dz) || Math.abs(dx) == 2 && pier(0, dz)) {
            return; // 棧道下面不放東西
        }
        final int deco = hash(dx, dz, 13) % 40;
        if (!deep) {
            if (deco < 4) {
                list.add(new Placement(x, floorY + 1, z, data(Material.SEAGRASS)));
            } else if (deco == 4) {
                list.add(new Placement(x, floorY + 1, z, data("minecraft:sea_pickle[pickles=" + (1 + hash(dx, dz, 14) % 3) + ",waterlogged=true]")));
            }
            return;
        }
        if (deco < 3) {
            final Material[] corals = {Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK, Material.FIRE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK};
            final String[] fans = {"tube", "brain", "fire", "horn", "bubble"};
            final int kind = hash(dx, dz, 15) % corals.length;
            list.add(new Placement(x, floorY + 1, z, data(corals[kind])));
            list.add(new Placement(x, floorY + 2, z, data("minecraft:" + fans[kind] + "_coral_fan[waterlogged=true]")));
        } else if (deco < 7) {
            list.add(new Placement(x, floorY + 1, z, data("minecraft:tall_seagrass[half=lower]")));
            list.add(new Placement(x, floorY + 2, z, data("minecraft:tall_seagrass[half=upper]")));
        } else if (deco < 10) {
            list.add(new Placement(x, floorY + 1, z, data(Material.SEAGRASS)));
        }
    }

    private Material floor(final int dx, final int dz, final double d) {
        if (d <= ARENA + 0.5) {
            return Material.SAND; // 中央競技沙地
        }
        if (d <= ARENA + 1.5) {
            return Material.SMOOTH_SANDSTONE; // 邊
        }
        if (boardwalk(dx, dz)) {
            return hash(dx, dz, 10) % 7 == 0 ? Material.STRIPPED_JUNGLE_LOG : Material.JUNGLE_PLANKS;
        }
        if (Math.abs(dx) == 2 && boardwalk(0, dz) && hash(dx, dz, 11) % 3 == 0) {
            return Material.SANDSTONE; // 棧道邊緣幾塊石板
        }
        return Material.SAND;
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
        sender.sendMessage(plugin.text().prefixed("<green>星潮海灘蓋好了：中心（" + cx + ", " + (cy + 1) + ", " + cz + "），半徑 " + radius + " 格"));
        done.run();
    }

    static Location center(final World world, final SummerConfig config) {
        return new Location(world, config.arena().x() + 0.5, FLOOR_Y, config.arena().z() + 0.5);
    }
}
