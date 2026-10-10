package tw.exilecore.zone;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.List;
import java.util.function.Consumer;

/**
 * 玩家指令（不需要權限）：
 * <pre>
 * /wp      傳送選單（營地、發現過的檢查點、回到剛才的位置）
 * /town    免費回營地（記住原本的位置，/wp 可以傳回去）
 * </pre>
 */
public final class ZoneCommand {

    private ZoneCommand() {
    }

    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        final LiteralCommandNode<CommandSourceStack> waypoint = Commands.literal("wp")
                .executes(context -> run(plugin, context, player -> new WaypointMenu(plugin, player).open()))
                .build();
        final LiteralCommandNode<CommandSourceStack> town = Commands.literal("town")
                .executes(context -> run(plugin, context, player -> plugin.zones().goTown(player)))
                .build();
        return List.of(waypoint, town);
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.zones() == null) {
            player.sendMessage(plugin.text().prefixed("<red>區域模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
