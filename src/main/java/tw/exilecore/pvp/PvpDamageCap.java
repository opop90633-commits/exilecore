package tw.exilecore.pvp;

import java.util.ArrayDeque;

/**
 * PvP 傷害的保底（2026-10）：一個人在競技場裡最近一秒被玩家打掉多少。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>單一下最多打掉 hitCap（總血量的幾 %，換算好的數字；0 = 不限）。</li>
 *   <li>最近一秒（20 tick）加起來最多 windowCap（0 = 不限）；超過的那一部分直接不算。</li>
 *   <li>這一秒還沒被打過時，第一下可以打到 hitCap（就算 hitCap 比 windowCap 高）——所以爆發型一下最多 hitCap，
 *       接著要等這一秒過去；連續輸出型每秒最多 windowCap。</li>
 * </ul>
 * 不分是誰打的：好幾個人一起打同一個人，加起來也是這個上限。
 */
final class PvpDamageCap {

    /** 算「最近一秒」的長度（tick）。 */
    static final int WINDOW_TICKS = 20;

    /** 最近一秒內每一筆：{tick, 傷害}。 */
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();

    /**
     * 這一筆傷害實際算多少（並記下來）。
     *
     * @param amount    原本要扣的（護盾＋生命＋魔力那幾份加起來）
     * @param hitCap    單一下的上限（0 = 不限）；持續傷害（hit = false）不看這個
     * @param windowCap 最近一秒的上限（0 = 不限）
     * @param tick      現在的 tick
     * @param hit       是擊中（不是持續傷害的一跳）
     */
    double allow(final double amount, final double hitCap, final double windowCap, final long tick, final boolean hit) {
        if (amount <= 0) {
            return 0;
        }
        while (!recent.isEmpty() && (tick - recent.peekFirst()[0] >= WINDOW_TICKS || recent.peekFirst()[0] > tick)) {
            recent.pollFirst();
        }
        double used = 0;
        for (final double[] entry : recent) {
            used += entry[1];
        }
        double allowed = amount;
        if (hit && hitCap > 0) {
            allowed = Math.min(allowed, hitCap);
        }
        if (windowCap > 0) {
            final boolean opening = hit && hitCap > 0 && used <= 0;
            allowed = Math.min(allowed, opening ? Math.max(hitCap, windowCap) : Math.max(0, windowCap - used));
        }
        if (allowed > 0) {
            recent.addLast(new double[] {tick, allowed});
        }
        return allowed;
    }

    /** 最近一秒都沒有紀錄（可以丟掉了）。 */
    boolean idle(final long tick) {
        return recent.isEmpty() || tick - recent.peekLast()[0] >= WINDOW_TICKS;
    }
}
