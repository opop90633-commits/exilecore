package tw.exilecore.quest;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import java.util.function.Consumer;

/**
 * 任務指令。
 * <pre>
 * /quests、/q                              任務日誌（玩家）
 * /ec quest list                           自己每個任務的狀態（管理員；用 /execute as 玩家 run ec quest … 改別人的）
 * /ec quest start &lt;任務&gt;                   直接開始（不管前置）
 * /ec quest stage &lt;任務&gt; &lt;第幾步&gt;          跳到第幾步（從 1 開始）
 * /ec quest complete &lt;任務&gt; [選項]          直接完成（幾選一的任務沒寫選項 = 第一個）
 * /ec quest reset &lt;任務|all&gt;                清掉（變回沒開始過）
 * </pre>
 */
public final class QuestCommand {

    private QuestCommand() {
    }

    /** 玩家指令。 */
    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        return List.of(
                Commands.literal("quests").executes(context -> run(plugin, context, player -> open(plugin, player))).build(),
                Commands.literal("q").executes(context -> run(plugin, context, player -> open(plugin, player))).build());
    }

    private static void open(final ExileCore plugin, final Player player) {
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        new QuestMenu(plugin, player, plugin.quests()).open();
    }

    /** /ec quest 底下的管理指令。 */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("quest")
                .executes(context -> run(plugin, context, player -> list(plugin, player)))
                .then(Commands.literal("list").executes(context -> run(plugin, context, player -> list(plugin, player))))
                .then(Commands.literal("start")
                        .then(questArgument(plugin).executes(context -> withQuest(plugin, context, (player, quest) -> {
                            final QuestLog log = plugin.quests().log(player);
                            log.forceStage(quest, 0);
                            plugin.quests().admin(player, log);
                            player.sendMessage(plugin.text().prefixed("<green>開始任務 " + QuestText.escape(quest.name())));
                        }))))
                .then(Commands.literal("stage")
                        .then(questArgument(plugin)
                                .then(Commands.argument("stage", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> withQuest(plugin, context, (player, quest) -> {
                                            final int stage = IntegerArgumentType.getInteger(context, "stage");
                                            final QuestLog log = plugin.quests().log(player);
                                            log.forceStage(quest, stage - 1);
                                            plugin.quests().admin(player, log);
                                            player.sendMessage(plugin.text().prefixed("<green>" + QuestText.escape(quest.name())
                                                    + " 跳到第 " + Math.min(stage, quest.stages().size()) + " 步"));
                                        })))))
                .then(Commands.literal("complete")
                        .then(questArgument(plugin)
                                .executes(context -> withQuest(plugin, context, (player, quest) -> complete(plugin, player, quest, null)))
                                .then(Commands.argument("choice", StringArgumentType.word())
                                        .executes(context -> withQuest(plugin, context, (player, quest) ->
                                                complete(plugin, player, quest, StringArgumentType.getString(context, "choice")))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("quest", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    builder.suggest("all");
                                    return suggestQuests(plugin, builder);
                                })
                                .executes(context -> run(plugin, context, player -> reset(plugin, player,
                                        StringArgumentType.getString(context, "quest"))))));
    }

    private static void complete(final ExileCore plugin, final Player player, final QuestConfig.Quest quest, final String choice) {
        final QuestLog log = plugin.quests().log(player);
        log.forceComplete(quest, choice);
        plugin.quests().admin(player, log);
    }

    private static void reset(final ExileCore plugin, final Player player, final String id) {
        final QuestService service = plugin.quests();
        final QuestLog log = service.log(player);
        int count = 0;
        for (final QuestConfig.Quest quest : service.config().quests()) {
            if (("all".equalsIgnoreCase(id) || quest.id().equals(id)) && log.state(quest).started()) {
                log.reset(quest);
                count++;
            }
        }
        if (count == 0 && !"all".equalsIgnoreCase(id) && service.config().quest(id) == null) {
            player.sendMessage(plugin.text().prefixed("<red>沒有任務 " + QuestText.escape(id)));
            return;
        }
        service.admin(player, log);
        // 清掉之後，自動開始的任務再對一次（例如序章）
        service.refresh(player);
        player.sendMessage(plugin.text().prefixed("<green>清掉 " + count + " 個任務的進度（天賦點與加成跟著拿掉）"));
    }

    private static void list(final ExileCore plugin, final Player player) {
        final QuestService service = plugin.quests();
        final QuestLog log = service.log(player);
        player.sendMessage(plugin.text().prefixed("<white>任務（" + service.config().quests().size() + " 個）"));
        for (final QuestConfig.Quest quest : service.config().quests()) {
            final QuestLog.State state = log.state(quest);
            final QuestLog.Phase phase = log.phase(quest);
            final String status = switch (phase) {
                case DONE -> "<green>完成" + (state.choice() == null ? "" : "（" + QuestText.escape(state.choice()) + "）");
                case ACTIVE -> "<yellow>第 " + (state.stage() + 1) + "/" + quest.stages().size() + " 步 "
                        + QuestText.escape(quest.stage(state.stage()).text()) + " " + QuestText.progress(log, quest);
                case AVAILABLE -> "<white>可以接";
                default -> "<dark_gray>" + QuestText.escape(String.valueOf(log.lockReason(quest)));
            };
            player.sendMessage(plugin.text().parse(" " + quest.kind().color() + quest.id() + " <gray>" + QuestText.escape(quest.name())
                    + "：" + status));
        }
        player.sendMessage(plugin.text().parse("<dark_gray>任務給的天賦點 " + service.passivePoints(plugin.players().get(player))
                + "；/ec quest start|stage|complete|reset"));
    }

    @FunctionalInterface
    private interface QuestAction {
        void run(Player player, QuestConfig.Quest quest);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> questArgument(final ExileCore plugin) {
        return Commands.argument("quest", StringArgumentType.word()).suggests((context, builder) -> suggestQuests(plugin, builder));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestQuests(
            final ExileCore plugin, final com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (plugin.quests() != null) {
            final String remaining = builder.getRemainingLowerCase();
            for (final QuestConfig.Quest quest : plugin.quests().config().quests()) {
                if (quest.id().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(quest.id());
                }
            }
        }
        return builder.buildFuture();
    }

    private static int withQuest(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final QuestAction action) {
        return run(plugin, context, player -> {
            final String id = StringArgumentType.getString(context, "quest");
            final QuestConfig.Quest quest = plugin.quests().config().quest(id);
            if (quest == null) {
                player.sendMessage(plugin.text().prefixed("<red>沒有任務 " + QuestText.escape(id) + "（/ec quest list）"));
                return;
            }
            action.run(player, quest);
        });
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.quests() == null) {
            player.sendMessage(plugin.text().prefixed("<red>任務模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.quests().ready(player)) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
