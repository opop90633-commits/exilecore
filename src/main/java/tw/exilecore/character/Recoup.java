package tw.exilecore.character;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 2026-10-26 照 PoE2 的回復（Recoup）：受到的傷害有 x% 在之後 4 秒內平均回來（生命、魔力、能量護盾各一條），
 * 「增加回復的速度」讓這 4 秒變短。這次之前生命的回復存進偷取（每秒最多回 10% 最大生命），魔力的存進魔力偷取。
 * 純邏輯，不依賴 Bukkit。
 */
public final class Recoup {

    /** 基礎：4 秒回完。 */
    public static final long BASE_TICKS = 80;

    public static final int LIFE = 0;
    public static final int MANA = 1;
    public static final int ES = 2;
    /**
     * 2026-10-27：創傷信仰（PoE2 Scarred Faith）回復的能量護盾：回到的護盾不超過護甲（{@link #cappedEs}）。
     * 跟一般的護盾回復（元素傷害回復成護盾的天賦）分開——這次之前混在 ES 裡，有創傷信仰時一般的那份也被夾在護甲。
     */
    public static final int ES_CAPPED = 3;
    private static final int KINDS = 4;

    private static final class Entry {
        final int kind;
        final double perTick;
        long ticksLeft;

        Entry(final int kind, final double perTick, final long ticksLeft) {
            this.kind = kind;
            this.perTick = perTick;
            this.ticksLeft = ticksLeft;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /** 加一筆：amount 在 4 秒 ÷ speed 內平均回來（speed＝1 ＋ 增加回復速度）。 */
    public void add(final int kind, final double amount, final double speed) {
        if (amount <= 0) {
            return;
        }
        final long ticks = Math.max(1, Math.round(BASE_TICKS / Math.max(0.1, speed)));
        entries.add(new Entry(kind, amount / ticks, ticks));
    }

    /** 過了 ticks：回傳這段時間各回多少 {生命, 魔力, 護盾, 創傷信仰的護盾}。 */
    public double[] tick(final long ticks) {
        final double[] out = new double[KINDS];
        if (ticks <= 0 || entries.isEmpty()) {
            return out;
        }
        final Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            final Entry entry = iterator.next();
            final long used = Math.min(ticks, entry.ticksLeft);
            out[entry.kind] += entry.perTick * used;
            entry.ticksLeft -= used;
            if (entry.ticksLeft <= 0) {
                iterator.remove();
            }
        }
        return out;
    }

    /** 還沒回來的總量（某一種）。 */
    public double pending(final int kind) {
        double sum = 0;
        for (final Entry entry : entries) {
            if (entry.kind == kind) {
                sum += entry.perTick * entry.ticksLeft;
            }
        }
        return sum;
    }

    /**
     * 2026-10-27：回復之後的能量護盾：先加一般的（ES），再加創傷信仰的（ES_CAPPED，加到超過護甲的部分不算；本來就超過的不會被拉低）。
     */
    public static double cappedEs(final double current, final double normal, final double capped, final double armour) {
        final double afterNormal = current + Math.max(0, normal);
        if (capped <= 0) {
            return afterNormal;
        }
        return Math.min(afterNormal + capped, Math.max(afterNormal, armour));
    }

    /** 全部清掉（死掉、回滿）。 */
    public void clear() {
        entries.clear();
    }
}
