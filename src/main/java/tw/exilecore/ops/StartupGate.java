package tw.exilecore.ops;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerLoadEvent;
import tw.exilecore.ExileCore;

/**
 * 2026-10-23：剛開機先擋一下登入。
 * <p>
 * 重啟倒數一結束，幾十個人幾乎同時重連；伺服器剛「Done」時還在載世界、跑啟動後的工作，主執行緒很忙，
 * 早連進來的人容易連線逾時。開機完成（ServerLoadEvent STARTUP）後 config.yml 的 startup.login-delay-seconds 秒內先不放人進來。
 * OP 不擋。插件重讀（不是開機）不會觸發。
 * <p>
 * 2026-10-23 晚修正：以前是直接踢掉（「X 秒後再連線」），但 Velocity 的 LimboReconnect 開好 6 秒就把等待室的人送回來，
 * 一被踢就斷線、不會再試，大家要自己重連，看起來像「重啟回不來」。改成<b>不踢，先在登入那一步等</b>（這個事件本來就在非同步的執行緒，
 * 可以等）：等到時間到再放行，同時一個一個錯開 {@value #STAGGER_MILLIS} 毫秒，不會全部一起擠進去。
 * 最多等 {@value #MAX_HOLD_MILLIS} 毫秒（Velocity 的 read-timeout 是 30 秒），還要更久的才照舊踢掉請他晚點再連。
 */
final class StartupGate implements Listener {

    /** 一個一個錯開幾毫秒放行。 */
    static final long STAGGER_MILLIS = 300;
    /** 登入最多先等多久（毫秒）；超過就踢掉請他晚點再連。 */
    static final long MAX_HOLD_MILLIS = 20_000;

    /** 擋到哪個時間（毫秒；0 = 不擋）。 */
    private volatile long openAt;
    /** 下一個放行的時間（錯開用）。 */
    private final java.util.concurrent.atomic.AtomicLong nextSlot = new java.util.concurrent.atomic.AtomicLong();
    private final ExileCore plugin;

    StartupGate(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 還要擋幾秒（不擋 0；不足一秒算一秒）。純計算，測試用。 */
    static long secondsLeft(final long openAt, final long now) {
        final long left = openAt - now;
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** 設定的秒數（沒寫預設 15，夾在 0～300；0 = 不擋）。 */
    static int clampDelay(final int seconds) {
        return Math.max(0, Math.min(300, seconds));
    }

    /**
     * 這個人排到什麼時候放行：開放時間跟上一個人的時間 + 錯開，取晚的那個。純計算，測試用。
     *
     * @param openAt   開放的時間
     * @param lastSlot 上一個人排到的時間（沒有 0）
     */
    static long slot(final long openAt, final long lastSlot) {
        return Math.max(openAt, lastSlot + STAGGER_MILLIS);
    }

    @EventHandler
    public void onServerLoad(final ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        final int delay = clampDelay(plugin.getConfig().getInt("startup.login-delay-seconds", 15));
        if (delay > 0) {
            openAt = System.currentTimeMillis() + delay * 1000L;
            nextSlot.set(0);
            plugin.getSLF4JLogger().info("開機完成：接下來 {} 秒先讓登入的人等一下（OP 除外），之後一個一個放進來", delay);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final long gate = openAt;
        final long now = System.currentTimeMillis();
        if (gate <= 0 || now >= gate) {
            openAt = 0;
            return;
        }
        if (plugin.getServer().getOfflinePlayer(event.getUniqueId()).isOp()) {
            return;
        }
        final long mine = nextSlot.accumulateAndGet(gate, (last, open) -> slot(open, last));
        final long wait = mine - now;
        if (wait > MAX_HOLD_MILLIS) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.text().parse("<gradient:#9fd3ff:#f5b942>星骸</gradient>\n\n<white>伺服器剛啟動，正在準備中\n<gray>請 <yellow>"
                    + secondsLeft(mine, now) + " 秒<gray>後再連線"));
            return;
        }
        try {
            Thread.sleep(Math.max(0, wait)); // 非同步的登入執行緒：等到輪到他再放行
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
