package tw.exilecore.skill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Boss 一次只做一件事（2026-10，純邏輯，不依賴 Bukkit）。以前場地危害照自己的時間落、不管 Boss 正在放什麼，
 * 齊射與光球還在飛的時候 1.5 秒後就接下一招，看起來像同時放兩招。現在：
 * <ul>
 *   <li>Boss 自己的招：前搖到放完是「正在做」；放完之後齊射還在飛、擴散環還在擴散、光球放出去還不到
 *       {@link #ORB_SETTLE_TICKS}、場地危害還沒落下，都算「還沒完」——這時不出新的招，場地危害也不落。</li>
 *   <li>場地危害照各自的間隔到時間，但要等 Boss 手上這一招放完：放完之後的空檔（gap）裡就可以落；
 *       換階段的停頓、剛看到人的等待中不落（招式引擎用 {@link #delayHazards} 把計時延到等待結束）；
 *       落下之前 Boss 也不出新的招。同時到時間的一次只落一條，最早到的先。</li>
 * </ul>
 * 留在地上的區域（毒池、暴風雪）不算：它們是上一招留下的地形，會跟下一招同時存在。
 * 凍結、暈眩這些「被控住」的時候什麼都不出，由招式引擎自己判斷（要看實體的狀態）。
 */
final class BossPacing {

    /** 光球放出去多久之內算這一招還沒放完（先看清楚光球往哪飛，再出下一招）：2 秒。 */
    static final long ORB_SETTLE_TICKS = 40;

    /** 沒放過光球時的「上次放光球」：很久以前。 */
    static final long NEVER = Long.MIN_VALUE / 4;

    /** 這個 tick 可以開始做什麼。 */
    enum Step {
        /** 什麼都不開始。 */
        WAIT,
        /** 只有到時間的場地危害可以落（放完一招之後的空檔）。 */
        HAZARD,
        /** 到時間的場地危害先落；沒有就出招。 */
        ANY
    }

    /** 場地危害的計時：代號（第幾條:招式）→ 下一次的 tick。 */
    private final Map<String, Long> hazardAt = new HashMap<>();

    /**
     * 上一件事還沒完：齊射還在飛、擴散環還在擴散、場地危害還沒落下、光球放出去還不到 {@link #ORB_SETTLE_TICKS}。
     *
     * @param newestOrb 最近一次放出光球的 tick（沒有是 {@link #NEVER}）
     */
    static boolean unsettled(final int bolts, final int rings, final int pendings, final long newestOrb, final long now) {
        return bolts > 0 || rings > 0 || pendings > 0 || now - newestOrb < ORB_SETTLE_TICKS;
    }

    /**
     * 沒在放招、上一件事也做完了的時候，這個 tick 能開始什麼（只看時間）。
     *
     * @param readyAt   下一招最早的 tick（放完一招 + gap、換階段的停頓、剛看到人的等待）
     * @param hazardGap 放完一招後的空檔（tick），這段時間裡場地危害可以落；這隻 Boss 沒有場地危害時傳 0
     */
    static Step step(final long now, final long readyAt, final long hazardGap) {
        if (now >= readyAt) {
            return Step.ANY;
        }
        return hazardGap > 0 && now >= readyAt - hazardGap ? Step.HAZARD : Step.WAIT;
    }

    /**
     * 到時間的場地危害：還沒開始計時的（剛到會落的階段）從現在開始算，到時間的回傳最早到期的那一條
     * （一次只落一條，同時到期照寫的順序）；沒有回傳 null。回傳的那一條要呼叫 {@link #fired} 重新計時。
     *
     * @param keys  這個階段會落的場地危害（照寫的順序）
     * @param every 每一條的間隔（tick），只在開始計時的時候問
     */
    String due(final List<String> keys, final ToLongFunction<String> every, final long now) {
        String best = null;
        long bestAt = Long.MAX_VALUE;
        for (final String key : keys) {
            final Long at = hazardAt.get(key);
            if (at == null) {
                hazardAt.put(key, now + every.applyAsLong(key));
            } else if (at <= now && at < bestAt) {
                best = key;
                bestAt = at;
            }
        }
        return best;
    }

    /** 這一條落下了（或附近沒人、這次跳過）：下一次從現在起算，等 Boss 耽擱的時間不會讓下一次提早。 */
    void fired(final String key, final long now, final long everyTicks) {
        hazardAt.put(key, now + everyTicks);
    }

    /** 剛看到人的等待、換階段的停頓：還在計時的場地危害至少等到 until。 */
    void delayHazards(final long until) {
        hazardAt.replaceAll((key, at) -> Math.max(at, until));
    }

    /** 這一條下一次的 tick（還沒開始計時是 null）。 */
    Long nextAt(final String key) {
        return hazardAt.get(key);
    }

    void clear() {
        hazardAt.clear();
    }
}
