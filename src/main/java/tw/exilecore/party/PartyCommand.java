package tw.exilecore.party;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 玩家指令：
 * <pre>
 * /party                    隊伍選單
 * /party invite &lt;角色名稱&gt;  邀請（隊長，或還沒有隊伍的人；也可以打玩家 ID）
 * /party accept [玩家]       接受邀請（不寫就是最新的一筆）
 * /party decline [玩家]      拒絕邀請
 * /party leave              離開隊伍
 * /party kick &lt;玩家&gt;        踢出隊伍（隊長）
 * /party leader &lt;玩家&gt;      把隊長交給別人（隊長）
 * /p &lt;訊息&gt;                隊伍聊天（也可以 /party chat 訊息）
 * </pre>
 */
public final class PartyCommand {

    private PartyCommand() {
    }

    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        final LiteralCommandNode<CommandSourceStack> party = Commands.literal("party")
                .executes(context -> run(plugin, context, (service, player) -> new PartyMenu(plugin, player, service).open()))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(onlinePlayers(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.invite(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("accept")
                        .executes(context -> run(plugin, context, (service, player) -> service.accept(player, null)))
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(inviters(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.accept(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("decline")
                        .executes(context -> run(plugin, context, (service, player) -> service.decline(player, null)))
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(inviters(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.decline(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("leave")
                        .executes(context -> run(plugin, context, PartyService::leave)))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(members(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.kick(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("leader")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(members(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.promote(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("chat")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.chat(player, StringArgumentType.getString(context, "message"))))))
                .build();
        final LiteralCommandNode<CommandSourceStack> chat = Commands.literal("p")
                .executes(context -> run(plugin, context, (service, player) -> {
                    // 2026-10：不帶訊息 = 切換固定的隊伍頻道（之後直接打字都送到隊伍）
                    if (plugin.chat() != null) {
                        plugin.chat().toggleChannel(player, tw.exilecore.chat.ChatService.Channel.PARTY);
                    } else {
                        player.sendMessage(plugin.text().prefixed("<gray>用法：<white>/p 訊息<gray>（隊伍聊天）"));
                    }
                }))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> run(plugin, context, (service, player) ->
                                service.chat(player, StringArgumentType.getString(context, "message")))))
                .build();
        return List.of(party, chat);
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                           final BiConsumer<PartyService, Player> action) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final PartyService service = plugin.party();
        if (service == null) {
            player.sendMessage(plugin.text().prefixed("<red>隊伍模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(service, player);
        return Command.SINGLE_SUCCESS;
    }

    /** 在線的其他玩家（角色名稱；角色名稱有中文，所以名字的參數是「剩下的整串」）。 */
    private static SuggestionProvider<CommandSourceStack> onlinePlayers(final ExileCore plugin) {
        return names(plugin, player -> {
            if (plugin.chat() != null) {
                return plugin.chat().suggest("", player);
            }
            final List<String> result = new ArrayList<>();
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(player)) {
                    result.add(online.getName());
                }
            }
            return result;
        });
    }

    /** 邀請你的人。 */
    private static SuggestionProvider<CommandSourceStack> inviters(final ExileCore plugin) {
        return names(plugin, player -> plugin.party() == null ? List.of() : plugin.party().inviterNames(player));
    }

    /** 你的隊員（不含自己）。 */
    private static SuggestionProvider<CommandSourceStack> members(final ExileCore plugin) {
        return names(plugin, player -> {
            final List<String> result = new ArrayList<>();
            if (plugin.party() != null) {
                for (final UUID uuid : plugin.party().groupMembers(player.getUniqueId())) {
                    if (!uuid.equals(player.getUniqueId())) {
                        result.add(plugin.party().name(uuid));
                    }
                }
            }
            return result;
        });
    }

    private static SuggestionProvider<CommandSourceStack> names(final ExileCore plugin, final Function<Player, List<String>> source) {
        return (context, builder) -> {
            if (context.getSource().getExecutor() instanceof Player player) {
                final String remaining = builder.getRemainingLowerCase();
                for (final String name : source.apply(player)) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        };
    }
}
