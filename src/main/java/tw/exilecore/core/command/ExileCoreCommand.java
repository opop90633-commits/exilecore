package tw.exilecore.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;

import java.util.Locale;

/**
 * {@code /ec} 管理指令（別名 {@code /exilecore}）。
 * <pre>
 * /ec ping     伺服器是否活著、TPS 與 MSPT
 * /ec version  插件、Paper、Java 版本與已啟用模組
 * /ec db       資料庫連線狀態與來回時間
 * /ec reload   重新載入 config.yml
 * </pre>
 */
public final class ExileCoreCommand {

    private ExileCoreCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(final ExileCore plugin) {
        return Commands.literal("ec")
                .requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                .executes(context -> help(plugin, context.getSource().getSender()))
                .then(Commands.literal("ping")
                        .executes(context -> ping(plugin, context.getSource().getSender())))
                .then(Commands.literal("version")
                        .executes(context -> version(plugin, context.getSource().getSender())))
                .then(Commands.literal("db")
                        .executes(context -> db(plugin, context.getSource().getSender())))
                .then(Commands.literal("reload")
                        .executes(context -> reload(plugin, context.getSource().getSender())))
                .build();
    }

    private static int help(final ExileCore plugin, final CommandSender sender) {
        sender.sendMessage(plugin.text().prefixed("<gray>指令：<white>/ec ping</white>、<white>/ec version</white>、"
                + "<white>/ec db</white>、<white>/ec reload</white></gray>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int ping(final ExileCore plugin, final CommandSender sender) {
        final double tps = plugin.getServer().getTPS()[0];
        final double mspt = plugin.getServer().getAverageTickTime();
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<green>pong</green> <gray>TPS <white>%.1f</white>，每 tick <white>%.1f</white> 毫秒</gray>",
                tps, mspt)));
        return Command.SINGLE_SUCCESS;
    }

    private static int version(final ExileCore plugin, final CommandSender sender) {
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>ExileCore <white>%s</white>　Paper <white>%s</white>　Java <white>%s</white></gray>",
                plugin.getPluginMeta().getVersion(),
                plugin.getServer().getMinecraftVersion(),
                Runtime.version().feature())));
        sender.sendMessage(plugin.text().prefixed("<gray>已啟用模組：<white>"
                + String.join(", ", plugin.modules().enabledIds()) + "</white></gray>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int db(final ExileCore plugin, final CommandSender sender) {
        if (!plugin.database().isConnected()) {
            sender.sendMessage(plugin.text().prefixed("<red>資料庫未連線</red> <gray>請看主控台的錯誤訊息，或檢查 config.yml 的 storage 區塊</gray>"));
            return Command.SINGLE_SUCCESS;
        }
        final long millis = plugin.database().ping();
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>%s，來回 <white>%d</white> 毫秒</gray>", plugin.database().description(), millis)));
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(final ExileCore plugin, final CommandSender sender) {
        plugin.reload();
        sender.sendMessage(plugin.text().prefixed("<green>設定已重新載入</green>"));
        return Command.SINGLE_SUCCESS;
    }
}
