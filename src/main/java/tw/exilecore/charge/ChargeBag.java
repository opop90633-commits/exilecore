package tw.exilecore.charge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一個玩家身上某一種球的持有狀態：每一顆各自記什麼時候消失（tick）。純函式，不依賴 Bukkit。
 * <p>
 * 2026-10-27 照 PoE2（poe2db Charges：「戰星預設撐 15 秒，獲得同一種的另一顆時刷新」）：獲得一顆時，這一種的每一顆都刷新成新的持續時間
 * （滿了就只刷新），所以同一種的球一起到期、一起掉。
 * 這次之前：每顆各自倒數，滿了只刷新最舊那一顆，過期的一顆一顆掉。
 */
public final class ChargeBag {

    /** 每一顆消失的 tick，由早到晚。 */
    private final List<Long> expiries = new ArrayList<>();

    public int count() {
        return expiries.size();
    }

    public boolean isEmpty() {
        return expiries.isEmpty();
    }

    /** 到 now 還沒過期的顆數（過期的要等下一次檢查才丟掉，這裡先不算）。 */
    public int count(final long now) {
        int live = 0;
        for (final long expiry : expiries) {
            if (expiry > now) {
                live++;
            }
        }
        return live;
    }

    /** 最近一顆消失的 tick；沒有球回傳 -1。 */
    public long nextExpiry() {
        return expiries.isEmpty() ? -1 : expiries.getFirst();
    }

    /**
     * 獲得一顆。
     *
     * @param now           現在的 tick
     * @param max           最多幾顆
     * @param durationTicks 這一顆撐多久（tick）
     * @return 顆數有沒有變多（滿了只刷新時間 = false）
     */
    public boolean gain(final long now, final int max, final long durationTicks) {
        expire(now);
        if (max <= 0) {
            return false;
        }
        boolean grew = false;
        if (expiries.size() >= max) {
            expiries.removeFirst(); // 滿了：最舊那顆換成新的（等於刷新時間）
        } else {
            grew = true;
        }
        final long until = now + Math.max(1, durationTicks);
        expiries.add(until);
        expiries.replaceAll(expiry -> until); // 2026-10-27 照 PoE2：同一種的全部刷新
        Collections.sort(expiries);
        return grew;
    }

    /** 用掉一顆（最快消失的那顆，留下撐比較久的；已經過期的先丟掉、不算）；沒有球回傳 false。 */
    public boolean consume(final long now) {
        expire(now);
        if (expiries.isEmpty()) {
            return false;
        }
        expiries.removeFirst();
        return true;
    }

    /** 丟掉已經過期的；回傳丟了幾顆。 */
    public int expire(final long now) {
        int dropped = 0;
        while (!expiries.isEmpty() && expiries.getFirst() <= now) {
            expiries.removeFirst();
            dropped++;
        }
        return dropped;
    }

    /** 上限變小了（脫掉加最大顆數的裝備）：多出來的丟掉，先丟快消失的；回傳丟了幾顆。 */
    public int trim(final int max) {
        int dropped = 0;
        while (expiries.size() > Math.max(0, max)) {
            expiries.removeFirst();
            dropped++;
        }
        return dropped;
    }

    public void clear() {
        expiries.clear();
    }
}
