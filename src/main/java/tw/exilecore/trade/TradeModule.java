package tw.exilecore.trade;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;
import java.util.Locale;

/**
 * trade 模組：面對面交易（/trade 玩家，或蹲下右鍵另一個玩家）。
 */
public final class TradeModule implements Module, Listener {

    private final ExileCore plugin;
    private TradeService service;
    private BukkitTask task;

    public TradeModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "trade";
    }

    @Override
    public void enable() {
        service = new TradeService(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("trade", service::tick, 20, 20);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.closeAll();
        }
    }

    public TradeService service() {
        return service;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.quit(event.getPlayer());
    }

    /** 蹲下右鍵另一個玩家：邀請交易（PoE 的 Ctrl＋點名字）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        final Player player = event.getPlayer();
        if (!player.isSneaking() || plugin.npcs() != null && plugin.npcs().isNpc(target)) {
            return;
        }
        if (target.hasMetadata("NPC")) {
            return; // 其他插件的假人
        }
        service.request(player, target);
    }

    // ---- 指令 ----

    /** /trade 玩家、/trade accept [玩家]、/trade decline [玩家]。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("trade")
                .executes(context -> help(plugin, context))
                .then(Commands.literal("accept")
                        .executes(context -> run(plugin, context, (service, player) -> service.accept(player, null)))
                        .then(Commands.argument("player", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    if (context.getSource().getExecutor() instanceof Player player && plugin.trade() != null) {
                                        for (final String name : plugin.trade().requesterNames(player)) {
                                            if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                                builder.suggest(name);
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.accept(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("decline")
                        .executes(context -> run(plugin, context, (service, player) -> service.decline(player, null)))
                        .then(Commands.argument("player", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.decline(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.argument("player", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            if (plugin.chat() != null) {
                                for (final String name : plugin.chat().suggest(builder.getRemaining(), context.getSource().getSender())) {
                                    builder.suggest(name);
                                }
                                return builder.buildFuture();
                            }
                            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                                if (online.getName().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(online.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> run(plugin, context, (service, player) -> {
                            final String name = StringArgumentType.getString(context, "player");
                            if (plugin.chat() == null) {
                                service.request(player, plugin.getServer().getPlayerExact(name.strip()));
                                return;
                            }
                            final Player target = plugin.chat().findOrTell(player, name); // 角色名稱或玩家 ID
                            if (target != null) {
                                service.request(player, target);
                            }
                        })))
                .build());
    }

    @FunctionalInterface
    private interface Action {
        void run(TradeService service, Player player);
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Action action) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.trade() == null) {
            player.sendMessage(plugin.text().prefixed("<red>交易模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.run(plugin.trade(), player);
        return Command.SINGLE_SUCCESS;
    }

    private static int help(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(plugin.text().prefixed(
                "<gray>面對面交易：<white>/trade 玩家</white>（或蹲下右鍵他）、<white>/trade accept</white>、<white>/trade decline</white>"));
        return Command.SINGLE_SUCCESS;
    }
}
