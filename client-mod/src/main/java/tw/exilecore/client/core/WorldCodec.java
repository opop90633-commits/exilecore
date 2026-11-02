package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：地圖資訊框（exilecore:map）、怪物頭上的血條（exilecore:mobs）、準心（exilecore:aim）的封包，
 * 以及招呼多的三個旗標。格式跟插件的 {@code tw.exilecore.clientmod.WorldHudProtocol} 一樣（兩邊要一起改）。純 Java（測試用）。
 * 這次之前這些都沒有。
 */
public final class WorldCodec {

    public static final String MAP = "map";
    public static final String MOBS = "mobs";
    public static final String AIM = "aim";
    public static final int MAP_VERSION = 1;
    public static final int MOBS_FORMAT = 1;
    static final int MAX_MOBS = 256;

    public static final int RARITY_NORMAL = 0;
    public static final int RARITY_MAGIC = 1;
    public static final int RARITY_RARE = 2;
    public static final int RARITY_UNIQUE = 3;

    public static final int BOSS_NONE = -1;
    public static final int BOSS_WAITING = 0;
    public static final int BOSS_FIGHTING = 1;
    public static final int BOSS_KILLED = 2;

    public static final int EVENT_IDLE = 0;
    public static final int EVENT_ACTIVE = 1;
    public static final int EVENT_DONE = 2;
    public static final int EVENT_FAILED = 3;

    public static final int MARK_ENRAGED = 1;
    public static final int MARK_AURA = 1 << 1;
    public static final int MARK_PROXY = 1 << 2;
    public static final int MARK_REGEN = 1 << 3;
    public static final int MARK_CURSED = 1 << 4;
    public static final int MARK_IGNITE = 1 << 5;
    public static final int MARK_CHILL = 1 << 6;
    public static final int MARK_FREEZE = 1 << 7;
    public static final int MARK_SHOCK = 1 << 8;
    public static final int MARK_ALL = (1 << 9) - 1;

    private WorldCodec() {
    }

    // ---- 招呼 ----

    /**
     * 在招呼的 JSON 多寫三個旗標（只寫 true 的；只多欄位、協定不升，舊插件不認得就忽略）。
     * 格式不對的招呼原樣回傳。
     */
    public static byte[] withFeatures(final byte[] hello, final boolean mapbox, final boolean dmgnum, final boolean mobbars) {
        if (!mapbox && !dmgnum && !mobbars) {
            return hello;
        }
        try {
            final JsonObject root = JsonParser.parseString(new String(hello, StandardCharsets.UTF_8)).getAsJsonObject();
            if (mapbox) {
                root.addProperty("mapbox", true);
            }
            if (dmgnum) {
                root.addProperty("dmgnum", true);
            }
            if (mobbars) {
                root.addProperty("mobbars", true);
            }
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final RuntimeException e) {
            return hello;
        }
    }

    // ---- 地圖資訊框 ----

    public record EventLine(String name, int color, String status, int progress, int state) {
    }

    /** 地圖資訊框（{@link #parseMap} 回傳 {@link #CLEAR} 表示離開地圖）。 */
    public record MapPanel(String name, int color, int tier, int level, List<List<HudData.Part>> mods, int portals, String unit,
                           int explored, int cleared, int boss, String bossName, int bossLife, List<EventLine> events) {
    }

    /** 清除（離開地圖）。 */
    public static final MapPanel CLEAR = new MapPanel("", 0, 0, 0, List.of(), -1, "", 0, 0, BOSS_NONE, "", -1, List.of());

    /** 讀地圖資訊框；清除回傳 {@link #CLEAR}；格式不對回傳 null。 */
    public static MapPanel parseMap(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("clear") && root.get("clear").getAsBoolean() || !root.has("name")) {
                return CLEAR;
            }
            final List<List<HudData.Part>> mods = new ArrayList<>();
            if (root.has("mods")) {
                for (final JsonElement line : root.getAsJsonArray("mods")) {
                    final List<HudData.Part> parts = new ArrayList<>();
                    for (final JsonElement part : line.getAsJsonArray()) {
                        final JsonArray pair = part.getAsJsonArray();
                        parts.add(new HudData.Part(pair.get(0).getAsString(), pair.size() > 1 ? pair.get(1).getAsInt() & 0xFFFFFF : 0xFFFFFF));
                    }
                    if (mods.size() < 32) {
                        mods.add(parts);
                    }
                }
            }
            int boss = BOSS_NONE;
            String bossName = "";
            int bossLife = -1;
            if (root.has("boss")) {
                final JsonObject b = root.getAsJsonObject("boss");
                boss = integer(b, "s", BOSS_NONE);
                bossName = text(b, "name");
                bossLife = integer(b, "life", -1);
            }
            final List<EventLine> events = new ArrayList<>();
            if (root.has("events")) {
                for (final JsonElement element : root.getAsJsonArray("events")) {
                    final JsonObject e = element.getAsJsonObject();
                    if (events.size() < 24) {
                        events.add(new EventLine(text(e, "n"), integer(e, "c", 0xFFFFFF) & 0xFFFFFF, text(e, "s"), integer(e, "p", -1),
                                integer(e, "st", EVENT_IDLE)));
                    }
                }
            }
            return new MapPanel(text(root, "name"), integer(root, "color", 0xFFFFFF) & 0xFFFFFF, integer(root, "tier", 0),
                    integer(root, "level", 0), mods, integer(root, "portals", -1), text(root, "unit"), integer(root, "explored", 0),
                    integer(root, "cleared", 0), boss, bossName, bossLife, events);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static int integer(final JsonObject o, final String key, final int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    private static String text(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    // ---- 血條 ----

    /** 一隻怪的血條（life、shield 是 0–255；shield -1 = 沒有）。 */
    public record MobBar(int entityId, int life, int shield, int rarity, boolean boss, boolean invulnerable, int marks) {

        public double lifeFraction() {
            return life / 255.0;
        }

        public double shieldFraction() {
            return shield < 0 ? 0 : shield / 255.0;
        }

        public boolean has(final int mark) {
            return (marks & mark) != 0;
        }
    }

    /** 讀完的血條封包。 */
    public record Mobs(boolean full, List<MobBar> bars, List<Integer> removed) {
    }

    /** 讀血條封包；格式不對回傳 null（整包不要）。 */
    public static Mobs parseMobs(final byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            final In in = new In(data);
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

    /** 測試用（伺服器那邊的寫法）。 */
    public static byte[] encodeMobs(final boolean full, final List<MobBar> bars, final List<Integer> removed) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MOBS_FORMAT);
        out.write(full ? 1 : 0);
        varint(out, bars.size());
        for (final MobBar bar : bars) {
            varint(out, bar.entityId());
            final int marks = bar.marks() & MARK_ALL;
            out.write((bar.rarity() & 3) | (bar.boss() ? 4 : 0) | (bar.shield() >= 0 ? 8 : 0) | (bar.invulnerable() ? 16 : 0)
                    | (marks != 0 ? 32 : 0));
            out.write(bar.life());
            if (bar.shield() >= 0) {
                out.write(bar.shield());
            }
            if (marks != 0) {
                varint(out, marks);
            }
        }
        varint(out, removed.size());
        for (final int id : removed) {
            varint(out, id);
        }
        return out.toByteArray();
    }

    // ---- 準心 ----

    /** 準心指著的實體 id（-1 = 沒有）：{@code [1, varint (id + 1)]}。 */
    public static byte[] aim(final int entityId) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        varint(out, entityId < 0 ? 0 : entityId + 1);
        return out.toByteArray();
    }

    private static void varint(final ByteArrayOutputStream out, final int value) {
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write(v & 0x7f | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static final class In {
        private final byte[] data;
        private int pos;

        In(final byte[] data) {
            this.data = data;
        }

        int u8() {
            if (pos >= data.length) {
                throw new IndexOutOfBoundsException();
            }
            return data[pos++] & 0xff;
        }

        int varint() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                final int b = u8();
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("varint 太長");
        }

        int count(final int max) {
            final int n = varint();
            if (n < 0 || n > max) {
                throw new IllegalArgumentException("數量 " + n);
            }
            return n;
        }
    }
}
