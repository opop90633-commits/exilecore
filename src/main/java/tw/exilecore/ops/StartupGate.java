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
 * 早連進來的人容易連線逾時。開機完成（ServerLoadEvent STARTUP）後 config.yml 的 startup.login-delay-seconds 秒內
 * 先回「伺服器剛啟動，X 秒後再連」，讓伺服器喘口氣。OP 不擋。插件重讀（不是開機）不會觸發。
 */
final class StartupGate implements Listener {

    /** 擋到哪個時間（毫秒；0 = 不擋）。 */
    private volatile long openAt;
    private final ExileCore plugin;

    StartupGate(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 還要擋幾秒（不擋 0；不足一秒算一秒）。純計算，測試用。 */
    static long secondsLeft(final long openAt, final long now) {
        final long left = openAt - now;
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** 設定的秒數（沒寫預設 25，夾在 0～300；0 = 不擋）。 */
    static int clampDelay(final int seconds) {
        return Math.max(0, Math.min(300, seconds));
    }

    @EventHandler
    public void onServerLoad(final ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        final int delay = clampDelay(plugin.getConfig().getInt("startup.login-delay-seconds", 25));
        if (delay > 0) {
            openAt = System.currentTimeMillis() + delay * 1000L;
            plugin.getSLF4JLogger().info("開機完成：接下來 {} 秒先不開放登入（OP 除外），讓伺服器穩定下來", delay);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final long left = secondsLeft(openAt, System.currentTimeMillis());
        if (left <= 0) {
            openAt = 0;
            return;
        }
        if (plugin.getServer().getOfflinePlayer(event.getUniqueId()).isOp()) {
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.text().parse("<gradient:#9fd3ff:#f5b942>星骸</gradient>\n\n<white>伺服器剛啟動，正在準備中\n<gray>請 <yellow>"
                        + left + " 秒<gray>後再連線"));
    }
}
