package tw.exilecore.clientmod;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-11-02 照站長（C 組）：模組畫的預警、小地圖、死亡回顧（{@link FeedProtocol}）的伺服器這一邊：記下每個人招呼裡的旗標，
 * 送封包。{@link ClientModService} 只在入口叫這裡（招呼、離線、開關模組）。
 * <p>
 * 送之前都看玩家有沒有真的註冊那個頻道（Bukkit 對沒註冊的頻道會直接丟掉）：沒註冊的照舊走原版（大標題、聊天欄）。
 * 沒裝模組、舊模組（招呼沒寫旗標）的人完全照舊。
 */
public final class ClientFeeds {

    private final ExileCore plugin;
    private final Map<UUID, FeedProtocol.Flags> flags = new ConcurrentHashMap<>();
    private final MinimapFeed minimap;
    private BukkitTask task;

    public ClientFeeds(final ExileCore plugin) {
        this.plugin = plugin;
        this.minimap = new MinimapFeed(plugin, this);
    }

    /** 這個伺服器上的（clientmod 模組沒開是 null）。 */
    public static ClientFeeds of(final ExileCore plugin) {
        final ClientModService service = plugin == null ? null : plugin.clientMod();
        return service == null ? null : service.feeds();
    }

    void enable(final Messenger messenger) {
        messenger.registerOutgoingPluginChannel(plugin, FeedProtocol.ALERT);
        messenger.registerOutgoingPluginChannel(plugin, FeedProtocol.MINIMAP);
        messenger.registerOutgoingPluginChannel(plugin, FeedProtocol.DEATH);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, minimap::tick, 10L, MinimapFeed.INTERVAL);
    }

    void disable(final Messenger messenger) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        messenger.unregisterOutgoingPluginChannel(plugin, FeedProtocol.ALERT);
        messenger.unregisterOutgoingPluginChannel(plugin, FeedProtocol.MINIMAP);
        messenger.unregisterOutgoingPluginChannel(plugin, FeedProtocol.DEATH);
        flags.clear();
        minimap.clear();
    }

    /** 收到招呼（accepted = 協定對得上、算有裝模組）。 */
    void hello(final Player player, final byte[] raw, final boolean accepted) {
        final FeedProtocol.Flags parsed = accepted ? FeedProtocol.flags(raw) : FeedProtocol.Flags.NONE;
        final FeedProtocol.Flags before = parsed.any() ? flags.put(player.getUniqueId(), parsed) : flags.remove(player.getUniqueId());
        if (before != null && before.minimap() && !parsed.minimap()) {
            minimap.forget(player.getUniqueId());
        } else if (parsed.minimap()) {
            minimap.resend(player.getUniqueId()); // 重打招呼（切換設定）：小地圖重送一次整張
        }
    }

    void remove(final UUID uuid) {
        flags.remove(uuid);
        minimap.forget(uuid);
    }

    FeedProtocol.Flags flags(final Player player) {
        return player == null ? FeedProtocol.Flags.NONE : flags.getOrDefault(player.getUniqueId(), FeedProtocol.Flags.NONE);
    }

    java.util.Set<UUID> players() {
        return flags.keySet();
    }

    /** 這個人的模組畫預警（而且收得到）。 */
    public boolean alerts(final Player player) {
        return flags(player).alerts() && listens(player, FeedProtocol.ALERT);
    }

    public boolean minimap(final Player player) {
        return flags(player).minimap() && listens(player, FeedProtocol.MINIMAP);
    }

    public boolean death(final Player player) {
        return flags(player).death() && listens(player, FeedProtocol.DEATH);
    }

    /** 模組自己整理裝備說明（插件不用做什麼；給 /ec 的狀態看）。 */
    public boolean tooltip(final Player player) {
        return flags(player).tooltip();
    }

    private static boolean listens(final Player player, final String channel) {
        return player.isOnline() && player.getListeningPluginChannels().contains(channel);
    }

    void send(final Player player, final String channel, final byte[] data) {
        if (player.isOnline()) {
            player.sendPluginMessage(plugin, channel, data);
        }
    }
}
