package tw.exilecore.zone;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 防刷（純邏輯＋每個帳號在每個區域的狀態，不依賴 Bukkit）：同一個帳號在同一個劇情區域刷太多怪，掉落慢慢變少，
 * 離開一陣子就恢復（照 PoE：區域的怪打完就沒了；這裡的怪會一直刷，所以改成這樣）。
 * <p>
 * 每殺一隻怪加 1 點「疲勞」（區域 Boss 算 bossWeight 點），疲勞每分鐘減 recoverPerMinute 點（一直在減，不殺也在減）。
 * 疲勞到 free 之前都是全額掉落，之後再 span 點從 100% 一路降到 floor。
 * 只算等級已經到這個區域最高等級的角色（還在這裡升級的人不受影響），地圖不受影響——由呼叫端決定要不要算。
 * 狀態只在記憶體（重開伺服器歸零）。
 */
public final class ZoneFatigue {

    /**
     * 設定（zones.yml 的 fatigue）。
     *
     * @param enabled          false = 不防刷
     * @param free             先殺這麼多隻都是全額掉落
     * @param span             之後再殺這麼多隻，掉落從 100% 降到 floor
     * @param floor            最少剩幾成（0.2 = 20%）
     * @param recoverPerMinute 每分鐘恢復幾隻的量
     * @param bossWeight       區域 Boss 算幾隻
     */
    public record Settings(boolean enabled, double free, double span, double floor, double recoverPerMinute, double bossWeight) {
        public static Settings defaults() {
            return new Settings(true, 500, 1000, 0.2, 15, 50);
        }
    }

    /**
     * 一次擊殺的結果。
     *
     * @param multiplier 這一隻的掉落倍率（1 = 全額）
     * @param announce   掉落剛好又少了一級（每 20% 一級）：提示一次
     */
    public record Kill(double multiplier, boolean announce) {
    }

    private record Key(UUID account, String zone) {
    }

    private static final class State {
        private double load;
        private long at;
        /** 上一次提示到第幾級（0 = 全額）。 */
        private int notified;
    }

    /** 每多少次擊殺清一次已經恢復的狀態。 */
    private static final int PRUNE_EVERY = 500;

    private final Map<Key, State> states = new HashMap<>();
    private int kills;

    /** 過了 elapsedMillis 之後的疲勞。 */
    static double decay(final double load, final long elapsedMillis, final double recoverPerMinute) {
        if (load <= 0) {
            return 0;
        }
        return Math.max(0, load - Math.max(0, elapsedMillis) / 60_000.0 * Math.max(0, recoverPerMinute));
    }

    /** 疲勞 → 掉落倍率。 */
    public static double multiplier(final double load, final Settings settings) {
        if (!settings.enabled() || load <= settings.free()) {
            return 1;
        }
        final double floor = Math.max(0, Math.min(1, settings.floor()));
        if (settings.span() <= 0) {
            return floor;
        }
        final double progress = Math.min(1, (load - settings.free()) / settings.span());
        return 1 - progress * (1 - floor);
    }

    /** 掉落倍率是第幾級（每少 20% 一級；1 = 全額是 0 級）。 */
    static int level(final double multiplier) {
        return (int) Math.ceil(Math.max(0, 1 - multiplier) * 5 - 1e-9);
    }

    /**
     * 記一次擊殺：先用目前的疲勞算這一隻的掉落倍率，再加上這一隻的疲勞。
     *
     * @param weight 這一隻算幾隻（區域 Boss 用 bossWeight）
     */
    public Kill onKill(final UUID account, final String zone, final double weight, final long now, final Settings settings) {
        if (!settings.enabled()) {
            return new Kill(1, false);
        }
        if (++kills % PRUNE_EVERY == 0) {
            prune(now, settings);
        }
        final State state = states.computeIfAbsent(new Key(account, zone), key -> new State());
        state.load = decay(state.load, now - state.at, settings.recoverPerMinute());
        state.at = now;
        final double multiplier = multiplier(state.load, settings);
        state.load += Math.max(0, weight);
        final int level = level(multiplier);
        boolean announce = false;
        if (level > state.notified) {
            announce = true;
            state.notified = level;
        } else if (level < state.notified) {
            state.notified = level; // 恢復了：之後再變少會再提示
        }
        return new Kill(multiplier, announce);
    }

    /** 現在的掉落倍率（不記擊殺；給畫面顯示用）。 */
    public double current(final UUID account, final String zone, final long now, final Settings settings) {
        final State state = states.get(new Key(account, zone));
        if (state == null) {
            return 1;
        }
        return multiplier(decay(state.load, now - state.at, settings.recoverPerMinute()), settings);
    }

    /** 管理指令：清掉一個帳號的狀態。 */
    public void reset(final UUID account) {
        states.keySet().removeIf(key -> key.account().equals(account));
    }

    /** 清掉已經完全恢復的狀態。 */
    void prune(final long now, final Settings settings) {
        final Iterator<Map.Entry<Key, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            final State state = iterator.next().getValue();
            if (decay(state.load, now - state.at, settings.recoverPerMinute()) <= 0) {
                iterator.remove();
            }
        }
    }

    /** 記著幾個帳號×區域（測試、除錯用）。 */
    int size() {
        return states.size();
    }
}
