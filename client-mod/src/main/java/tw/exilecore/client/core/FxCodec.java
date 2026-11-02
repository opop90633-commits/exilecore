package tw.exilecore.client.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 特效封包 exilecore:fx（S→C，二進位）的解碼；格式跟插件的 {@code tw.exilecore.clientmod.FxProtocol} 一樣（兩邊要一起改）。純 Java（測試用）。
 *
 * <pre>
 * 封包 = u8 FORMAT(1) · zigzag 原點 x,y,z · varint 字串數 · 字串×（varint 長度 · UTF-8）· varint 樣式數 · 樣式× · varint 特效數 · 特效×
 * 樣式 = varint 粒子（字串）· u8 種類 · 資料（DUST rgb+大小、DUST_TRANSITION 從+到+大小、BLOCK 方塊狀態、ITEM 物品、COLOR argb、
 *        SPELL rgb+power、FLOAT、INT、TRAIL 目標+rgb+tick）
 * 特效 = u8 頭（形狀、階段、重要、遠座標）· 不是 ONCE：varlong 編號（STOP 到這裡）· u8 選項 · varint 樣式 · 中心 · [終點] · [u16 半徑/64]
 *        · varint 顆數 · [u8 密度] · [varint 剩餘 tick] · [3×f32 offset] · [f32 extra]
 * 數字 = u8 頭（形狀 6、遠座標）· u8 旗標（強調、自己受到的、有目標、有字、有數值、護盾吃掉的）· 座標 · [varint 數值] · u8×3 rgb
 *        · [varint 字串] · [varint 目標實體 id]
 * </pre>
 * 2026-11-02 照站長（模組 1.4 B 組）：多了數字（傷害數字改由模組畫；招呼說 dmgnum:true 伺服器才送）。這次之前形狀 6 以上整包不要。
 */
public final class FxCodec {

    public static final int FORMAT = 1;

    public static final int SHAPE_POINT = 0;
    public static final int SHAPE_BURST = 1;
    public static final int SHAPE_RING = 2;
    public static final int SHAPE_AREA = 3;
    public static final int SHAPE_LINE = 4;
    public static final int SHAPE_BEAM = 5;
    /** 2026-11-02：傷害數字。 */
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
    /** 2026-11-02 照站長：能踩的機制圈（站位圈、安全區）：重要（一定畫）但不染紅。這次之前沒有這個位元。 */
    static final int HEAD_SAFE = 1 << 7;
    static final int OPT_OFFSET = 1;
    static final int OPT_EXTRA = 1 << 1;
    static final int OPT_EXTRA_ONE = 1 << 2;
    static final int OPT_DURATION = 1 << 3;
    static final double NEAR_SCALE = 128.0;
    static final double RADIUS_SCALE = 64.0;
    static final int MAX_TABLE = 4096;
    static final int MAX_EFFECTS = 65536;

    private FxCodec() {
    }

    /**
     * 粒子＋資料。particle 是登錄 id（沒寫命名空間 = minecraft）；text 是方塊狀態或物品 id；a、b 顏色；f 大小／power／浮點數；
     * n 整數或 TRAIL 的 tick；tx/ty/tz TRAIL 的目標（世界座標）。
     */
    public record Style(String particle, int kind, int a, int b, float f, int n, String text, double tx, double ty, double tz) {
    }

    /**
     * 一個特效（世界座標）。count：ONCE 是要畫幾顆（伺服器照密度打薄過）、持續範圍是一圈的點數（全密度）；
     * density：持續範圍的密度（0–1）；duration：剩餘 tick（-1 不知道）；style：STOP 是 null。
     */
    public record Effect(int shape, int phase, long id, boolean important, Style style, double x, double y, double z, double ex, double ey,
                         double ez, double radius, int count, double density, int duration, double dx, double dy, double dz, double extra,
                         boolean safe) {

        /** 不是能踩的（舊的寫法）。 */
        public Effect(final int shape, final int phase, final long id, final boolean important, final Style style, final double x,
                      final double y, final double z, final double ex, final double ey, final double ez, final double radius, final int count,
                      final double density, final int duration, final double dx, final double dy, final double dz, final double extra) {
            this(shape, phase, id, important, style, x, y, z, ex, ey, ez, radius, count, density, duration, dx, dy, dz, extra, false);
        }

        public boolean persistent() {
            return phase != PHASE_ONCE;
        }
    }

    /**
     * 2026-11-02：一個傷害數字（世界座標）。value &lt; 0 = 只有字；emphasis 暴擊／大傷害；self 自己受到的；absorbed 被護盾吃掉；
     * text 後面的字（null = 沒有）；target 打到的實體 id（-1 = 沒有）。
     */
    public record Number(double x, double y, double z, long value, int rgb, boolean emphasis, boolean self, boolean absorbed, String text,
                         int target) {
    }

    public static boolean hasEnd(final int shape) {
        return shape == SHAPE_LINE || shape == SHAPE_BEAM;
    }

    public static boolean hasRadius(final int shape) {
        return shape == SHAPE_RING || shape == SHAPE_AREA || shape == SHAPE_LINE || shape == SHAPE_BEAM;
    }

    /** 讀一個封包；格式不對回傳 null（整包不要）。數字略過（{@link #decode(byte[], List)} 才拿得到）。 */
    public static List<Effect> decode(final byte[] data) {
        return decode(data, null);
    }

    /** 讀一個封包；numbers 不是 null 時數字放進去。格式不對回傳 null（整包不要；numbers 可能已經放了一些，呼叫的人自己清）。 */
    public static List<Effect> decode(final byte[] data, final List<Number> numbers) {
        if (data == null) {
            return null;
        }
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
                    case DATA_NONE -> new Style(particle, kind, 0, 0, 0, 0, null, 0, 0, 0);
                    case DATA_DUST -> {
                        final int rgb = in.i32();
                        yield new Style(particle, kind, rgb, 0, in.f32(), 0, null, 0, 0, 0);
                    }
                    case DATA_DUST_TRANSITION -> {
                        final int from = in.i32();
                        final int to = in.i32();
                        yield new Style(particle, kind, from, to, in.f32(), 0, null, 0, 0, 0);
                    }
                    case DATA_BLOCK, DATA_ITEM -> new Style(particle, kind, 0, 0, 0, 0, strings[in.index(stringCount)], 0, 0, 0);
                    case DATA_COLOR -> new Style(particle, kind, in.i32(), 0, 0, 0, null, 0, 0, 0);
                    case DATA_SPELL -> {
                        final int rgb = in.i32();
                        yield new Style(particle, kind, rgb, 0, in.f32(), 0, null, 0, 0, 0);
                    }
                    case DATA_FLOAT -> new Style(particle, kind, 0, 0, in.f32(), 0, null, 0, 0, 0);
                    case DATA_INT -> new Style(particle, kind, 0, 0, 0, in.zigzag(), null, 0, 0, 0);
                    case DATA_TRAIL -> {
                        final double tx = ox + in.f32();
                        final double ty = oy + in.f32();
                        final double tz = oz + in.f32();
                        final int rgb = in.i32();
                        yield new Style(particle, kind, rgb, 0, 0, in.varint(), null, tx, ty, tz);
                    }
                    default -> throw new IllegalArgumentException("資料種類 " + kind);
                };
            }
            final int effectCount = in.count(MAX_EFFECTS);
            final List<Effect> out = new ArrayList<>(Math.min(effectCount, 4096));
            for (int i = 0; i < effectCount; i++) {
                final int head = in.u8();
                final int shape = head & 7;
                final int phase = head >> 3 & 3;
                final boolean important = (head & HEAD_IMPORTANT) != 0;
                final boolean far = (head & HEAD_FAR) != 0;
                final boolean safe = (head & HEAD_SAFE) != 0;
                if (shape == SHAPE_NUMBER) {
                    final int flags = in.u8();
                    final double[] c = in.position(far, ox, oy, oz);
                    final long value = (flags & NUM_VALUE) != 0 ? in.varint() & 0xFFFFFFFFL : -1;
                    final int rgb = in.u8() << 16 | in.u8() << 8 | in.u8();
                    final String text = (flags & NUM_TEXT) != 0 ? strings[in.index(stringCount)] : null;
                    final int target = (flags & NUM_TARGET) != 0 ? in.varint() : -1;
                    if (numbers != null) {
                        numbers.add(new Number(c[0], c[1], c[2], value, rgb, (flags & NUM_EMPHASIS) != 0, (flags & NUM_SELF) != 0,
                                (flags & NUM_ABSORBED) != 0, text, target));
                    }
                    continue;
                }
                if (shape > SHAPE_BEAM) {
                    return null;
                }
                final long id = phase == PHASE_ONCE ? 0 : in.varlong();
                if (phase == PHASE_STOP) {
                    out.add(new Effect(shape, phase, id, important, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
                    continue;
                }
                final int opts = in.u8();
                final Style style = styles[in.index(styleCount)];
                final double[] c = in.position(far, ox, oy, oz);
                final double[] e = hasEnd(shape) ? in.position(far, ox, oy, oz) : c;
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
                if (count < 0 || duration < -1) {
                    return null;
                }
                out.add(new Effect(shape, phase, id, important, style, c[0], c[1], c[2], e[0], e[1], e[2], radius, count, density, duration, dx,
                        dy, dz, extra, safe));
            }
            return out;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 讀位元組（大端序）；讀過頭丟 IndexOutOfBounds。 */
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
            final float v = Float.intBitsToFloat(i32());
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("不是數字");
            }
            return v;
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
