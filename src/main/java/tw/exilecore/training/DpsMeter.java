package tw.exilecore.training;

/**
 * 打一隻訓練假人的一段紀錄（停手幾秒就結束）：總傷害、每秒傷害、命中、暴擊、未命中、格擋、最高一擊、點燃。純邏輯，不依賴 Bukkit。
 * <p>
 * 經過的時間＝第一下到最後一下，再加上平均每下的間隔（打 10 下、每秒一下＝10 秒，不是 9 秒）；
 * 點燃在最後一下之後還在燒的話算到最後一跳。至少算 1 秒。
 */
public final class DpsMeter {

    /** 點燃每跳的間隔（AilmentService 每 10 tick 燒一次）。 */
    static final double DOT_INTERVAL_SECONDS = 0.5;

    private long first = -1;
    private long lastAttempt = -1;
    private long lastDot = -1;
    private int attempts;
    private int hits;
    private int crits;
    private int misses;
    private int blocks;
    private double total;
    private double dot;
    private double highest;

    /** 打中（傷害是減免之後的）。 */
    public void hit(final long nowMillis, final double damage, final boolean crit) {
        attempt(nowMillis);
        hits++;
        if (crit) {
            crits++;
        }
        total += Math.max(0, damage);
        highest = Math.max(highest, damage);
    }

    /** 沒打中（閃避）。 */
    public void miss(final long nowMillis) {
        attempt(nowMillis);
        misses++;
    }

    /** 被格擋。 */
    public void block(final long nowMillis) {
        attempt(nowMillis);
        blocks++;
    }

    /** 點燃的一跳。 */
    public void dot(final long nowMillis, final double damage) {
        if (first < 0) {
            first = nowMillis;
        }
        lastDot = Math.max(lastDot, nowMillis);
        total += Math.max(0, damage);
        dot += Math.max(0, damage);
    }

    private void attempt(final long nowMillis) {
        if (first < 0) {
            first = nowMillis;
        }
        lastAttempt = Math.max(lastAttempt, nowMillis);
        attempts++;
    }

    /** 最後一次有動靜的時間（判斷停手了沒）。 */
    public long lastActivity() {
        return Math.max(lastAttempt, lastDot);
    }

    public boolean isEmpty() {
        return first < 0;
    }

    /** 經過的秒數（見類別說明）。 */
    public double seconds() {
        if (first < 0) {
            return 0;
        }
        double end;
        if (attempts > 0) {
            final double span = (lastAttempt - first) / 1000.0;
            final double interval = attempts > 1 ? span / (attempts - 1) : 1.0;
            end = span + interval;
        } else {
            end = 0;
        }
        if (lastDot > lastAttempt) {
            end = Math.max(end, (lastDot - first) / 1000.0 + DOT_INTERVAL_SECONDS);
        }
        return Math.max(1.0, end);
    }

    public double dps() {
        return first < 0 ? 0 : total / seconds();
    }

    public double total() {
        return total;
    }

    public double dotTotal() {
        return dot;
    }

    public double highest() {
        return highest;
    }

    public int hits() {
        return hits;
    }

    public int crits() {
        return crits;
    }

    public int misses() {
        return misses;
    }

    public int blocks() {
        return blocks;
    }

    /** 命中率（打中 ÷ 出手；沒出手是 0）。 */
    public double hitRate() {
        return attempts == 0 ? 0 : (double) hits / attempts;
    }

    /** 整數加上千分位（1,234,568）。 */
    public static String number(final double value) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.round(value));
    }

    /** 暴擊率（暴擊 ÷ 打中）。 */
    public double critRate() {
        return hits == 0 ? 0 : (double) crits / hits;
    }
}
