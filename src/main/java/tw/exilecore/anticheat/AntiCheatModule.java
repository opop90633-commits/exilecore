package tw.exilecore.anticheat;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.module.Module;

/**
 * anticheat 模組（2026-10）：防作弊——只管改遊戲數值那一類（移動速度、飛行、懸空、瞬移），data/anticheat.yml。
 * <pre>
 * /ec anticheat 玩家   看這個人的檢查狀態（管理員）
 * </pre>
 * 權限：exilecore.anticheat.bypass 不檢查；exilecore.admin 收到通知。
 */
public final class AntiCheatModule implements Module {

    private final ExileCore plugin;
    private AntiCheatService service;

    public AntiCheatModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "anticheat";
    }

    public AntiCheatService service() {
        return service;
    }

    @Override
    public void enable() throws Exception {
        final AntiCheatConfig config = load();
        final AntiCheatService created = new AntiCheatService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(created, plugin);
        created.start();
        service = created;
        plugin.getSLF4JLogger().info("防作弊：{}（超速 {}、飛行 {}；放寬 {}、{} 秒內拉回 {} 次踢出）", config.enabled() ? "開" : "關",
                config.speed() ? "查" : "不查", config.flight() ? "查" : "不查", config.movement().tolerance(), config.strikeWindowSeconds(),
                config.strikesToKick() > 0 ? String.valueOf(config.strikesToKick()) : "不踢");
    }

    private AntiCheatConfig load() throws Exception {
        final AntiCheatConfig config = new AntiCheatConfig();
        config.load(plugin.dataFolder().resolve("data"));
        for (final String problem : config.problems()) {
            plugin.getSLF4JLogger().warn(problem);
        }
        return config;
    }

    @Override
    public void reload() throws Exception {
        if (service != null) {
            service.reload(load());
        }
    }

    @Override
    public void disable() {
        if (service != null) {
            service.stop();
            service = null;
        }
    }

    /** /ec anticheat 玩家：看檢查狀態。 */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("anticheat")
                .executes(context -> {
                    final AntiCheatService service = plugin.antiCheat();
                    context.getSource().getSender().sendMessage(plugin.text().prefixed(service == null ? "<red>防作弊模組沒有啟用"
                            : "<gray>防作弊：" + (service.config().enabled() ? "<green>開" : "<red>關") + "<gray>　/ec anticheat 玩家 看狀態；改 data/anticheat.yml 後 /ec reload"));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
                    for (final Player online : plugin.getServer().getOnlinePlayers()) {
                        if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(online.getName());
                        }
                    }
                    return builder.buildFuture();
                }).executes(context -> {
                    final AntiCheatService service = plugin.antiCheat();
                    final String name = StringArgumentType.getString(context, "player");
                    final Player target = plugin.getServer().getPlayerExact(name);
                    if (service == null) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>防作弊模組沒有啟用"));
                    } else if (target == null) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(name) + " 不在線"));
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<white>" + ItemRenderer.escape(target.getName())
                                + "<gray>：" + ItemRenderer.escape(service.status(target))));
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
