package tw.exilecore.clientmod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 2026-11-02：增益的總長（純邏輯，測試用）。插件的增益只記「到哪個 tick 結束」，模組要畫剩下的比例：
 * 第一次看到這個增益（或剩下的時間變多了＝重新放了）時記下那時候剩幾 tick 當總長。
 */
final class BuffTotals {

    private final Map<String, Long> totals = new HashMap<>();
    private final Map<String, Long> lastLeft = new HashMap<>();
    private final Set<String> seen = new HashSet<>();

    /** 這一輪看到一個增益：回傳它的總長。 */
    long observe(final String key, final long left) {
        seen.add(key);
        final Long before = lastLeft.get(key);
        lastLeft.put(key, left);
        final Long total = totals.get(key);
        if (total == null || before == null || left > before) {
            totals.put(key, left);
            return left;
        }
        return Math.max(total, left);
    }

    /** 一輪結束：這一輪沒看到的增益忘掉（下次出現重新算總長）。 */
    void endRound() {
        totals.keySet().retainAll(seen);
        lastLeft.keySet().retainAll(seen);
        seen.clear();
    }
}
