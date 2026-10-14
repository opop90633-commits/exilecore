package tw.exilecore.flask;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 藥劑的管理指令（測試用）。
 * <pre>
 * /ec flask list              所有藥劑基底
 * /ec flask give &lt;基底&gt;       給自己一瓶（滿充能）
 * /ec flask refill            背包裡的藥劑補滿
 * </pre>
 */
public final class FlaskCommands {

    private FlaskCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("flask")
                .then(Commands.literal("list").executes(context -> run(plugin, context, player -> list(plugin, player))))
                .then(Commands.literal("give")
                        .then(Commands.argument("base", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    if (plugin.flasks() != null) {
                                        final String remaining = builder.getRemainingLowerCase();
                                        for (final FlaskBase base : plugin.flasks().config().bases()) {
                                            if (base.id().startsWith(remaining)) {
                                                builder.suggest(base.id());
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> run(plugin, context, player -> give(plugin, player,
                                        StringArgumentType.getString(context, "base"))))))
                .then(Commands.literal("refill").executes(context -> run(plugin, context, player -> {
                    final int filled = plugin.flasks().refill(player);
                    player.sendMessage(plugin.text().prefixed("<green>補滿了 " + filled + " 瓶藥劑"));
                })));
    }

    private static void list(final ExileCore plugin, final Player player) {
        player.sendMessage(plugin.text().prefixed("<gray>藥劑（代號　名稱　需求等級　回復）："));
        for (final FlaskBase base : plugin.flasks().config().bases()) {
            player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>  <white>%s</white>　%s　Lv.%d　%d %s／%.1f 秒",
                    base.id(), ItemRenderer.escape(base.name()), base.level(), Math.round(base.recovery()),
                    base.kind().displayName(), base.duration())));
        }
    }

    private static void give(final ExileCore plugin, final Player player, final String id) {
        final FlaskBase base = plugin.flasks().config().base(id.toLowerCase(Locale.ROOT));
        if (base == null) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這種藥劑：" + ItemRenderer.escape(id) + "（/ec flask list）"));
            return;
        }
        final var flask = plugin.flasks().create(base, base.charges());
        if (!plugin.flasks().equipIfEmpty(player, flask)) { // 藥劑欄空著就直接放進去，跟撿到一樣
            for (final var rest : player.getInventory().addItem(flask).values()) {
                plugin.personalDrops().dropForever(player, rest);
            }
        }
        player.sendMessage(plugin.text().prefixed("<green>給了一瓶 " + ItemRenderer.escape(base.name())
                + "，" + base.kind().displayName() + "藥劑放在快捷欄 " + plugin.flasks().slotKey(player, base.kind()) + " 按數字鍵喝"));
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.flasks() == null) {
            player.sendMessage(plugin.text().prefixed("<red>藥劑模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
