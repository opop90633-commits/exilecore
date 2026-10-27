package tw.exilecore.character;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-27 照 PoE2（PoB CalcSetup：MaxLifeLeechRate／MaxManaLeechRate 20、MaxLifeLeechInstance／MaxManaLeechInstance 10；
 * poe2db Leech：「偷取在一秒內回復」）：一種資源（生命或魔力）的偷取。
 * <ul>
 *   <li>擊中偷到的每一份是一個「偷取」：最多最大值的 10%（多的不算），在 1 秒內回完</li>
 *   <li>同時進行的偷取加起來每秒最多回最大值的 20%（「增加最大偷取速度」再乘）；超過的部分放慢（不會不見）</li>
 *   <li>資源滿了，剩下的偷取全部結束</li>
 * </ul>
 * 這次之前：全部存進一個池子，每秒最多回最大值的 10%，一份沒有上限。
 * 不是擊中來的「存進偷取慢慢回」（昇華、傳奇的補償）照舊每秒回最大值的 10%，不受一份 10% 的上限。純計算，不依賴 Bukkit。
 */
public final class Leech {

    /** 每秒最多回最大值的這個比例（PoB MaxLifeLeechRate 20）。 */
    public static final double MAX_RATE = 0.20;
    /** 一份偷取最多最大值的這個比例（PoB MaxLifeLeechInstance 10）。 */
    public static final double MAX_INSTANCE = 0.10;
    /** 一份偷取回完要幾秒（poe2db：一秒）。 */
    public static final double INSTANCE_SECONDS = 1.0;
    /** 不是擊中來的「慢慢回」：每秒回最大值的這個比例（跟這次之前的偷取一樣）。 */
    public static final double SLOW_RATE = 0.10;

    /** 每一份：{剩下多少, 每秒回多少}。 */
    private final List<double[]> instances = new ArrayList<>();

    /** 擊中偷到的一份：最多 max × 10%，1 秒內回完。回傳實際加進去的量。 */
    public double addHit(final double amount, final double max) {
        final double capped = Math.min(amount, Math.max(0, max) * MAX_INSTANCE);
        if (capped > 0) {
            instances.add(new double[] {capped, capped / INSTANCE_SECONDS});
        }
        return Math.max(0, capped);
    }

    /** 不是擊中來的：照每秒最大值 10% 的速度慢慢回（沒有一份的上限）。 */
    public void addSlow(final double amount, final double max) {
        if (amount > 0) {
            instances.add(new double[] {amount, Math.max(1e-9, Math.max(0, max) * SLOW_RATE)});
        }
    }

    /** 還沒回的總量。 */
    public double pending() {
        double total = 0;
        for (final double[] instance : instances) {
            total += instance[0];
        }
        return total;
    }

    public void clear() {
        instances.clear();
    }

    /**
     * 經過 seconds 秒：回傳這段時間要回多少（總和最多 max × 20% × rateScale × seconds；超過時每一份照比例放慢）。
     *
     * @param rateScale 最大偷取速度的倍率（「增加 x% 最大偷取速度」）
     */
    public double tick(final double seconds, final double max, final double rateScale) {
        if (instances.isEmpty() || seconds <= 0) {
            return 0;
        }
        double wanted = 0;
        for (final double[] instance : instances) {
            wanted += Math.min(instance[0], instance[1] * seconds);
        }
        final double cap = Math.max(0, max) * MAX_RATE * Math.max(0.1, rateScale) * seconds;
        final double factor = wanted <= cap || wanted <= 0 ? 1.0 : cap / wanted;
        double recovered = 0;
        for (int i = instances.size() - 1; i >= 0; i--) {
            final double[] instance = instances.get(i);
            final double take = Math.min(instance[0], instance[1] * seconds) * factor;
            instance[0] -= take;
            recovered += take;
            if (instance[0] <= 1e-9) {
                instances.remove(i);
            }
        }
        return recovered;
    }
}
