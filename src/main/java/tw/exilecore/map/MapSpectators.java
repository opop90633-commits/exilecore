package tw.exilecore.map;

import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import tw.exilecore.ExileCore;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 極境 Boss 與金庫之主（只能進一次的實例）死掉之後在旁邊觀戰（2026-10）：重生在倒下的地方、變成旁觀模式，
 * 只能在這個實例裡看（出界拉回來、旁觀的傳送只能傳到實例裡的人）。Boss 倒下、裡面沒有活著的人、實例關了，
 * 或自己按聊天欄的「離開觀戰」（/spectate leave；2026-10 原本是連按兩下蹲下，旁觀模式常常要蹲下，太容易誤觸），
 * 就回營地、恢復原本的模式。
 * 旁觀模式的人不算人數（怪物變硬、Boss 的「人數越多越多發」）、不會被當目標、也不分掉落（都看 SURVIVAL／ADVENTURE）。
 */
public final class MapSpectators implements Listener {

    /** Boss 倒下之後還能看幾秒（看掉落、慶祝）。 */
    private static final long AFTER_KILL_TICKS = 10 * 20;

    private record Watch(int instanceId, UUID token, Location anchor, GameMode previous) {
    }

    private final ExileCore plugin;
    private final MapService service;
    /** 死了、等重生的人：倒下的地方。 */
    private final Map<UUID, Watch> pending = new HashMap<>();
    private final Map<UUID, Watch> watching = new HashMap<>();
    private final Map<UUID, Long> killedAt = new HashMap<>();

    MapSpectators(final ExileCore plugin, final MapService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 死在只能進一次的實例、Boss 還活著：記下來，重生時變成觀戰。 */
    void died(final Player player) {
        final MapInstance instance = service.instanceAt(player.getLocation());
        if (instance == null || !(instance.singleEntry() || instance.usesAttempts()) || instance.bossKilled()
                || instance.state() == MapInstance.State.CLOSING) {
            return;
        }
        // 2026-10-17：用「門」算的（終局、萬界…）也觀戰：隊友還在打就在旁邊看，這一門不能再進；
        // 全部倒下這一門才結束，觀戰的回營地、可以走下一門。自己一個人（沒有隊友還活著）直接回營地
        if (instance.usesAttempts() && !otherChallengerAlive(instance, player)) {
            return;
        }
        final GameMode previous = player.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : player.getGameMode();
        pending.put(player.getUniqueId(), new Watch(instance.id(), instance.token(), player.getLocation().clone().add(0, 1, 0), previous));
    }

    /** 這一門除了他還有沒有人活著在打。 */
    private boolean otherChallengerAlive(final MapInstance instance, final Player self) {
        for (final UUID uuid : instance.inside()) {
            if (uuid.equals(self.getUniqueId())) {
                continue;
            }
            final Player other = plugin.getServer().getPlayer(uuid);
            if (other != null && !other.isDead() && instance.enteredThisAttempt(uuid) && !watching(other)
                    && (other.getGameMode() == GameMode.SURVIVAL || other.getGameMode() == GameMode.ADVENTURE)) {
                return true;
            }
        }
        return false;
    }

    /** 重生點：觀戰的人重生在倒下的地方（null = 不是觀戰）。 */
    Location respawn(final Player player) {
        final Watch watch = pending.remove(player.getUniqueId());
        if (watch == null || find(watch) == null) {
            return null;
        }
        watching.put(player.getUniqueId(), watch);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !watching.containsKey(player.getUniqueId())) {
                return;
            }
            player.setGameMode(GameMode.SPECTATOR);
            if (plugin.skills() != null) {
                plugin.skills().dismissSummons(player); // 2026-10-17：召喚師變觀戰：召喚物、圖騰一起收掉
            }
            // 2026-10-17：記在玩家身上——伺服器當掉、沒走到 end() 時，下次登入改回原本的模式（MapListener.onJoin）
            player.getPersistentDataContainer().set(flagKey(), org.bukkit.persistence.PersistentDataType.STRING, watch.previous().name());
            player.showTitle(Title.title(plugin.text().parse("<#ff5555>你倒下了"),
                    plugin.text().parse("<gray>觀戰中：隊友打完再一起回營地"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.sendMessage(plugin.text().prefixed("<gray>你在旁邊觀戰（不算人數、不會被打、也不分掉落；這一門不能再進去）；"
                    + "Boss 倒下或隊友都倒下後自動回營地、離開觀戰模式　<red><click:run_command:'/spectate leave'>"
                    + "<hover:show_text:'<gray>不看了，現在回營地'>[離開觀戰]</hover></click>"));
        });
        return watch.anchor();
    }

    public boolean watching(final Player player) {
        return watching.containsKey(player.getUniqueId());
    }

    private MapInstance find(final Watch watch) {
        for (final MapInstance instance : service.instances()) {
            if (instance.id() == watch.instanceId() && instance.token().equals(watch.token())) {
                return instance;
            }
        }
        return null;
    }

    /** 每秒：出界拉回來；結束了就送回營地。 */
    void tick() {
        if (watching.isEmpty()) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        for (final Map.Entry<UUID, Watch> entry : new HashMap<>(watching).entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                watching.remove(entry.getKey());
                continue;
            }
            final Watch watch = entry.getValue();
            final MapInstance instance = find(watch);
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                end(player, "<gray>實例關閉了，回到營地");
                continue;
            }
            if (instance.bossKilled()) {
                final long since = killedAt.computeIfAbsent(instance.token(), key -> now);
                if (now - since >= AFTER_KILL_TICKS) {
                    end(player, "<green>Boss 倒下了，回到營地");
                }
                continue;
            }
            if (!anyoneFighting(instance)) {
                end(player, "<gray>裡面已經沒有人在打了，回到營地");
                continue;
            }
            if (!instance.contains(player.getLocation())) {
                player.teleport(watch.anchor()); // 出界：拉回倒下的地方
            }
        }
        killedAt.keySet().removeIf(token -> watching.values().stream().noneMatch(watch -> watch.token().equals(token)));
    }

    private boolean anyoneFighting(final MapInstance instance) {
        for (final Player player : instance.world().getPlayers()) {
            if (!player.isDead() && instance.contains(player.getLocation())
                    && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
                return true;
            }
        }
        // 剛死還沒按重生的隊友也算（等他重生變觀戰）
        for (final Map.Entry<UUID, Watch> entry : pending.entrySet()) {
            // 2026-10-18：只算同一個實例的（以前別的實例有人在死亡畫面，所有觀戰的人都不會被送回去）
            if (entry.getValue().instanceId() != instance.id() || !entry.getValue().token().equals(instance.token())) {
                continue;
            }
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isDead()) {
                return true;
            }
        }
        return false;
    }

    /** 結束觀戰：恢復模式、回營地。 */
    public void end(final Player player, final String message) {
        final Watch watch = watching.remove(player.getUniqueId());
        if (watch == null) {
            return;
        }
        player.setGameMode(watch.previous());
        player.getPersistentDataContainer().remove(flagKey());
        service.sendToCamp(player);
        if (message != null) {
            player.sendMessage(plugin.text().prefixed(message));
        }
    }

    /** 觀戰中的標記（存原本的模式）。 */
    private org.bukkit.NamespacedKey flagKey() {
        return new org.bukkit.NamespacedKey(plugin, "map_spectating");
    }

    /** 模組停用（關機、重開）：觀戰的人全部改回原本的模式、回營地（以前會卡在旁觀模式）。 */
    void endAll() {
        for (final UUID uuid : List.copyOf(watching.keySet())) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                end(player, null);
            } else {
                watching.remove(uuid);
            }
        }
        pending.clear();
    }

    /** 登入時：身上有觀戰標記、卻不在觀戰（伺服器當掉沒還原）→ 改回原本的模式，回傳要不要送回營地。 */
    boolean restoreStale(final Player player) {
        final String previous = player.getPersistentDataContainer().get(flagKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (previous == null || watching(player)) {
            return false;
        }
        player.getPersistentDataContainer().remove(flagKey());
        if (player.getGameMode() == GameMode.SPECTATOR) {
            GameMode mode;
            try {
                mode = GameMode.valueOf(previous);
            } catch (final IllegalArgumentException e) {
                mode = GameMode.SURVIVAL;
            }
            player.setGameMode(mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : mode);
        }
        return true;
    }

    void forget(final Player player) {
        pending.remove(player.getUniqueId());
    }

    /** /spectate leave：不看了，現在回營地。 */
    public static com.mojang.brigadier.tree.LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> command(final ExileCore plugin) {
        return io.papermc.paper.command.brigadier.Commands.literal("spectate")
                .then(io.papermc.paper.command.brigadier.Commands.literal("leave").executes(context -> {
                    if (context.getSource().getExecutor() instanceof Player player && plugin.maps() != null) {
                        if (plugin.maps().spectators().watching(player)) {
                            plugin.maps().spectators().end(player, "<gray>離開觀戰，回到營地");
                        } else {
                            player.sendMessage(plugin.text().prefixed("<gray>你沒有在觀戰"));
                        }
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    /** 旁觀模式的傳送（選一個人傳過去）：只能傳到同一個實例裡。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        final Watch watch = watching.get(event.getPlayer().getUniqueId());
        if (watch == null || event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }
        final MapInstance instance = find(watch);
        if (instance == null || !instance.contains(event.getTo())) {
            event.setCancelled(true);
        }
    }

    /** 觀戰中下線：恢復原本的模式、回營地（下次登入不會卡在旁觀模式）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        end(event.getPlayer(), null);
        forget(event.getPlayer());
    }
}
