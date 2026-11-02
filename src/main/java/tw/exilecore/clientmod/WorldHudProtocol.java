package tw.exilecore.clientmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4 B 組：世界中與地圖）：地圖資訊框、怪物頭上血條、準心指著誰的封包格式。純函式，不依賴 Bukkit（測試用）；
 * 模組那邊的 {@code tw.exilecore.client.core.WorldCodec} 是同一套格式，兩邊要一起改。這次之前這些都沒有（地圖資訊只有進圖的大標題、
 * 怪物只有原版名牌、目標框只看最後打到的那隻或伺服器自己量的視線）。
 * <ul>
 *   <li>招呼（C→S {@value ClientModProtocol#HELLO}）多三個旗標（只多欄位、協定不升；舊模組沒寫 = false，舊插件不認得就忽略）：
 *       {@code mapbox:true} 模組畫地圖資訊框、{@code dmgnum:true} 模組畫傷害數字（{@link FxProtocol} 的 NUMBER）、
 *       {@code mobbars:true} 模組畫怪物頭上的血條（名牌只在準心指著或很近時顯示）。</li>
 *   <li>S→C {@value #MAP}：UTF-8 JSON（{@link #encodeMap}）；離開地圖送 {@code {"v":1,"clear":true}}。只送給 mapbox:true 的模組。</li>
 *   <li>S→C {@value #MOBS}：二進位（{@link #encodeMobs}）；只送有變化的。只送給 mobbars:true 的模組。</li>
 *   <li>C→S {@value #AIM}：2～6 個位元組 {@code [版本 1, varint (實體 id + 1)]}，0 = 準心沒指著怪；有變化才送。</li>
 * </ul>
 */
public final class WorldHudProtocol {

    public static final String MAP = "exilecore:map";
    public static final String MOBS = "exilecore:mobs";
    public static final String AIM = "exilecore:aim";
    /** 地圖封包 JSON 的版本。 */
    public static final int MAP_VERSION = 1;
    /** 血條封包的格式（第一個位元組）。 */
    public static final int MOBS_FORMAT = 1;
    static final int AIM_VERSION = 1;
    /** 血條封包一次最多幾筆（解碼時防亂送）。 */
    static final int MAX_MOBS = 256;

    public static final int RARITY_NORMAL = 0;
    public static final int RARITY_MAGIC = 1;
    public static final int RARITY_RARE = 2;
    public static final int RARITY_UNIQUE = 3;

    /** 地圖資訊框裡 Boss 的狀態。 */
    public static final int BOSS_NONE = -1;
    public static final int BOSS_WAITING = 0;
    public static final int BOSS_FIGHTING = 1;
    public static final int BOSS_KILLED = 2;

    /** 事件的狀態。 */
    public static final int EVENT_IDLE = 0;
    public static final int EVENT_ACTIVE = 1;
    public static final int EVENT_DONE = 2;
    public static final int EVENT_FAILED = 3;

    private WorldHudProtocol() {
    }

    // ---- 招呼的旗標 ----

    /** 模組 1.4 B 組的功能（招呼裡的旗標）。 */
    public record Features(boolean mapbox, boolean dmgnum, boolean mobbars) {
        public static final Features NONE = new Features(false, false, false);

        public boolean any() {
            return mapbox || dmgnum || mobbars;
        }
    }

    /** 從客戶端的招呼讀這三個旗標（格式不對、沒寫 = 都是 false）。 */
    public static Features decodeFeatures(final byte[] hello) {
        if (hello == null || hello.length == 0 || hello.length > ClientModProtocol.MAX_HELLO) {
            return Features.NONE;
        }
        try {
            final JsonElement parsed = JsonParser.parseString(new String(hello, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Features.NONE;
            }
            final JsonObject root = parsed.getAsJsonObject();
            return new Features(flag(root, "mapbox"), flag(root, "dmgnum"), flag(root, "mobbars"));
        } catch (final RuntimeException e) {
            return Features.NONE;
        }
    }

    private static boolean flag(final JsonObject root, final String key) {
        final JsonElement value = root.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    // ---- 地圖資訊框 ----

    /**
     * 地圖裡的一個事件。
     *
     * @param progress 0–100（沒有進度 -1）
     * @param state    {@link #EVENT_IDLE}…{@link #EVENT_FAILED}
     */
    public record EventLine(String name, int color, String status, int progress, int state) {
    }

    /**
     * 地圖資訊框的內容。
     *
     * @param mods     地圖詞綴（一條一行，一行是幾段字＋顏色）
     * @param portals  還剩幾門／幾次（-1 = 不適用）
     * @param unit     「門」或「次」
     * @param explored 刷過的怪物群比例 0–100
     * @param cleared  清掉的怪物群比例 0–100
     * @param boss     Boss 狀態（{@link #BOSS_NONE}…{@link #BOSS_KILLED}）
     * @param bossLife Boss 剩下的生命 0–100（-1 = 不知道）
     */
    public record MapPanel(String name, int color, int tier, int level, List<List<MiniParts.Part>> mods, int portals, String unit,
                           int explored, int cleared, int boss, String bossName, int bossLife, List<EventLine> events) {
    }

    /** 地圖資訊框（null = 清除：離開地圖）。 */
    public static String encodeMap(final MapPanel panel) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", MAP_VERSION);
        if (panel == null) {
            root.addProperty("clear", true);
            return root.toString();
        }
        root.addProperty("name", panel.name());
        root.addProperty("color", panel.color());
        root.addProperty("tier", panel.tier());
        root.addProperty("level", panel.level());
        final JsonArray mods = new JsonArray();
        for (final List<MiniParts.Part> line : panel.mods()) {
            final JsonArray parts = new JsonArray();
            for (final MiniParts.Part part : line) {
                final JsonArray pair = new JsonArray();
                pair.add(part.text());
                pair.add(part.color());
                parts.add(pair);
            }
            mods.add(parts);
        }
        root.add("mods", mods);
        root.addProperty("portals", panel.portals());
        root.addProperty("unit", panel.unit() == null ? "" : panel.unit());
        root.addProperty("explored", clamp(panel.explored()));
        root.addProperty("cleared", clamp(panel.cleared()));
        final JsonObject boss = new JsonObject();
        boss.addProperty("s", panel.boss());
        boss.addProperty("name", panel.bossName() == null ? "" : panel.bossName());
        boss.addProperty("life", panel.bossLife() < 0 ? -1 : clamp(panel.bossLife()));
        root.add("boss", boss);
        final JsonArray events = new JsonArray();
        for (final EventLine event : panel.events()) {
            final JsonObject e = new JsonObject();
            e.addProperty("n", event.name());
            e.addProperty("c", event.color());
            e.addProperty("s", event.status() == null ? "" : event.status());
            e.addProperty("p", event.progress() < 0 ? -1 : clamp(event.progress()));
            e.addProperty("st", event.state());
            events.add(e);
        }
        root.add("events", events);
        return root.toString();
    }

    private static int clamp(final int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    // ---- 怪物頭上的血條 ----

    /** 2026-11-02 照站長：血條旁邊的小圖示（{@link MobBar#marks} 的位元）。 */
    public static final int MARK_ENRAGED = 1;
    public static final int MARK_AURA = 1 << 1;
    public static final int MARK_PROXY = 1 << 2;
    public static final int MARK_REGEN = 1 << 3;
    public static final int MARK_CURSED = 1 << 4;
    public static final int MARK_IGNITE = 1 << 5;
    public static final int MARK_CHILL = 1 << 6;
    public static final int MARK_FREEZE = 1 << 7;
    public static final int MARK_SHOCK = 1 << 8;
    /** 目前用到的位元（解碼時多的不管）。 */
    public static final int MARK_ALL = (1 << 9) - 1;

    /**
     * 一隻怪的血條狀態。
     *
     * @param life         生命比例 0–255（255 = 滿）
     * @param shield       能量護盾比例 0–255；-1 = 沒有護盾
     * @param invulnerable 現在打不動（Boss 鎖血、結晶、開場、庇護光環）
     * @param marks        小圖示（{@link #MARK_ENRAGED}…{@link #MARK_SHOCK}）
     */
    public record MobBar(int entityId, int life, int shield, int rarity, boolean boss, boolean invulnerable, int marks) {

        public MobBar(final int entityId, final int life, final int shield, final int rarity, final boolean boss) {
            this(entityId, life, shield, rarity, boss, false, 0);
        }
    }

    /** 0–1 → 0–255（四捨五入；有剩一點點就不會是 0）。 */
    public static int fraction255(final double fraction) {
        if (!(fraction > 0)) {
            return 0;
        }
        if (fraction >= 1) {
            return 255;
        }
        return Math.max(1, Math.min(254, (int) Math.round(fraction * 255)));
    }

    /**
     * 血條封包。
     *
     * <pre>
     * 封包 = u8 FORMAT(1) · u8 旗標（bit0 完整：沒列到的都拿掉）· varint 筆數 · 筆× · varint 拿掉的筆數 · varint 實體 id×
     * 筆   = varint 實體 id · u8 狀態（bit0–1 稀有度 0 普通～3 傳奇、bit2 Boss、bit3 有護盾、bit4 無敵、bit5 有小圖示）· u8 生命（0–255）
     *        · [bit3：u8 護盾（0–255）] · [bit5：varint 小圖示位元（狂暴、光環、替身、回血、詛咒、點燃、冰緩、凍結、感電）]
     * </pre>
     * 一般的怪一筆 3～4 個位元組；有護盾、狀態的多 1～3 個。
     */
    public static byte[] encodeMobs(final boolean full, final List<MobBar> bars, final List<Integer> removed) {
        final FxProtocol.Buf out = new FxProtocol.Buf();
        out.u8(MOBS_FORMAT);
        out.u8(full ? 1 : 0);
        out.varint(bars.size());
        for (final MobBar bar : bars) {
            out.varint(Math.max(0, bar.entityId()));
            final boolean shield = bar.shield() >= 0;
            final int marks = bar.marks() & MARK_ALL;
            out.u8((bar.rarity() & 3) | (bar.boss() ? 4 : 0) | (shield ? 8 : 0) | (bar.invulnerable() ? 16 : 0) | (marks != 0 ? 32 : 0));
            out.u8(Math.max(0, Math.min(255, bar.life())));
            if (shield) {
                out.u8(Math.max(0, Math.min(255, bar.shield())));
            }
            if (marks != 0) {
                out.varint(marks);
            }
        }
        out.varint(removed.size());
        for (final int id : removed) {
            out.varint(Math.max(0, id));
        }
        return out.toByteArray();
    }

    /** 讀血條封包（測試用；模組那邊有自己的）：{完整?, 筆, 拿掉的}；格式不對回傳 null。 */
    public record Mobs(boolean full, List<MobBar> bars, List<Integer> removed) {
    }

    public static Mobs decodeMobs(final byte[] data) {
        try {
            final FxProtocol.In in = new FxProtocol.In(data);
            if (in.u8() != MOBS_FORMAT) {
                return null;
            }
            final boolean full = (in.u8() & 1) != 0;
            final int count = in.count(MAX_MOBS);
            final List<MobBar> bars = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int id = in.varint();
                final int state = in.u8();
                final int life = in.u8();
                final int shield = (state & 8) != 0 ? in.u8() : -1;
                final int marks = (state & 32) != 0 ? in.varint() & MARK_ALL : 0;
                bars.add(new MobBar(id, life, shield, state & 3, (state & 4) != 0, (state & 16) != 0, marks));
            }
            final int gone = in.count(MAX_MOBS * 4);
            final List<Integer> removed = new ArrayList<>(gone);
            for (int i = 0; i < gone; i++) {
                removed.add(in.varint());
            }
            return new Mobs(full, bars, removed);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // ---- 準心 ----

    /** 準心指著的實體 id（-1 = 沒有）。 */
    public static byte[] encodeAim(final int entityId) {
        final FxProtocol.Buf out = new FxProtocol.Buf();
        out.u8(AIM_VERSION);
        out.varint(entityId < 0 ? 0 : entityId + 1);
        return out.toByteArray();
    }

    /** 讀準心封包：實體 id、-1 = 沒有；格式不對回傳 null。 */
    public static Integer decodeAim(final byte[] data) {
        if (data == null || data.length < 2 || data.length > 6 || data[0] != AIM_VERSION) {
            return null;
        }
        try {
            final FxProtocol.In in = new FxProtocol.In(data);
            in.u8();
            final int value = in.varint();
            return value <= 0 ? -1 : value - 1;
        } catch (final RuntimeException e) {
            return null;
        }
    }
}
