package tw.exilecore.bounty;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
 * 委託指令。
 * <pre>
 * /bounty、/daily                        委託板（玩家）
 * /title、/titles                        稱號：擁有的一覽、戴上／拿下、還沒有的怎麼拿（玩家）
 * /ec bounty                             自己的委託與功勳（管理員；用 /execute as 玩家 run ec bounty … 改別人的）
 * /ec bounty reroll                      今天的委託重抽
 * /ec bounty complete &lt;1–6&gt;              直接完成第幾個委託（給獎勵）
 * /ec bounty weekly                      這週的回響變回沒完成
 * /ec bounty merits &lt;數量&gt;               給功勳（負數扣）；merits set &lt;數量&gt; 直接設定
 * /ec bounty title &lt;代號&gt;                 直接給一個稱號（功勳兌換的或每月活動的 event.主題；測加成用）
 * /ec bounty untitle &lt;代號&gt;               拿掉一個稱號
 * </pre>
 */
public final class BountyCommand {

    private BountyCommand() {
    }

    /** 玩家指令。 */
    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        return List.of(
                Commands.literal("bounty").executes(context -> run(plugin, context, false, player -> plugin.bounties().open(player))).build(),
                Commands.literal("daily").executes(context -> run(plugin, context, false, player -> plugin.bounties().open(player))).build(),
                Commands.literal("title").executes(context -> run(plugin, context, false, player -> plugin.bounties().openTitles(player))).build(),
                Commands.literal("titles").executes(context -> run(plugin, context, false, player -> plugin.bounties().openTitles(player))).build());
    }

    /** /ec bounty 底下的管理指令。 */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("bounty")
                .executes(context -> run(plugin, context, true, player -> info(plugin, player)))
                .then(Commands.literal("reroll").executes(context -> run(plugin, context, true, player -> {
                    plugin.bounties().adminReroll(player);
                    player.sendMessage(plugin.text().prefixed("<green>今天的委託重抽了"));
                })))
                .then(Commands.literal("complete")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, BountyConfig.MAX_DAILY))
                                .executes(context -> run(plugin, context, true, player -> {
                                    final int slot = IntegerArgumentType.getInteger(context, "slot");
                                    if (!plugin.bounties().adminComplete(player, slot - 1)) {
                                        player.sendMessage(plugin.text().prefixed("<red>第 " + slot + " 個委託不存在或已經完成"));
                                    }
                                }))))
                .then(Commands.literal("weekly").executes(context -> run(plugin, context, true, player -> {
                    plugin.bounties().adminResetWeekly(player);
                    player.sendMessage(plugin.text().prefixed("<green>這週的回響變回沒完成（開回響的冷卻也清掉了）"));
                })))
                .then(Commands.literal("merits")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(-1_000_000, 1_000_000))
                                .executes(context -> run(plugin, context, true, player -> {
                                    plugin.bounties().adminMerits(player, IntegerArgumentType.getInteger(context, "amount"), false);
                                    player.sendMessage(plugin.text().prefixed("<green>功勳：" + plugin.bounties().merits(player)));
                                })))
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 1_000_000))
                                        .executes(context -> run(plugin, context, true, player -> {
                                            plugin.bounties().adminMerits(player, IntegerArgumentType.getInteger(context, "value"), true);
                                            player.sendMessage(plugin.text().prefixed("<green>功勳：" + plugin.bounties().merits(player)));
                                        })))))
                .then(Commands.literal("title")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(titleIds(plugin))
                                .executes(context -> run(plugin, context, true, player -> {
                                    final String id = StringArgumentType.getString(context, "id");
                                    final String problem = plugin.bounties().grantTitle(player, id);
                                    player.sendMessage(plugin.text().prefixed(problem == null
                                            ? "<green>給了稱號 " + MeritShop.tag(plugin.bounties().title(id)) + "<green>（已經戴上；/title 看加成）"
                                            : "<red>" + BountyService.escape(problem)));
                                }))
                                // 2026-10-17：發給別的玩家（例如 /ec bounty title mentor 玩家：新手指導員）
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames(plugin))
                                        .executes(context -> runAny(plugin, context, admin -> {
                                            final String id = StringArgumentType.getString(context, "id");
                                            final Player target = plugin.getServer().getPlayerExact(StringArgumentType.getString(context, "player"));
                                            if (target == null) {
                                                admin.sendMessage(plugin.text().prefixed("<red>那個玩家不在線上（要在線才能發）"));
                                                return;
                                            }
                                            final String problem = plugin.bounties().grantTitle(target, id);
                                            if (problem != null) {
                                                admin.sendMessage(plugin.text().prefixed("<red>" + BountyService.escape(problem)));
                                                return;
                                            }
                                            final String tag = MeritShop.tag(plugin.bounties().title(id));
                                            admin.sendMessage(plugin.text().prefixed("<green>給了 " + BountyService.escape(target.getName()) + " 稱號 " + tag));
                                            target.sendMessage(plugin.text().prefixed("<green>你得到了稱號 " + tag + "<green>（已經戴上；/title 可以換）"));
                                            plugin.getSLF4JLogger().info("[稱號] {} 發給 {}：{}", admin.getName(), target.getName(), id);
                                        })))))
                .then(Commands.literal("untitle")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(titleIds(plugin))
                                .executes(context -> run(plugin, context, true, player -> {
                                    final String problem = plugin.bounties().adminRemoveTitle(player, StringArgumentType.getString(context, "id"));
                                    player.sendMessage(plugin.text().prefixed(problem == null ? "<green>拿掉了" : "<red>" + BountyService.escape(problem)));
                                }))
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames(plugin))
                                        .executes(context -> runAny(plugin, context, admin -> {
                                            final Player target = plugin.getServer().getPlayerExact(StringArgumentType.getString(context, "player"));
                                            if (target == null) {
                                                admin.sendMessage(plugin.text().prefixed("<red>那個玩家不在線上（要在線才能拿掉）"));
                                                return;
                                            }
                                            final String id = StringArgumentType.getString(context, "id");
                                            final String problem = plugin.bounties().adminRemoveTitle(target, id);
                                            admin.sendMessage(plugin.text().prefixed(problem == null
                                                    ? "<green>拿掉了 " + BountyService.escape(target.getName()) + " 的稱號 " + BountyService.escape(id)
                                                    : "<red>" + BountyService.escape(problem)));
                                            if (problem == null) {
                                                plugin.getSLF4JLogger().info("[稱號] {} 拿掉 {} 的：{}", admin.getName(), target.getName(), id);
                                            }
                                        })))));
    }

    /** 在線玩家的名字（發稱號用）。 */
    private static SuggestionProvider<CommandSourceStack> onlineNames(final ExileCore plugin) {
        return (context, builder) -> {
            final String remaining = builder.getRemainingLowerCase();
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(online.getName());
                }
            }
            return builder.buildFuture();
        };
    }

    /** 認得的稱號代號（功勳兌換的，再接每月活動的 event.主題）。 */
    private static SuggestionProvider<CommandSourceStack> titleIds(final ExileCore plugin) {
        return (context, builder) -> {
            if (plugin.bounties() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final BountyConfig.Title title : plugin.bounties().allTitles()) {
                    if (title.id().startsWith(remaining)) {
                        builder.suggest(title.id());
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private static void info(final ExileCore plugin, final Player player) {
        player.sendMessage(plugin.text().prefixed("<white>委託與功勳（" + BountyService.escape(player.getName()) + "）"));
        for (final String line : plugin.bounties().adminInfo(player)) {
            player.sendMessage(plugin.text().parse(line));
        }
        player.sendMessage(plugin.text().parse("<dark_gray>/ec bounty reroll|complete 第幾個|weekly|merits 數量|merits set 數量|title 代號 [玩家]|untitle 代號 [玩家]"));
    }

    /** 指定玩家的管理指令（2026-10-17）：主控台也能用（不需要管理員自己在線、帳號載入）。 */
    private static int runAny(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                              final Consumer<org.bukkit.command.CommandSender> action) {
        final org.bukkit.command.CommandSender sender = context.getSource().getSender();
        if (plugin.bounties() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>委託模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(sender);
        return Command.SINGLE_SUCCESS;
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final boolean admin,
                           final Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.bounties() == null) {
            player.sendMessage(plugin.text().prefixed("<red>委託模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        if (admin && plugin.bounties().merits(player) < 0) {
            player.sendMessage(plugin.text().prefixed("<red>帳號資料還沒載入"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
