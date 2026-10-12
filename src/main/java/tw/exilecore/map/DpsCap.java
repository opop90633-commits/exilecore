package tw.exilecore.map;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 巔峰的每秒傷害上限（2026-10，maps.yml 的 pinnacle.dps_cap、dps_over）：每個玩家（連他的召喚物、持續傷害）對巔峰 Boss
 * 每秒傷害超過上限的部分大幅減傷（分段：例如 15–30 萬的部分算 30%，30 萬以上算 10%）。
 * <p>
 * 這個人的每秒傷害取兩個的較高者：打訓練假人的實測紀錄、這場實際打出來的（最近約 {@link #WINDOW_SECONDS} 秒）。
 * 沒打過假人、或打出來比紀錄高，照樣減得到。每一下都乘同一個比例 = 分段算出來的等效每秒傷害 ÷ 每秒傷害。
 * 純邏輯，不依賴 Bukkit。
 */
final class DpsCap {

    /** 實際打出來的每秒傷害看最近幾秒（衰減的時間常數）。 */
    static final double WINDOW_SECONDS = 2;

    /** 每個人：[最近的累積傷害（會衰減）, 上次更新的 tick]。 */
    private final Map<String, double[]> recent = new HashMap<>();

    /**
     * 這一下實際算多少傷害。
     *
     * @param key       誰打的（實例代號＋玩家）
     * @param amount    原本的傷害
     * @param cap       每秒上限（≤ 0 = 不限）
     * @param over      超過上限的分段（空的 = 超過的部分全部不算）
     * @param tick      現在的伺服器 tick
     * @param recordDps 這個人打假人的紀錄（沒有寫 0）
     */
    double apply(final String key, final double amount, final double cap, final List<MapConfig.DpsTier> over, final long tick,
                 final double recordDps) {
        if (amount <= 0 || cap <= 0) {
            return amount;
        }
        final double[] state = recent.computeIfAbsent(key, k -> new double[] {0, tick});
        final double elapsed = Math.max(0, tick - state[1]) / 20.0;
        state[0] = state[0] * Math.exp(-elapsed / WINDOW_SECONDS) + amount;
        state[1] = tick;
        final double dps = Math.max(recordDps, state[0] / WINDOW_SECONDS);
        return dps <= cap ? amount : amount * effective(dps, cap, over) / dps;
    }

    /** 每秒傷害 dps 換成等效的每秒傷害：上限以內照算，超過的部分照分段的比例。 */
    static double effective(final double dps, final double cap, final List<MapConfig.DpsTier> over) {
        if (cap <= 0 || dps <= cap) {
            return dps;
        }
        double total = cap;
        for (int i = 0; i < over.size(); i++) {
            final double low = Math.max(cap, over.get(i).from());
            final double high = i + 1 < over.size() ? over.get(i + 1).from() : Double.MAX_VALUE;
            if (dps > low) {
                total += (Math.min(dps, high) - low) * over.get(i).rate();
            }
        }
        return total;
    }

    /** 實例關掉：那張圖的紀錄都清掉。 */
    void forget(final int instanceId) {
        final String prefix = instanceId + ":";
        recent.keySet().removeIf(key -> key.startsWith(prefix));
    }

    static String key(final int instanceId, final java.util.UUID player) {
        return instanceId + ":" + player;
    }
}
