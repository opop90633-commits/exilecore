package tw.exilecore.map;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 巔峰的每秒傷害上限（2026-10，maps.yml 的 pinnacle.dps_cap、dps_over）：每個玩家（連他的召喚物、持續傷害）對巔峰 Boss
 * 每秒傷害超過上限的部分大幅減傷（分段：例如 15–30 萬的部分算 30%，30 萬以上算 10%）。
 * <p>
 * 這個人的每秒傷害：這一下之前、最近約 {@link #WINDOW_SECONDS} 秒實際打出來的平均（2026-10-29 起不含這一下、不看打假人的紀錄：
 * 單發大傷害照算，下一下才遞減）。每一下都乘同一個比例 = 分段算出來的等效每秒傷害 ÷ 每秒傷害。
 * <p>
 * 2026-10-21：以前一直拿假人紀錄當下限：紀錄是打沒有防禦的白怪、而且是那個人打過的最高紀錄（可能是別的技能、別套裝備），
 * 打 Boss 實際每秒 14 萬、紀錄換算 47 萬的人，每一下都被乘 0.36，實際只剩 5 萬——比上限 15 萬還低很多。
 * 純邏輯，不依賴 Bukkit。
 */
final class DpsCap {

    /** 實際打出來的每秒傷害看最近幾秒（衰減的時間常數）。2026-10-29 照站長：2 → 4 秒（看最近幾秒的平均）。 */
    static final double WINDOW_SECONDS = 4;
    /** 剛開打的幾秒內，假人紀錄還算數（實際打出來的還沒累積起來）。 */
    static final double WARMUP_SECONDS = 4;

    /** 每個人：[最近的累積傷害（會衰減）, 上次更新的 tick, 開打的 tick, 這場原本的總傷害, 這場遞減後的總傷害]。 */
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
        if (amount <= 0) {
            return amount;
        }
        if (recent.size() > 512) {
            // 2026-10-17：世界 Boss 的紀錄（world:玩家）不跟實例一起清：太多筆時把一分鐘沒更新的丟掉
            recent.values().removeIf(old -> tick - old[1] > 60 * 20);
        }
        final double[] state = recent.computeIfAbsent(key, k -> new double[] {0, tick, tick, 0, 0});
        if (cap <= 0) {
            // 2026-10-26：Boss 的每秒傷害遞減拿掉了（管理員決定）：照原本的傷害算，只記 /ec bossdps 的統計
            state[1] = tick;
            state[3] += amount;
            state[4] += amount;
            return amount;
        }
        final double elapsed = Math.max(0, tick - state[1]) / 20.0;
        if (elapsed > WINDOW_SECONDS * 5) {
            state[2] = tick; // 停手好一陣子（離開、換階段）再打：重新算開場
        }
        // 2026-10-29 照站長「要能接受一下大傷害」：遞減看的是這一下「之前」最近幾秒的平均每秒傷害（不含這一下）——
        //   單發的大傷害照算，打完之後平均變高，接下來的才開始遞減；打假人的紀錄不再拿來當開場的下限（recordDps 不用了）。
        //   這次之前平均含這一下本身（一下 1000 萬的平均直接 500 萬，這一下自己就被減）、開場 4 秒取假人紀錄與實際的較高者
        final double before = state[0] * Math.exp(-elapsed / WINDOW_SECONDS);
        final double dps = before / WINDOW_SECONDS;
        state[0] = before + amount;
        state[1] = tick;
        final double result = dps <= cap ? amount : amount * effective(dps, cap, over) / dps;
        state[3] += amount; // 2026-10-21：/ec bossdps 的統計（這場每個人原本、遞減後各打了多少）
        state[4] += result;
        return result;
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

    /** 這張圖每個人這場的總傷害：玩家 → [原本的, 遞減後的]（2026-10-21，Boss 倒下時記進 /ec bossdps 的統計）。 */
    Map<java.util.UUID, double[]> totals(final int instanceId) {
        final String prefix = instanceId + ":";
        final Map<java.util.UUID, double[]> result = new HashMap<>();
        for (final Map.Entry<String, double[]> entry : recent.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                try {
                    result.put(java.util.UUID.fromString(entry.getKey().substring(prefix.length())),
                            new double[] {entry.getValue()[3], entry.getValue()[4]});
                } catch (final IllegalArgumentException ignored) {
                    // 不是玩家的鍵
                }
            }
        }
        return result;
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
