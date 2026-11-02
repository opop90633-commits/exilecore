package tw.exilecore.client.core;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 2026-11-02 照站長（1.4）：客戶端自己算的每小時經驗（經驗條上寫的）。照伺服器送來的等級、這一級的經驗、到下一級要幾點，
 * 累積拿到多少經驗（升級的時候把上一級剩下的補上），用最近 {@link #WINDOW_MS} 的量換算。換角色（等級往下、一次跳好幾級）重新算。
 * 純 Java（測試用）。
 */
public final class XpRate {

    /** 用最近幾毫秒來算。 */
    public static final long WINDOW_MS = 10 * 60 * 1000L;
    /** 至少看了幾毫秒才寫（剛開始的數字跳很大）。 */
    public static final long MIN_MS = 30 * 1000L;

    private record Sample(long millis, double gained) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private int level = -1;
    private long xp;
    private long need;
    private double gained;

    /** 收到一次資料。 */
    public void observe(final long millis, final int level, final long xp, final long need) {
        if (level <= 0) {
            return;
        }
        if (this.level < 0 || level < this.level || level > this.level + 1) {
            reset();
        } else if (level == this.level + 1) {
            gained += Math.max(0, this.need - this.xp) + Math.max(0, xp);
        } else if (xp >= this.xp) {
            gained += xp - this.xp;
        } else {
            reset(); // 同一級經驗變少（死亡懲罰、換角色）：重新算
        }
        this.level = level;
        this.xp = xp;
        this.need = need;
        if (samples.isEmpty() || samples.getLast().gained() != gained || millis - samples.getLast().millis() > 5000) {
            samples.addLast(new Sample(millis, gained));
        }
        while (samples.size() > 2 && millis - samples.getFirst().millis() > WINDOW_MS) {
            samples.removeFirst();
        }
    }

    public void reset() {
        samples.clear();
        level = -1;
        gained = 0;
    }

    /** 每小時多少經驗；看的時間不夠回傳 -1。 */
    public double perHour(final long millis) {
        if (samples.isEmpty()) {
            return -1;
        }
        final Sample first = samples.getFirst();
        final long span = millis - first.millis();
        if (span < MIN_MS) {
            return -1;
        }
        return (gained - first.gained()) * 3_600_000.0 / span;
    }

    /** 經驗的數字：一萬以上寫「12.3萬」、一億以上寫「1.23億」。 */
    public static String format(final double value) {
        if (value >= 1e8) {
            return String.format(Locale.ROOT, "%.2f億", value / 1e8);
        }
        if (value >= 1e4) {
            return String.format(Locale.ROOT, "%.1f萬", value / 1e4);
        }
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }

    /** 百分比：「42.0%」。 */
    public static String percent(final double progress) {
        final long tenths = Math.round(Math.max(0, Math.min(1, progress)) * 1000);
        return tenths / 10 + "." + tenths % 10 + "%";
    }
}
