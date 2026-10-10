package tw.exilecore.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.module.Module;
import tw.exilecore.web.WebModule;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * admin 模組：管理網頁（網址/admin）。管理員在遊戲裡打 /ec web 拿一次性的登入連結，在網頁上
 * <ul>
 *   <li>發放物資：通貨、金幣、功勳、裝備、傳奇、寶石、藥劑、地圖、免費退點，給在線或離線的玩家、線上所有人、所有帳號
 *       （離線的下次登入領；放不下的留著，/claim 再領；還沒領的可以取消）；</li>
 *   <li>改遊戲設定（{@link AdminSettings}）：全服加成、每月活動主題、手續費與價格、Boss 傳奇掉率、PvP、能不能丟東西——
 *       存在 admin-overrides.yml，不動資料檔，馬上生效；</li>
 *   <li>全服公告（聊天欄、畫面中間）、維護模式（只讓管理員進）、看線上玩家與操作紀錄。</li>
 * </ul>
 * config.yml 的 web.admin-page: false 關掉（網址 404、/ec web 不給連結）。
 */
public final class AdminModule implements Module, Listener {

    private final ExileCore plugin;
    private AdminAuth auth;
    private AdminRepository repository;
    private DeliveryService deliveries;
    private AdminWeb web;
    private BukkitTask pruneTask;
    private RestartScheduler restarts;
    private volatile boolean pageEnabled = true;
    /** 玩家看到的網址是 https：Cookie 加 Secure（主執行緒讀設定時算好，網頁的執行緒只讀這個值）。 */
    private volatile boolean secureCookie;
    /** 維護模式踢掉的人：不要顯示「離開了遊戲」。 */
    private final Set<UUID> maintenanceKicked = ConcurrentHashMap.newKeySet();

    public AdminModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "admin";
    }

    @Override
    public void enable() throws Exception {
        auth = new AdminAuth(Clock.systemUTC());
        repository = new AdminRepository();
        deliveries = new DeliveryService(plugin, repository);
        web = new AdminWeb(plugin, this);
        readConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.characters().addAfterApply(player -> deliveries.afterApply(player)); // 登入、建立、切換角色：領離線時發的物資
        final WebModule webModule = plugin.web();
        if (webModule != null) {
            webModule.extend(web::install);
        }
        pruneTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, auth::prune, 20L * 300, 20L * 300);
        restarts = new RestartScheduler(plugin);
        restarts.start();
        if (plugin.overrides().maintenance()) {
            plugin.getSLF4JLogger().warn("維護模式開著：只有管理員進得來（管理網頁或刪掉 admin-overrides.yml 的 maintenance 關掉）");
        }
    }

    @Override
    public void reload() {
        readConfig();
    }

    @Override
    public void disable() {
        if (pruneTask != null) {
            pruneTask.cancel();
            pruneTask = null;
        }
        if (restarts != null) {
            restarts.stop();
        }
        if (auth != null) {
            auth.revokeAll();
        }
    }

    private void readConfig() {
        pageEnabled = plugin.getConfig().getBoolean("web.admin-page", true);
        final WebModule web = plugin.web();
        secureCookie = web != null && web.secure();
    }

    /** Cookie 要不要加 Secure（網址是 https）；任何執行緒都可以看。 */
    boolean secureCookie() {
        return secureCookie;
    }

    /** 管理網頁有沒有開（config.yml 的 web.admin-page）；任何執行緒都可以看。 */
    public boolean pageEnabled() {
        return pageEnabled;
    }

    AdminAuth auth() {
        return auth;
    }

    AdminRepository repository() {
        return repository;
    }

    /** 重新啟動（幾分鐘後、每天定時）。 */
    public RestartScheduler restarts() {
        return restarts;
    }

    /** 發放物資（領取、取消）。 */
    public DeliveryService deliveries() {
        return deliveries;
    }

    // ---- 維護模式 ----

    /** 維護模式：沒有管理權限的人進來就請出去（加入、離開的訊息都不顯示）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        if (!plugin.overrides().maintenance()) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(plugin.text().prefixed("<yellow>維護模式開著：現在只有管理員進得來（管理網頁的「伺服器開關」關掉）"));
                }
            }, 40);
            return;
        }
        event.joinMessage(null);
        maintenanceKicked.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.kick(maintenanceScreen());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        if (maintenanceKicked.remove(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    /** 伺服器列表：維護中。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(final ServerListPingEvent event) {
        if (plugin.overrides().maintenance()) {
            event.motd(plugin.text().parse("<#f5b942>《星骸》</#f5b942> <yellow>維護中</yellow>\n<gray>"
                    + ItemRenderer.escape(plugin.overrides().maintenanceMessage())));
        }
    }

    /** 被請出去的玩家看到的畫面。 */
    Component maintenanceScreen() {
        return plugin.text().parse("<#f5b942>《星骸》維護中</#f5b942>\n\n<white>" + ItemRenderer.escape(plugin.overrides().maintenanceMessage()));
    }

    /** 把線上沒有管理權限的人請出去（主執行緒；他們的角色照常存檔）；回傳幾個人。 */
    int kickNonAdmins() {
        int kicked = 0;
        for (final Player player : java.util.List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (!player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
                maintenanceKicked.add(player.getUniqueId());
                player.kick(maintenanceScreen());
                kicked++;
            }
        }
        return kicked;
    }

    // ---- 公告 ----

    /** 全服公告（主執行緒）：聊天欄、畫面中間（標題）。回傳幾個人收到。 */
    int broadcast(final String message, final boolean chat, final boolean title) {
        final String text = ItemRenderer.escape(message);
        int count = 0;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (chat) {
                player.sendMessage(plugin.text().prefixed("<#f5b942>【公告】</#f5b942><white>" + text));
            }
            if (title) {
                final boolean shortText = message.codePointCount(0, message.length()) <= 16;
                player.showTitle(Title.title(
                        plugin.text().parse(shortText ? "<#f5b942>" + text : "<#f5b942>公告"),
                        shortText ? Component.empty() : plugin.text().parse("<white>" + text),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            }
            count++;
        }
        plugin.getServer().getConsoleSender().sendMessage(plugin.text().prefixed("<#f5b942>【公告】</#f5b942><white>" + text));
        return count;
    }

    // ---- 指令 ----

    /** /ec web [logout]：拿管理網頁的一次性登入連結；全部登出。掛在 /ec 底下（只有管理員）。 */
    /** /ec restart [分鐘 [原因]｜cancel]：幾分鐘後重新啟動（不寫 = 看現在排了什麼）。掛在 /ec 底下（只有管理員）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> restartNode(final ExileCore plugin) {
        return Commands.literal("restart")
                .executes(context -> restartStatus(plugin, context.getSource().getSender()))
                .then(Commands.literal("cancel").executes(context -> {
                    final AdminModule admin = plugin.admin();
                    final boolean had = admin != null && admin.restarts() != null && admin.restarts().cancel("管理員取消");
                    context.getSource().getSender().sendMessage(plugin.text().prefixed(had ? "<green>取消了" : "<gray>現在沒有排重新啟動"));
                    return 1;
                }))
                .then(Commands.argument("minutes", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, RestartScheduler.MAX_MINUTES))
                        .executes(context -> restartIn(plugin, context.getSource().getSender(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "minutes"), ""))
                        .then(Commands.argument("reason", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(context -> restartIn(plugin, context.getSource().getSender(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "minutes"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "reason")))));
    }

    private static int restartIn(final ExileCore plugin, final CommandSender sender, final int minutes, final String reason) {
        final AdminModule admin = plugin.admin();
        if (admin == null || admin.restarts() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>管理模組沒有啟用"));
            return 1;
        }
        admin.restarts().schedule(minutes, reason, sender.getName());
        sender.sendMessage(plugin.text().prefixed("<green>排好了：" + minutes + " 分鐘後重新啟動<gray>（/ec restart cancel 取消）"));
        return 1;
    }

    private static int restartStatus(final ExileCore plugin, final CommandSender sender) {
        final AdminModule admin = plugin.admin();
        if (admin == null || admin.restarts() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>管理模組沒有啟用"));
            return 1;
        }
        final RestartScheduler.Pending pending = admin.restarts().pending();
        sender.sendMessage(plugin.text().prefixed(pending == null ? "<gray>現在沒有排重新啟動"
                : "<gray>將在 <white>" + RestartScheduler.describe(Math.max(0, (pending.at() - System.currentTimeMillis()) / 1000))
                + "</white> 後重新啟動（" + ItemRenderer.escape(pending.by()) + "）"));
        final List<String> daily = admin.restarts().dailyTimes();
        sender.sendMessage(plugin.text().parse("<gray>每天定時：<white>" + (daily.isEmpty() ? "沒有" : String.join("、", daily))
                + "<gray>（管理網頁「遊戲設定 → 伺服器開關」改）"));
        sender.sendMessage(plugin.text().parse("<dark_gray>/ec restart 分鐘 [原因]　/ec restart cancel"));
        return 1;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("web")
                .executes(context -> link(plugin, context.getSource().getSender()))
                .then(Commands.literal("logout").executes(context -> logout(plugin, context.getSource().getSender())));
    }

    private static int link(final ExileCore plugin, final CommandSender sender) {
        final AdminModule admin = plugin.admin();
        final WebModule web = plugin.web();
        if (admin == null || !admin.pageEnabled()) {
            sender.sendMessage(plugin.text().prefixed("<red>管理網頁沒有開（config.yml 的 web.admin-page）"));
            return Command.SINGLE_SUCCESS;
        }
        if (web == null || !web.available()) {
            sender.sendMessage(plugin.text().prefixed("<red>網頁伺服器沒有在跑（config.yml 的 web.enabled、web.port；看主控台的訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        final Player player = sender instanceof Player p ? p : null;
        final String name = player == null ? "主控台" : player.getName();
        final String token = admin.auth().issue(name, player == null ? null : player.getUniqueId());
        final String url = web.publicUrl() + "/admin/login?t=" + token;
        if (player == null) {
            sender.sendMessage(plugin.text().prefixed("<gray>管理網頁的登入連結（10 分鐘內有效、只能用一次；這行會留在 logs 裡，不要把 log 貼給別人）：<white>" + url));
        } else {
            player.sendMessage(plugin.text().prefixed("<#f5b942><click:open_url:'" + url + "'><hover:show_text:'在瀏覽器打開管理網頁'>[打開管理網頁]</hover></click>"
                    + "</#f5b942> <gray>10 分鐘內有效、只能用一次"));
            player.sendMessage(plugin.text().parse("<dark_gray>拿到這個連結的人就能管理伺服器：不要貼給別人。用完可以打 /ec web logout 全部登出"));
            if (web.localOnlyUrl() && !WebModule.connectedLocally(player)) {
                player.sendMessage(plugin.text().parse("<yellow>config.yml 的 web.public-url 沒填：這個網址只有在伺服器那台電腦上打得開"));
            } else if (!web.secure() && !WebModule.connectedLocally(player)) {
                player.sendMessage(plugin.text().parse("<yellow>網址是 http：在公共網路（咖啡廳、學校的 Wi-Fi）上別人可能看得到你的登入。"
                        + "最好在伺服器那台電腦上開，或把網頁伺服器放在 https 後面（public-url 填 https://…）"));
            }
        }
        plugin.getSLF4JLogger().info("[管理網頁] {} 拿了登入連結", name);
        return Command.SINGLE_SUCCESS;
    }

    private static int logout(final ExileCore plugin, final CommandSender sender) {
        final AdminModule admin = plugin.admin();
        if (admin == null) {
            sender.sendMessage(plugin.text().prefixed("<red>管理網頁沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final int count = admin.auth().revokeAll();
        sender.sendMessage(plugin.text().prefixed("<gray>管理網頁全部登出（" + count + " 個），還沒用的登入連結也作廢了"));
        plugin.getSLF4JLogger().info("[管理網頁] {} 把管理網頁全部登出（{} 個）", sender.getName(), count);
        return Command.SINGLE_SUCCESS;
    }

    /** /claim：領管理員發的物資（放不下留著的、帳號資料晚載入的）。 */
    public static LiteralCommandNode<CommandSourceStack> claimCommand(final ExileCore plugin) {
        return Commands.literal("claim")
                .executes(context -> {
                    if (!(context.getSource().getExecutor() instanceof Player player)) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                        return Command.SINGLE_SUCCESS;
                    }
                    final AdminModule admin = plugin.admin();
                    if (admin == null) {
                        player.sendMessage(plugin.text().prefixed("<gray>沒有待領取的物資"));
                        return Command.SINGLE_SUCCESS;
                    }
                    admin.deliveries().claim(player, true);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
