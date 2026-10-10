package tw.exilecore.pvp;

import tw.exilecore.worldgen.BlockSink;
import tw.exilecore.worldgen.Palette;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PvP 競技場的場地（純邏輯，不依賴 Bukkit）：61×61 的場內（x、z 從 −{@value #HALF} 到 {@value #HALF}），
 * 地板在 y = {@value #FLOOR_Y}（站在上面一格），四周 6 格高的圍牆、上面一路屏障到 y = {@value #BARRIER_TOP}（跳、位移技能都出不去）。
 * <p>
 * 掩體照八個方向對稱（上下左右、對角線都一樣），每個出生點看出去的場地都一樣公平：
 * <ul>
 *   <li>中央高台（5×5、4 格高）：四面各一把梯子，站上去視野最好，也最顯眼。</li>
 *   <li>對角線上的箱子（3×3、3 格高）：朝中央的兩面有梯子；箱頂有兩格寬的橋接到 L 形牆頂（橋下可以走）。</li>
 *   <li>L 形牆（兩格厚、3 格高）：外側有梯子，牆頂可以走。</li>
 *   <li>柱子（2×2、5 格高）：只擋視線，爬不上去。</li>
 *   <li>出生點前的矮牆、角落出生點的 L 形矮牆（2 格高）：朝中央那面有梯子。</li>
 * </ul>
 * 出生點：四邊中間各一個、四個角落各一個（都在掩體後面）。
 * <p>
 * 觀看席（2026-10）：南北兩邊的圍牆外面各有四排階梯座位，從牆頂上面看進場內；跟場地之間隔著屏障，下不去、打不進去。
 */
public final class ArenaLayout {

    /** 場地的版本：改了形狀就加一，伺服器啟動時會清掉舊的重蓋。 */
    public static final int VERSION = 2; // 2026-10：南北兩邊加觀看席
    /** 地板方塊的高度（站在上面一格）。 */
    public static final int FLOOR_Y = 64;
    /** 場內 x、z 從 −HALF 到 HALF。 */
    public static final int HALF = 30;
    /** 圍牆的位置（±31）。 */
    public static final int WALL = HALF + 1;
    /** 圍牆高出地板幾格。 */
    public static final int WALL_HEIGHT = 6;
    /** 屏障蓋到這一層。 */
    public static final int BARRIER_TOP = 100;
    /** 重蓋時清掉的水平範圍（±）。 */
    public static final int CLEAR = WALL + 4 + 2; // 圍牆外面還有觀看席（STAND_DEPTH 排）
    /** 觀看席（2026-10）：圍牆外面南北兩邊各一排、一層比一層高，幾排、多寬。 */
    public static final int STAND_DEPTH = 4;
    public static final int STAND_HALF_WIDTH = 18;
    /** 重蓋時清掉的高度範圍。 */
    public static final int CLEAR_MIN_Y = FLOOR_Y - 3;

    private static final int Y = FLOOR_Y;

    /** 掩體的種類（決定材質）。 */
    public enum Kind { TOWER, CRATE, WALL, BRIDGE, PILLAR }

    /** 實心的長方體（兩端都含）。 */
    public record Box(int x1, int z1, int x2, int z2, int y1, int y2, Kind kind) {
        public Box {
            if (x1 > x2 || z1 > z2 || y1 > y2) {
                throw new IllegalArgumentException("box 的範圍要由小到大");
            }
        }

        public boolean contains(final int x, final int y, final int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2 && y >= y1 && y <= y2;
        }

        /** 站在地上（擋路）；橋這種離地的不擋。 */
        public boolean grounded() {
            return y1 == FLOOR_Y + 1;
        }
    }

    /**
     * 梯子：一直線從 y1 到 y2。(dx, dz) 是梯子朝的方向（背對它貼著的那面牆），貼著的方塊在 (x − dx, z − dz)。
     */
    public record Ladder(int x, int z, int y1, int y2, int dx, int dz) {
        public Ladder {
            if (Math.abs(dx) + Math.abs(dz) != 1) {
                throw new IllegalArgumentException("梯子的方向要是東南西北其中一個");
            }
        }

        /** 方塊狀態的 facing。 */
        public String facing() {
            return dx == 1 ? "east" : dx == -1 ? "west" : dz == 1 ? "south" : "north";
        }
    }

    /** 出生點（站的那一格）與面向（看向場地中央）。 */
    public record Spawn(int x, int z, float yaw) {
    }

    // ---- 一個方向的掩體（再照八個方向對稱複製） ----

    private static final List<Box> BASE_BOXES = List.of(
            new Box(-2, -2, 2, 2, Y + 1, Y + 4, Kind.TOWER),     // 中央高台
            new Box(9, 9, 11, 11, Y + 1, Y + 3, Kind.CRATE),      // 對角線上的箱子
            new Box(16, 6, 20, 7, Y + 1, Y + 3, Kind.WALL),       // L 形牆的長邊
            new Box(19, 6, 20, 11, Y + 1, Y + 3, Kind.WALL),      // L 形牆的短邊
            new Box(12, 10, 18, 11, Y + 3, Y + 3, Kind.BRIDGE),   // 箱子到 L 形牆的橋（離地兩格，底下可以走）
            new Box(13, 2, 14, 3, Y + 1, Y + 5, Kind.PILLAR),     // 柱子
            new Box(23, -4, 24, 4, Y + 1, Y + 2, Kind.WALL),      // 邊上出生點前的矮牆
            new Box(21, 21, 22, 26, Y + 1, Y + 2, Kind.WALL),     // 角落出生點的 L 形矮牆
            new Box(21, 21, 26, 22, Y + 1, Y + 2, Kind.WALL));

    private static final List<Ladder> BASE_LADDERS = List.of(
            new Ladder(3, 0, Y + 1, Y + 4, 1, 0),        // 高台
            new Ladder(8, 10, Y + 1, Y + 3, -1, 0),      // 箱子朝中央
            new Ladder(18, 5, Y + 1, Y + 3, 0, -1),      // L 形牆外側
            new Ladder(22, 0, Y + 1, Y + 2, -1, 0),      // 出生點矮牆朝中央
            new Ladder(20, 24, Y + 1, Y + 2, -1, 0));    // 角落矮牆朝中央

    private static final List<Spawn> BASE_SPAWNS = List.of(new Spawn(27, 0, 0), new Spawn(26, 26, 0));

    // ---- 材質 ----

    private static final int FLOOR = Palette.id("deepslate_tiles");
    private static final int FLOOR_LINE = Palette.id("polished_deepslate");
    private static final int FOUNDATION = Palette.id("deepslate");
    private static final int PAD = Palette.id("polished_blackstone");
    private static final int LIGHT = Palette.id("sea_lantern");
    private static final int WALL_BLOCK = Palette.id("deepslate_bricks");
    private static final int WALL_TRIM = Palette.id("polished_blackstone_bricks");
    private static final int WALL_CORNER = Palette.id("chiseled_deepslate");
    private static final int BARRIER = Palette.id("barrier");
    private static final int TOWER_SIDE = Palette.id("deepslate_bricks");
    private static final int TOWER_EDGE = Palette.id("chiseled_deepslate");
    private static final int TOWER_TOP = Palette.id("polished_deepslate");
    private static final int CRATE_SIDE = Palette.id("spruce_planks");
    private static final int CRATE_EDGE = Palette.id("stripped_spruce_log[axis=y]");
    private static final int STONE = Palette.id("stone_bricks");
    private static final int STONE_MOSSY = Palette.id("mossy_stone_bricks");
    private static final int STONE_CRACKED = Palette.id("cracked_stone_bricks");
    private static final int BRIDGE = Palette.id("dark_oak_planks");
    private static final int PILLAR = Palette.id("polished_basalt[axis=y]");

    private final List<Box> boxes;
    private final List<Ladder> ladders;
    private final List<Spawn> spawns;

    private ArenaLayout(final List<Box> boxes, final List<Ladder> ladders, final List<Spawn> spawns) {
        this.boxes = List.copyOf(boxes);
        this.ladders = List.copyOf(ladders);
        this.spawns = List.copyOf(spawns);
    }

    /** 出貨的場地。 */
    public static ArenaLayout standard() {
        final Set<Box> boxes = new LinkedHashSet<>();
        final Set<Ladder> ladders = new LinkedHashSet<>();
        final Set<Spawn> spawns = new LinkedHashSet<>();
        for (int op = 0; op < 8; op++) {
            for (final Box box : BASE_BOXES) {
                boxes.add(transform(box, op));
            }
            for (final Ladder ladder : BASE_LADDERS) {
                ladders.add(transform(ladder, op));
            }
            for (final Spawn spawn : BASE_SPAWNS) {
                spawns.add(transform(spawn, op));
            }
        }
        return new ArenaLayout(new ArrayList<>(boxes), new ArrayList<>(ladders), new ArrayList<>(spawns));
    }

    public List<Box> boxes() {
        return boxes;
    }

    public List<Ladder> ladders() {
        return ladders;
    }

    public List<Spawn> spawns() {
        return spawns;
    }

    // ---- 對稱（正方形的八種翻轉與旋轉） ----

    /** 第 op 種對稱（0–7）作用在 (x, z)：op 的位元 0 = x 變號、1 = z 變號、2 = x、z 交換（先交換再變號）。 */
    static int[] apply(final int op, final int x, final int z) {
        int a = (op & 4) != 0 ? z : x;
        int b = (op & 4) != 0 ? x : z;
        if ((op & 1) != 0) {
            a = -a;
        }
        if ((op & 2) != 0) {
            b = -b;
        }
        return new int[] {a, b};
    }

    static Box transform(final Box box, final int op) {
        final int[] p = apply(op, box.x1(), box.z1());
        final int[] q = apply(op, box.x2(), box.z2());
        return new Box(Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.max(p[0], q[0]), Math.max(p[1], q[1]), box.y1(), box.y2(), box.kind());
    }

    static Ladder transform(final Ladder ladder, final int op) {
        final int[] p = apply(op, ladder.x(), ladder.z());
        final int[] d = apply(op, ladder.dx(), ladder.dz());
        return new Ladder(p[0], p[1], ladder.y1(), ladder.y2(), d[0], d[1]);
    }

    static Spawn transform(final Spawn spawn, final int op) {
        final int[] p = apply(op, spawn.x(), spawn.z());
        return new Spawn(p[0], p[1], yawTowardsCenter(p[0], p[1]));
    }

    /** 從 (x, z) 看向場地中央的面向（Minecraft 的 yaw：0 = 南、90 = 西、180 = 北、270 = 東）。 */
    static float yawTowardsCenter(final int x, final int z) {
        if (x == 0 && z == 0) {
            return 0;
        }
        final double degrees = Math.toDegrees(Math.atan2(x, -z)); // 方向 (−x, −z)：yaw = atan2(−dx, dz)
        return (float) ((degrees % 360 + 360) % 360);
    }

    // ---- 查詢 ----

    /** 這一格是不是掩體的方塊（不含地板、圍牆、梯子）。 */
    public boolean coverAt(final int x, final int y, final int z) {
        for (final Box box : boxes) {
            if (box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** 這一格有沒有梯子。 */
    public Ladder ladderAt(final int x, final int y, final int z) {
        for (final Ladder ladder : ladders) {
            if (ladder.x() == x && ladder.z() == z && y >= ladder.y1() && y <= ladder.y2()) {
                return ladder;
            }
        }
        return null;
    }

    /** 這個水平位置在圍牆裡面（場內的方塊是 −30 到 30，也就是座標 −30 到 31 之前）。 */
    public static boolean inside(final double x, final double z) {
        return x >= -HALF && x < HALF + 1 && z >= -HALF && z < HALF + 1;
    }

    // ---- 蓋 ----

    /** 把整個場地寫進 sink（地基、地板、圍牆與屏障、掩體、梯子、出生點的地墊）。 */
    public void build(final BlockSink sink) {
        // 地基與地板：每 6 格一條線，出生點一塊亮的地墊
        for (int x = -WALL; x <= WALL; x++) {
            for (int z = -WALL; z <= WALL; z++) {
                sink.set(x, Y - 1, z, FOUNDATION);
                sink.set(x, Y, z, Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0 ? FLOOR_LINE : FLOOR);
            }
        }
        for (final Spawn spawn : spawns) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    sink.set(spawn.x() + dx, Y, spawn.z() + dz, dx == 0 && dz == 0 ? LIGHT : PAD);
                }
            }
        }
        // 圍牆（含地板那一層）、牆頂的飾邊、每 8 格一盞燈、上面一路屏障
        for (int i = -WALL; i <= WALL; i++) {
            for (final int[] at : new int[][] {{i, -WALL}, {i, WALL}, {-WALL, i}, {WALL, i}}) {
                final boolean corner = Math.abs(at[0]) == WALL && Math.abs(at[1]) == WALL;
                for (int y = Y; y <= Y + WALL_HEIGHT; y++) {
                    final boolean lamp = !corner && y == Y + 3 && Math.floorMod(i, 8) == 4;
                    sink.set(at[0], y, at[1], corner ? WALL_CORNER : lamp ? LIGHT : WALL_BLOCK);
                }
                sink.set(at[0], Y + WALL_HEIGHT + 1, at[1], WALL_TRIM);
                for (int y = Y + WALL_HEIGHT + 2; y <= BARRIER_TOP; y++) {
                    sink.set(at[0], y, at[1], BARRIER);
                }
            }
        }
        for (final Box box : boxes) {
            buildBox(sink, box);
        }
        buildStands(sink);
        for (final Ladder ladder : ladders) {
            final int block = Palette.id("ladder[facing=" + ladder.facing() + "]");
            for (int y = ladder.y1(); y <= ladder.y2(); y++) {
                sink.set(ladder.x(), y, ladder.z(), block);
            }
        }
    }

    /** 觀看席站的地方：北邊中間第三排（面向南、看進場內）。 */
    public static double[] standSpot(final boolean north) {
        final int z = north ? -(WALL + 3) : WALL + 3;
        return new double[] {0.5, Y + WALL_HEIGHT + 1 + 2 + 1, z + 0.5, north ? 0 : 180};
    }

    /** 這個位置在觀看席上（南北兩邊圍牆外面、座位的範圍）。 */
    public static boolean onStands(final double x, final double z) {
        final double az = Math.abs(z);
        return Math.abs(x) <= STAND_HALF_WIDTH + 1 && az > WALL && az <= WALL + STAND_DEPTH + 1;
    }

    /** 南北兩邊的觀看席：四排階梯（一排高一格），兩端與後面圍屏障，每隔 6 格一盞燈。 */
    private static void buildStands(final BlockSink sink) {
        final int stand = Palette.id("polished_deepslate");
        final int seat = Palette.id("dark_oak_planks");
        for (final int side : new int[] {-1, 1}) {
            for (int row = 0; row < STAND_DEPTH; row++) {
                final int z = side * (WALL + 1 + row);
                final int top = Y + WALL_HEIGHT + 1 + row;      // 這一排站的方塊
                for (int x = -STAND_HALF_WIDTH; x <= STAND_HALF_WIDTH; x++) {
                    for (int y = Y - 1; y < top; y++) {
                        sink.set(x, y, z, FOUNDATION);
                    }
                    sink.set(x, top, z, Math.floorMod(x, 6) == 0 ? LIGHT : row % 2 == 0 ? stand : seat);
                }
            }
            // 後面與兩端：屏障（掉不下去，也不會走到場外的虛空）
            final int back = side * (WALL + STAND_DEPTH + 1);
            for (int x = -STAND_HALF_WIDTH - 1; x <= STAND_HALF_WIDTH + 1; x++) {
                for (int y = Y; y <= Y + WALL_HEIGHT + STAND_DEPTH + 6; y++) {
                    sink.set(x, y, back, BARRIER);
                }
            }
            for (int row = 0; row <= STAND_DEPTH; row++) {
                final int z = side * (WALL + 1 + row);
                for (final int x : new int[] {-STAND_HALF_WIDTH - 1, STAND_HALF_WIDTH + 1}) {
                    // 兩端的牆（實心，上面再一路屏障）
                    for (int y = Y - 1; y <= Y + WALL_HEIGHT + STAND_DEPTH; y++) {
                        sink.set(x, y, z, y == Y + WALL_HEIGHT + STAND_DEPTH ? WALL_TRIM : WALL_BLOCK);
                    }
                    for (int y = Y + WALL_HEIGHT + STAND_DEPTH + 1; y <= Y + WALL_HEIGHT + STAND_DEPTH + 6; y++) {
                        sink.set(x, y, z, BARRIER);
                    }
                }
            }
        }
    }

    private static void buildBox(final BlockSink sink, final Box box) {
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                final boolean edge = (x == box.x1() || x == box.x2()) && (z == box.z1() || z == box.z2());
                for (int y = box.y1(); y <= box.y2(); y++) {
                    sink.set(x, y, z, material(box, x, y, z, edge));
                }
            }
        }
    }

    private static int material(final Box box, final int x, final int y, final int z, final boolean edge) {
        return switch (box.kind()) {
            case TOWER -> edge ? TOWER_EDGE : y == box.y2() ? TOWER_TOP : TOWER_SIDE;
            case CRATE -> edge ? CRATE_EDGE : CRATE_SIDE;
            case WALL -> {
                final int hash = Math.floorMod(x * 31 + z * 17 + y * 7, 11);
                yield hash == 0 ? STONE_MOSSY : hash == 1 ? STONE_CRACKED : STONE;
            }
            case BRIDGE -> BRIDGE;
            case PILLAR -> PILLAR;
        };
    }
}
