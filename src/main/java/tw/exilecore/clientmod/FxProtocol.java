package tw.exilecore.clientmod;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-02 星骸輔助模組 1.1：特效封包 {@value ClientModProtocol#FX}（S→C，二進位）。純函式，不依賴 Bukkit（測試用）；
 * 模組那邊的 {@code tw.exilecore.client.core.FxCodec} 是同一套格式，兩邊要一起改。
 *
 * <p>一個封包是一個玩家一個 tick 的所有特效（{@link Writer} 一筆一筆加，tick 結束送出）。數字都是大端序；varint 是 7 bit 一組（低位先）、
 * zigzag 是有號的 varint。
 *
 * <pre>
 * 封包    = u8 FORMAT(1) · zigzag ox, oy, oz（原點：第一筆的方塊座標）
 *          · varint 字串數 · 字串×（varint 長度 · UTF-8）       粒子、方塊狀態、物品的 id（「minecraft:」拿掉）
 *          · varint 樣式數 · 樣式×                              粒子＋資料（顏色大小等），同一批重複的只寫一次
 *          · varint 特效數 · 特效×
 * 樣式    = varint 粒子（字串索引）· u8 資料種類 · 資料
 *          NONE 0 · DUST 1（i32 rgb, f32 大小）· DUST_TRANSITION 2（i32 從, i32 到, f32 大小）· BLOCK 3（varint 字串：方塊狀態）
 *          · ITEM 4（varint 字串：物品）· COLOR 5（i32 argb）· SPELL 6（i32 rgb, f32 power）· FLOAT 7（f32）· INT 8（zigzag）
 *          · TRAIL 9（座標 目標, i32 rgb, varint tick）
 * 特效    = u8 頭（bit0–2 形狀、bit3–4 階段、bit5 重要、bit6 座標用 f32）
 *          · 不是 ONCE：varlong 編號；STOP 到這裡結束
 *          · u8 選項（bit0 有 offset 3×f32、bit1 extra 是 f32、bit2 extra 是 1、bit3 有剩餘 tick）
 *          · varint 樣式 · 座標 中心 · LINE／BEAM：座標 終點 · RING／AREA／LINE／BEAM：u16 半徑（1/64 格）
 *          · varint 顆數 · START／UPDATE：u8 密度（0–255）· 選項 bit3：varint 剩餘 tick · bit0：offset · bit1：extra
 * 座標    = 相對原點：頭 bit6 = 0 → 3×i16（1/128 格，±256 格內）；= 1 → 3×f32
 * 數字    = u8 頭（形狀 6 NUMBER、階段 ONCE、bit6 座標用 f32）· u8 旗標（bit0 強調：暴擊／大傷害、bit1 自己受到的、bit2 有目標、
 *           bit3 有字、bit4 有數值、bit5 護盾吃掉的）· 座標 · bit4：varint 數值 · u8×3 rgb · bit3：varint 字串 · bit2：varint 目標實體 id
 * </pre>
 * 2026-11-02 照站長（模組 1.4 B 組）：多了 NUMBER（傷害數字改由模組畫）。舊模組讀到形狀 6 會整包不要，所以只送給招呼說
 * {@code dmgnum:true} 的模組（{@link FxRouter#setNumbers}）；這次之前傷害數字一律是伺服器生的文字顯示實體。
 *
 * 一次性的（ONCE）顆數是已經照這個人的密度打薄過的（模組照顆數畫）；持續範圍（START／UPDATE）附密度，模組照密度撒裡面的點。
 */
public final class FxProtocol {

    /** 封包格式版本（第一個位元組）。 */
    public static final int FORMAT = 1;

    public static final int SHAPE_POINT = 0;
    public static final int SHAPE_BURST = 1;
    public static final int SHAPE_RING = 2;
    public static final int SHAPE_AREA = 3;
    public static final int SHAPE_LINE = 4;
    public static final int SHAPE_BEAM = 5;
    /** 2026-11-02（模組 1.4）：傷害數字；不是粒子，沒有樣式。 */
    public static final int SHAPE_NUMBER = 6;

    static final int NUM_EMPHASIS = 1;
    static final int NUM_SELF = 1 << 1;
    static final int NUM_TARGET = 1 << 2;
    static final int NUM_TEXT = 1 << 3;
    static final int NUM_VALUE = 1 << 4;
    static final int NUM_ABSORBED = 1 << 5;

    public static final int PHASE_ONCE = 0;
    public static final int PHASE_START = 1;
    public static final int PHASE_UPDATE = 2;
    public static final int PHASE_STOP = 3;

    public static final int DATA_NONE = 0;
    public static final int DATA_DUST = 1;
    public static final int DATA_DUST_TRANSITION = 2;
    public static final int DATA_BLOCK = 3;
    public static final int DATA_ITEM = 4;
    public static final int DATA_COLOR = 5;
    public static final int DATA_SPELL = 6;
    public static final int DATA_FLOAT = 7;
    public static final int DATA_INT = 8;
    public static final int DATA_TRAIL = 9;

    static final int HEAD_IMPORTANT = 1 << 5;
    static final int HEAD_FAR = 1 << 6;
    /** 2026-11-02 照站長：能踩的機制圈（{@link tw.exilecore.skill.Fx#safeZone}）：模組不染成危險的紅色。舊模組不看這個位元。 */
    static final int HEAD_SAFE = 1 << 7;
    static final int OPT_OFFSET = 1;
    static final int OPT_EXTRA = 1 << 1;
    static final int OPT_EXTRA_ONE = 1 << 2;
    static final int OPT_DURATION = 1 << 3;

    /** 近的座標：1/128 格。 */
    static final double NEAR_SCALE = 128.0;
    /** 半徑：1/64 格（最大約 1024 格）。 */
    static final double RADIUS_SCALE = 64.0;
    /** 一批最多幾個字串、樣式、特效（解碼時防亂送）。 */
    static final int MAX_TABLE = 4096;
    static final int MAX_EFFECTS = 65536;

    private FxProtocol() {
    }

    /**
     * 粒子＋資料。particle 是登錄 id（「minecraft:」拿掉的）；text 是方塊狀態或物品 id；a、b 是顏色；f 是大小／power／浮點數；
     * n 是整數或 TRAIL 的 tick；tx/ty/tz 是 TRAIL 的目標（世界座標）。
     */
    public record Style(String particle, int kind, int a, int b, float f, int n, String text, double tx, double ty, double tz) {

        public static Style none(final String particle) {
            return new Style(particle, DATA_NONE, 0, 0, 0, 0, null, 0, 0, 0);
        }

        public static Style dust(final String particle, final int rgb, final float size) {
            return new Style(particle, DATA_DUST, rgb, 0, size, 0, null, 0, 0, 0);
        }

        public static Style transition(final String particle, final int from, final int to, final float size) {
            return new Style(particle, DATA_DUST_TRANSITION, from, to, size, 0, null, 0, 0, 0);
        }

        public static Style block(final String particle, final String state) {
            return new Style(particle, DATA_BLOCK, 0, 0, 0, 0, state, 0, 0, 0);
        }

        public static Style item(final String particle, final String item) {
            return new Style(particle, DATA_ITEM, 0, 0, 0, 0, item, 0, 0, 0);
        }

        public static Style color(final String particle, final int argb) {
            return new Style(particle, DATA_COLOR, argb, 0, 0, 0, null, 0, 0, 0);
        }

        public static Style spell(final String particle, final int rgb, final float power) {
            return new Style(particle, DATA_SPELL, rgb, 0, power, 0, null, 0, 0, 0);
        }

        public static Style floating(final String particle, final float value) {
            return new Style(particle, DATA_FLOAT, 0, 0, value, 0, null, 0, 0, 0);
        }

        public static Style integer(final String particle, final int value) {
            return new Style(particle, DATA_INT, 0, 0, 0, value, null, 0, 0, 0);
        }

        public static Style trail(final String particle, final double x, final double y, final double z, final int rgb, final int ticks) {
            return new Style(particle, DATA_TRAIL, rgb, 0, 0, ticks, null, x, y, z);
        }
    }

    /**
     * 一筆特效（座標是世界座標）。count 是要畫的顆數（ONCE 已經打薄過）；density 0–1（持續範圍的）；duration 剩餘 tick（-1 不知道）。
     */
    public record Entry(int shape, int phase, long id, boolean important, double x, double y, double z, double ex, double ey, double ez,
                        double radius, int count, double density, int duration, double dx, double dy, double dz, double extra) {

        /** STOP 只要編號。 */
        public static Entry stop(final int shape, final long id, final boolean important) {
            return new Entry(shape, PHASE_STOP, id, important, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 2026-11-02：一個傷害數字（世界座標）。
     *
     * @param value    數值（四捨五入；&lt; 0 = 只有字，例如「閃避」）
     * @param rgb      主要屬性的顏色
     * @param emphasis 暴擊（打出的）或大傷害（受到的）：模組畫大一點
     * @param self     自己受到的（模組畫在畫面邊緣或眼前）
     * @param absorbed 被怪物的護盾吃掉的
     * @param text     後面的字（屬性名稱、「閃避」；null = 沒有）
     * @param target   打到的實體 id（同一個目標的數字合併用；-1 = 沒有）
     */
    public record Number(double x, double y, double z, long value, int rgb, boolean emphasis, boolean self, boolean absorbed, String text,
                         int target) {
    }

    /** 解碼後的一筆（測試用；模組那邊有自己的）。數字的 entry、style 是 null。 */
    public record Decoded(Entry entry, Style style, Number number) {

        public Decoded(final Entry entry, final Style style) {
            this(entry, style, null);
        }
    }

    static boolean hasEnd(final int shape) {
        return shape == SHAPE_LINE || shape == SHAPE_BEAM;
    }

    static boolean hasRadius(final int shape) {
        return shape == SHAPE_RING || shape == SHAPE_AREA || shape == SHAPE_LINE || shape == SHAPE_BEAM;
    }

    /** 「minecraft:dust」→「dust」（別的命名空間照寫）。 */
    public static String shortId(final String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    // ---- 寫 ----

    /** 一個玩家這個 tick 的封包（一筆一筆加，{@link #toBytes} 拿最後的位元組）。只在一個執行緒用。 */
    public static final class Writer {
        private boolean hasOrigin;
        private int ox;
        private int oy;
        private int oz;
        private final Map<String, Integer> strings = new HashMap<>();
        private final List<String> stringList = new ArrayList<>();
        private final Map<Style, Integer> styles = new HashMap<>();
        private final Buf stringBuf = new Buf();
        private final Buf styleBuf = new Buf();
        private final Buf body = new Buf();
        private int effects;
        /** 估算用（不改狀態）。 */
        private final Buf scratch = new Buf();

        public boolean isEmpty() {
            return effects == 0;
        }

        public int count() {
            return effects;
        }

        /** 現在送出去的話大約幾個位元組（表頭的 varint 用最大估）。 */
        public int size() {
            return 1 + 15 + 5 + stringBuf.size() + 5 + styleBuf.size() + 5 + body.size();
        }

        /**
         * 加一筆（style 是 STOP 時可以 null）。
         *
         * @param limit 加了以後超過這麼多位元組就不加（&lt;= 0 = 不限）
         * @return 有加進去
         */
        public boolean add(final Entry entry, final Style style, final int limit) {
            if (!hasOrigin && entry.phase() != PHASE_STOP) {
                hasOrigin = true; // 原點：第一筆有座標的
                ox = (int) Math.floor(entry.x());
                oy = (int) Math.floor(entry.y());
                oz = (int) Math.floor(entry.z());
            }
            int extraBytes = 0;
            final boolean needsStyle = entry.phase() != PHASE_STOP;
            if (needsStyle && style == null) {
                return false;
            }
            if (needsStyle && !styles.containsKey(style)) {
                extraBytes += styleCost(style);
            }
            scratch.reset();
            writeEntry(scratch, entry, 0);
            final int cost = scratch.size() + extraBytes;
            if (limit > 0 && size() + cost > limit) {
                return false;
            }
            final int styleIndex = needsStyle ? intern(style) : 0;
            writeEntry(body, entry, styleIndex);
            effects++;
            return true;
        }

        /**
         * 2026-11-02：加一個傷害數字。
         *
         * @param limit 加了以後超過這麼多位元組就不加（&lt;= 0 = 不限）
         * @return 有加進去
         */
        public boolean addNumber(final Number number, final int limit) {
            if (!hasOrigin) {
                hasOrigin = true;
                ox = (int) Math.floor(number.x());
                oy = (int) Math.floor(number.y());
                oz = (int) Math.floor(number.z());
            }
            final String text = number.text() == null || number.text().isEmpty() ? null : number.text();
            final int cost = 2 + 12 + 5 + 3 + 5 + (text != null && !strings.containsKey(text) ? 3 + utf8Length(text) : text != null ? 3 : 0);
            if (limit > 0 && size() + cost > limit) {
                return false;
            }
            final boolean far = !near(number.x() - ox, number.y() - oy, number.z() - oz);
            body.u8(SHAPE_NUMBER | (far ? HEAD_FAR : 0));
            final boolean value = number.value() >= 0;
            body.u8((number.emphasis() ? NUM_EMPHASIS : 0) | (number.self() ? NUM_SELF : 0) | (number.target() >= 0 ? NUM_TARGET : 0)
                    | (text != null ? NUM_TEXT : 0) | (value ? NUM_VALUE : 0) | (number.absorbed() ? NUM_ABSORBED : 0));
            position(body, far, number.x(), number.y(), number.z());
            if (value) {
                body.varint((int) Math.min(Integer.MAX_VALUE, number.value()));
            }
            body.u8(number.rgb() >> 16 & 0xff);
            body.u8(number.rgb() >> 8 & 0xff);
            body.u8(number.rgb() & 0xff);
            if (text != null) {
                body.varint(string(text.length() > 64 ? text.substring(0, 64) : text));
            }
            if (number.target() >= 0) {
                body.varint(number.target());
            }
            effects++;
            return true;
        }

        private int styleCost(final Style style) {
            int cost = 2 + 22; // 粒子索引、種類、最多的資料
            if (!strings.containsKey(style.particle())) {
                cost += 3 + utf8Length(style.particle());
            }
            if (style.text() != null && !strings.containsKey(style.text())) {
                cost += 3 + utf8Length(style.text());
            }
            return cost;
        }

        private int string(final String value) {
            final Integer index = strings.get(value);
            if (index != null) {
                return index;
            }
            final int created = stringList.size();
            strings.put(value, created);
            stringList.add(value);
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            stringBuf.varint(bytes.length);
            stringBuf.bytes(bytes);
            return created;
        }

        private int intern(final Style style) {
            final Integer index = styles.get(style);
            if (index != null) {
                return index;
            }
            final int created = styles.size();
            styles.put(style, created);
            styleBuf.varint(string(style.particle()));
            styleBuf.u8(style.kind());
            switch (style.kind()) {
                case DATA_DUST -> {
                    styleBuf.i32(style.a());
                    styleBuf.f32(style.f());
                }
                case DATA_DUST_TRANSITION -> {
                    styleBuf.i32(style.a());
                    styleBuf.i32(style.b());
                    styleBuf.f32(style.f());
                }
                case DATA_BLOCK, DATA_ITEM -> styleBuf.varint(string(style.text() == null ? "" : style.text()));
                case DATA_COLOR -> styleBuf.i32(style.a());
                case DATA_SPELL -> {
                    styleBuf.i32(style.a());
                    styleBuf.f32(style.f());
                }
                case DATA_FLOAT -> styleBuf.f32(style.f());
                case DATA_INT -> styleBuf.zigzag(style.n());
                case DATA_TRAIL -> {
                    styleBuf.f32((float) (style.tx() - ox));
                    styleBuf.f32((float) (style.ty() - oy));
                    styleBuf.f32((float) (style.tz() - oz));
                    styleBuf.i32(style.a());
                    styleBuf.varint(Math.max(0, style.n()));
                }
                default -> {
                    // NONE：沒有資料
                }
            }
            return created;
        }

        private void writeEntry(final Buf out, final Entry e, final int styleIndex) {
            final boolean far = e.phase() != PHASE_STOP && (!near(e.x() - ox, e.y() - oy, e.z() - oz)
                    || hasEnd(e.shape()) && !near(e.ex() - ox, e.ey() - oy, e.ez() - oz));
            out.u8((e.shape() & 7) | (e.phase() & 3) << 3 | (e.important() ? HEAD_IMPORTANT : 0) | (far ? HEAD_FAR : 0)
                    | (e.phase() != PHASE_STOP && tw.exilecore.skill.Fx.isSafeZone(e.id()) ? HEAD_SAFE : 0));
            if (e.phase() != PHASE_ONCE) {
                out.varlong(e.id());
            }
            if (e.phase() == PHASE_STOP) {
                return;
            }
            final boolean offset = e.dx() != 0 || e.dy() != 0 || e.dz() != 0;
            final boolean extraOne = e.extra() == 1.0;
            final boolean extra = !extraOne && e.extra() != 0;
            final boolean zone = e.phase() == PHASE_START || e.phase() == PHASE_UPDATE;
            final boolean duration = zone && e.duration() >= 0;
            out.u8((offset ? OPT_OFFSET : 0) | (extra ? OPT_EXTRA : 0) | (extraOne ? OPT_EXTRA_ONE : 0) | (duration ? OPT_DURATION : 0));
            out.varint(styleIndex);
            position(out, far, e.x(), e.y(), e.z());
            if (hasEnd(e.shape())) {
                position(out, far, e.ex(), e.ey(), e.ez());
            }
            if (hasRadius(e.shape())) {
                out.u16((int) Math.max(0, Math.min(0xffff, Math.round(e.radius() * RADIUS_SCALE))));
            }
            out.varint(Math.max(0, e.count()));
            if (zone) {
                out.u8((int) Math.max(0, Math.min(255, Math.round(e.density() * 255))));
            }
            if (duration) {
                out.varint(e.duration());
            }
            if (offset) {
                out.f32((float) e.dx());
                out.f32((float) e.dy());
                out.f32((float) e.dz());
            }
            if (extra) {
                out.f32((float) e.extra());
            }
        }

        private void position(final Buf out, final boolean far, final double x, final double y, final double z) {
            if (far) {
                out.f32((float) (x - ox));
                out.f32((float) (y - oy));
                out.f32((float) (z - oz));
            } else {
                out.i16((int) Math.round((x - ox) * NEAR_SCALE));
                out.i16((int) Math.round((y - oy) * NEAR_SCALE));
                out.i16((int) Math.round((z - oz) * NEAR_SCALE));
            }
        }

        /** 最後的封包。 */
        public byte[] toBytes() {
            final Buf out = new Buf();
            out.u8(FORMAT);
            out.zigzag(ox);
            out.zigzag(oy);
            out.zigzag(oz);
            out.varint(stringList.size());
            out.append(stringBuf);
            out.varint(styles.size());
            out.append(styleBuf);
            out.varint(effects);
            out.append(body);
            return out.toByteArray();
        }
    }

    static boolean near(final double x, final double y, final double z) {
        final double max = 32767 / NEAR_SCALE;
        return Math.abs(x) <= max && Math.abs(y) <= max && Math.abs(z) <= max;
    }

    private static int utf8Length(final String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- 讀（測試用，跟模組那邊一樣的規則） ----

    /** 讀一個封包；格式不對回傳 null。 */
    public static List<Decoded> decode(final byte[] data) {
        try {
            final In in = new In(data);
            if (in.u8() != FORMAT) {
                return null;
            }
            final int ox = in.zigzag();
            final int oy = in.zigzag();
            final int oz = in.zigzag();
            final int stringCount = in.count(MAX_TABLE);
            final String[] strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                strings[i] = in.string();
            }
            final int styleCount = in.count(MAX_TABLE);
            final Style[] styles = new Style[styleCount];
            for (int i = 0; i < styleCount; i++) {
                final String particle = strings[in.index(stringCount)];
                final int kind = in.u8();
                styles[i] = switch (kind) {
                    case DATA_NONE -> Style.none(particle);
                    case DATA_DUST -> Style.dust(particle, in.i32(), in.f32());
                    case DATA_DUST_TRANSITION -> Style.transition(particle, in.i32(), in.i32(), in.f32());
                    case DATA_BLOCK -> Style.block(particle, strings[in.index(stringCount)]);
                    case DATA_ITEM -> Style.item(particle, strings[in.index(stringCount)]);
                    case DATA_COLOR -> Style.color(particle, in.i32());
                    case DATA_SPELL -> Style.spell(particle, in.i32(), in.f32());
                    case DATA_FLOAT -> Style.floating(particle, in.f32());
                    case DATA_INT -> Style.integer(particle, in.zigzag());
                    case DATA_TRAIL -> {
                        final double tx = ox + in.f32();
                        final double ty = oy + in.f32();
                        final double tz = oz + in.f32();
                        yield Style.trail(particle, tx, ty, tz, in.i32(), in.varint());
                    }
                    default -> throw new IllegalArgumentException("資料種類 " + kind);
                };
            }
            final int effectCount = in.count(MAX_EFFECTS);
            final List<Decoded> out = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                final int head = in.u8();
                final int shape = head & 7;
                final int phase = head >> 3 & 3;
                final boolean important = (head & HEAD_IMPORTANT) != 0;
                final boolean far = (head & HEAD_FAR) != 0;
                if (shape == SHAPE_NUMBER) {
                    final int flags = in.u8();
                    final double[] c = in.position(far, ox, oy, oz);
                    final long value = (flags & NUM_VALUE) != 0 ? in.varint() : -1;
                    final int rgb = in.u8() << 16 | in.u8() << 8 | in.u8();
                    final String text = (flags & NUM_TEXT) != 0 ? strings[in.index(stringCount)] : null;
                    final int target = (flags & NUM_TARGET) != 0 ? in.varint() : -1;
                    out.add(new Decoded(null, null, new Number(c[0], c[1], c[2], value, rgb, (flags & NUM_EMPHASIS) != 0, (flags & NUM_SELF) != 0,
                            (flags & NUM_ABSORBED) != 0, text, target)));
                    continue;
                }
                if (shape > SHAPE_BEAM) {
                    return null;
                }
                final long id = phase == PHASE_ONCE ? 0 : in.varlong();
                if (phase == PHASE_STOP) {
                    out.add(new Decoded(Entry.stop(shape, id, important), null));
                    continue;
                }
                final int opts = in.u8();
                final Style style = styles[in.index(styleCount)];
                final double[] c = in.position(far, ox, oy, oz);
                final double[] end = hasEnd(shape) ? in.position(far, ox, oy, oz) : c;
                final double radius = hasRadius(shape) ? in.u16() / RADIUS_SCALE : 0;
                final int count = in.varint();
                final boolean zone = phase == PHASE_START || phase == PHASE_UPDATE;
                final double density = zone ? in.u8() / 255.0 : 1.0;
                final int duration = (opts & OPT_DURATION) != 0 ? in.varint() : zone ? -1 : 0;
                double dx = 0;
                double dy = 0;
                double dz = 0;
                if ((opts & OPT_OFFSET) != 0) {
                    dx = in.f32();
                    dy = in.f32();
                    dz = in.f32();
                }
                final double extra = (opts & OPT_EXTRA) != 0 ? in.f32() : (opts & OPT_EXTRA_ONE) != 0 ? 1.0 : 0.0;
                out.add(new Decoded(new Entry(shape, phase, id, important, c[0], c[1], c[2], end[0], end[1], end[2], radius, count, density,
                        duration, dx, dy, dz, extra), style));
            }
            return out;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // ---- 位元組 ----

    /** 可以一直長的位元組（大端序）。 */
    static final class Buf extends ByteArrayOutputStream {
        Buf() {
            super(256);
        }

        void u8(final int v) {
            write(v & 0xff);
        }

        void i16(final int v) {
            final int clamped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            write(clamped >> 8 & 0xff);
            write(clamped & 0xff);
        }

        void u16(final int v) {
            write(v >> 8 & 0xff);
            write(v & 0xff);
        }

        void i32(final int v) {
            write(v >>> 24);
            write(v >>> 16 & 0xff);
            write(v >>> 8 & 0xff);
            write(v & 0xff);
        }

        void f32(final float v) {
            i32(Float.floatToIntBits(v));
        }

        void varint(final int value) {
            int v = value;
            while ((v & ~0x7f) != 0) {
                write(v & 0x7f | 0x80);
                v >>>= 7;
            }
            write(v);
        }

        void varlong(final long value) {
            long v = value;
            while ((v & ~0x7fL) != 0) {
                write((int) (v & 0x7f) | 0x80);
                v >>>= 7;
            }
            write((int) v);
        }

        void zigzag(final int v) {
            varint(v << 1 ^ v >> 31);
        }

        void bytes(final byte[] b) {
            write(b, 0, b.length);
        }

        void append(final Buf other) {
            write(other.buf, 0, other.count);
        }
    }

    /** 讀位元組；讀過頭丟 IndexOutOfBounds（外面接住當格式不對）。 */
    static final class In {
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

        int u16() {
            return u8() << 8 | u8();
        }

        int i16() {
            return (short) u16();
        }

        int i32() {
            return u8() << 24 | u8() << 16 | u8() << 8 | u8();
        }

        float f32() {
            return Float.intBitsToFloat(i32());
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

        long varlong() {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                final int b = u8();
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("varlong 太長");
        }

        int zigzag() {
            final int v = varint();
            return v >>> 1 ^ -(v & 1);
        }

        int count(final int max) {
            final int n = varint();
            if (n < 0 || n > max) {
                throw new IllegalArgumentException("數量 " + n);
            }
            return n;
        }

        int index(final int size) {
            final int i = varint();
            if (i < 0 || i >= size) {
                throw new IndexOutOfBoundsException("索引 " + i);
            }
            return i;
        }

        String string() {
            final int length = count(1024);
            if (pos + length > data.length) {
                throw new IndexOutOfBoundsException();
            }
            final String s = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return s;
        }

        double[] position(final boolean far, final int ox, final int oy, final int oz) {
            if (far) {
                return new double[] {ox + (double) f32(), oy + (double) f32(), oz + (double) f32()};
            }
            return new double[] {ox + i16() / NEAR_SCALE, oy + i16() / NEAR_SCALE, oz + i16() / NEAR_SCALE};
        }
    }
}
