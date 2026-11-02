package tw.exilecore.clientmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.exilecore.map.MapLayout;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 2026-11-02 照站長（C 組）：星骸輔助模組的預警、小地圖、死亡回顧三個新封包的格式。純函式，不依賴 Bukkit（測試用）。
 * 這次之前 Boss 預警走原版的大標題（只有一個槽，後送的蓋掉前面的）、死亡回顧印在聊天欄、沒有小地圖。
 * <p>
 * 招呼（C→S exilecore:hello）多四個欄位（只多欄位、協定不升；舊插件不認得就忽略，舊模組沒寫 = false）：
 * {@code "alerts":true}（模組畫預警，插件不送那些大標題）、{@code "minimap":true}（模組畫小地圖）、
 * {@code "death":true}（模組畫死亡回顧面板，插件不在聊天欄印）、{@code "tooltip":true}（模組自己整理裝備說明；插件不用改什麼，只記下來）。
 * <p>
 * 三個封包（S→C，UTF-8 JSON，前面沒有長度；一段字是 {@code {"t":字,"c":0xRRGGBB}}，跟 hud 的目標框一樣）：
 * <ul>
 *   <li>{@value #ALERT}：{@code {"v":1,"op":"show","id":代號,"lane":"top"|"side","p":優先度 0～3,"t":[大字],"s":[說明],
 *       "l":[[一行],…]（side 的清單）,"d":留幾 tick（0 = 等下一個同代號的或 clear）,"cd":倒數幾 tick（0 = 沒有）,
 *       "at":[x,y,z]（可選）,"k":"danger"|"safe"（箭頭：危險區＝往外逃的方向、安全區＝往那裡走）}}；
 *       {@code {"v":1,"op":"clear","id":代號}}（"*" = 全部）。同一個代號再送一次是更新（每 5 tick 重送的那種不會疊起來）。</li>
 *   <li>{@value #MINIMAP}：進圖送一次 {@code {"v":1,"op":"init","map":實例編號,"ox":原點 x,"oz":原點 z,"y":地板高度,"size":格子邊長,
 *       "res":一格幾方塊,"w":寬,"h":高,"tiles":base64（每格 2 bit：0 空、1 地板、2 牆、3 液體；從 z=0 那排開始，每排 x 由小到大，
 *       每個位元組先放低位）,"rooms":[{"x","z","hx","hz","r":圓的(1/0),"k":START|NORMAL|SIDE|BOSS}],"boss":{…},"events":[{…}]}}；
 *       之後只送變化 {@code {"op":"events","events":[有變的]}}、{@code {"op":"boss","boss":{"x","z","st":wait|alive|fight|dead}}}、
 *       {@code {"op":"party","party":[{"n":名字,"x","z"}]}}；離開地圖送 {@code {"op":"clear"}}。
 *       事件：{@code {"i":編號,"x","z","k":種類,"n":名稱,"c":顏色,"st":0 沒開始／1 進行中／2 完成}}。座標都是世界座標。</li>
 *   <li>{@value #DEATH}：{@code {"v":1,"lines":[[一行的字]…]}}（死亡回顧那幾行，已經拆好顏色）。</li>
 * </ul>
 */
public final class FeedProtocol {

    public static final String ALERT = "exilecore:alert";
    public static final String MINIMAP = "exilecore:minimap";
    public static final String DEATH = "exilecore:death";
    public static final int VERSION = 1;

    /** 招呼裡的四個旗標。 */
    public record Flags(boolean alerts, boolean minimap, boolean death, boolean tooltip) {
        public static final Flags NONE = new Flags(false, false, false, false);

        public boolean any() {
            return alerts || minimap || death || tooltip;
        }
    }

    /** 預警放哪裡：畫面上半部那一排、側邊的機制清單。 */
    public enum Lane { TOP, SIDE }

    /**
     * 一則預警。
     *
     * @param title    大字（MiniMessage）
     * @param sub      說明（MiniMessage；可以是空的）
     * @param lines    側邊清單的每一行（MiniMessage；top 的不用）
     * @param ticks    留幾 tick（0 = 一直留著，等同代號的更新或 clear）
     * @param countdown 倒數幾 tick（0 = 沒有）
     * @param at       指向的世界座標 {x, y, z}；沒有 null
     * @param safe     at 是安全區（往那裡走）；false = 危險區（往外逃）
     */
    public record Alert(String id, Lane lane, int priority, String title, String sub, List<String> lines, int ticks, int countdown,
                        double[] at, boolean safe) {

        public static Alert top(final String id, final int priority, final String title, final String sub, final int ticks) {
            return new Alert(id, Lane.TOP, priority, title, sub, List.of(), ticks, 0, null, false);
        }

        public static Alert side(final String id, final String title, final List<String> lines, final int countdown) {
            return new Alert(id, Lane.SIDE, 1, title, "", List.copyOf(lines), 0, countdown, null, false);
        }

        public Alert countdown(final int ticks) {
            return new Alert(id, lane, priority, title, sub, lines, this.ticks, Math.max(0, ticks), at, safe);
        }

        public Alert at(final double x, final double y, final double z, final boolean towards) {
            return new Alert(id, lane, priority, title, sub, lines, ticks, countdown, new double[] {x, y, z}, towards);
        }
    }

    private FeedProtocol() {
    }

    /** 讀招呼裡的四個旗標（招呼格式不對、沒寫的都是 false）。 */
    public static Flags flags(final byte[] hello) {
        if (hello == null || hello.length == 0 || hello.length > ClientModProtocol.MAX_HELLO) {
            return Flags.NONE;
        }
        try {
            final JsonElement parsed = JsonParser.parseString(new String(hello, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Flags.NONE;
            }
            final JsonObject root = parsed.getAsJsonObject();
            return new Flags(bool(root, "alerts"), bool(root, "minimap"), bool(root, "death"), bool(root, "tooltip"));
        } catch (final RuntimeException e) {
            return Flags.NONE;
        }
    }

    private static boolean bool(final JsonObject root, final String key) {
        try {
            return root.has(key) && root.get(key).getAsBoolean();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    // ---- 預警 ----

    public static byte[] alert(final Alert alert) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "show");
        root.addProperty("id", alert.id());
        root.addProperty("lane", alert.lane() == Lane.SIDE ? "side" : "top");
        root.addProperty("p", Math.max(0, Math.min(3, alert.priority())));
        root.add("t", parts(alert.title()));
        if (alert.sub() != null && !alert.sub().isBlank()) {
            root.add("s", parts(alert.sub()));
        }
        if (alert.lines() != null && !alert.lines().isEmpty()) {
            final JsonArray lines = new JsonArray();
            for (final String line : alert.lines()) {
                lines.add(parts(line));
            }
            root.add("l", lines);
        }
        root.addProperty("d", Math.max(0, alert.ticks()));
        if (alert.countdown() > 0) {
            root.addProperty("cd", alert.countdown());
        }
        if (alert.at() != null && alert.at().length == 3) {
            final JsonArray at = new JsonArray();
            for (final double value : alert.at()) {
                at.add(Math.round(value * 10) / 10.0);
            }
            root.add("at", at);
            root.addProperty("k", alert.safe() ? "safe" : "danger");
        }
        return bytes(root);
    }

    public static byte[] clearAlert(final String id) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "clear");
        root.addProperty("id", id == null ? "*" : id);
        return bytes(root);
    }

    // ---- 死亡回顧 ----

    /** 死亡回顧那幾行（「/deaths 可以再看一次」那行拿掉：模組有自己的按鍵）。 */
    public static byte[] death(final List<String> lines) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        final JsonArray array = new JsonArray();
        for (final String line : lines) {
            if (line == null || line.contains("/deaths")) {
                continue;
            }
            array.add(parts(line));
        }
        root.add("lines", array);
        return bytes(root);
    }

    // ---- 小地圖 ----

    public static final int TILE_EMPTY = 0;
    public static final int TILE_FLOOR = 1;
    public static final int TILE_WALL = 2;
    public static final int TILE_LIQUID = 3;

    /** 小地圖一格幾方塊（224 格的地圖 → 112×112 格，2 bit 一格約 3 KB）。 */
    public static final int RES = 2;

    /**
     * 版面縮成 res×res 一格：有一半以上是地板就算地板，不然有液體算液體、有牆算牆，都沒有是空的。
     *
     * @return 每格一個值（{@link #TILE_EMPTY}…），z 一排一排、x 由小到大
     */
    public static int[] tiles(final MapLayout layout, final int res) {
        final int size = (MapLayout.SIZE + res - 1) / res;
        final int[] out = new int[size * size];
        for (int cz = 0; cz < size; cz++) {
            for (int cx = 0; cx < size; cx++) {
                int floor = 0;
                int cells = 0;
                boolean liquid = false;
                boolean wall = false;
                for (int dz = 0; dz < res; dz++) {
                    for (int dx = 0; dx < res; dx++) {
                        final int x = cx * res + dx;
                        final int z = cz * res + dz;
                        if (x >= MapLayout.SIZE || z >= MapLayout.SIZE) {
                            continue;
                        }
                        cells++;
                        if (layout.isFloor(x, z)) {
                            floor++;
                        } else if (layout.isLiquid(x, z)) {
                            liquid = true;
                        } else if (layout.isWall(x, z)) {
                            wall = true;
                        }
                    }
                }
                out[cz * size + cx] = cells > 0 && floor * 2 >= cells ? TILE_FLOOR : liquid ? TILE_LIQUID : wall ? TILE_WALL : TILE_EMPTY;
            }
        }
        return out;
    }

    /** 2 bit 一格打包（每個位元組先放低位）。 */
    public static byte[] pack(final int[] tiles) {
        final byte[] out = new byte[(tiles.length + 3) / 4];
        for (int i = 0; i < tiles.length; i++) {
            out[i >> 2] |= (byte) ((tiles[i] & 3) << ((i & 3) * 2));
        }
        return out;
    }

    /** 事件標記（小地圖用）。st：0 沒開始、1 進行中、2 完成。 */
    public record Marker(int index, double x, double z, String kind, String name, int color, int state) {
    }

    /** Boss：st 是 wait（還沒出來）、alive、fight（開打了）、dead。 */
    public record Boss(double x, double z, String state) {
    }

    /** 進圖時送一次的整張圖。 */
    public static byte[] minimapInit(final int mapId, final int originX, final int originZ, final MapLayout layout, final Boss boss,
                                     final List<Marker> markers) {
        final int size = (MapLayout.SIZE + RES - 1) / RES;
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "init");
        root.addProperty("map", mapId);
        root.addProperty("ox", originX);
        root.addProperty("oz", originZ);
        root.addProperty("y", MapLayout.FLOOR_Y);
        root.addProperty("size", MapLayout.SIZE);
        root.addProperty("res", RES);
        root.addProperty("w", size);
        root.addProperty("h", size);
        root.addProperty("tiles", Base64.getEncoder().encodeToString(pack(tiles(layout, RES))));
        final JsonArray rooms = new JsonArray();
        for (final MapLayout.Room room : layout.rooms()) {
            final JsonObject o = new JsonObject();
            o.addProperty("x", originX + room.cx());
            o.addProperty("z", originZ + room.cz());
            o.addProperty("hx", room.halfX());
            o.addProperty("hz", room.halfZ());
            o.addProperty("r", room.round() ? 1 : 0);
            o.addProperty("k", room.kind().name());
            rooms.add(o);
        }
        root.add("rooms", rooms);
        if (boss != null) {
            root.add("boss", boss(boss));
        }
        root.add("events", markers(markers));
        return bytes(root);
    }

    public static byte[] minimapEvents(final List<Marker> changed) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "events");
        root.add("events", markers(changed));
        return bytes(root);
    }

    public static byte[] minimapBoss(final Boss boss) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "boss");
        root.add("boss", boss(boss));
        return bytes(root);
    }

    /** 隊友的位置：{名字, x, z}。 */
    public record Mate(String name, double x, double z) {
    }

    public static byte[] minimapParty(final List<Mate> mates) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "party");
        final JsonArray array = new JsonArray();
        for (final Mate mate : mates) {
            final JsonObject o = new JsonObject();
            o.addProperty("n", mate.name());
            o.addProperty("x", Math.round(mate.x() * 10) / 10.0);
            o.addProperty("z", Math.round(mate.z() * 10) / 10.0);
            array.add(o);
        }
        root.add("party", array);
        return bytes(root);
    }

    /** 2026-11-02 照站長：地圖上的怪（x、z 世界座標取整；r：0 普通、1 魔法、2 稀有、3 傳奇）。 */
    public record Mob(int x, int z, int rarity) {
    }

    /** 怪的位置：{"op":"mobs","m":[x,z,r, x,z,r, …]}（攤平成一串數字，封包小一點）。舊模組不認得這個 op，直接略過。 */
    public static byte[] minimapMobs(final List<Mob> mobs) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "mobs");
        final JsonArray array = new JsonArray();
        for (final Mob mob : mobs) {
            array.add(mob.x());
            array.add(mob.z());
            array.add(mob.rarity());
        }
        root.add("m", array);
        return bytes(root);
    }

    public static byte[] minimapClear() {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("op", "clear");
        return bytes(root);
    }

    /** 事件的變化：after 裡跟 before 不一樣（或 before 沒有）的。 */
    public static List<Marker> changed(final List<Marker> before, final List<Marker> after) {
        final List<Marker> out = new java.util.ArrayList<>();
        for (final Marker marker : after) {
            if (before == null || !before.contains(marker)) {
                out.add(marker);
            }
        }
        return out;
    }

    private static JsonObject boss(final Boss boss) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", Math.round(boss.x() * 10) / 10.0);
        o.addProperty("z", Math.round(boss.z() * 10) / 10.0);
        o.addProperty("st", boss.state());
        return o;
    }

    private static JsonArray markers(final List<Marker> markers) {
        final JsonArray array = new JsonArray();
        for (final Marker marker : markers) {
            final JsonObject o = new JsonObject();
            o.addProperty("i", marker.index());
            o.addProperty("x", Math.round(marker.x() * 10) / 10.0);
            o.addProperty("z", Math.round(marker.z() * 10) / 10.0);
            o.addProperty("k", marker.kind());
            o.addProperty("n", marker.name());
            o.addProperty("c", marker.color());
            o.addProperty("st", marker.state());
            array.add(o);
        }
        return array;
    }

    // ---- 共用 ----

    static JsonArray parts(final String miniMessage) {
        final JsonArray array = new JsonArray();
        for (final MiniParts.Part part : MiniParts.parse(miniMessage)) {
            final JsonObject o = new JsonObject();
            o.addProperty("t", part.text());
            o.addProperty("c", part.color());
            array.add(o);
        }
        return array;
    }

    private static byte[] bytes(final JsonObject root) {
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
