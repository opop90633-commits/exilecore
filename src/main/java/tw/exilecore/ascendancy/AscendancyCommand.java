package tw.exilecore.ascendancy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 昇華指令。
 * <pre>
 * /ascend、/trials                    昇華與試煉（玩家）
 * /ascend tree                        昇華天賦（還沒選昇華職業時打開選擇畫面）
 * /ascend choose                      選擇昇華職業（試煉完成時聊天欄的連結）
 * /ascend preview                     昇華預覽：還沒選、還沒解鎖也能看每個昇華職業的完整天賦樹（可切換看全部職業）
 * /ec ascend                          自己的昇華狀態（管理員；用 /execute as 玩家 run ec ascend … 改別人的）
 * /ec ascend trial &lt;試煉&gt;              直接完成一個試煉（給昇華點）
 * /ec ascend untrial &lt;試煉&gt;            變回沒完成
 * /ec ascend reset                    清掉昇華職業與配點（可以重選；試煉不動）
 * /ec ascend wipe                     連試煉一起清掉
 * </pre>
 */
public final class AscendancyCommand {

    private AscendancyCommand() {
    }

    /** 玩家指令。 */
    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        return List.of(
                Commands.literal("ascend")
                        .executes(context -> run(plugin, context, player -> plugin.ascendancy().open(player)))
                        .then(Commands.literal("tree").executes(context -> run(plugin, context, player -> plugin.ascendancy().openTree(player))))
                        .then(Commands.literal("choose").executes(context -> run(plugin, context, player -> plugin.ascendancy().openTree(player))))
                        .then(Commands.literal("preview").executes(context -> run(plugin, context, player -> plugin.ascendancy().openPreview(player, null, false))))
                        .build(),
                Commands.literal("trials").executes(context -> run(plugin, context, player -> plugin.ascendancy().open(player))).build());
    }

    /** /ec ascend 底下的管理指令。 */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("ascend")
                .executes(context -> run(plugin, context, player -> info(plugin, player)))
                .then(Commands.literal("trial").then(trialArgument(plugin).executes(context -> withTrial(plugin, context, (player, trial) -> {
                    plugin.ascendancy().adminTrial(player, trial);
                    player.sendMessage(plugin.text().prefixed("<green>完成試煉 " + AscendancyService.escape(trial.name())
                            + "（昇華點 " + plugin.ascendancy().ascension(player).points() + "）"));
                }))))
                .then(Commands.literal("untrial").then(trialArgument(plugin).executes(context -> withTrial(plugin, context, (player, trial) -> {
                    plugin.ascendancy().adminUntrial(player, trial);
                    player.sendMessage(plugin.text().prefixed("<green>" + AscendancyService.escape(trial.name()) + " 變回沒完成（昇華點 "
                            + plugin.ascendancy().ascension(player).points() + "，放不下的昇華天賦拿掉了）"));
                }))))
                .then(Commands.literal("reset").executes(context -> run(plugin, context, player -> {
                    plugin.ascendancy().adminReset(player);
                    player.sendMessage(plugin.text().prefixed("<green>清掉了昇華職業與配點（試煉還在，可以重選）"));
                })))
                .then(Commands.literal("wipe").executes(context -> run(plugin, context, player -> {
                    plugin.ascendancy().adminWipe(player);
                    player.sendMessage(plugin.text().prefixed("<green>清掉了昇華職業、配點與試煉"));
                })));
    }

    private static void info(final ExileCore plugin, final Player player) {
        player.sendMessage(plugin.text().prefixed("<white>昇華（" + AscendancyService.escape(player.getName()) + "）"));
        for (final String line : plugin.ascendancy().adminInfo(player)) {
            player.sendMessage(plugin.text().parse(line));
        }
        player.sendMessage(plugin.text().parse("<dark_gray>/ec ascend trial 試煉|untrial 試煉|reset|wipe"));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> trialArgument(final ExileCore plugin) {
        return Commands.argument("trial", StringArgumentType.word()).suggests((context, builder) -> {
            if (plugin.ascendancy() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final AscendancyConfig.Trial trial : plugin.ascendancy().config().trials()) {
                    if (trial.id().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(trial.id());
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private static int withTrial(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                                 final BiConsumer<Player, AscendancyConfig.Trial> action) {
        return run(plugin, context, player -> {
            final String id = StringArgumentType.getString(context, "trial");
            final AscendancyConfig.Trial trial = plugin.ascendancy().config().trial(id);
            if (trial == null) {
                player.sendMessage(plugin.text().prefixed("<red>沒有試煉 " + AscendancyService.escape(id) + "（/ec ascend）"));
                return;
            }
            action.accept(player, trial);
        });
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.ascendancy() == null) {
            player.sendMessage(plugin.text().prefixed("<red>昇華模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.ascendancy().ready(player)) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
