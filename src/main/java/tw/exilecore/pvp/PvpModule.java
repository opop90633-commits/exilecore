package tw.exilecore.pvp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.module.Module;

/**
 * pvp 模組：PvP 競技場（data/pvp.yml）。要在世界、角色、戰鬥模組之後啟用。
 * <pre>
 * /pvp           競技場選單（進入、離開、規則、場上的人）
 * /pvp join      進入競技場
 * /pvp leave     離開競技場
 * /ec pvp rebuild  重蓋場地（管理員）
 * </pre>
 */
public final class PvpModule implements Module {

    private final ExileCore plugin;
    private final PvpConfig config = new PvpConfig();
    private PvpService service;

    public PvpModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "pvp";
    }

    @Override
    public void enable() throws Exception {
        load();
        final PvpService created = new PvpService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(created, plugin);
        service = created;
        // 世界要等所有模組都啟用之後（跟營地的自動擺設一樣）才蓋場地
        plugin.getServer().getScheduler().runTask(plugin, created::ensureBuilt);
        plugin.getSLF4JLogger().info("競技場：{}，PvP 減傷 {}%{}", config.enabled() ? "開放" : "關閉", Math.round(config.reduction()),
                created.arena() == null ? "（世界沒有載入）" : "");
    }

    private void load() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        for (final String problem : config.problems()) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.ensureBuilt(); // 原本關著、改成開放時：場地還沒蓋就蓋（蓋好的不動）
        }
    }

    @Override
    public void disable() {
        if (service != null) {
            service.shutdown();
        }
    }

    /** 競技場；模組沒啟用時為 null。 */
    public PvpService service() {
        return service;
    }

    // ---- 指令 ----

    /** /pvp [join|leave|watch] */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("pvp")
                .executes(context -> run(plugin, context, 0))
                .then(Commands.literal("join").executes(context -> run(plugin, context, 1)))
                .then(Commands.literal("leave").executes(context -> run(plugin, context, 2)))
                .then(Commands.literal("watch").executes(context -> run(plugin, context, 3)))
                .build();
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int action) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final PvpService service = plugin.pvp();
        if (service == null) {
            player.sendMessage(plugin.text().prefixed("<red>競技場模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        if (action == 0) {
            new PvpMenu(plugin, player, service).open();
            return Command.SINGLE_SUCCESS;
        }
        final String problem = action == 1 ? service.join(player) : action == 3 ? service.watch(player) : service.leave(player);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /ec pvp rebuild */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("pvp")
                .then(Commands.literal("rebuild").executes(context -> {
                    final PvpService service = plugin.pvp();
                    if (service == null) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>競技場模組沒有啟用"));
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<gray>競技場開始重蓋（場內的人可能會卡在方塊裡：先請他們離場）"));
                        service.rebuild(context.getSource().getSender());
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
