package tw.exilecore.map;

import tw.exilecore.worldgen.Noise;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 一張地圖的隨機版面（純邏輯）：6×6 個 32 格的格位（外面再留 {@link #MARGIN} 格），從左邊的起點隨機走一條主路到最遠的 Boss 房，
 * 途中岔出幾條死路（多一些怪、多一些寶）。Boss 房是半徑 {@link #BOSS_RADIUS} 的大圓場（比一個格位大，往離入口的方向挪開一點，
 * 旁邊有別的房間時縮小到留得出牆）。
 * <p>
 * 版面有幾種樣式（{@link Style}，maps.yml 的 layout）：廳堂（方形或圓形的房間、5–7 格寬的 L 形走道）、曠野（大片不規則的空地，
 * 岩石與樹叢擋路）、洞窟（不規則的洞室、S 形的彎曲通道、地下水池）、群島（水上的島嶼，窄窄的堤道相連，四周是水）、
 * 城區（方形廣場與直街，相鄰的廣場常常有別條路繞得到）。每一格是地板、牆、液體（水或岩漿；走不進去）或什麼都沒有；
 * 走得到的地方（地板）與液體外面兩格是牆。障礙物與水池放完之後，從入口走不到的地板會封起來（變成牆），不會有怪卡在裡面。
 * <p>
 * 座標都是相對於格子原點（0 ≤ x、z &lt; {@link #SIZE}），地板在 {@link #FLOOR_Y}。同一個種子、同一種樣式永遠得到同一張圖。
 */
public final class MapLayout {

    public static final int GRID = 6;
    public static final int SLOT = 32;
    /** 格位外面多留的空間（Boss 房比格位大、會凸出去）。 */
    public static final int MARGIN = 16;
    public static final int SIZE = GRID * SLOT + 2 * MARGIN;
    public static final int FLOOR_Y = 64;
    /** Boss 房的半徑（直徑 41 格；旁邊有房間時最小縮到 {@link #BOSS_RADIUS_MIN}）。 */
    public static final int BOSS_RADIUS = 20;
    public static final int BOSS_RADIUS_MIN = 13;
    /** Boss 房中心往離入口的方向挪幾格（入口那邊才留得出走道）。 */
    static final int BOSS_SHIFT = 6;
    /** 牆的厚度（地板外面幾格是牆）。 */
    static final int WALL = 2;
    /** 群島：地板外面幾格是水（再外面才是牆）。 */
    static final int LAKE = 6;
    /** 新樣式的地板、液體離格子邊至少幾格（外面要留得出牆）。 */
    static final int EDGE = WALL + 1;

    public enum Kind { START, NORMAL, SIDE, BOSS }

    /** 版面的樣式（maps.yml 的 layout）。 */
    public enum Style {
        HALLS("廳堂", "房間與走道"),
        OPEN("曠野", "大片空地，岩石與樹叢擋路"),
        CAVERN("洞窟", "不規則的洞室、彎曲的通道與地下水池"),
        ISLANDS("群島", "水上的島嶼，窄窄的堤道相連"),
        CITY("城區", "方形的廣場與直街，好幾條路繞得到");

        private final String displayName;
        private final String description;

        Style(final String displayName, final String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }

        /** 設定檔用的代號（halls、open、cavern、islands、city）。 */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 代號（不分大小寫）換成樣式；不認得回傳 null。 */
        public static Style parse(final String text) {
            if (text == null) {
                return null;
            }
            for (final Style style : values()) {
                if (style.id().equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return style;
                }
            }
            return null;
        }
    }

    /** 一個房間：中心、半寬、是不是圓的。 */
    public record Room(int cx, int cz, int halfX, int halfZ, boolean round, Kind kind) {
        public boolean contains(final int x, final int z) {
            if (round) {
                final double dx = (x - cx) / (halfX + 0.5);
                final double dz = (z - cz) / (halfZ + 0.5);
                return dx * dx + dz * dz <= 1.0;
            }
            return Math.abs(x - cx) <= halfX && Math.abs(z - cz) <= halfZ;
        }

        public int area() {
            return (2 * halfX + 1) * (2 * halfZ + 1);
        }
    }

    /**
     * 主路上兩個房間之間的走道：從 from 中心沿著 points 一段一段走到 to 中心（每一段都是直的、沿 x 或沿 z）。
     * 廳堂、曠野、群島、城區是 L 形（一個轉角 cornerX、cornerZ）；洞窟是 S 形的好幾段。
     */
    record Link(Room from, Room to, int cornerX, int cornerZ, List<Spot> points) {
        Link {
            points = List.copyOf(points);
        }
    }

    /** 路標：地上的箭頭，中心 (x, z)，指向 (dx, dz)（東南西北其中一個）。 */
    public record Guide(int x, int z, int dx, int dz) {
    }

    /** 地圖事件（神龕、寶箱…）放在房間中心加這些偏移的第一個可以放的位置（{@code MapEvents.spotIn}）；裝飾避開這幾格。 */
    public static final int[][] EVENT_OFFSETS = {{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {3, 3}, {-3, -3}};

    /** 同一段直的走道上，兩個路標至少隔幾格。 */
    static final int GUIDE_SPACING = 16;

    /** 一個點（相對座標）。 */
    public record Spot(int x, int z) {
        public double distance(final double otherX, final double otherZ) {
            return Math.hypot(x + 0.5 - otherX, z + 0.5 - otherZ);
        }
    }

    private final long seed;
    private final Style style;
    private final boolean[] floor = new boolean[SIZE * SIZE];
    private final boolean[] wall = new boolean[SIZE * SIZE];
    private final boolean[] corridor = new boolean[SIZE * SIZE];
    /** 液體（水或岩漿；蓋的時候上面是屏障，走不進去）。 */
    private final boolean[] liquid = new boolean[SIZE * SIZE];
    /** 主路、岔路的中線附近、房間中心、起點與 Boss 房：障礙物與水池不放這裡（新樣式才有）。 */
    private boolean[] lane;
    /** 挖過的每一條走道（主路、岔路、城區的繞路；每一條是一串點）。 */
    private final List<List<Spot>> paths = new ArrayList<>();
    /** 已經有走道相連的兩個房間（城區補繞路時不重複）。 */
    private final Set<String> joined = new HashSet<>();
    private final List<Room> rooms = new ArrayList<>();
    private final List<Spot> packSpots = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final List<Guide> guides = new ArrayList<>();
    private Room start;
    private Room boss;
    private int minX = SIZE;
    private int minZ = SIZE;
    private int maxX;
    private int maxZ;

    private MapLayout(final long seed, final Style style) {
        this.seed = seed;
        this.style = style;
    }

    /** 產生一張廳堂樣式的地圖。{@code rooms} 是主路大約要幾個房間（6–12，含起點與 Boss 房）。 */
    public static MapLayout generate(final long seed, final int roomTarget) {
        return generate(seed, roomTarget, Style.HALLS);
    }

    /** 產生一張地圖（{@code style} 為 null 當作廳堂）。 */
    public static MapLayout generate(final long seed, final int roomTarget, final Style style) {
        final SplittableRandom random = new SplittableRandom(seed);
        // 2 = 只有起點跟 Boss 房（極境、劫盜終局：一進去就是一間，下一間就是王房）
        final int target = Math.max(2, Math.min(12, roomTarget));
        // 2026-10-16：Boss 房旁邊（八個方向）有別的房間，王房會被縮到很窄——多走幾次，挑走得到目標、王房旁邊最空的那條
        List<int[]> path = null;
        for (int attempt = 0; attempt < 60 && (path == null || path.size() < target || bossCrowd(path) > 0); attempt++) {
            final List<int[]> candidate = walk(random, target);
            if (path == null || better(candidate, path, target)) {
                path = candidate;
            }
        }
        final MapLayout layout = new MapLayout(seed, style == null ? Style.HALLS : style);
        layout.build(path, random);
        return layout;
    }

    /** 走到目標房間數的優先；一樣的話王房旁邊越空越好；再一樣的話越長越好。 */
    private static boolean better(final List<int[]> candidate, final List<int[]> current, final int target) {
        final boolean reach = candidate.size() >= target;
        final boolean reachNow = current.size() >= target;
        if (reach != reachNow) {
            return reach;
        }
        final int crowd = bossCrowd(candidate);
        final int crowdNow = bossCrowd(current);
        if (crowd != crowdNow) {
            return crowd < crowdNow;
        }
        return candidate.size() > current.size();
    }

    /** 王房（最後一格）旁邊八個方向有幾個主路的格位（不算前一格：從那裡進來）。 */
    static int bossCrowd(final List<int[]> path) {
        if (path.size() < 3) {
            return 0;
        }
        final int[] last = path.get(path.size() - 1);
        int crowd = 0;
        for (int i = 0; i < path.size() - 2; i++) {
            final int[] cell = path.get(i);
            if (Math.abs(cell[0] - last[0]) <= 1 && Math.abs(cell[1] - last[1]) <= 1) {
                crowd++;
            }
        }
        return crowd;
    }

    /** 從左邊一格隨機走到沒走過的鄰格，偏好離起點越來越遠。 */
    private static List<int[]> walk(final SplittableRandom random, final int target) {
        final boolean[][] used = new boolean[GRID][GRID];
        final List<int[]> path = new ArrayList<>();
        int x = 0;
        int z = random.nextInt(GRID);
        final int startZ = z;
        path.add(new int[] {x, z});
        used[x][z] = true;
        while (path.size() < target) {
            final List<int[]> options = new ArrayList<>();
            final List<Integer> weights = new ArrayList<>();
            for (final int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int nx = x + dir[0];
                final int nz = z + dir[1];
                if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID || used[nx][nz]) {
                    continue;
                }
                final int before = x + Math.abs(z - startZ);
                final int after = nx + Math.abs(nz - startZ);
                options.add(new int[] {nx, nz});
                weights.add(after > before ? 4 : dir[0] < 0 ? 1 : 2);
            }
            if (options.isEmpty()) {
                break;
            }
            int total = 0;
            for (final int weight : weights) {
                total += weight;
            }
            int pick = random.nextInt(total);
            int chosen = options.size() - 1;
            for (int i = 0; i < options.size(); i++) {
                pick -= weights.get(i);
                if (pick < 0) {
                    chosen = i;
                    break;
                }
            }
            x = options.get(chosen)[0];
            z = options.get(chosen)[1];
            used[x][z] = true;
            path.add(new int[] {x, z});
        }
        return path;
    }

    private void build(final List<int[]> path, final SplittableRandom random) {
        final Room[][] bySlot = new Room[GRID][GRID];
        for (int i = 0; i < path.size(); i++) {
            final Kind kind = i == 0 ? Kind.START : i == path.size() - 1 ? Kind.BOSS : Kind.NORMAL;
            final Room room = kind == Kind.BOSS ? bossRoom(path.get(i), path.get(i - 1)) : room(path.get(i)[0], path.get(i)[1], kind, random);
            bySlot[path.get(i)[0]][path.get(i)[1]] = room;
            rooms.add(room);
            if (kind == Kind.START) {
                start = room;
            } else if (kind == Kind.BOSS) {
                boss = room;
            }
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            links.add(connect(bySlot[path.get(i)[0]][path.get(i)[1]], bySlot[path.get(i + 1)[0]][path.get(i + 1)[1]], random, true));
        }
        // 岔路：從主路中間的房間往沒用過的鄰格多開一兩間
        final int branches = 2 + random.nextInt(2);
        for (int b = 0; b < branches; b++) {
            for (int attempt = 0; attempt < 12; attempt++) {
                final int index = 1 + random.nextInt(Math.max(1, path.size() - 2));
                final int[] from = path.get(Math.min(index, path.size() - 1));
                final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                final int[] dir = dirs[random.nextInt(4)];
                final int nx = from[0] + dir[0];
                final int nz = from[1] + dir[1];
                if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID || bySlot[nx][nz] != null) {
                    continue;
                }
                final int[] bossSlot = path.get(path.size() - 1);
                if (Math.abs(nx - bossSlot[0]) <= 1 && Math.abs(nz - bossSlot[1]) <= 1) {
                    continue; // 2026-10-16：岔路不開在王房旁邊（不然王房要縮小讓出牆）
                }
                final Room fromRoom = bySlot[from[0]][from[1]];
                if (fromRoom.kind() != Kind.NORMAL) {
                    continue;
                }
                final Room side = room(nx, nz, Kind.SIDE, random);
                bySlot[nx][nz] = side;
                rooms.add(side);
                connect(fromRoom, side, random, false);
                break;
            }
        }
        if (style == Style.CITY) {
            loops(bySlot, random);
        }
        fitBoss();
        if (style == Style.OPEN) {
            meadows(random);
        }
        for (final Room room : rooms) {
            carveRoom(room);
        }
        if (style != Style.HALLS) {
            protectLanes();
            if (style == Style.OPEN) {
                obstacles(random);
            }
            if (style == Style.OPEN || style == Style.CAVERN) {
                pools(random);
            }
            if (style == Style.ISLANDS) {
                lake();
            }
            seal();
        }
        buildWalls();
        placePacks(random);
        placeGuides();
    }

    /** 格位中心的座標。 */
    static int slotCenter(final int slot) {
        return MARGIN + slot * SLOT + SLOT / 2;
    }

    private Room room(final int slotX, final int slotZ, final Kind kind, final SplittableRandom random) {
        final int centerX = slotCenter(slotX) + random.nextInt(5) - 2;
        final int centerZ = slotCenter(slotZ) + random.nextInt(5) - 2;
        if (kind == Kind.BOSS) {
            throw new IllegalArgumentException("Boss 房用 bossRoom");
        }
        if (kind == Kind.START) {
            return new Room(centerX, centerZ, 6, 6, false, kind);
        }
        final boolean side = kind == Kind.SIDE;
        return switch (style) {
            case HALLS -> side ? new Room(centerX, centerZ, 6 + random.nextInt(4), 6 + random.nextInt(4), random.nextDouble() < 0.4, kind)
                    : new Room(centerX, centerZ, 8 + random.nextInt(5), 8 + random.nextInt(5), random.nextDouble() < 0.3, kind);
            // 曠野：大片的空地（不規則的圓）
            case OPEN -> side ? new Room(centerX, centerZ, 7 + random.nextInt(3), 7 + random.nextInt(3), true, kind)
                    : new Room(centerX, centerZ, 9 + random.nextInt(4), 9 + random.nextInt(4), true, kind);
            // 洞窟：大小不一的洞室
            case CAVERN -> side ? new Room(centerX, centerZ, 6 + random.nextInt(3), 6 + random.nextInt(3), true, kind)
                    : new Room(centerX, centerZ, 8 + random.nextInt(4), 8 + random.nextInt(4), true, kind);
            // 群島：島
            case ISLANDS -> side ? new Room(centerX, centerZ, 6 + random.nextInt(3), 6 + random.nextInt(3), true, kind)
                    : new Room(centerX, centerZ, 8 + random.nextInt(4), 8 + random.nextInt(4), true, kind);
            // 城區：方形的廣場
            case CITY -> side ? new Room(centerX, centerZ, 5 + random.nextInt(3), 5 + random.nextInt(3), false, kind)
                    : new Room(centerX, centerZ, 7 + random.nextInt(4), 7 + random.nextInt(4), false, kind);
        };
    }

    /** Boss 房：大圓場，中心往離入口（前一個格位）的方向挪 {@link #BOSS_SHIFT} 格。 */
    private static Room bossRoom(final int[] slot, final int[] previous) {
        final int dx = Integer.signum(slot[0] - previous[0]);
        final int dz = Integer.signum(slot[1] - previous[1]);
        return new Room(slotCenter(slot[0]) + dx * BOSS_SHIFT, slotCenter(slot[1]) + dz * BOSS_SHIFT, BOSS_RADIUS, BOSS_RADIUS, true, Kind.BOSS);
    }

    /** Boss 房旁邊有別的房間（主路繞回來、岔路）時縮小半徑，跟它們之間至少留得出兩格牆。 */
    private void fitBoss() {
        int radius = BOSS_RADIUS;
        for (final Room other : rooms) {
            if (other == boss || other.kind() == Kind.BOSS) {
                continue;
            }
            final int gapX = Math.max(0, Math.abs(other.cx() - boss.cx()) - other.halfX());
            final int gapZ = Math.max(0, Math.abs(other.cz() - boss.cz()) - other.halfZ());
            final int distance = (int) Math.floor(Math.hypot(gapX, gapZ)); // 圓心到那個房間外框最近的距離
            radius = Math.min(radius, distance - WALL * 2 - 1);
        }
        radius = Math.max(BOSS_RADIUS_MIN, radius);
        if (radius == boss.halfX()) {
            return;
        }
        final Room resized = new Room(boss.cx(), boss.cz(), radius, radius, true, Kind.BOSS);
        rooms.set(rooms.indexOf(boss), resized);
        for (int i = 0; i < links.size(); i++) {
            final Link link = links.get(i);
            if (link.to() == boss) {
                links.set(i, new Link(link.from(), resized, link.cornerX(), link.cornerZ(), link.points()));
            }
        }
        boss = resized;
    }

    /**
     * 兩個房間之間的走道：廳堂、曠野、群島、城區是 L 形（先沿 x 再沿 z，或反過來），洞窟是 S 形。
     * 寬度：廳堂 5 或 7 格、曠野 9–11 格、群島的主路 5 格（岔路 3–5 格的堤道）、城區 5 或 7 格、洞窟 5–9 格忽寬忽窄。
     *
     * @param main 主路（群島的主路要放得下路標）
     */
    private Link connect(final Room a, final Room b, final SplittableRandom random, final boolean main) {
        joined.add(pairKey(a, b));
        if (style == Style.CAVERN) {
            final List<Spot> points = winding(a, b, random);
            carveWorm(points, 2);
            paths.add(points);
            return new Link(a, b, points.get(1).x(), points.get(1).z(), points);
        }
        final int half = switch (style) {
            case OPEN -> 4 + random.nextInt(2);
            case ISLANDS -> main ? 2 : 1 + random.nextInt(2);
            case CITY -> random.nextBoolean() ? 3 : 2;
            default -> random.nextDouble() < 0.35 ? 3 : 2;
        };
        final boolean xFirst = random.nextBoolean();
        final int cornerX = xFirst ? b.cx() : a.cx();
        final int cornerZ = xFirst ? a.cz() : b.cz();
        carveLine(a.cx(), a.cz(), cornerX, cornerZ, half);
        carveLine(cornerX, cornerZ, b.cx(), b.cz(), half);
        final List<Spot> points = List.of(new Spot(a.cx(), a.cz()), new Spot(cornerX, cornerZ), new Spot(b.cx(), b.cz()));
        paths.add(points);
        return new Link(a, b, cornerX, cornerZ, points);
    }

    private static String pairKey(final Room a, final Room b) {
        final String one = a.cx() + "," + a.cz();
        final String two = b.cx() + "," + b.cz();
        return one.compareTo(two) < 0 ? one + "|" + two : two + "|" + one;
    }

    /**
     * 洞窟的 S 形通道：沿主軸走三到四成 → 往旁邊偏 6–9 格 → 沿主軸走到六到七成 → 回到目標那一排 → 走到目標。
     * 每一段都是直的（路標放得上去），挖的時候寬度忽寬忽窄（{@link #carveWorm}）。
     */
    private List<Spot> winding(final Room a, final Room b, final SplittableRandom random) {
        final List<Spot> points = new ArrayList<>();
        points.add(new Spot(a.cx(), a.cz()));
        final boolean horizontal = Math.abs(b.cx() - a.cx()) >= Math.abs(b.cz() - a.cz());
        final int along = horizontal ? b.cx() - a.cx() : b.cz() - a.cz();
        final int first = along * (30 + random.nextInt(11)) / 100;
        final int second = along * (60 + random.nextInt(11)) / 100;
        final int offset = (random.nextBoolean() ? 1 : -1) * (6 + random.nextInt(4));
        if (horizontal) {
            final int lane = clampLane(a.cz() + offset);
            points.add(new Spot(a.cx() + first, a.cz()));
            points.add(new Spot(a.cx() + first, lane));
            points.add(new Spot(a.cx() + second, lane));
            points.add(new Spot(a.cx() + second, b.cz()));
        } else {
            final int lane = clampLane(a.cx() + offset);
            points.add(new Spot(a.cx(), a.cz() + first));
            points.add(new Spot(lane, a.cz() + first));
            points.add(new Spot(lane, a.cz() + second));
            points.add(new Spot(b.cx(), a.cz() + second));
        }
        points.add(new Spot(b.cx(), b.cz()));
        return points;
    }

    private static int clampLane(final int value) {
        return Math.max(EDGE + 6, Math.min(SIZE - EDGE - 7, value));
    }

    /** 沿著一串點挖通道：每一格挖一個圓，半徑 base 到 base + 2（照雜訊，忽寬忽窄）。 */
    private void carveWorm(final List<Spot> points, final int base) {
        for (int i = 0; i + 1 < points.size(); i++) {
            final Spot a = points.get(i);
            final Spot b = points.get(i + 1);
            final int dx = Integer.signum(b.x() - a.x());
            final int dz = Integer.signum(b.z() - a.z());
            final int length = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
            for (int t = 0; t <= length; t++) {
                final int x = a.x() + dx * t;
                final int z = a.z() + dz * t;
                final double n = Noise.value(seed ^ 0xC0FFEEL, x / 5.0, z / 5.0);
                carveDisc(x, z, base + (int) Math.round((n * 0.5 + 0.5) * 2.0), true);
            }
        }
    }

    /** 挖一個圓（新樣式用；離格子邊太近的格子不挖）。 */
    private void carveDisc(final int cx, final int cz, final int radius, final boolean asCorridor) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                final int dx = x - cx;
                final int dz = z - cz;
                if (dx * dx + dz * dz > radius * radius + radius || !edgeOk(x, z)) {
                    continue;
                }
                final int i = index(x, z);
                if (asCorridor && !floor[i]) {
                    corridor[i] = true;
                }
                floor[i] = true;
                grow(x, z);
            }
        }
    }

    /** 新樣式的地板與液體離格子邊夠遠（外面留得出牆）。 */
    private static boolean edgeOk(final int x, final int z) {
        return x >= EDGE && z >= EDGE && x < SIZE - EDGE && z < SIZE - EDGE;
    }

    /** 城區：相鄰的兩個廣場（不含起點與 Boss 房）有一半的機會多一條街，繞得過去。 */
    private void loops(final Room[][] bySlot, final SplittableRandom random) {
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                final Room room = bySlot[x][z];
                if (room == null || room.kind() == Kind.START || room.kind() == Kind.BOSS) {
                    continue;
                }
                for (final int[] dir : new int[][] {{1, 0}, {0, 1}}) {
                    final int nx = x + dir[0];
                    final int nz = z + dir[1];
                    if (nx >= GRID || nz >= GRID) {
                        continue;
                    }
                    final Room other = bySlot[nx][nz];
                    if (other == null || other.kind() == Kind.START || other.kind() == Kind.BOSS || joined.contains(pairKey(room, other))) {
                        continue;
                    }
                    if (random.nextDouble() < 0.5) {
                        connect(room, other, random, false);
                    }
                }
            }
        }
    }

    /** 曠野：主路的每一段旁邊多挖兩塊不規則的空地（讓走道變成一大片原野）。 */
    private void meadows(final SplittableRandom random) {
        for (final Link link : links) {
            final List<Spot> points = link.points();
            for (int i = 0; i + 1 < points.size(); i++) {
                final Spot a = points.get(i);
                final Spot b = points.get(i + 1);
                final int length = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
                if (length < 8) {
                    continue;
                }
                // 前半段、後半段各一塊，一左一右
                final int firstSide = random.nextBoolean() ? 1 : -1;
                for (int k = 0; k < 2; k++) {
                    final double t = (k == 0 ? 0.2 : 0.55) + random.nextDouble() * 0.25;
                    final int side = (k == 0 ? firstSide : -firstSide) * (4 + random.nextInt(6));
                    final int radius = 7 + random.nextInt(4);
                    int x = (int) Math.round(a.x() + (b.x() - a.x()) * t);
                    int z = (int) Math.round(a.z() + (b.z() - a.z()) * t);
                    if (a.z() == b.z()) {
                        z += side;
                    } else {
                        x += side;
                    }
                    if (Math.hypot(x - boss.cx(), z - boss.cz()) < boss.halfX() + radius) {
                        continue; // Boss 場地保持圓的
                    }
                    carveBlob(x, z, radius, 0.25);
                }
            }
        }
    }

    /** 不規則的一塊空地（半徑照雜訊上下 wobble 成）。 */
    private void carveBlob(final int cx, final int cz, final int radius, final double wobble) {
        final int reach = radius + (int) Math.ceil(radius * wobble) + 1;
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                if (!edgeOk(x, z)) {
                    continue;
                }
                final double d = Math.hypot(x - cx, z - cz) / (radius + 0.5);
                if (d <= 1 + wobble * Noise.fbm(seed ^ 0x51EDL, x / 7.0, z / 7.0, 2)) {
                    floor[index(x, z)] = true;
                    grow(x, z);
                }
            }
        }
    }

    /**
     * 路標：主路的每一段走道（起點 → … → Boss 房），一出房間、每個轉彎過後各一個箭頭，很長的直路每 {@link #GUIDE_SPACING} 格再一個。
     * 岔路、死路不放。箭頭整個要在走道的地板上（不在任何房間裡），也不跟別的箭頭重疊。
     */
    private void placeGuides() {
        final java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (final Link link : links) {
            final int before = guides.size();
            final int[][] legs = legs(link);
            for (final int[] leg : legs) {
                final int length = Math.abs(leg[2] - leg[0]) + Math.abs(leg[3] - leg[1]);
                if (length == 0) {
                    continue;
                }
                final int dx = Integer.signum(leg[2] - leg[0]);
                final int dz = Integer.signum(leg[3] - leg[1]);
                int last = -GUIDE_SPACING;
                // 箭頭的中心離這一段的起點至少 2 格、離終點至少 3 格（轉角、下一個房間留給下一個箭頭）
                for (int t = 2; t <= length - 3; t++) {
                    if (t - last < GUIDE_SPACING) {
                        continue;
                    }
                    final int x = leg[0] + dx * t;
                    final int z = leg[1] + dz * t;
                    final List<int[]> cells = tw.exilecore.worldgen.GuideArrow.cells(x, z, dx, dz);
                    if (!guideFits(cells, taken)) {
                        continue;
                    }
                    for (final int[] cell : cells) {
                        taken.add(index(cell[0], cell[1]));
                    }
                    guides.add(new Guide(x, z, dx, dz));
                    last = t;
                }
            }
            if (guides.size() == before) {
                shortLinkGuide(link, legs, taken);
            }
        }
    }

    /** 一條走道的每一段：{起點 x, 起點 z, 終點 x, 終點 z}。 */
    static int[][] legs(final Link link) {
        final List<Spot> points = link.points();
        final int[][] legs = new int[Math.max(0, points.size() - 1)][];
        for (int i = 0; i + 1 < points.size(); i++) {
            legs[i] = new int[] {points.get(i).x(), points.get(i).z(), points.get(i + 1).x(), points.get(i + 1).z()};
        }
        return legs;
    }

    /**
     * 兩個房間幾乎貼在一起、中間的走道放不下整個箭頭：箭頭可以有一部分在這兩個房間的地板上，
     * 放在走出 from 房間的那個位置附近（還是不能碰到別的房間、別的箭頭）。
     */
    private void shortLinkGuide(final Link link, final int[][] legs, final java.util.Set<Integer> taken) {
        for (final int[] leg : legs) {
            final int length = Math.abs(leg[2] - leg[0]) + Math.abs(leg[3] - leg[1]);
            if (length == 0) {
                continue;
            }
            final int dx = Integer.signum(leg[2] - leg[0]);
            final int dz = Integer.signum(leg[3] - leg[1]);
            int exit = -1;
            for (int t = 0; t <= length; t++) {
                final Room room = roomAt(leg[0] + dx * t, leg[1] + dz * t);
                if (room == null) {
                    exit = t;
                    break;
                }
            }
            if (exit < 0) {
                continue;
            }
            for (int shift = 0; shift <= 3; shift++) {
                for (final int t : new int[] {exit + shift, exit - shift}) {
                    if (t < 0 || t > length) {
                        continue;
                    }
                    final int x = leg[0] + dx * t;
                    final int z = leg[1] + dz * t;
                    final List<int[]> cells = tw.exilecore.worldgen.GuideArrow.cells(x, z, dx, dz);
                    boolean fits = true;
                    for (final int[] cell : cells) {
                        final Room room = roomAt(cell[0], cell[1]);
                        if (!isFloor(cell[0], cell[1]) || (room != null && room != link.from() && room != link.to())
                                || taken.contains(index(cell[0], cell[1]))) {
                            fits = false;
                            break;
                        }
                    }
                    if (fits) {
                        for (final int[] cell : cells) {
                            taken.add(index(cell[0], cell[1]));
                        }
                        guides.add(new Guide(x, z, dx, dz));
                        return;
                    }
                }
            }
        }
    }

    private boolean guideFits(final List<int[]> cells, final java.util.Set<Integer> taken) {
        for (final int[] cell : cells) {
            if (!isFloor(cell[0], cell[1]) || roomAt(cell[0], cell[1]) != null || taken.contains(index(cell[0], cell[1]))) {
                return false;
            }
        }
        return true;
    }

    private void carveLine(final int x1, final int z1, final int x2, final int z2, final int half) {
        for (int x = Math.min(x1, x2) - half; x <= Math.max(x1, x2) + half; x++) {
            for (int z = Math.min(z1, z2) - half; z <= Math.max(z1, z2) + half; z++) {
                if (inside(x, z)) {
                    floor[index(x, z)] = true;
                    corridor[index(x, z)] = true;
                    grow(x, z);
                }
            }
        }
    }

    private void carveRoom(final Room room) {
        // 曠野、洞窟、群島的房間邊緣不規則（起點與 Boss 房照舊是方的、圓的）
        final double wobble = room.kind() == Kind.START || room.kind() == Kind.BOSS ? 0 : switch (style) {
            case OPEN -> 0.15;
            case CAVERN -> 0.28;
            case ISLANDS -> 0.2;
            default -> 0;
        };
        final int pad = wobble <= 0 ? 0 : (int) Math.ceil(Math.max(room.halfX(), room.halfZ()) * wobble) + 1;
        for (int x = room.cx() - room.halfX() - pad; x <= room.cx() + room.halfX() + pad; x++) {
            for (int z = room.cz() - room.halfZ() - pad; z <= room.cz() + room.halfZ() + pad; z++) {
                if (!inside(x, z)) {
                    continue;
                }
                final boolean in;
                if (wobble <= 0) {
                    in = room.contains(x, z);
                } else {
                    final double dx = (x - room.cx()) / (room.halfX() + 0.5);
                    final double dz = (z - room.cz()) / (room.halfZ() + 0.5);
                    in = edgeOk(x, z) && Math.sqrt(dx * dx + dz * dz) <= 1 + wobble * Noise.fbm(seed ^ 0x51EDL, x / 7.0, z / 7.0, 2);
                }
                if (in) {
                    floor[index(x, z)] = true;
                    corridor[index(x, z)] = false;
                    grow(x, z);
                }
            }
        }
    }

    /**
     * 不放障礙物、水池的地方：每一條走道的中線兩邊幾格（曠野 4、其他 2）、房間中心 5 格內（洞窟 4 格）、起點房間與 Boss 場地（再外面 3 格）。
     * 主路的中線一定走得通：從入口走得到每一個房間。
     */
    private void protectLanes() {
        lane = new boolean[SIZE * SIZE];
        final int width = style == Style.OPEN ? 4 : 2;
        for (final List<Spot> points : paths) {
            for (int i = 0; i + 1 < points.size(); i++) {
                final Spot a = points.get(i);
                final Spot b = points.get(i + 1);
                final int dx = Integer.signum(b.x() - a.x());
                final int dz = Integer.signum(b.z() - a.z());
                final int length = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
                for (int t = 0; t <= length; t++) {
                    markLane(a.x() + dx * t, a.z() + dz * t, width);
                }
            }
        }
        for (final Room room : rooms) {
            final int radius = room.kind() == Kind.START ? 10 : room.kind() == Kind.BOSS ? room.halfX() + 3 : style == Style.CAVERN ? 4 : 5;
            markLane(room.cx(), room.cz(), radius);
        }
    }

    private void markLane(final int cx, final int cz, final int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (inside(x, z) && (x - cx) * (x - cx) + (z - cz) * (z - cz) <= radius * radius + radius) {
                    lane[index(x, z)] = true;
                }
            }
        }
    }

    /** 這個圓（半徑 radius）裡全部是地板、而且不在走道中線附近（障礙物與水池只放在這種地方）。 */
    private boolean clearDisc(final int cx, final int cz, final int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > radius * radius + radius) {
                    continue;
                }
                if (!isFloor(x, z) || lane[index(x, z)]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 曠野：空地上零星的岩石、樹叢（3–5 格的實心一團；四周都是地板，不會把路堵死）。 */
    private void obstacles(final SplittableRandom random) {
        for (int x = EDGE + 2; x < SIZE - EDGE - 2; x += 7) {
            for (int z = EDGE + 2; z < SIZE - EDGE - 2; z += 7) {
                if (random.nextDouble() >= 0.5) {
                    continue;
                }
                final int ox = x + random.nextInt(5) - 2;
                final int oz = z + random.nextInt(5) - 2;
                final int radius = random.nextDouble() < 0.55 ? 1 : 2;
                if (!clearDisc(ox, oz, radius + 1)) {
                    continue;
                }
                for (int px = ox - radius; px <= ox + radius; px++) {
                    for (int pz = oz - radius; pz <= oz + radius; pz++) {
                        if ((px - ox) * (px - ox) + (pz - oz) * (pz - oz) <= radius * radius + radius) {
                            floor[index(px, pz)] = false;
                            corridor[index(px, pz)] = false;
                        }
                    }
                }
            }
        }
    }

    /** 曠野、洞窟：空地上偏旁邊的地方放幾個小水池（洞窟 3–5 格寬、曠野 5–7 格寬；四周都是地板，不在走道中線附近）。 */
    private void pools(final SplittableRandom random) {
        final int want = style == Style.CAVERN ? 2 + random.nextInt(3) : 1 + random.nextInt(2);
        int made = 0;
        for (int attempt = 0; attempt < 400 && made < want; attempt++) {
            final int px = minX + random.nextInt(Math.max(1, maxX - minX + 1));
            final int pz = minZ + random.nextInt(Math.max(1, maxZ - minZ + 1));
            final int radius = style == Style.CAVERN ? 1 + random.nextInt(2) : 2 + random.nextInt(2);
            // 水池可以貼著牆（洞窟的水常常在角落）；被它圍起來走不到的地板最後會封掉
            if (!clearDisc(px, pz, style == Style.CAVERN ? radius : radius + 1)) {
                continue;
            }
            for (int x = px - radius; x <= px + radius; x++) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    if ((x - px) * (x - px) + (z - pz) * (z - pz) <= radius * radius + radius) {
                        floor[index(x, z)] = false;
                        corridor[index(x, z)] = false;
                        liquid[index(x, z)] = true;
                    }
                }
            }
            made++;
        }
    }

    /** 群島：地板外面 {@link #LAKE} 格內都是水（離格子邊太近的除外），再外面才是牆。 */
    private void lake() {
        final int reach = LAKE * LAKE + LAKE;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (!floor[index(x, z)]) {
                    continue;
                }
                for (int dx = -LAKE; dx <= LAKE; dx++) {
                    for (int dz = -LAKE; dz <= LAKE; dz++) {
                        final int nx = x + dx;
                        final int nz = z + dz;
                        if (dx * dx + dz * dz <= reach && edgeOk(nx, nz) && !floor[index(nx, nz)] && !liquid[index(nx, nz)]) {
                            liquid[index(nx, nz)] = true;
                            grow(nx, nz);
                        }
                    }
                }
            }
        }
    }

    /** 從入口走不到的地板（被障礙物、水池圍起來的小角落）封起來：不會有怪物、事件放在走不到的地方。 */
    private void seal() {
        final boolean[] seen = new boolean[SIZE * SIZE];
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        final Spot entrance = entrance();
        if (!isFloor(entrance.x(), entrance.z())) {
            return;
        }
        seen[index(entrance.x(), entrance.z())] = true;
        queue.add(new int[] {entrance.x(), entrance.z()});
        while (!queue.isEmpty()) {
            final int[] p = queue.poll();
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int x = p[0] + d[0];
                final int z = p[1] + d[1];
                if (isFloor(x, z) && !seen[index(x, z)]) {
                    seen[index(x, z)] = true;
                    queue.add(new int[] {x, z});
                }
            }
        }
        for (int i = 0; i < floor.length; i++) {
            if (floor[i] && !seen[i]) {
                floor[i] = false;
                corridor[i] = false;
            }
        }
    }

    private void grow(final int x, final int z) {
        minX = Math.min(minX, x);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxZ = Math.max(maxZ, z);
    }

    /** 牆：地板與液體外面兩格（不含對角的圓角）。 */
    private void buildWalls() {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (floor[index(x, z)] || liquid[index(x, z)]) {
                    continue;
                }
                boolean near = false;
                for (int dx = -WALL; dx <= WALL && !near; dx++) {
                    for (int dz = -WALL; dz <= WALL && !near; dz++) {
                        final int nx = x + dx;
                        final int nz = z + dz;
                        if (inside(nx, nz) && (floor[index(nx, nz)] || liquid[index(nx, nz)]) && dx * dx + dz * dz <= WALL * WALL + 1) {
                            near = true;
                        }
                    }
                }
                wall[index(x, z)] = near;
            }
        }
    }

    /** 怪物群：一般房間依面積 1–4 群、死路 1–2 群、三成的走道轉角一群；起點與 Boss 房沒有。 */
    private void placePacks(final SplittableRandom random) {
        for (final Room room : rooms) {
            if (room.kind() == Kind.START || room.kind() == Kind.BOSS) {
                continue;
            }
            final int count = room.kind() == Kind.SIDE ? 1 + random.nextInt(2) : Math.max(1, Math.min(4, room.area() / 140));
            for (int i = 0; i < count; i++) {
                for (int attempt = 0; attempt < 10; attempt++) {
                    final int x = room.cx() + random.nextInt(2 * Math.max(1, room.halfX() - 2) + 1) - Math.max(1, room.halfX() - 2);
                    final int z = room.cz() + random.nextInt(2 * Math.max(1, room.halfZ() - 2) + 1) - Math.max(1, room.halfZ() - 2);
                    if (isFloor(x, z) && farFromPacks(x, z, 6)) {
                        packSpots.add(new Spot(x, z));
                        break;
                    }
                }
            }
        }
        // 走道上的零星怪物群
        for (int x = 0; x < SIZE; x += 11) {
            for (int z = 0; z < SIZE; z += 11) {
                if (corridor[index(x, z)] && random.nextDouble() < 0.3 && farFromPacks(x, z, 14)
                        && start.cx() + 12 < x + Math.abs(z - start.cz()) * 2 && !boss.contains(x, z)) {
                    packSpots.add(new Spot(x, z));
                }
            }
        }
        // 曠野：房間與走道以外的空地上也有
        if (style == Style.OPEN) {
            for (int x = 0; x < SIZE; x += 13) {
                for (int z = 0; z < SIZE; z += 13) {
                    if (isFloor(x, z) && !corridor[index(x, z)] && roomAt(x, z) == null && random.nextDouble() < 0.35
                            && farFromPacks(x, z, 12) && start.cx() + 12 < x + Math.abs(z - start.cz()) * 2 && !boss.contains(x, z)) {
                        packSpots.add(new Spot(x, z));
                    }
                }
            }
        }
    }

    private boolean farFromPacks(final int x, final int z, final double distance) {
        for (final Spot spot : packSpots) {
            if (Math.hypot(spot.x() - x, spot.z() - z) < distance) {
                return false;
            }
        }
        return true;
    }

    // ---- 查詢 ----

    public long seed() {
        return seed;
    }

    static boolean inside(final int x, final int z) {
        return x >= 0 && z >= 0 && x < SIZE && z < SIZE;
    }

    private static int index(final int x, final int z) {
        return x * SIZE + z;
    }

    public boolean isFloor(final int x, final int z) {
        return inside(x, z) && floor[index(x, z)];
    }

    public boolean isWall(final int x, final int z) {
        return inside(x, z) && wall[index(x, z)];
    }

    public boolean isCorridor(final int x, final int z) {
        return inside(x, z) && corridor[index(x, z)];
    }

    /** 液體（水或岩漿，照主題）：走不進去，蓋的時候上面是屏障。 */
    public boolean isLiquid(final int x, final int z) {
        return inside(x, z) && liquid[index(x, z)];
    }

    /** 走道中線附近、房間中心（新樣式才有；裝飾不放這裡，路標都在這裡面）。 */
    public boolean isLane(final int x, final int z) {
        return lane != null && inside(x, z) && lane[index(x, z)];
    }

    public Style style() {
        return style;
    }

    public List<Room> rooms() {
        return List.copyOf(rooms);
    }

    public Room start() {
        return start;
    }

    public Room boss() {
        return boss;
    }

    public List<Spot> packSpots() {
        return List.copyOf(packSpots);
    }

    /** 主路上的路標（地上的箭頭）。 */
    public List<Guide> guides() {
        return List.copyOf(guides);
    }

    /** 挖過幾條走道（主路、岔路、城區的繞路；單元測試用）。 */
    int pathCount() {
        return paths.size();
    }

    /** 主路的走道（起點 → … → Boss 房；單元測試用）。 */
    List<Link> links() {
        return List.copyOf(links);
    }

    /** 玩家進來的位置：起點房間中心往東兩格。 */
    public Spot entrance() {
        return new Spot(start.cx() + 2, start.cz());
    }

    /**
     * 2026-10-23：進圖時面向哪裡（Minecraft 的 yaw）：朝主路第一段走道的方向（以前一律朝東，主路往別的方向走時一進來就對著牆）。
     */
    public float entranceYaw() {
        for (final Link link : links) {
            if (link.from() != start) {
                continue;
            }
            // 沿著走道從房間中心一格一格走，走出起點房間的那一格就是出口：朝出口看
            //（第一段可能只是一兩格的小轉折，只看第一個點會看錯方向）
            int x = start.cx();
            int z = start.cz();
            final List<Spot> points = new ArrayList<>(link.points());
            points.add(new Spot(link.to().cx(), link.to().cz()));
            for (final Spot point : points) {
                while (x != point.x() || z != point.z()) {
                    x += Integer.signum(point.x() - x);
                    if (x == point.x()) {
                        z += Integer.signum(point.z() - z);
                    }
                    if (!start.contains(x, z)) {
                        return yaw(x - start.cx(), z - start.cz());
                    }
                }
            }
            return yaw(link.to().cx() - start.cx(), link.to().cz() - start.cz());
        }
        return -90f; // 沒有主路（只有一個房間）：照舊朝東
    }

    /** 往 (dx, dz) 看的 yaw（東 −90、南 0、西 90、北 180）。 */
    static float yaw(final int dx, final int dz) {
        if (dx == 0 && dz == 0) {
            return -90f;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** 回營地的傳送門：起點房間的西邊（門洞在 x = cx − 4，z 在 cz−1..cz+1）。 */
    public Spot exitPortal() {
        return new Spot(start.cx() - 4, start.cz());
    }

    /** 蓋東西的範圍（相對座標，含牆）。 */
    public int minX() {
        return Math.max(0, minX - WALL);
    }

    public int minZ() {
        return Math.max(0, minZ - WALL);
    }

    public int maxX() {
        return Math.min(SIZE - 1, maxX + WALL);
    }

    public int maxZ() {
        return Math.min(SIZE - 1, maxZ + WALL);
    }

    /** 房間總數（含起點與 Boss 房）。 */
    public int roomCount() {
        return rooms.size();
    }

    /** 這一格在哪個房間裡（不在房間裡回傳 null）。 */
    public Room roomAt(final int x, final int z) {
        for (final Room room : rooms) {
            if (room.contains(x, z)) {
                return room;
            }
        }
        return null;
    }
}
