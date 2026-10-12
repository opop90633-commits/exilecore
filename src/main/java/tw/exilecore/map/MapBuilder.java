package tw.exilecore.map;

import tw.exilecore.worldgen.B;
import tw.exilecore.worldgen.BlockSink;
import tw.exilecore.worldgen.GuideArrow;
import tw.exilecore.worldgen.Noise;
import tw.exilecore.worldgen.Palette;

import java.util.SplittableRandom;

/**
 * 把 {@link MapLayout} 依主題蓋成方塊：地板（主題的鋪地＋雜訊）、牆（高低起伏）、液體（水或岩漿，水面跟地板齊平）、
 * 房間裡的柱子與道具、燈、Boss 房的圓形紋路與一圈柱子、起點的回程傳送門、主路上的路標（發光的箭頭）；
 * 曠野的空地上零星的草、城區的牆上嵌燈。純邏輯，寫進 {@link BlockSink}（世界座標 = 原點＋相對座標）。
 * <p>
 * 整張圖封起來：牆頂到天花板之間補滿屏障、液體上面一格到天花板也是屏障（走不進去、怪物也進不去）、
 * 地板上方 {@link #CEILING} 格蓋一層屏障天花板（看不到、光照得進來），蜘蛛爬牆、會飛的怪、被擊退的怪都出不去，也不會站在牆頂上打不到。
 */
public final class MapBuilder {

    // 主題用到、營地世界沒用到的方塊
    static final int END_STONE = Palette.id("end_stone");
    static final int DRIPSTONE = Palette.id("dripstone_block");
    static final int DRIP_UP = Palette.id("pointed_dripstone[vertical_direction=up,thickness=tip]");
    static final int CUT_SANDSTONE = Palette.id("cut_sandstone");
    static final int PRISMARINE_BRICKS = Palette.id("prismarine_bricks");
    static final int BARRIER = Palette.id("barrier");
    // 沙漠、火山、菌窟
    static final int RED_SAND = Palette.id("red_sand");
    static final int RED_SANDSTONE = Palette.id("red_sandstone");
    static final int CUT_RED_SANDSTONE = Palette.id("cut_red_sandstone");
    static final int SMOOTH_RED_SANDSTONE = Palette.id("smooth_red_sandstone");
    static final int CHISELED_RED_SANDSTONE = Palette.id("chiseled_red_sandstone");
    static final int TERRACOTTA = Palette.id("terracotta");
    static final int ORANGE_TERRACOTTA = Palette.id("orange_terracotta");
    static final int MAGMA = Palette.id("magma_block");
    static final int LAVA = Palette.id("lava[level=0]");
    static final int POLISHED_BASALT = Palette.id("polished_basalt[axis=y]");
    static final int MYCELIUM = Palette.id("mycelium[snowy=false]");
    static final int MUSHROOM_STEM = Palette.id("mushroom_stem");
    static final int BROWN_MUSHROOM_BLOCK = Palette.id("brown_mushroom_block");
    static final int RED_MUSHROOM_BLOCK = Palette.id("red_mushroom_block");
    static final int WARPED_WART = Palette.id("warped_wart_block");

    /** 屏障天花板在地板上方幾格（最高的牆、樹葉、柱子頂到地板上 10 格；清理範圍到地板上 18 格）。 */
    public static final int CEILING = 12;

    /** 一個主題：地板、牆、裝飾用的方塊；liquid 是水池、湖的液體（水或岩漿）。 */
    record Theme(int[] floor, int[] wall, int wallTop, int pillar, int pillarTop, int accent, int light, int deco, int base,
                 int minWall, int maxWall, int liquid) {
        Theme(final int[] floor, final int[] wall, final int wallTop, final int pillar, final int pillarTop, final int accent, final int light,
              final int deco, final int base, final int minWall, final int maxWall) {
            this(floor, wall, wallTop, pillar, pillarTop, accent, light, deco, base, minWall, maxWall, B.WATER);
        }
    }

    private MapBuilder() {
    }

    static Theme theme(final String id) {
        return switch (id) {
            case "beach" -> new Theme(new int[] {B.SAND, B.SAND, B.SMOOTH_SANDSTONE, B.GRAVEL, B.SAND}, new int[] {B.SANDSTONE, CUT_SANDSTONE, B.STONE, B.SANDSTONE},
                    B.SAND, B.DARK_PRISMARINE, B.SEA_LANTERN, PRISMARINE_BRICKS, B.LANTERN, B.BARREL, B.SANDSTONE, 4, 6);
            case "forest" -> new Theme(new int[] {B.GRASS, B.GRASS, B.PODZOL, B.MOSS, B.COARSE_DIRT}, new int[] {B.DARK_OAK_LOG, B.OAK_LOG, B.DARK_OAK_LOG},
                    B.DARK_OAK_LEAVES, B.OAK_LOG, B.SHROOMLIGHT, B.MOSS, B.LANTERN, B.FERN, B.DIRT, 5, 8);
            case "swamp" -> new Theme(new int[] {B.MUD, B.GRASS, B.MUD, B.MOSS, B.MUD_BRICKS, B.PACKED_MUD}, new int[] {B.MANGROVE_ROOTS, B.MUDDY_ROOTS, B.MANGROVE_LOG},
                    B.MANGROVE_LEAVES, B.MANGROVE_LOG, B.SHROOMLIGHT, B.MUD_BRICKS, B.LANTERN, B.FERN, B.MUD, 4, 7);
            case "snow" -> new Theme(new int[] {B.SNOW_BLOCK, B.SNOW_BLOCK, B.PACKED_ICE, B.STONE, B.SNOW_BLOCK}, new int[] {B.PACKED_ICE, B.SNOW_BLOCK, B.STONE, B.CALCITE},
                    B.SNOW_BLOCK, B.PACKED_ICE, B.SEA_LANTERN, B.BLUE_ICE, B.LANTERN, B.SNOW_LAYER, B.STONE, 4, 7);
            case "cave" -> new Theme(new int[] {B.STONE, B.ANDESITE, B.GRAVEL, B.TUFF, B.COBBLED_DEEPSLATE}, new int[] {B.DEEPSLATE, B.STONE, B.TUFF, B.DEEPSLATE},
                    B.DEEPSLATE, DRIPSTONE, B.GLOWSTONE, B.CALCITE, B.GLOWSTONE, DRIP_UP, B.DEEPSLATE, 6, 9);
            case "crypt" -> new Theme(new int[] {B.BLACKSTONE_BRICKS, B.CRACKED_BLACKSTONE_BRICKS, B.DEEPSLATE_TILES, B.POLISHED_BLACKSTONE},
                    new int[] {B.BLACKSTONE_BRICKS, B.DEEPSLATE_BRICKS, B.CRACKED_DEEPSLATE_BRICKS, B.POLISHED_BLACKSTONE},
                    B.CHISELED_DEEPSLATE, B.POLISHED_DEEPSLATE, B.SOUL_LANTERN, B.GILDED_BLACKSTONE, B.SOUL_LANTERN, B.SKULL, B.DEEPSLATE, 5, 7);
            case "star" -> new Theme(new int[] {B.END_STONE_BRICKS, END_STONE, B.PURPUR, B.END_STONE_BRICKS}, new int[] {B.OBSIDIAN, B.PURPUR, B.CRYING_OBSIDIAN, B.OBSIDIAN},
                    B.AMETHYST, B.PURPUR_PILLAR, B.END_ROD, B.AMETHYST, B.SEA_LANTERN, B.AMETHYST_CLUSTER, END_STONE, 5, 8);
            // 沙漠：紅沙、紅砂岩的崖、陶土；水池是綠洲
            case "desert" -> new Theme(new int[] {RED_SAND, B.SAND, RED_SAND, SMOOTH_RED_SANDSTONE, B.SAND}, new int[] {RED_SANDSTONE, CUT_RED_SANDSTONE, TERRACOTTA, ORANGE_TERRACOTTA, RED_SANDSTONE},
                    CUT_RED_SANDSTONE, CHISELED_RED_SANDSTONE, B.LANTERN, ORANGE_TERRACOTTA, B.LANTERN, B.DEAD_BUSH, B.SANDSTONE, 4, 7);
            // 火山：玄武岩、黑石、牆裡的岩漿塊；水池是岩漿（地板沒有岩漿塊：站上去會燙）
            case "volcano" -> new Theme(new int[] {B.BLACKSTONE, B.SMOOTH_BASALT, B.BASALT, B.POLISHED_BLACKSTONE, B.BLACKSTONE}, new int[] {B.BASALT, B.BLACKSTONE, MAGMA, POLISHED_BASALT, B.BASALT},
                    MAGMA, POLISHED_BASALT, B.SHROOMLIGHT, B.GILDED_BLACKSTONE, B.LANTERN, B.BASALT, B.BLACKSTONE, 5, 8, LAVA);
            // 菌窟：菌絲土、蘑菇柄與蘑菇塊的牆、菌光
            case "fungal" -> new Theme(new int[] {MYCELIUM, MYCELIUM, B.ROOTED_DIRT, B.MOSS, B.PODZOL}, new int[] {MUSHROOM_STEM, BROWN_MUSHROOM_BLOCK, RED_MUSHROOM_BLOCK, MUSHROOM_STEM},
                    RED_MUSHROOM_BLOCK, MUSHROOM_STEM, B.SHROOMLIGHT, WARPED_WART, B.SHROOMLIGHT, B.BROWN_MUSHROOM, B.DIRT, 5, 8);
            // 虛空（2026-10，巔峰「萬界之巔」）：懸在星骸之上的黑石平台，紫晶與末地燭的光，牆是黑曜石與哭泣黑曜石
            case "void" -> new Theme(new int[] {B.DEEPSLATE_TILES, B.POLISHED_DEEPSLATE, B.DEEPSLATE_TILES, B.PURPUR, B.POLISHED_BLACKSTONE},
                    new int[] {B.OBSIDIAN, B.DEEPSLATE_BRICKS, B.CRYING_OBSIDIAN, B.POLISHED_BLACKSTONE, B.OBSIDIAN},
                    B.CHISELED_DEEPSLATE, B.PURPUR_PILLAR, B.END_ROD, B.AMETHYST, B.SOUL_LANTERN, B.AMETHYST_CLUSTER, B.DEEPSLATE, 6, 9);
            default -> new Theme(new int[] {B.STONE_BRICKS, B.CRACKED_BRICKS, B.ANDESITE, B.GRAVEL, B.MOSSY_BRICKS}, new int[] {B.STONE_BRICKS, B.CRACKED_BRICKS, B.MOSSY_BRICKS},
                    B.STONE_BRICK_SLAB, B.STONE_BRICKS, B.SOUL_LANTERN, B.CHISELED_BRICKS, B.SOUL_LANTERN, B.SKULL, B.STONE, 4, 7);
        };
    }

    /**
     * 蓋一張地圖。
     *
     * @param originX 格子原點（世界座標）
     * @param originZ 同上
     */
    public static void build(final MapLayout layout, final String themeId, final int originX, final int originZ, final BlockSink sink) {
        final Theme theme = theme(themeId);
        final long seed = layout.seed();
        final int y = MapLayout.FLOOR_Y;
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                final int wx = originX + x;
                final int wz = originZ + z;
                if (layout.isFloor(x, z)) {
                    final double n = Noise.fbm(seed, x / 9.0, z / 9.0, 2);
                    final double r = Noise.random01(seed + 1, x, z);
                    final int[] floor = theme.floor();
                    final int pick = (int) Math.floor((n * 0.5 + 0.5) * floor.length * 0.8 + r * floor.length * 0.35) % floor.length;
                    sink.set(wx, y, wz, floor[Math.max(0, pick)]);
                    sink.fill(wx, y - 3, wz, wx, y - 1, wz, theme.base());
                    sink.set(wx, y + CEILING, wz, BARRIER); // 天花板
                } else if (layout.isLiquid(x, z)) {
                    // 液體：水面（岩漿面）跟地板齊平，上面一格到天花板是屏障：看得到、走不進去
                    sink.set(wx, y - 4, wz, theme.base());
                    sink.fill(wx, y - 3, wz, wx, y, wz, theme.liquid());
                    sink.fill(wx, y + 1, wz, wx, y + CEILING, wz, BARRIER);
                } else if (layout.isWall(x, z)) {
                    final int height = theme.minWall() + (int) Math.round((Noise.value(seed + 2, x / 7.0, z / 7.0) * 0.5 + 0.5)
                            * (theme.maxWall() - theme.minWall()));
                    final int[] walls = theme.wall();
                    for (int dy = -3; dy <= height; dy++) {
                        final double r = Noise.random01(seed + 3, x * 131L + dy, z);
                        sink.set(wx, y + dy, wz, walls[(int) (r * walls.length) % walls.length]);
                    }
                    // 城區：面向街道的牆上嵌燈（像房子的窗）
                    if (layout.style() == MapLayout.Style.CITY && height >= 4 && facesFloor(layout, x, z)
                            && Noise.random01(seed + 4, x, z) < 0.12) {
                        sink.set(wx, y + 3, wz, theme.light() == B.LANTERN || theme.light() == B.SOUL_LANTERN ? B.GLOWSTONE : theme.light());
                    }
                    int top = y + height + 1;
                    sink.set(wx, top, wz, theme.wallTop());
                    if ("forest".equals(themeId) || "swamp".equals(themeId)) {
                        // 樹牆：上面再疊一層樹葉
                        top++;
                        sink.set(wx, top, wz, theme.wallTop());
                    }
                    // 牆頂到天花板補滿屏障：爬不上去、也站不到牆頂
                    if (top < y + CEILING) {
                        sink.fill(wx, top + 1, wz, wx, y + CEILING, wz, BARRIER);
                    }
                }
            }
        }
        final SplittableRandom random = new SplittableRandom(seed ^ 0x5A5A5A5AL);
        if (layout.style() == MapLayout.Style.OPEN || layout.style() == MapLayout.Style.CAVERN) {
            scatter(layout, themeId, originX, originZ, sink);
        }
        for (final MapLayout.Room room : layout.rooms()) {
            switch (room.kind()) {
                case START -> startRoom(layout, room, theme, originX, originZ, sink);
                case BOSS -> bossRoom(room, theme, originX, originZ, sink, random);
                default -> decorate(layout, room, theme, themeId, originX, originZ, sink, random);
            }
        }
        // 路標：主路的走道上，地板換成發光的箭頭（一出房間、每個轉彎過後、長的直路每 16 格），岔路與死路沒有
        for (final MapLayout.Guide guide : layout.guides()) {
            for (final int[] cell : GuideArrow.cells(guide.x(), guide.z(), guide.dx(), guide.dz())) {
                sink.set(originX + cell[0], y, originZ + cell[1], B.GUIDE);
            }
        }
    }

    /** 這格牆的東西南北有沒有地板（城區的嵌燈只放在面向街道的那一面）。 */
    private static boolean facesFloor(final MapLayout layout, final int x, final int z) {
        return layout.isFloor(x + 1, z) || layout.isFloor(x - 1, z) || layout.isFloor(x, z + 1) || layout.isFloor(x, z - 1);
    }

    /**
     * 曠野、洞窟：房間以外的空地上零星的小東西（草、枯灌木、菌菇、雪…；不擋路）。
     * 走道中線附近（路標所在）、房間裡（另外裝飾）不放。
     */
    private static void scatter(final MapLayout layout, final String themeId, final int originX, final int originZ, final BlockSink sink) {
        final int y = MapLayout.FLOOR_Y;
        final long seed = layout.seed();
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                if (!layout.isFloor(x, z) || layout.isLane(x, z) || layout.roomAt(x, z) != null) {
                    continue;
                }
                final double r = Noise.random01(seed + 5, x, z);
                if (r >= 0.06) {
                    continue;
                }
                final int prop = switch (themeId) {
                    case "forest" -> r < 0.03 ? B.FERN : B.BUSH;
                    case "swamp" -> r < 0.03 ? B.FERN : B.MOSS_CARPET;
                    case "snow" -> B.SNOW_LAYER;
                    case "beach", "desert" -> r < 0.03 ? B.DEAD_BUSH : B.SHORT_DRY_GRASS;
                    case "fungal" -> r < 0.03 ? B.BROWN_MUSHROOM : B.RED_MUSHROOM;
                    case "cave" -> B.GLOW_LICHEN;
                    case "star", "void" -> B.AMETHYST_CLUSTER;
                    case "volcano" -> r < 0.02 ? B.DEAD_BUSH : Palette.AIR;
                    // 廢墟、墓穴：一點灰燼（以前是蜘蛛網：踩進去速度剩四分之一，跑起來像被拉住）
                    default -> r < 0.02 ? B.GRAY_CARPET : Palette.AIR;
                };
                if (prop != Palette.AIR) {
                    sink.set(originX + x, y + 1, originZ + z, prop);
                }
            }
        }
    }

    /** 起點：四角燈柱、西邊的回程傳送門（黑曜石門框、哭泣黑曜石地板）。 */
    private static void startRoom(final MapLayout layout, final MapLayout.Room room, final Theme theme, final int originX, final int originZ,
                                  final BlockSink sink) {
        final int y = MapLayout.FLOOR_Y;
        final MapLayout.Spot exit = layout.exitPortal();
        final int px = originX + exit.x();
        final int pz = originZ + exit.z();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 1; dy <= 5; dy++) {
                final boolean frame = Math.abs(dz) == 2 || dy == 5;
                sink.set(px - 1, y + dy, pz + dz, B.OBSIDIAN);
                sink.set(px, y + dy, pz + dz, frame ? B.CRYING_OBSIDIAN : B.AIR);
            }
            sink.set(px, y, pz + dz, B.CRYING_OBSIDIAN);
        }
        sink.set(px, y + 6, pz, B.AMETHYST_CLUSTER);
        for (final int[] corner : new int[][] {{-4, -4}, {-4, 4}, {4, -4}, {4, 4}}) {
            final int cx = originX + room.cx() + corner[0];
            final int cz = originZ + room.cz() + corner[1];
            sink.set(cx, y + 1, cz, theme.pillar());
            sink.set(cx, y + 2, cz, theme.pillar());
            sink.set(cx, y + 3, cz, theme.light());
        }
    }

    /** Boss 房：同心圓地板、一圈柱子（頂上有燈）、中間的星骸紋章。 */
    private static void bossRoom(final MapLayout.Room room, final Theme theme, final int originX, final int originZ, final BlockSink sink,
                                 final SplittableRandom random) {
        final int y = MapLayout.FLOOR_Y;
        final int cx = originX + room.cx();
        final int cz = originZ + room.cz();
        for (int dx = -room.halfX(); dx <= room.halfX(); dx++) {
            for (int dz = -room.halfZ(); dz <= room.halfZ(); dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d > room.halfX() + 0.4) {
                    continue;
                }
                final int ring = (int) Math.round(d);
                if (ring > 0 && ring % 5 == 0) {
                    sink.set(cx + dx, y, cz + dz, theme.accent());
                }
            }
        }
        final int pillars = 10;
        for (int i = 0; i < pillars; i++) {
            final double angle = Math.PI * 2 * i / pillars;
            final int px = cx + (int) Math.round(Math.cos(angle) * (room.halfX() - 2));
            final int pz = cz + (int) Math.round(Math.sin(angle) * (room.halfZ() - 2));
            final int height = 4 + random.nextInt(3);
            for (int dy = 1; dy <= height; dy++) {
                sink.set(px, y + dy, pz, theme.pillar());
            }
            sink.set(px, y + height + 1, pz, theme.pillarTop());
        }
        for (final int[] dir : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            sink.set(cx + dir[0], y, cz + dir[1], B.AMETHYST);
        }
        sink.set(cx, y, cz, B.CHISELED_BRICKS);
    }

    /**
     * 這格（或旁邊一格）是怪物群的出生點、或事件可能放的位置（房間中心與 {@link MapLayout#EVENT_OFFSETS}）：
     * 不放柱子與道具（怪不會生在柱子裡、寶箱神龕不會跟柱子疊在一起）。
     */
    static boolean reserved(final java.util.List<MapLayout.Spot> packs, final MapLayout.Room room, final int x, final int z) {
        for (final MapLayout.Spot spot : packs) {
            if (Math.abs(spot.x() - x) <= 1 && Math.abs(spot.z() - z) <= 1) {
                return true;
            }
        }
        for (final int[] offset : MapLayout.EVENT_OFFSETS) {
            if (Math.abs(room.cx() + offset[0] - x) <= 1 && Math.abs(room.cz() + offset[1] - z) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** 一般房間：幾根柱子、燈、主題的小道具。 */
    private static void decorate(final MapLayout layout, final MapLayout.Room room, final Theme theme, final String themeId,
                                 final int originX, final int originZ, final BlockSink sink, final SplittableRandom random) {
        final int y = MapLayout.FLOOR_Y;
        final java.util.List<MapLayout.Spot> packs = layout.packSpots();
        // 四角附近的柱子與燈
        final int ox = Math.max(3, room.halfX() - 3);
        final int oz = Math.max(3, room.halfZ() - 3);
        for (final int[] corner : new int[][] {{-ox, -oz}, {-ox, oz}, {ox, -oz}, {ox, oz}}) {
            if (random.nextDouble() < 0.35) {
                continue;
            }
            final int x = room.cx() + corner[0];
            final int z = room.cz() + corner[1];
            if (!layout.isFloor(x, z) || layout.isCorridor(x, z) || reserved(packs, room, x, z)) {
                continue;
            }
            final int height = 2 + random.nextInt(3);
            for (int dy = 1; dy <= height; dy++) {
                sink.set(originX + x, y + dy, originZ + z, theme.pillar());
            }
            sink.set(originX + x, y + height + 1, originZ + z, theme.light());
        }
        // 小道具：面積越大越多
        final int props = 2 + room.area() / 120;
        for (int i = 0; i < props; i++) {
            final int x = room.cx() + random.nextInt(2 * room.halfX() + 1) - room.halfX();
            final int z = room.cz() + random.nextInt(2 * room.halfZ() + 1) - room.halfZ();
            if (!layout.isFloor(x, z) || layout.isCorridor(x, z) || reserved(packs, room, x, z)) {
                continue;
            }
            final int wx = originX + x;
            final int wz = originZ + z;
            switch (themeId) {
                case "forest" -> {
                    sink.set(wx, y + 1, wz, random.nextBoolean() ? B.FERN : B.BUSH);
                    if (random.nextDouble() < 0.3) {
                        sink.set(wx, y + 1, wz, B.MOSS_CARPET);
                    }
                }
                case "cave" -> {
                    sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? DRIP_UP : B.GLOW_LICHEN);
                }
                case "crypt" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? B.SKULL : B.CANDLE);
                case "star" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.6 ? B.AMETHYST_CLUSTER : B.END_ROD);
                case "snow" -> sink.set(wx, y + 1, wz, B.SNOW_LAYER);
                case "swamp" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? B.FERN : B.MOSS_CARPET);
                case "beach" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? B.BARREL : B.DEAD_BUSH);
                case "desert" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? B.DEAD_BUSH : random.nextDouble() < 0.5 ? B.DECORATED_POT : B.SHORT_DRY_GRASS);
                case "volcano" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? B.BASALT : B.GILDED_BLACKSTONE);
                case "fungal" -> sink.set(wx, y + 1, wz, random.nextDouble() < 0.5 ? B.BROWN_MUSHROOM : B.RED_MUSHROOM);
                default -> {
                    // 廢墟：骨頭、灰燼、碎石（以前六成是蜘蛛網：踩進去速度剩四分之一，跑起來像被拉住）
                    final double roll = random.nextDouble();
                    sink.set(wx, y + 1, wz, roll < 0.3 ? B.SKULL : roll < 0.6 ? B.GRAY_CARPET : B.STONE_BRICK_SLAB);
                }
            }
        }
    }
}
