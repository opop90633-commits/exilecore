package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：模組畫的傷害數字（伺服器送 exilecore:fx 的 NUMBER）。沒有數量上限（只留一個防亂送的
 * {@link #SAFETY_CAP}）、平滑往上飄、暴擊放大、最後淡出；設定「合併同一個目標」時，短時間內打同一隻的數字加在一起。
 * 這次之前是伺服器生文字顯示實體（每人最多 16 個、往上瞬移一次）。純 Java（測試用），只在遊戲主執行緒用。
 */
public final class FloatingNumbers {

    /** 打出的數字飄幾 tick；受到的。 */
    public static final int LIFETIME = 24;
    public static final int SELF_LIFETIME = 30;
    /** 合併：同一個目標多久之內的數字加在一起（tick）。 */
    public static final int MERGE_WINDOW = 12;
    /** 防亂送：最多同時幾個（正常打不到）。 */
    public static final int SAFETY_CAP = 4096;
    /** 打出的數字往上飄幾格。 */
    public static final double RISE = 0.9;

    /** 一個在飄的數字。 */
    public static final class Floater {
        public final double x;
        public final double y;
        public final double z;
        public long value;
        public int rgb;
        public boolean emphasis;
        public final boolean self;
        public final boolean absorbed;
        public final String text;
        public final int target;
        /** 亂數的水平偏移（格；受到的是畫面的偏移比例 -1～1）。 */
        public final float jitterX;
        public final float jitterZ;
        public int age;
        /** 合併時跳一下（倒數 tick）。 */
        public int pulse;
        /** 合併進來的最大一下（決定顏色）。 */
        long biggest;

        Floater(final FxCodec.Number n, final float jitterX, final float jitterZ) {
            this.x = n.x();
            this.y = n.y();
            this.z = n.z();
            this.value = n.value();
            this.rgb = n.rgb();
            this.emphasis = n.emphasis();
            this.self = n.self();
            this.absorbed = n.absorbed();
            this.text = n.text();
            this.target = n.target();
            this.jitterX = jitterX;
            this.jitterZ = jitterZ;
            this.biggest = n.value();
        }

        public int lifetime() {
            return self ? SELF_LIFETIME : LIFETIME;
        }

        /** 0～1：這個數字過了多少（partial 是這一幀在 tick 之間的比例）。 */
        public double progress(final float partial) {
            return Math.min(1, (age + partial) / lifetime());
        }

        /** 畫的文字：「1,234」「12.3k」，暴擊加「!」，後面的字（屬性、閃避）。 */
        public String label() {
            final String number = value >= 0 ? (self ? "-" : "") + format(value) + (emphasis && !self ? "!" : "") : "";
            if (text == null || text.isEmpty()) {
                return number;
            }
            return number.isEmpty() ? text : number + " " + text;
        }
    }

    private final List<Floater> floaters = new ArrayList<>();
    private long seed = 0x9E3779B97F4A7C15L;

    public List<Floater> floaters() {
        return floaters;
    }

    public int size() {
        return floaters.size();
    }

    public void clear() {
        floaters.clear();
    }

    /** 收到一個數字；merge = 設定「合併同一個目標」。回傳畫它的那個（合併時是舊的那個）。 */
    public Floater add(final FxCodec.Number n, final boolean merge) {
        if (merge && n.target() >= 0 && !n.self() && n.value() >= 0) {
            for (int i = floaters.size() - 1; i >= 0; i--) {
                final Floater f = floaters.get(i);
                if (f.target == n.target() && !f.self && f.absorbed == n.absorbed() && f.value >= 0 && f.age < MERGE_WINDOW) {
                    f.value += n.value();
                    f.emphasis |= n.emphasis();
                    if (n.value() > f.biggest) {
                        f.biggest = n.value();
                        f.rgb = n.rgb();
                    }
                    f.age = Math.min(f.age, 3); // 重新飄（不要一直卡在頂端）
                    f.pulse = 4;
                    return f;
                }
            }
        }
        if (floaters.size() >= SAFETY_CAP) {
            floaters.removeFirst();
        }
        final Floater created = new Floater(n, random() * (n.self() ? 1f : 0.8f), random() * 0.8f);
        floaters.add(created);
        return created;
    }

    /** 過一個 tick：變老、到時間的拿掉。 */
    public void tick() {
        final Iterator<Floater> iterator = floaters.iterator();
        while (iterator.hasNext()) {
            final Floater f = iterator.next();
            f.age++;
            if (f.pulse > 0) {
                f.pulse--;
            }
            if (f.age >= f.lifetime()) {
                iterator.remove();
            }
        }
    }

    /** -0.5～0.5 的亂數（固定的序列，測試才穩）。 */
    private float random() {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return ((seed >>> 40) / (float) (1L << 24)) - 0.5f;
    }

    // ---- 動畫（純函式） ----

    /** 往上飄了多少（0～1 的比例，先快後慢）。 */
    public static double rise(final double progress) {
        final double t = Math.max(0, Math.min(1, progress));
        return 1 - (1 - t) * (1 - t);
    }

    /** 不透明度：前 60% 全亮，之後線性淡出。 */
    public static float alpha(final double progress) {
        final double t = Math.max(0, Math.min(1, progress));
        return t <= 0.6 ? 1f : (float) Math.max(0, 1 - (t - 0.6) / 0.4);
    }

    /** 大小：一般 1、護盾吃掉的 0.85、只有字的 0.8、暴擊 1.5（剛冒出來時 1.9 縮回來）；合併時跳一下。 */
    public static float scale(final Floater f, final float partial) {
        float base = f.value < 0 ? 0.8f : f.absorbed ? 0.85f : 1f;
        if (f.emphasis) {
            final double t = Math.min(1, (f.age + partial) / 4.0);
            base = (float) (1.9 - 0.4 * t);
        }
        if (f.pulse > 0) {
            base *= 1 + 0.06f * f.pulse;
        }
        return base;
    }

    /** 1,234、12.3k、1.2M（跟伺服器一樣）。 */
    public static String format(final long value) {
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%,d", value);
        }
        if (value < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
    }
}
