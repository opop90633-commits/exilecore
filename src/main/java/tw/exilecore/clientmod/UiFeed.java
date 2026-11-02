package tw.exilecore.clientmod;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-11-02 照站長（D 組）：天賦樹、星圖、星圖天賦、昇華天賦的原生畫面（裝了模組、招呼寫了 {@code treeui:true}／{@code atlasui:true} 的玩家）。
 * 這次之前只有箱子選單（9×5 的視野加捲動按鈕）；沒裝模組、舊模組的人照舊開箱子選單。
 * <ul>
 *   <li>打開：玩家原本開選單的地方（/tree、選單按鈕、/atlas、/ascend tree…）先問 {@link #open}，有模組就送狀態（open:true），模組打開原生畫面；
 *       模組的按鍵送 {@code {"a":"open"}} 也一樣。</li>
 *   <li>樹的靜態資料（節點、座標、連線、說明）很大：狀態裡只寫它的雜湊，模組沒有快取才送 {@code {"a":"static"}} 來要。</li>
 *   <li>配點、退點都是模組送動作、伺服器照現有的規則做（{@link Source#action}），做完回最新的狀態（含結果訊息 msg）。</li>
 *   <li>畫面開著時每秒比一次簽章（{@link Source#signature}），別的地方改了（升級、別的選單配點）也會送新的狀態。</li>
 *   <li>封包照 {@link UiWire} 切段，每人每 tick 最多送 {@code client-mod.ui-frames-per-tick} 段（預設 8 段 × 30000 位元組）。</li>
 * </ul>
 */
public final class UiFeed implements Listener, PluginMessageListener {

    /** 招呼裡的旗標：天賦樹與昇華天賦的原生畫面。 */
    public static final String FLAG_TREE = "treeui";
    /** 招呼裡的旗標：星圖與星圖天賦的原生畫面。 */
    public static final String FLAG_ATLAS = "atlasui";

    /** 一種畫面（天賦樹、星圖、星圖天賦、昇華天賦）。 */
    public interface Source {
        /** 畫面的代號（動作的 t、資料的名稱前綴）。 */
        String kind();

        /** 招呼要寫哪個旗標才用原生畫面。 */
        String flag();

        /** 現在不能打開的原因（沒角色、還沒選昇華…）；null = 可以。 */
        String problem(Player player);

        /** 靜態資料（沒有靜態資料的畫面回傳 null）：沒壓縮的 JSON。同一份請自己快取。 */
        default byte[] staticJson(Player player) {
            return null;
        }

        /** 最新的狀態（open、msg 由 {@link UiFeed} 補上）。 */
        JsonObject state(Player player, JsonObject args);

        /** 做一個動作（配點、退點…）；回傳要給玩家看的結果（MiniMessage 簡單格式；null = 不用說）。 */
        String action(Player player, String action, JsonObject message);

        /** 改開箱子選單。 */
        void chest(Player player);

        /** 畫面關了（模組送 close、玩家下線）：例如有配點就存檔。 */
        default void closed(final Player player) {
        }

        /**
         * 2026-11-02 照站長：路徑預覽「整條路加起來的能力」（跟箱子選單同一段文字；MiniMessage 簡單格式）。
         * 不支援、路徑太短回傳空的。這次之前原生畫面只寫要幾點。
         */
        default List<String> summary(final Player player, final List<String> path) {
            return List.of();
        }

        /** 便宜的簽章：變了就重送狀態（null = 不用比）。 */
        default String signature(Player player) {
            return null;
        }
    }

    private static final class Client {
        final Set<String> flags;
        /** 開著哪個畫面（null = 沒開）。 */
        String open;
        String signature;
        JsonObject args;
        final Deque<byte[]> queue = new ArrayDeque<>();

        Client(final Set<String> flags) {
            this.flags = flags;
        }
    }

    private final ExileCore plugin;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    /** 靜態資料的快取：畫面 → {雜湊, 壓縮後}（同一份 JSON 只壓一次）。 */
    private final Map<String, Object[]> packed = new HashMap<>();
    private BukkitTask task;
    private int chunk = UiWire.DEFAULT_CHUNK;
    private int framesPerTick = 8;
    private long ticks;

    public UiFeed(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 加一種畫面（插件啟動時；各模組自己的 Feed）。 */
    public void register(final Source source) {
        sources.put(source.kind(), source);
    }

    public void enable() {
        chunk = Math.max(UiWire.MIN_CHUNK, Math.min(UiWire.MAX_CHUNK, plugin.getConfig().getInt("client-mod.ui-chunk-bytes", UiWire.DEFAULT_CHUNK)));
        framesPerTick = Math.max(1, Math.min(64, plugin.getConfig().getInt("client-mod.ui-frames-per-tick", 8)));
        final var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, UiWire.ACTION, this);
        messenger.registerOutgoingPluginChannel(plugin, UiWire.DATA);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // 各畫面在 problem() 裡自己檢查模組有沒有啟用（地圖、昇華模組比 clientmod 早啟用，重新載入時也可能暫時沒有）
        register(new tw.exilecore.passive.TreeFeed(plugin));
        register(new tw.exilecore.map.AtlasFeed(plugin, false));
        register(new tw.exilecore.map.AtlasFeed(plugin, true));
        register(tw.exilecore.ascendancy.AscFeed.create(plugin));
        register(new tw.exilecore.market.ExchangeFeed(plugin)); // 2026-11-02 照站長：通貨交易所的原生畫面（PoE2 的樣子）
    }

    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        final var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, UiWire.ACTION, this);
        messenger.unregisterOutgoingPluginChannel(plugin, UiWire.DATA);
        HandlerList.unregisterAll(this);
        clients.clear();
        sources.clear();
        packed.clear();
    }

    /** 模組打了招呼（accepted = 協定對得上）：記下它要哪些原生畫面。 */
    public void hello(final Player player, final byte[] raw, final boolean accepted) {
        if (!accepted) {
            clients.remove(player.getUniqueId());
            return;
        }
        final Set<String> flags = new java.util.HashSet<>();
        for (final String flag : new String[] {FLAG_TREE, FLAG_ATLAS, tw.exilecore.market.ExchangeFeed.FLAG}) { // 2026-11-02：＋通貨交易所
            if (UiWire.helloFlag(raw, flag)) {
                flags.add(flag);
            }
        }
        if (flags.isEmpty()) {
            clients.remove(player.getUniqueId());
            return;
        }
        final Client before = clients.get(player.getUniqueId());
        final Client client = new Client(Set.copyOf(flags));
        if (before != null) {
            client.open = before.open;
            client.args = before.args;
        }
        clients.put(player.getUniqueId(), client);
    }

    /** 這個玩家看得到這種原生畫面（模組要了、頻道註冊了）。 */
    public boolean available(final Player player, final String kind) {
        final Source source = sources.get(kind);
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return source != null && client != null && client.flags.contains(source.flag())
                && player.getListeningPluginChannels().contains(UiWire.DATA);
    }

    /**
     * 有模組的話打開原生畫面，回傳 true；沒有（或不能開）回傳 false，呼叫的地方照舊開箱子選單。
     *
     * @param args 給畫面的參數（搜尋字、星圖的中心…；可以是 null）
     */
    public boolean open(final Player player, final String kind, final JsonObject args) {
        if (!available(player, kind)) {
            return false;
        }
        final Source source = sources.get(kind);
        if (source.problem(player) != null) {
            return false; // 不能開的原因交給箱子選單那邊照舊說
        }
        final Client client = clients.get(player.getUniqueId());
        client.open = kind;
        client.args = args;
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof tw.exilecore.gui.Menu) {
            // 從箱子選單（例如選完珠寶）回到原生畫面：先把箱子關掉（下一個 tick，不在點擊事件裡關），再叫模組打開
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isConnected() && kind.equals(client.open)) {
                    player.closeInventory();
                    push(player, client, source, true, null);
                }
            });
            return true;
        }
        push(player, client, source, true, null);
        return true;
    }

    /** 送最新的狀態（畫面開著才送；別的模組改了東西時可以叫）。 */
    public void refresh(final Player player, final String kind) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        final Source source = sources.get(kind);
        if (client != null && source != null && kind.equals(client.open)) {
            push(player, client, source, false, null);
        }
    }

    private void push(final Player player, final Client client, final Source source, final boolean open, final String message) {
        final JsonObject state;
        final long started = System.nanoTime();
        try {
            state = source.state(player, client.args == null ? new JsonObject() : client.args);
            // 2026-11-02 照站長（星圖打開會卡）：算一次狀態超過 20 毫秒記在主控台，看得出是哪個畫面、多久
            final long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms >= 20) {
                plugin.getSLF4JLogger().warn("原生畫面：{} 的 {} 狀態算了 {} 毫秒（{}）", player.getName(), source.kind(), ms, open ? "打開" : "更新");
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("原生畫面：算 {} 的 {} 狀態出錯", player.getName(), source.kind(), e);
            return;
        }
        if (state == null) {
            return;
        }
        state.addProperty("kind", source.kind());
        state.addProperty("open", open);
        if (message != null && !message.isEmpty()) {
            state.addProperty("msg", message);
        }
        final byte[] staticJson = source.staticJson(player);
        if (staticJson != null) {
            state.addProperty("static", UiWire.hex((Long) packedStatic(source.kind(), staticJson)[0]));
        }
        client.signature = source.signature(player);
        queue(player, client, source.kind() + ".state", state.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 靜態資料的 {雜湊, 壓縮後}（JSON 沒變就用上次壓好的）。 */
    private Object[] packedStatic(final String kind, final byte[] raw) {
        final Object[] cached = packed.get(kind);
        if (cached != null && cached[2] == raw) {
            return cached;
        }
        final Object[] fresh = {UiWire.hash(raw), UiWire.deflate(raw), raw};
        packed.put(kind, fresh);
        return fresh;
    }

    private void queue(final Player player, final Client client, final String name, final byte[] raw) {
        for (final byte[] frame : UiWire.encode(name, raw, chunk)) {
            client.queue.add(frame);
        }
        drain(player, client);
    }

    private void queuePacked(final Player player, final Client client, final String name, final long hash, final byte[] packedBytes) {
        for (final byte[] frame : UiWire.encode(name, hash, packedBytes, chunk)) {
            client.queue.add(frame);
        }
        drain(player, client);
    }

    private void drain(final Player player, final Client client) {
        int sent = 0;
        while (sent < framesPerTick && !client.queue.isEmpty()) {
            final byte[] frame = client.queue.poll();
            if (player.isConnected()) {
                player.sendPluginMessage(plugin, UiWire.DATA, frame);
            }
            sent++;
        }
    }

    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!UiWire.ACTION.equals(channel)) {
            return;
        }
        final Client client = clients.get(player.getUniqueId());
        final JsonObject action = UiWire.decodeAction(message);
        if (client == null || action == null) {
            return;
        }
        final String kind = action.get("t").getAsString();
        final String verb = action.get("a").getAsString();
        final Source source = sources.get(kind);
        if (source == null || !client.flags.contains(source.flag())) {
            return;
        }
        try {
            switch (verb) {
                case "open" -> {
                    final String problem = source.problem(player);
                    if (problem != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + problem));
                        return;
                    }
                    client.open = kind;
                    client.args = action.has("args") && action.get("args").isJsonObject() ? action.getAsJsonObject("args") : null;
                    push(player, client, source, true, null);
                }
                case "static" -> {
                    final byte[] raw = source.staticJson(player);
                    if (raw != null) {
                        final Object[] cached = packedStatic(kind, raw);
                        queuePacked(player, client, kind + ".static", (Long) cached[0], (byte[]) cached[1]);
                    }
                }
                case "sum" -> {
                    // 2026-11-02 照站長：模組問一條路徑加起來的能力，回 種類.sum {"path":[…],"lines":[…]}
                    if (source.problem(player) != null || !action.has("path") || !action.get("path").isJsonArray()) {
                        return;
                    }
                    final List<String> path = new java.util.ArrayList<>();
                    for (final com.google.gson.JsonElement element : action.getAsJsonArray("path")) {
                        if (element.isJsonPrimitive() && path.size() < 300) {
                            path.add(element.getAsString());
                        }
                    }
                    final JsonObject reply = new JsonObject();
                    reply.add("path", action.getAsJsonArray("path"));
                    final com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
                    source.summary(player, path).forEach(lines::add);
                    reply.add("lines", lines);
                    queue(player, client, kind + ".sum", reply.toString().getBytes(StandardCharsets.UTF_8));
                }
                case "close" -> {
                    if (kind.equals(client.open)) {
                        client.open = null;
                        client.args = null;
                    }
                    source.closed(player);
                }
                case "chest" -> {
                    client.open = null;
                    source.chest(player);
                }
                default -> {
                    if (source.problem(player) != null) {
                        return;
                    }
                    client.open = kind;
                    final String result = source.action(player, verb, action);
                    if (kind.equals(client.open)) { // 動作打開了箱子選單（選珠寶、選地圖）時不用回
                        push(player, client, source, false, result);
                    }
                }
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("原生畫面：處理 {} 的動作 {}/{} 出錯", player.getName(), kind, verb, e);
        }
    }

    /** 模組在別的地方打開了箱子選單（例如選珠寶）：當作原生畫面關了。 */
    public void closed(final Player player) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        if (client != null) {
            client.open = null;
        }
    }

    private void tick() {
        ticks++;
        for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
            final Client client = entry.getValue();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            if (!client.queue.isEmpty()) {
                drain(player, client);
            }
            if (ticks % 20 == 0 && client.open != null) {
                final Source source = sources.get(client.open);
                if (source == null) {
                    continue;
                }
                try {
                    final String signature = source.signature(player);
                    if (signature != null && !signature.equals(client.signature)) {
                        push(player, client, source, false, null);
                    }
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().warn("原生畫面：比 {} 的簽章出錯", player.getName(), e);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Client client = clients.remove(event.getPlayer().getUniqueId());
        final Source source = client == null || client.open == null ? null : sources.get(client.open);
        if (source != null) {
            source.closed(event.getPlayer());
        }
    }
}
