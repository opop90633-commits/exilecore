package tw.exilecore.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

/**
 * 維護模式的指令（2026-10）：主控台也能用，不用進遊戲、不用開管理網頁。跟管理網頁的「伺服器開關」是同一個設定
 * （admin-overrides.yml 的 maintenance），重開伺服器也會記得。
 * <pre>
 * /ec maintenance              現在開著沒有、給玩家看的說明
 * /ec maintenance on [說明]     開：沒有管理權限的人進不來，在線的請出去（角色照常存檔）；沒寫說明沿用上次的
 * /ec maintenance off          關
 * </pre>
 */
public final class MaintenanceCommand {

    /** 說明最長幾個字（跟管理網頁一樣）。 */
    private static final int MAX_MESSAGE = 200;

    private MaintenanceCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("maintenance")
                .executes(context -> status(plugin, context.getSource().getSender()))
                .then(Commands.literal("on")
                        .executes(context -> set(plugin, context.getSource().getSender(), true, null))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> set(plugin, context.getSource().getSender(), true,
                                        StringArgumentType.getString(context, "message")))))
                .then(Commands.literal("off")
                        .executes(context -> set(plugin, context.getSource().getSender(), false, null)));
    }

    private static int status(final ExileCore plugin, final CommandSender sender) {
        final boolean on = plugin.overrides().maintenance();
        sender.sendMessage(plugin.text().prefixed(on
                ? "<yellow>維護模式：開著</yellow> <gray>（只有管理員進得來）"
                : "<green>維護模式：關著</green>"));
        sender.sendMessage(plugin.text().prefixed("<gray>給玩家看的說明：<white>" + ItemRenderer.escape(plugin.overrides().maintenanceMessage())));
        sender.sendMessage(plugin.text().prefixed("<dark_gray>/ec maintenance on [說明]　/ec maintenance off"));
        return Command.SINGLE_SUCCESS;
    }

    private static int set(final ExileCore plugin, final CommandSender sender, final boolean enabled, final String message) {
        if (message != null && message.strip().length() > MAX_MESSAGE) {
            sender.sendMessage(plugin.text().prefixed("<red>說明最多 " + MAX_MESSAGE + " 個字"));
            return Command.SINGLE_SUCCESS;
        }
        try {
            plugin.overrides().setMaintenance(enabled, message);
        } catch (final java.io.IOException exception) {
            sender.sendMessage(plugin.text().prefixed("<red>存不進 admin-overrides.yml：" + ItemRenderer.escape(String.valueOf(exception.getMessage()))));
            return Command.SINGLE_SUCCESS;
        }
        final AdminModule admin = plugin.admin();
        final int kicked = enabled && admin != null ? admin.kickNonAdmins() : 0;
        final String text = (enabled ? "開啟維護模式" : "關閉維護模式") + (kicked > 0 ? "，請出 " + kicked + " 個玩家" : "");
        plugin.getSLF4JLogger().info("[指令] {} {}{}", sender.getName(), text,
                enabled ? "（說明：" + plugin.overrides().maintenanceMessage() + "）" : "");
        sender.sendMessage(plugin.text().prefixed(enabled
                ? "<yellow>維護模式開了</yellow><gray>：只有管理員進得來" + (kicked > 0 ? "，請出 <white>" + kicked + "</white> 個玩家" : "")
                + "\n<gray>說明：<white>" + ItemRenderer.escape(plugin.overrides().maintenanceMessage())
                : "<green>維護模式關了</green><gray>：大家都進得來"));
        return Command.SINGLE_SUCCESS;
    }
}
