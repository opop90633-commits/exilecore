package tw.exilecore.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 重新啟動（2026-10，管理網頁「伺服器開關」、/ec restart）：
 * <ul>
 *   <li>幾分鐘後重啟：倒數時全服提醒（30、15、10、5、3、2、1 分鐘，30、10、5–1 秒），最後 5 分鐘上方有倒數條；時間到存檔關服。</li>
 *   <li>每天定時重啟：每天的幾點（可以好幾個時間），時間到之前 {@link #DAILY_WARNING_MINUTES} 分鐘自動開始倒數。</li>
 *   <li>倒數關服（2026-10）：倒數跟重啟一樣，時間到先把所有人請出去（顯示原因；不會被 Velocity 的等待室接住），再關服。
 *       主機商（Pterodactyl 面板）會把「自己關掉」當成當機自動開回來，所以 config.yml 有填 panel（面板網址、伺服器代號、
 *       Client API 金鑰）時改用面板的 API 送「停止」，面板才不會再開；沒填就直接關服（主機商可能會自動開回來）。</li>
 * </ul>
 * 關服前在 plugins/ExileCore/ 寫一個 {@value #FLAG_FILE}：用 start-all.bat 開的伺服器看到它會自動重新建置、再開起來
 * （玩家在 Velocity 的等待室等，開好自動送回來）；直接用 run-server.bat 開的只會關掉。
 * 每天的時間存在 plugins/ExileCore/{@value #FILE}；排定的那一次不存（重開就沒了，也不需要）。
 */
public final class RestartScheduler {

    public static final String FILE = "restart.yml";
    public static final String FLAG_FILE = "restart.flag";
    /** 每天定時重啟：時間到之前幾分鐘開始倒數。 */
    public static final int DAILY_WARNING_MINUTES = 10;
    /** 最多排幾分鐘後（一天）。 */
    public static final int MAX_MINUTES = 24 * 60;
    private static final int[] WARN_SECONDS = {30 * 60, 15 * 60, 10 * 60, 5 * 60, 3 * 60, 2 * 60, 60, 30, 10, 5, 4, 3, 2, 1};
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 排好的一次重啟（或關服）：什麼時候（毫秒）、原因、誰排的、是不是每天定時的、是不是關服（不開回來）。 */
    public record Pending(long at, String reason, String by, boolean daily, boolean shutdown) {
        /** 「重新啟動」或「關閉」。 */
        public String verb() {
            return shutdown ? "關閉" : "重新啟動";
        }
    }

    private final ExileCore plugin;
    private final File file;
    private final TreeSet<LocalTime> daily = new TreeSet<>();
    private Pending pending;
    /** 上一次照每天的時間排的是哪一天的哪個時間（不要同一個時間排兩次）。 */
    private LocalDateTime lastDaily;
    private BossBar bar;
    private BukkitTask task;
    private long lastWarned = Long.MAX_VALUE;

    RestartScheduler(final ExileCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        load();
        // 上一次排的重啟留下的標記（start-all 沒開、或從 IDE 開的時候不會被刪掉）：開好了就不需要了
        final File flag = new File(plugin.getDataFolder(), FLAG_FILE);
        if (flag.exists() && !flag.delete()) {
            plugin.getSLF4JLogger().warn("刪不掉 {}", flag);
        }
    }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        hideBar();
    }

    // ---- 設定 ----

    private void load() {
        daily.clear();
        if (!file.exists()) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (final String text : yaml.getStringList("daily")) {
            final LocalTime time = parseTime(text);
            if (time != null) {
                daily.add(time);
            }
        }
    }

    private void save() {
        final YamlConfiguration yaml = new YamlConfiguration();
        final List<String> times = new ArrayList<>();
        for (final LocalTime time : daily) {
            times.add(time.format(HHMM));
        }
        yaml.set("daily", times);
        yaml.setComments("daily", List.of("每天定時重新啟動的時間（24 小時制，伺服器的時間）；管理網頁「伺服器開關」可以改",
                "時間到之前 " + DAILY_WARNING_MINUTES + " 分鐘開始全服倒數"));
        try {
            yaml.save(file);
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("存不了 {}", FILE, e);
        }
    }

    /** 「05:30」「5:30」；看不懂回傳 null。 */
    public static LocalTime parseTime(final String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim().replace('：', ':');
        try {
            return LocalTime.parse(trimmed.length() == 4 ? "0" + trimmed : trimmed, HHMM);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    public List<String> dailyTimes() {
        final List<String> list = new ArrayList<>();
        for (final LocalTime time : daily) {
            list.add(time.format(HHMM));
        }
        return list;
    }

    /** 改每天的時間（空的 = 不定時重啟）。 */
    public void setDaily(final Set<LocalTime> times) {
        daily.clear();
        daily.addAll(times);
        save();
        // 已經照每天的時間排了、那個時間被拿掉了：取消
        if (pending != null && pending.daily()) {
            final LocalTime at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(pending.at()), ZoneId.systemDefault()).toLocalTime()
                    .withSecond(0).withNano(0);
            if (!daily.contains(at)) {
                cancel("定時重啟的時間改了");
            }
        }
    }

    public Pending pending() {
        return pending;
    }

    // ---- 排定、取消 ----

    /** 幾分鐘後重啟（0 = 馬上）。已經排了就改成這個時間。 */
    public void schedule(final int minutes, final String reason, final String by) {
        final long at = System.currentTimeMillis() + Math.max(0, Math.min(MAX_MINUTES, minutes)) * 60_000L;
        pending = new Pending(at, reason == null ? "" : reason.trim(), by, false, false);
        lastWarned = Long.MAX_VALUE;
        plugin.getSLF4JLogger().info("{} 排定 {} 分鐘後重新啟動{}", by, minutes, pending.reason().isEmpty() ? "" : "：" + pending.reason());
        announce(secondsLeft(), true);
    }

    /** 幾分鐘後關服（不開回來；0 = 馬上）。已經排了重啟或關服就改成這個。 */
    public void scheduleShutdown(final int minutes, final String reason, final String by) {
        final long at = System.currentTimeMillis() + Math.max(0, Math.min(MAX_MINUTES, minutes)) * 60_000L;
        pending = new Pending(at, reason == null ? "" : reason.trim(), by, false, true);
        lastWarned = Long.MAX_VALUE;
        plugin.getSLF4JLogger().info("{} 排定 {} 分鐘後關服{}", by, minutes, pending.reason().isEmpty() ? "" : "：" + pending.reason());
        announce(secondsLeft(), true);
    }

    /** config.yml 有沒有填面板的 API（關服才不會被主機商自動開回來）。 */
    public boolean panelConfigured() {
        return !panelSetting("url").isEmpty() && !panelSetting("server-id").isEmpty() && !panelSetting("api-key").isEmpty();
    }

    private String panelSetting(final String key) {
        final String value = plugin.getConfig().getString("panel." + key, "");
        return value == null ? "" : value.trim();
    }

    /** 取消排好的重啟；本來沒有回傳 false。 */
    public boolean cancel(final String why) {
        if (pending == null) {
            return false;
        }
        final String verb = pending.verb();
        pending = null;
        hideBar();
        broadcast("<green>伺服器" + verb + "取消了" + (why == null || why.isBlank() ? "" : "<gray>（" + ItemRenderer.escape(why) + "）"));
        plugin.getSLF4JLogger().info("{}取消了{}", verb, why == null ? "" : "（" + why + "）");
        return true;
    }

    private long secondsLeft() {
        return pending == null ? -1 : Math.max(0, (pending.at() - System.currentTimeMillis() + 999) / 1000);
    }

    // ---- 每秒 ----

    private void tick() {
        if (pending == null) {
            checkDaily();
            return;
        }
        final long left = secondsLeft();
        if (left <= 0) {
            if (pending.shutdown()) {
                shutdownNow();
            } else {
                restartNow();
            }
            return;
        }
        for (final int mark : WARN_SECONDS) {
            if (left <= mark && lastWarned > mark) {
                announce(left, false);
                break;
            }
        }
        updateBar(left);
    }

    /** 每天的時間：快到了（剩 DAILY_WARNING_MINUTES 分鐘以內）就排。 */
    private void checkDaily() {
        if (daily.isEmpty()) {
            return;
        }
        final LocalDateTime now = LocalDateTime.now().withNano(0);
        for (final LocalTime time : daily) {
            LocalDateTime at = LocalDate.now().atTime(time);
            if (at.isBefore(now)) {
                at = at.plusDays(1);
            }
            final long minutes = java.time.Duration.between(now, at).getSeconds();
            if (minutes <= DAILY_WARNING_MINUTES * 60L && (lastDaily == null || !lastDaily.equals(at))) {
                lastDaily = at;
                final long epoch = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                pending = new Pending(epoch, "每天定時重新啟動", "定時", true, false);
                lastWarned = Long.MAX_VALUE;
                plugin.getSLF4JLogger().info("每天定時重新啟動：{}", time.format(HHMM));
                announce(secondsLeft(), true);
                return;
            }
        }
    }

    private void announce(final long left, final boolean first) {
        lastWarned = left;
        if (pending == null) {
            return;
        }
        final String when = describe(left);
        final String reason = pending.reason().isEmpty() ? "" : "<gray>（" + ItemRenderer.escape(pending.reason()) + "）";
        final boolean shutdown = pending.shutdown();
        broadcast((shutdown ? "<#ff7a5c>" : "<#ffcc55>") + "伺服器將在 <white>" + when + "</white> 後" + pending.verb() + reason
                + (left >= 60 || first ? shutdown ? "<gray>，請找安全的地方準備下線（關服時會請所有人離開）"
                        : "<gray>，重開時會先在等待室休息一下，開好了自動送你回來" : ""));
        if (left <= 10 || left == 60 || first) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                player.showTitle(Title.title(plugin.text().parse((shutdown ? "<#ff7a5c>" : "<#ffcc55>") + when + "後" + pending.verb()),
                        Component.empty(),
                        Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(left <= 10 ? 900 : 2500),
                                java.time.Duration.ofMillis(300))));
                if (left <= 5) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
                }
            }
        }
    }

    static String describe(final long seconds) {
        if (seconds >= 60) {
            final long minutes = (seconds + 30) / 60;
            return minutes + " 分鐘";
        }
        return seconds + " 秒";
    }

    private void broadcast(final String text) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(plugin.text().prefixed(text));
        }
        plugin.getServer().getConsoleSender().sendMessage(plugin.text().prefixed(text));
    }

    private void updateBar(final long left) {
        if (left > 5 * 60) {
            hideBar();
            return;
        }
        if (bar == null) {
            bar = plugin.getServer().createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
        }
        bar.setTitle(String.format(Locale.ROOT, "§e伺服器將在 §f%d:%02d§e 後%s", left / 60, left % 60,
                pending == null ? "重新啟動" : pending.verb()));
        bar.setProgress(Math.max(0, Math.min(1, left / 300.0)));
        bar.setColor(left <= 60 ? BarColor.RED : BarColor.YELLOW);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        }
    }

    private void hideBar() {
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
    }

    /**
     * 關服時間到：把所有人請出去（原因寫在畫面上；不是「Server closed」，Velocity 的等待室不會把人接住），
     * 有面板的 API 就請面板停止（不然主機商會當成當機自動開回來），沒有就直接關服。
     */
    private void shutdownNow() {
        final Pending done = pending;
        pending = null;
        hideBar();
        final String reason = done == null || done.reason().isEmpty() ? "" : "\n<gray>" + ItemRenderer.escape(done.reason());
        final Component kick = plugin.text().parse("<#ff7a5c>《星骸》伺服器關閉了" + reason + "\n\n<gray>開服時間請看 Discord 公告");
        for (final Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            player.kick(kick);
        }
        plugin.getSLF4JLogger().info("關服：請所有人離開、存檔（{}）", done == null ? "" : done.by());
        // 下線的存檔排進資料庫之後再停（關服時資料庫會等它們寫完）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!panelConfigured()) {
                plugin.getSLF4JLogger().warn("config.yml 沒有填 panel：直接關服（主機商的面板可能會當成當機自動開回來）");
                plugin.getServer().shutdown();
                return;
            }
            final String url = panelSetting("url").replaceAll("/+$", "") + "/api/client/servers/" + panelSetting("server-id") + "/power";
            final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + panelSetting("api-key"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"signal\":\"stop\"}"))
                    .build();
            java.net.http.HttpClient.newHttpClient().sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        final boolean ok = error == null && response.statusCode() / 100 == 2;
                        if (ok) {
                            plugin.getSLF4JLogger().info("已請面板停止伺服器");
                        } else {
                            plugin.getSLF4JLogger().warn("面板 API 停止失敗（{}）：直接關服",
                                    error != null ? error.toString() : "HTTP " + response.statusCode());
                        }
                        // 面板收到會在主控台送 stop；失敗或 30 秒還沒停就自己關
                        if (plugin.isEnabled()) {
                            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.getServer().shutdown(), ok ? 600 : 1);
                        }
                    });
        }, 60);
    }

    /** 時間到：留下 restart.flag（start-all 看到會自動再開），存檔關服。 */
    private void restartNow() {
        final Pending done = pending;
        pending = null;
        hideBar();
        try {
            Files.writeString(new File(plugin.getDataFolder(), FLAG_FILE).toPath(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + (done == null ? "" : done.by()) + "\n");
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("寫不了 {}（start-all 不會自動再開）", FLAG_FILE, e);
        }
        broadcast("<#ffcc55>伺服器重新啟動中…");
        plugin.getSLF4JLogger().info("重新啟動：存檔、關服（{}）", done == null ? "" : done.by());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.getServer().shutdown(), 20);
    }
}
