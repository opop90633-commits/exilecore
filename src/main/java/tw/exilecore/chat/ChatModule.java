package tw.exilecore.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * chat 模組：角色名稱（TAB、聊天、私訊）、私訊指令、聊天裡的物品連結。
 * <pre>
 * /w 角色名稱 訊息      私訊（/msg、/tell、/whisper 一樣；取代原版的 /msg，原版只認玩家 ID）
 * /r 訊息              回覆最後一個私訊的人（/reply 一樣）
 * 聊天打 [i]            貼出手上的物品（[i1]～[i9]：快捷列第幾格）
 * /report 玩家 原因      檢舉（在線的管理員馬上看到，也寫進 reports.log）
 * /mute 玩家 分鐘 [原因] 禁言（管理員；0 分鐘 = 永久）
 * /unmute 玩家          解除禁言（管理員）
 * </pre>
 * 公開聊天與私訊都有洗頻限制與髒話過濾（{@link ChatModeration}）。
 * 要在角色模組之後啟用。
 */
public final class ChatModule implements Module {

    /** 私訊指令（每個都是主要名稱：Paper 的主要名稱才會蓋掉原版的同名指令）。 */
    static final List<String> WHISPER_LABELS = List.of("w", "msg", "tell", "whisper");
    static final List<String> REPLY_LABELS = List.of("r", "reply");

    private final ExileCore plugin;
    private ChatService service;

    public ChatModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public void enable() {
        final ChatService created = new ChatService(plugin);
        plugin.getServer().getPluginManager().registerEvents(new ChatListener(created), plugin);
        service = created;
        // 2026-10：頭上的名牌（稱號、角色名稱、等級職業、公會）
        final Nameplates plates = new Nameplates(plugin);
        plugin.getServer().getPluginManager().registerEvents(plates, plugin);
        created.nameplates = plates;
        plates.start();
        // 插件重載：在線的人重新記名字（TAB 與聊天的名字一起更新）
        if (plugin.players() != null) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.players().updateListName(player);
            }
        }
    }

    @Override
    public void disable() {
        if (service != null && service.nameplates != null) {
            service.nameplates.stop();
        }
        service = null;
    }

    public ChatService service() {
        return service;
    }

    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        final List<LiteralCommandNode<CommandSourceStack>> nodes = new ArrayList<>();
        for (final String label : WHISPER_LABELS) {
            nodes.add(Commands.literal(label)
                    .executes(context -> whisper(plugin, context, ""))
                    .then(Commands.argument("角色名稱與訊息", StringArgumentType.greedyString())
                            .suggests(targets(plugin))
                            .executes(context -> whisper(plugin, context, StringArgumentType.getString(context, "角色名稱與訊息"))))
                    .build());
        }
        for (final String label : REPLY_LABELS) {
            nodes.add(Commands.literal(label)
                    .executes(context -> reply(plugin, context, ""))
                    .then(Commands.argument("訊息", StringArgumentType.greedyString())
                            .executes(context -> reply(plugin, context, StringArgumentType.getString(context, "訊息"))))
                    .build());
        }
        nodes.add(Commands.literal("report")
                .executes(context -> usage(plugin, context, "<gray>用法：<white>/report 玩家 原因<gray>（例如：/report 某某 聊天辱罵）"))
                .then(Commands.argument("玩家與原因", StringArgumentType.greedyString())
                        .suggests(targets(plugin))
                        .executes(context -> report(plugin, context, StringArgumentType.getString(context, "玩家與原因"))))
                .build());
        nodes.add(Commands.literal("mute")
                .requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                .executes(context -> usage(plugin, context, "<gray>用法：<white>/mute 玩家 分鐘 [原因]<gray>（0 分鐘 = 永久）"))
                .then(Commands.argument("玩家 分鐘 原因", StringArgumentType.greedyString())
                        .suggests(targets(plugin))
                        .executes(context -> mute(plugin, context, StringArgumentType.getString(context, "玩家 分鐘 原因"))))
                .build());
        nodes.add(Commands.literal("unmute")
                .requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                .executes(context -> usage(plugin, context, "<gray>用法：<white>/unmute 玩家"))
                .then(Commands.argument("玩家", StringArgumentType.greedyString())
                        .suggests(targets(plugin))
                        .executes(context -> unmute(plugin, context, StringArgumentType.getString(context, "玩家"))))
                .build());
        return nodes;
    }

    private static int usage(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String text) {
        context.getSource().getSender().sendMessage(plugin.text().prefixed(text));
        return 1;
    }

    /** 線上的找角色名稱或玩家 ID；不在線的照玩家 ID 找（玩過的）。 */
    private static org.bukkit.OfflinePlayer resolve(final ExileCore plugin, final org.bukkit.command.CommandSender viewer, final String name) {
        final ChatService chat = plugin.chat();
        if (chat != null) {
            final NameMatch.Result<Player> found = chat.find(viewer, name);
            if (found != null && found.found() != null) {
                return found.found();
            }
        }
        final org.bukkit.OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
        return cached != null && (cached.hasPlayedBefore() || cached.isOnline()) ? cached : null;
    }

    private static int report(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String input) {
        final ChatService chat = plugin.chat();
        if (chat == null || !(context.getSource().getSender() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>只有玩家可以檢舉"));
            return 0;
        }
        final String[] split = NameMatch.splitTarget(input);
        if (split[0].isEmpty() || split[1].isBlank()) {
            player.sendMessage(plugin.text().prefixed("<gray>用法：<white>/report 玩家 原因<gray>（原因要寫，例如：聊天辱罵、外掛、詐騙）"));
            return 0;
        }
        final Player target = chat.findOrTell(player, split[0]);
        if (target != null) {
            player.sendMessage(plugin.text().prefixed(chat.moderation().report(player, target, split[1].strip())));
        }
        return 1;
    }

    private static int mute(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String input) {
        final ChatService chat = plugin.chat();
        final org.bukkit.command.CommandSender sender = context.getSource().getSender();
        if (chat == null) {
            return 0;
        }
        final String[] parts = input.strip().split("\\s+", 3);
        if (parts.length < 2) {
            sender.sendMessage(plugin.text().prefixed("<gray>用法：<white>/mute 玩家 分鐘 [原因]<gray>（0 分鐘 = 永久）"));
            return 0;
        }
        final long minutes;
        try {
            minutes = Long.parseLong(parts[1]);
        } catch (final NumberFormatException e) {
            sender.sendMessage(plugin.text().prefixed("<red>分鐘要寫數字（0 = 永久）"));
            return 0;
        }
        final org.bukkit.OfflinePlayer target = resolve(plugin, sender, parts[0]);
        if (target == null) {
            sender.sendMessage(plugin.text().prefixed("<red>找不到玩家 " + tw.exilecore.item.ItemRenderer.escape(parts[0])));
            return 0;
        }
        final String reason = parts.length > 2 ? parts[2] : "";
        chat.moderation().mute(target.getUniqueId(), Math.max(0, minutes), reason, sender.getName());
        final String length = minutes <= 0 ? "永久" : minutes + " 分鐘";
        sender.sendMessage(plugin.text().prefixed("<green>已禁言 " + tw.exilecore.item.ItemRenderer.escape(String.valueOf(target.getName()))
                + "（" + length + "）"));
        if (target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.text().prefixed("<red>你被禁言了（" + length + "）"
                    + (reason.isBlank() ? "" : "：" + tw.exilecore.item.ItemRenderer.escape(reason))));
        }
        plugin.getSLF4JLogger().info("[禁言] {} 禁言 {}（{}）：{}", sender.getName(), target.getName(), length, reason);
        return 1;
    }

    private static int unmute(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String input) {
        final ChatService chat = plugin.chat();
        final org.bukkit.command.CommandSender sender = context.getSource().getSender();
        if (chat == null) {
            return 0;
        }
        final org.bukkit.OfflinePlayer target = resolve(plugin, sender, input.strip());
        if (target == null) {
            sender.sendMessage(plugin.text().prefixed("<red>找不到玩家 " + tw.exilecore.item.ItemRenderer.escape(input.strip())));
            return 0;
        }
        final boolean had = chat.moderation().unmute(target.getUniqueId());
        sender.sendMessage(plugin.text().prefixed(had ? "<green>已解除 " + tw.exilecore.item.ItemRenderer.escape(String.valueOf(target.getName())) + " 的禁言"
                : "<gray>" + tw.exilecore.item.ItemRenderer.escape(String.valueOf(target.getName())) + " 沒有被禁言"));
        if (had && target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.text().prefixed("<green>你的禁言已經解除了"));
        }
        return 1;
    }

    private static int whisper(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String input) {
        final ChatService chat = plugin.chat();
        if (chat == null) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>聊天模組沒有啟用"));
            return 0;
        }
        chat.whisper(context.getSource().getSender(), input);
        return 1;
    }

    private static int reply(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String message) {
        final ChatService chat = plugin.chat();
        if (chat == null || !(context.getSource().getSender() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>只有玩家可以回覆私訊"));
            return 0;
        }
        chat.reply(player, message);
        return 1;
    }

    /** 名字補完（在線的角色名稱與玩家 ID，不含自己）：只在打第一個字（名字）的時候。私訊、/stats 名字、/ec stats 名字共用。 */
    public static SuggestionProvider<CommandSourceStack> targets(final ExileCore plugin) {
        return (context, builder) -> {
            final ChatService chat = plugin.chat();
            final String typed = builder.getRemaining();
            if (chat == null || typed.chars().anyMatch(Character::isWhitespace)) {
                return builder.buildFuture();
            }
            for (final String name : chat.suggest(typed, context.getSource().getSender())) {
                builder.suggest(name);
            }
            return builder.buildFuture();
        };
    }
}
