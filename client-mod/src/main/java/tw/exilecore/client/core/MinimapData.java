package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-02 照站長（C 組）：小地圖的資料（exilecore:minimap，格式見插件的 FeedProtocol）＋走過哪裡（迷霧）。純 Java（測試用）。
 * 進圖時伺服器送一次整張（格子、房間、Boss、事件），之後只送變化；走過的地方（{@link #reveal}）才畫出來。
 */
public final class MinimapData {

    public static final int EMPTY = 0;
    public static final int FLOOR = 1;
    public static final int WALL = 2;
    public static final int LIQUID = 3;

    /** 走過的地方周圍幾格算看過（方塊）。 */
    public static final double REVEAL = 14;

    public record Room(int x, int z, int halfX, int halfZ, boolean round, String kind) {
    }

    public record Event(int index, double x, double z, String kind, String name, int color, int state) {
        public boolean done() {
            return state >= 2;
        }
    }

    public record Boss(double x, double z, String state) {
    }

    public record Mate(String name, double x, double z) {
    }

    /** 2026-11-02 照站長：地圖上的怪（世界座標；rarity 0 普通、1 魔法、2 稀有、3 傳奇）。 */
    public record Mob(int x, int z, int rarity) {

        /** 標示的顏色（RGB）：普通白、魔法藍、稀有黃、傳奇橘（跟怪物名字的顏色一樣）。 */
        public int color() {
            return switch (rarity) {
                case 1 -> 0x8888FF;
                case 2 -> 0xFFFF77;
                case 3 -> 0xAF6025;
                default -> 0xFFFFFF;
            };
        }
    }

    private final int mapId;
    private final int originX;
    private final int originZ;
    private final int res;
    private final int width;
    private final int height;
    private final byte[] tiles;
    private final List<Room> rooms;
    private final Map<Integer, Event> events = new LinkedHashMap<>();
    private Boss boss;
    private List<Mate> party = List.of();
    private List<Mob> mobs = List.of();
    private final BitSet seen;
    /** 看過的地方有變（畫小地圖的圖要重畫）。 */
    private int revision;

    MinimapData(final int mapId, final int originX, final int originZ, final int res, final int width, final int height, final byte[] tiles,
                final List<Room> rooms) {
        this.mapId = mapId;
        this.originX = originX;
        this.originZ = originZ;
        this.res = Math.max(1, res);
        this.width = width;
        this.height = height;
        this.tiles = tiles;
        this.rooms = List.copyOf(rooms);
        this.seen = new BitSet(width * height);
    }

    /** 一個封包：op 是 init、events、boss、party、clear；init 的 data 是新的整張。 */
    public record Packet(String op, MinimapData data, List<Event> events, Boss boss, List<Mate> party, List<Mob> mobs) {

        Packet(final String op, final MinimapData data, final List<Event> events, final Boss boss, final List<Mate> party) {
            this(op, data, events, boss, party, List.of());
        }
    }

    public static Packet parse(final byte[] bytes) {
        try {
            final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            final String op = root.has("op") ? root.get("op").getAsString() : "";
            return switch (op) {
                case "init" -> {
                    final List<Room> rooms = new ArrayList<>();
                    for (final JsonElement element : array(root, "rooms")) {
                        final JsonObject o = element.getAsJsonObject();
                        rooms.add(new Room(o.get("x").getAsInt(), o.get("z").getAsInt(), o.get("hx").getAsInt(), o.get("hz").getAsInt(),
                                o.has("r") && o.get("r").getAsInt() != 0, o.has("k") ? o.get("k").getAsString() : "NORMAL"));
                    }
                    final int w = root.get("w").getAsInt();
                    final int h = root.get("h").getAsInt();
                    if (w <= 0 || h <= 0 || w > 1024 || h > 1024) {
                        yield null;
                    }
                    final byte[] tiles = Base64.getDecoder().decode(root.get("tiles").getAsString());
                    final MinimapData data = new MinimapData(root.get("map").getAsInt(), root.get("ox").getAsInt(), root.get("oz").getAsInt(),
                            root.get("res").getAsInt(), w, h, tiles, rooms);
                    for (final Event event : events(root)) {
                        data.events.put(event.index(), event);
                    }
                    data.boss = root.has("boss") ? boss(root.getAsJsonObject("boss")) : null;
                    yield new Packet(op, data, List.of(), data.boss, List.of());
                }
                case "events" -> new Packet(op, null, events(root), null, List.of());
                case "boss" -> new Packet(op, null, List.of(), boss(root.getAsJsonObject("boss")), List.of());
                case "party" -> {
                    final List<Mate> mates = new ArrayList<>();
                    for (final JsonElement element : array(root, "party")) {
                        final JsonObject o = element.getAsJsonObject();
                        mates.add(new Mate(o.has("n") ? o.get("n").getAsString() : "", o.get("x").getAsDouble(), o.get("z").getAsDouble()));
                    }
                    yield new Packet(op, null, List.of(), null, mates);
                }
                case "mobs" -> {
                    // 2026-11-02 照站長：怪的位置 [x,z,r, x,z,r, …]
                    final List<Mob> list = new ArrayList<>();
                    final JsonArray flat = array(root, "m");
                    for (int i = 0; i + 2 < flat.size(); i += 3) {
                        list.add(new Mob(flat.get(i).getAsInt(), flat.get(i + 1).getAsInt(), flat.get(i + 2).getAsInt()));
                    }
                    yield new Packet(op, null, List.of(), null, List.of(), list);
                }
                case "clear" -> new Packet(op, null, List.of(), null, List.of());
                default -> null;
            };
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 套用 events、boss、party（init、clear 由呼叫的地方換掉整張）。 */
    public void apply(final Packet packet) {
        if (packet == null) {
            return;
        }
        switch (packet.op()) {
            case "events" -> {
                for (final Event event : packet.events()) {
                    events.put(event.index(), event);
                }
            }
            case "boss" -> boss = packet.boss();
            case "party" -> party = List.copyOf(packet.party());
            case "mobs" -> mobs = List.copyOf(packet.mobs());
            default -> {
            }
        }
    }

    /** 這一格（格子座標）是什麼（超出範圍算空的）。 */
    public int tile(final int cx, final int cz) {
        if (cx < 0 || cz < 0 || cx >= width || cz >= height) {
            return EMPTY;
        }
        final int i = cz * width + cx;
        if ((i >> 2) >= tiles.length) {
            return EMPTY;
        }
        return (tiles[i >> 2] >> ((i & 3) * 2)) & 3;
    }

    /** 世界座標換成格子座標（x）。 */
    public double cellX(final double worldX) {
        return (worldX - originX) / res;
    }

    public double cellZ(final double worldZ) {
        return (worldZ - originZ) / res;
    }

    /** 這個世界座標在不在這張圖的格子裡（外面多留一格）。 */
    public boolean contains(final double worldX, final double worldZ) {
        final double x = cellX(worldX);
        final double z = cellZ(worldZ);
        return x >= -1 && z >= -1 && x < width + 1 && z < height + 1;
    }

    /** 走到 (worldX, worldZ)：周圍 radius 格看過了。回傳有沒有新看到的地方。 */
    public boolean reveal(final double worldX, final double worldZ, final double radius) {
        final double cx = cellX(worldX);
        final double cz = cellZ(worldZ);
        final double r = radius / res;
        boolean changed = false;
        for (int z = (int) Math.floor(cz - r); z <= (int) Math.ceil(cz + r); z++) {
            for (int x = (int) Math.floor(cx - r); x <= (int) Math.ceil(cx + r); x++) {
                if (x < 0 || z < 0 || x >= width || z >= height) {
                    continue;
                }
                final double dx = x + 0.5 - cx;
                final double dz = z + 0.5 - cz;
                if (dx * dx + dz * dz <= r * r) {
                    final int i = z * width + x;
                    if (!seen.get(i)) {
                        seen.set(i);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            revision++;
        }
        return changed;
    }

    public boolean seen(final int cx, final int cz) {
        return cx >= 0 && cz >= 0 && cx < width && cz < height && seen.get(cz * width + cx);
    }

    /** 看過的格數佔地板的比例（大地圖寫「探索 x%」）。 */
    public double explored() {
        int floor = 0;
        int known = 0;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (tile(x, z) == FLOOR) {
                    floor++;
                    if (seen(x, z)) {
                        known++;
                    }
                }
            }
        }
        return floor == 0 ? 0 : known / (double) floor;
    }

    /** 這一格畫什麼顏色（ARGB）：沒看過的是透明（迷霧）。 */
    public int color(final int cx, final int cz) {
        return color(cx, cz, false);
    }

    /**
     * 2026-11-02 照站長：showAll = 沒走過的房間也畫（預設），只是暗一點、淡一點（看得出哪裡走過）；這次之前沒走過的是透明（迷霧）。
     */
    public int color(final int cx, final int cz, final boolean showAll) {
        if (!seen(cx, cz)) {
            if (!showAll) {
                return 0;
            }
            return switch (tile(cx, cz)) {
                case FLOOR -> 0x90202430;
                case WALL -> 0xA0686258;
                case LIQUID -> 0x90203A60;
                default -> 0;
            };
        }
        return switch (tile(cx, cz)) {
            case FLOOR -> 0xD0383C48;
            case WALL -> 0xF0A8A090;
            case LIQUID -> 0xD02A5A9A;
            default -> 0;
        };
    }

    /** 整張的顏色（每格一個 ARGB，z 一排一排）：畫成小地圖的圖。 */
    public int[] pixels() {
        return pixels(false);
    }

    public int[] pixels(final boolean showAll) {
        final int[] out = new int[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                out[z * width + x] = color(x, z, showAll);
            }
        }
        return out;
    }

    public int mapId() {
        return mapId;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public int res() {
        return res;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public List<Room> rooms() {
        return rooms;
    }

    public List<Event> events() {
        return new ArrayList<>(events.values());
    }

    public Boss boss() {
        return boss;
    }

    public List<Mate> party() {
        return party;
    }

    public List<Mob> mobs() {
        return mobs;
    }

    public int revision() {
        return revision;
    }

    /** Boss 房（格子裡最大的那個 BOSS）；沒有 null。 */
    public Room bossRoom() {
        for (final Room room : rooms) {
            if ("BOSS".equals(room.kind())) {
                return room;
            }
        }
        return null;
    }

    private static List<Event> events(final JsonObject root) {
        final List<Event> list = new ArrayList<>();
        for (final JsonElement element : array(root, "events")) {
            final JsonObject o = element.getAsJsonObject();
            list.add(new Event(o.get("i").getAsInt(), o.get("x").getAsDouble(), o.get("z").getAsDouble(), o.has("k") ? o.get("k").getAsString() : "",
                    o.has("n") ? o.get("n").getAsString() : "", o.has("c") ? o.get("c").getAsInt() : 0xFFFFFF, o.has("st") ? o.get("st").getAsInt() : 0));
        }
        return list;
    }

    private static Boss boss(final JsonObject o) {
        return o == null ? null : new Boss(o.get("x").getAsDouble(), o.get("z").getAsDouble(), o.has("st") ? o.get("st").getAsString() : "wait");
    }

    private static JsonArray array(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }
}
