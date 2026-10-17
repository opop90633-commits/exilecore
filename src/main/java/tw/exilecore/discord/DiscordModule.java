package tw.exilecore.discord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * discord 模組（2026-10）：伺服器聊天與 Discord 頻道雙向同步，另外把上下線、世界事件、重大成就、開關服送到 Discord。
 * 設定在 config.yml 的 discord 段落（Webhook 網址、機器人 Token、頻道 ID）。最後啟用、最先停用（開服、關服的公告在最外面）。
 */
public final class DiscordModule implements Module, Listener {

    private final ExileCore plugin;
    private DiscordBridge bridge;
    private boolean announcedStart;

    public DiscordModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public void enable() {
        bridge = new DiscordBridge(plugin);
        bridge.configure();
        bridge.start();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (bridge.active() && !announcedStart) {
            announcedStart = true;
            bridge.server("🟢 伺服器開啟了，快上線吧！");
        }
    }

    @Override
    public void reload() {
        if (bridge != null) {
            final boolean was = bridge.active();
            bridge.configure();
            bridge.start();
            if (!was && bridge.active()) {
                plugin.getSLF4JLogger().info("[Discord] 重新載入後啟用了");
            }
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (bridge != null) {
            if (bridge.active()) {
                bridge.server("🔴 伺服器關閉了");
            }
            bridge.stop(true);
            bridge.tickets().stop();
        }
    }

    /** 送到 Discord 的入口；模組沒啟用或沒開時 null。 */
    public DiscordBridge bridge() {
        return bridge != null && bridge.active() ? bridge : null;
    }

    /** 公開聊天（聊天管理擋掉的、對話框等著打字的都已經取消了，MONITOR 只拿到真的送出的）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        if (bridge == null || !bridge.active()) {
            return;
        }
        final String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        final java.util.List<org.bukkit.inventory.ItemStack> items = plugin.chat() == null ? java.util.List.of()
                : plugin.chat().takeLinked(event.getPlayer().getUniqueId()); // 2026-10：[i] 貼的物品也送到 Discord
        // 2026-10-18：聊天執行緒只取純文字與物品複本；名字（PlayerState、稱號、昇華）與嵌入排回主執行緒算
        final DiscordBridge current = bridge;
        final Player player = event.getPlayer();
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (current.active()) {
                current.chat(player, plain, items);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        if (bridge != null && bridge.active()) {
            bridge.tickets().deliverPending(event.getPlayer()); // 不在線時客服回覆的話
            final int online = plugin.getServer().getOnlinePlayers().size();
            bridge.joinLeave("📥 " + escape(event.getPlayer()) + " 上線了（在線 " + online + " 人）");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        if (bridge != null && bridge.active()) {
            final int online = Math.max(0, plugin.getServer().getOnlinePlayers().size() - 1);
            final String full = plugin.players() == null ? null : plugin.players().plainFullName(event.getPlayer()); // 2026-10：有角色就寫角色
            bridge.joinLeave("📤 " + (full != null ? tw.exilecore.item.ItemRenderer.escape(full) : escape(event.getPlayer())) + " 下線了（在線 " + online + " 人）");
        }
    }

    /**
     * /ticket 內容　　　　　　　開客服單（已經有開著的就把話接上去）
     * /ticket close　　　　　　 關掉自己的客服單
     * /ticket panel 面板 頻道ID  （管理員）把客服單面板發到 Discord 頻道
     */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("ticket")
                .executes(context -> {
                    context.getSource().getSender().sendMessage(plugin.text().prefixed(
                            "<gray>用法：<white>/ticket 問題描述<gray>（開客服單，客服會在 Discord 回覆、這裡收得到）　<white>/ticket close<gray> 關掉"));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("close").executes(context -> {
                    if (context.getSource().getSender() instanceof Player player) {
                        final DiscordBridge bridge = plugin.discord();
                        if (bridge == null) {
                            player.sendMessage(plugin.text().prefixed("<red>客服單現在沒有開放"));
                        } else {
                            bridge.tickets().closeFromGame(player);
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("panel").requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                        .then(Commands.argument("panel", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    final DiscordBridge bridge = plugin.discord();
                                    if (bridge != null) {
                                        bridge.tickets().panelIds().forEach(builder::suggest);
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("channel", StringArgumentType.word()).executes(context -> {
                                    final org.bukkit.command.CommandSender sender = context.getSource().getSender();
                                    final DiscordBridge bridge = plugin.discord();
                                    if (bridge == null) {
                                        sender.sendMessage(plugin.text().prefixed("<red>Discord 同步沒有開（config.yml 的 discord.enabled）"));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    bridge.tickets().postPanel(StringArgumentType.getString(context, "panel"), StringArgumentType.getString(context, "channel"),
                                            text -> plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.text().prefixed(text))));
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(context -> {
                    if (context.getSource().getSender() instanceof Player player) {
                        final DiscordBridge bridge = plugin.discord();
                        if (bridge == null) {
                            player.sendMessage(plugin.text().prefixed("<red>客服單現在沒有開放，請到 Discord 聯絡管理員"));
                        } else {
                            bridge.tickets().fromGame(player, StringArgumentType.getString(context, "message"));
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    private static String escape(final Player player) {
        return tw.exilecore.item.ItemRenderer.escape(player.getName());
    }
}
