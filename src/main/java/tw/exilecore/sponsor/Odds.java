package tw.exilecore.sponsor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** 抽獎箱的機率（純邏輯，單元測試直接跑）。 */
public final class Odds {

    private Odds() {
    }

    /** 照權重抽一個；roll 是 [0, 1)。 */
    public static String pick(final Map<String, Double> weights, final double roll) {
        double total = 0;
        for (final double weight : weights.values()) {
            total += weight;
        }
        double at = roll * total;
        String last = null;
        for (final Map.Entry<String, Double> entry : weights.entrySet()) {
            last = entry.getKey();
            at -= entry.getValue();
            if (at < 0) {
                return entry.getKey();
            }
        }
        return last;
    }

    /** 權重換成百分比的寫法（「3.5%」「0.25%」）。 */
    public static String percent(final double weight, final double total) {
        if (total <= 0) {
            return "0%";
        }
        final double value = weight / total * 100;
        final int scale = value >= 10 ? 1 : 2;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }
}
