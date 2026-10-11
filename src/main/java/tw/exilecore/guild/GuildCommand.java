package tw.exilecore.guild;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 玩家指令：
 * <pre>
 * /guild                          公會選單
 * /guild create &lt;標籤&gt; &lt;名稱&gt;      建立公會（要等級與金幣）
 * /guild invite &lt;角色名稱&gt;        邀請（會長、幹部）
 * /guild accept [公會]            接受邀請（不寫就是最新的一筆）
 * /guild decline [公會]           拒絕邀請
 * /guild apply &lt;公會&gt; [留言]      申請加入（2026-10；沒有公會的人，最多同時 3 個）
 * /guild unapply [公會]           撤回申請（不寫 = 全部）
 * /guild applications             入會申請名單（會長、幹部審核；沒有公會的人看自己的申請）
 * /guild approve｜reject &lt;名字&gt;   批准、拒絕申請（會長、幹部）
 * /guild leave                    離開
 * /guild kick &lt;名字&gt;             請出（會長、幹部）
 * /guild promote｜demote &lt;名字&gt;   升成幹部、降成成員（會長）
 * /guild leader &lt;名字&gt;           交出會長
 * /guild motd [文字]              改公告（不寫 = 清掉；會長、幹部）
 * /guild donate &lt;金幣&gt;           捐金幣換公會經驗
 * /guild stash                    公會倉庫（營地）
 * /guild info [公會]、/guild top  資料、排行
 * /guild disband [confirm]        解散（會長）
 * /guild rename &lt;標籤&gt; &lt;名稱&gt;      改公會的名稱與標籤（會長；要一張公會改名卡）
 * /g &lt;訊息&gt;                      公會頻道（也可以 /guild chat 訊息）
 * /guild admin                    管理員指令一覽（2026-10 補齊）：
 *   list、info｜log｜stash &lt;公會&gt;                         查詢（stash 只能看）
 *   leader｜promote｜demote｜kick &lt;成員&gt;                 改會長、升降階、請出（成員在哪個公會自己找）
 *   add &lt;公會&gt; &lt;玩家&gt;、rename &lt;公會&gt; &lt;標籤&gt; &lt;名稱&gt;、motd &lt;公會&gt; [文字]
 *   xp &lt;數量&gt; &lt;公會&gt;、level &lt;等級&gt; &lt;公會&gt;、pages &lt;頁數&gt; &lt;公會&gt;
 *   disband｜forcedisband &lt;公會&gt;
 * </pre>
 * 公會的名稱與標籤都沒有空白，所以「公會 其他」的參數第一段就是公會（名稱或標籤都可以）。
 * 名字有中文，所以名字、公會名稱的參數都是「剩下的整串」。
 */
public final class GuildCommand {

    private GuildCommand() {
    }

    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        final LiteralCommandNode<CommandSourceStack> guild = Commands.literal("guild")
                .executes(context -> run(plugin, context, GuildService::openMenu))
                .then(Commands.literal("create")
                        .executes(context -> run(plugin, context, (service, player) -> player.sendMessage(plugin.text().prefixed(
                                "<gray>用法：<white>/guild create 標籤 名稱<gray>（例如 /guild create 星骸 星骸遠征隊；要 Lv." + service.config().createLevel()
                                        + " 與 " + tw.exilecore.economy.GoldService.format(service.config().createCost()) + " 金幣）"))))
                        // 標籤與名稱都可能是中文（Brigadier 的單字參數只收英數），所以收「剩下的整串」自己切：第一段是標籤，後面是名稱
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, (service, player) -> {
                                    final String[] parts = StringArgumentType.getString(context, "text").strip().split("\\s+", 2);
                                    if (parts.length < 2) {
                                        player.sendMessage(plugin.text().prefixed("<gray>用法：<white>/guild create 標籤 名稱<gray>（標籤與名稱中間空一格）"));
                                        return;
                                    }
                                    service.create(player, parts[0], parts[1]);
                                }))))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(onlinePlayers(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.invite(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("accept")
                        .executes(context -> run(plugin, context, (service, player) -> service.accept(player, null)))
                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(invites(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.accept(player, StringArgumentType.getString(context, "guild"))))))
                .then(Commands.literal("decline")
                        .executes(context -> run(plugin, context, (service, player) -> service.decline(player, null)))
                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(invites(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.decline(player, StringArgumentType.getString(context, "guild"))))))
                .then(Commands.literal("apply")
                        .executes(context -> run(plugin, context, (service, player) -> player.sendMessage(plugin.text().prefixed(
                                "<gray>用法：<white>/guild apply 公會 [留言]<gray>（公會寫名稱或標籤；/guild top 看所有公會）"))))
                        // 公會名稱沒有空白：第一段是公會，後面是留言
                        .then(Commands.argument("text", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                .executes(context -> run(plugin, context, (service, player) -> {
                                    final String[] parts = StringArgumentType.getString(context, "text").strip().split("\\s+", 2);
                                    service.apply(player, parts[0], parts.length > 1 ? parts[1] : "");
                                }))))
                .then(Commands.literal("unapply")
                        .executes(context -> run(plugin, context, (service, player) -> service.withdraw(player, null)))
                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(applied(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.withdraw(player, StringArgumentType.getString(context, "guild"))))))
                .then(Commands.literal("applications")
                        .executes(context -> run(plugin, context, GuildService::openApplications)))
                .then(Commands.literal("approve")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(applicants(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.approve(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("reject")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(applicants(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.reject(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("leave")
                        .executes(context -> run(plugin, context, GuildService::leave)))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(members(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.kick(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("promote")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(members(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.setRank(player, StringArgumentType.getString(context, "player"), GuildRank.OFFICER)))))
                .then(Commands.literal("demote")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(members(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.setRank(player, StringArgumentType.getString(context, "player"), GuildRank.MEMBER)))))
                .then(Commands.literal("leader")
                        .then(Commands.argument("player", StringArgumentType.greedyString()).suggests(members(plugin))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.transfer(player, StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("motd")
                        .executes(context -> run(plugin, context, (service, player) -> service.motd(player, "")))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.motd(player, StringArgumentType.getString(context, "text"))))))
                .then(Commands.literal("donate")
                        .then(Commands.argument("gold", LongArgumentType.longArg(1))
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.donate(player, LongArgumentType.getLong(context, "gold"))))))
                .then(Commands.literal("stash")
                        .executes(context -> run(plugin, context, (service, player) -> service.openStash(player, 0))))
                .then(Commands.literal("info")
                        .executes(context -> any(plugin, context, (service, sender) -> service.info(sender, null)))
                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                .executes(context -> any(plugin, context, (service, sender) ->
                                        service.info(sender, StringArgumentType.getString(context, "guild"))))))
                .then(Commands.literal("top")
                        .executes(context -> any(plugin, context, GuildService::top)))
                .then(Commands.literal("disband")
                        .executes(context -> run(plugin, context, (service, player) -> service.disband(player, false)))
                        .then(Commands.literal("confirm")
                                .executes(context -> run(plugin, context, (service, player) -> service.disband(player, true)))))
                .then(Commands.literal("rename")
                        .executes(context -> run(plugin, context, (service, player) -> player.sendMessage(plugin.text().prefixed(
                                "<gray>用法：<white>/guild rename 新標籤 新名稱<gray>（會長；要一張公會改名卡，星幣商城買）"))))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, (service, player) -> {
                                    final String[] parts = StringArgumentType.getString(context, "text").strip().split("\\s+", 2);
                                    if (parts.length < 2) {
                                        player.sendMessage(plugin.text().prefixed("<gray>用法：<white>/guild rename 新標籤 新名稱<gray>（標籤與名稱中間空一格）"));
                                        return;
                                    }
                                    service.rename(player, parts[0], parts[1]);
                                }))))
                .then(Commands.literal("chat")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, (service, player) ->
                                        service.chat(player, StringArgumentType.getString(context, "message"))))))
                .then(Commands.literal("admin")
                        .requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                        .executes(context -> any(plugin, context, GuildService::adminHelp))
                        .then(Commands.literal("list")
                                .executes(context -> any(plugin, context, GuildService::adminList)))
                        .then(Commands.literal("info")
                                .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminInfo(sender, StringArgumentType.getString(context, "guild"))))))
                        .then(Commands.literal("log")
                                .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminLog(sender, StringArgumentType.getString(context, "guild"))))))
                        .then(Commands.literal("stash")
                                .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> run(plugin, context, (service, player) ->
                                                service.adminStash(player, StringArgumentType.getString(context, "guild"))))))
                        .then(Commands.literal("leader")
                                .then(Commands.argument("member", StringArgumentType.greedyString()).suggests(allMembers(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminLeader(sender, StringArgumentType.getString(context, "member"))))))
                        .then(Commands.literal("promote")
                                .then(Commands.argument("member", StringArgumentType.greedyString()).suggests(allMembers(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminRank(sender, StringArgumentType.getString(context, "member"), GuildRank.OFFICER)))))
                        .then(Commands.literal("demote")
                                .then(Commands.argument("member", StringArgumentType.greedyString()).suggests(allMembers(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminRank(sender, StringArgumentType.getString(context, "member"), GuildRank.MEMBER)))))
                        .then(Commands.literal("kick")
                                .then(Commands.argument("member", StringArgumentType.greedyString()).suggests(allMembers(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminKick(sender, StringArgumentType.getString(context, "member"))))))
                        .then(Commands.literal("add")
                                .executes(context -> any(plugin, context, (service, sender) -> service.adminAdd(sender, "")))
                                .then(Commands.argument("text", StringArgumentType.greedyString()).suggests(guildThenPlayer(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminAdd(sender, StringArgumentType.getString(context, "text"))))))
                        .then(Commands.literal("rename")
                                .executes(context -> any(plugin, context, (service, sender) -> service.adminRename(sender, "")))
                                .then(Commands.argument("text", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminRename(sender, StringArgumentType.getString(context, "text"))))))
                        .then(Commands.literal("motd")
                                .executes(context -> any(plugin, context, (service, sender) -> service.adminMotd(sender, "")))
                                .then(Commands.argument("text", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminMotd(sender, StringArgumentType.getString(context, "text"))))))
                        .then(Commands.literal("level")
                                .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                                .executes(context -> any(plugin, context, (service, sender) -> service.adminLevel(sender,
                                                        IntegerArgumentType.getInteger(context, "level"), StringArgumentType.getString(context, "guild")))))))
                        .then(Commands.literal("pages")
                                .then(Commands.argument("pages", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                                .executes(context -> any(plugin, context, (service, sender) -> service.adminPages(sender,
                                                        IntegerArgumentType.getInteger(context, "pages"), StringArgumentType.getString(context, "guild")))))))
                        .then(Commands.literal("disband")
                                .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminDisband(sender, StringArgumentType.getString(context, "guild"), false)))))
                        .then(Commands.literal("forcedisband")
                                .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                        .executes(context -> any(plugin, context, (service, sender) ->
                                                service.adminDisband(sender, StringArgumentType.getString(context, "guild"), true)))))
                        .then(Commands.literal("xp")
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .then(Commands.argument("guild", StringArgumentType.greedyString()).suggests(guilds(plugin))
                                                .executes(context -> any(plugin, context, (service, sender) -> service.adminExperience(sender,
                                                        LongArgumentType.getLong(context, "amount"), StringArgumentType.getString(context, "guild"))))))))
                .build();
        final LiteralCommandNode<CommandSourceStack> chat = Commands.literal("g")
                .executes(context -> run(plugin, context, (service, player) ->
                        player.sendMessage(plugin.text().prefixed("<gray>用法：<white>/g 訊息<gray>（公會頻道）"))))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> run(plugin, context, (service, player) ->
                                service.chat(player, StringArgumentType.getString(context, "message")))))
                .build();
        return List.of(guild, chat);
    }

    /** 要在遊戲內用的指令。 */
    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                           final BiConsumer<GuildService, Player> action) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final GuildService service = plugin.guilds();
        if (service == null) {
            player.sendMessage(plugin.text().prefixed("<red>公會模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(service, player);
        return Command.SINGLE_SUCCESS;
    }

    /** 主控台也能用的指令（資料、排行、管理員）。 */
    private static int any(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                           final BiConsumer<GuildService, CommandSender> action) {
        final CommandSender sender = context.getSource().getExecutor() instanceof Player player ? player : context.getSource().getSender();
        final GuildService service = plugin.guilds();
        if (service == null) {
            sender.sendMessage(plugin.text().prefixed("<red>公會模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(service, sender);
        return Command.SINGLE_SUCCESS;
    }

    /** 在線的其他玩家（角色名稱）。 */
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

    /** 邀請你的公會。 */
    private static SuggestionProvider<CommandSourceStack> invites(final ExileCore plugin) {
        return names(plugin, player -> plugin.guilds() == null ? List.of() : plugin.guilds().inviteNames(player));
    }

    /** 申請你公會的人。 */
    private static SuggestionProvider<CommandSourceStack> applicants(final ExileCore plugin) {
        return names(plugin, player -> plugin.guilds() == null ? List.of() : plugin.guilds().applicantNames(player));
    }

    /** 你申請過的公會。 */
    private static SuggestionProvider<CommandSourceStack> applied(final ExileCore plugin) {
        return names(plugin, player -> plugin.guilds() == null ? List.of() : plugin.guilds().appliedGuildNames(player));
    }

    /** 你的公會成員（不含自己）。 */
    private static SuggestionProvider<CommandSourceStack> members(final ExileCore plugin) {
        return names(plugin, player -> plugin.guilds() == null ? List.of() : plugin.guilds().memberNames(player));
    }

    /** 所有公會的成員（管理員指令；主控台也可以補完）。 */
    private static SuggestionProvider<CommandSourceStack> allMembers(final ExileCore plugin) {
        return (context, builder) -> {
            if (plugin.guilds() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final String name : plugin.guilds().allMemberNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    /** 「公會 玩家」：第一段補公會，打了空白之後補在線的玩家（角色名稱與玩家 ID）。 */
    private static SuggestionProvider<CommandSourceStack> guildThenPlayer(final ExileCore plugin) {
        final SuggestionProvider<CommandSourceStack> first = guilds(plugin);
        return (context, builder) -> {
            final String typed = builder.getRemaining();
            final int space = typed.lastIndexOf(' ');
            if (space < 0) {
                return first.getSuggestions(context, builder);
            }
            final var second = builder.createOffset(builder.getStart() + space + 1);
            final String remaining = second.getRemainingLowerCase();
            final CommandSender sender = context.getSource().getSender();
            final List<String> names = new ArrayList<>();
            if (plugin.chat() != null) {
                names.addAll(plugin.chat().suggest(second.getRemaining(), sender));
            } else {
                for (final Player online : plugin.getServer().getOnlinePlayers()) {
                    names.add(online.getName());
                }
            }
            for (final String name : names) {
                if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    second.suggest(name);
                }
            }
            return second.buildFuture();
        };
    }

    /** 全部的公會（主控台也可以補完）。 */
    private static SuggestionProvider<CommandSourceStack> guilds(final ExileCore plugin) {
        return (context, builder) -> {
            if (plugin.guilds() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final String name : plugin.guilds().guildNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        };
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
