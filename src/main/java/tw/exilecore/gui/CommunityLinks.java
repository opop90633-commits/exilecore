package tw.exilecore.gui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.List;

/**
 * Discord、贊助商城、新手教學的連結（2026-10）：選單的按鈕、/discord、/store、/tutorial。網址寫在 config.yml 的 links（改完 /ec reload）；
 * 沒填的按鈕寫「即將開放」。點了在聊天欄送一行可以點的連結（Minecraft 會先問要不要打開網頁）。
 */
public final class CommunityLinks {

    public enum Link {
        DISCORD("discord", "Discord 社群", "#7289da", "公告、組隊、回報問題、跟大家聊天"),
        STORE("store", "贊助商城", "#f5b942", "支持《星骸》的營運"),
        /** 2026-10：圖文的新手教學（網誌）。 */
        TUTORIAL("tutorial", "新手教學", "#7fe07f", "圖文教學：從建角色、配天賦、掛技能到打地圖");

        final String key;
        final String title;
        final String color;
        final String about;

        Link(final String key, final String title, final String color, final String about) {
            this.key = key;
            this.title = title;
            this.color = color;
            this.about = about;
        }
    }

    private CommunityLinks() {
    }

    /** config.yml 的 links.discord／links.store／links.tutorial（沒填是空字串）。 */
    public static String url(final ExileCore plugin, final Link link) {
        final String path = "links." + link.key;
        final String url = plugin.getConfig().getString(path, "");
        final String trimmed = url == null ? "" : url.trim();
        // 伺服器上舊的 config.yml 不會被新版 jar 覆蓋：沒填或還是退役的舊網址時，改用 jar 內建的預設值
        if (trimmed.isEmpty() || retired(trimmed)) {
            final org.bukkit.configuration.Configuration defaults = plugin.getConfig().getDefaults();
            final String fallback = defaults == null ? null : defaults.getString(path);
            if (fallback != null && !fallback.isBlank()) {
                return fallback.trim();
            }
        }
        return trimmed;
    }

    /**
     * 換掉的舊網址（2026-10：新手教學改成新的 Google Drive 檔案；2026-10-20：Discord 換新的邀請連結；
     * 2026-10-25：贊助商城從自己的 /shop 頁改成 Tebex，舊的 …/shop 當退役）。
     */
    private static final List<String> RETIRED = List.of("1xOcljxPEcDzPjPnLUeOWBXpvxUdeeMCh", "S6FZuGFR93", "/shop");

    static boolean retired(final String url) {
        for (final String old : RETIRED) {
            if (url.contains(old)) {
                return true;
            }
        }
        return false;
    }

    /** 只收 http(s) 的網址（設定檔打錯字的話當成沒填）。 */
    private static boolean valid(final String url) {
        return url.startsWith("https://") || url.startsWith("http://");
    }

    /** 選單上的按鈕。 */
    public static ItemStack button(final ExileCore plugin, final Link link) {
        final String url = url(plugin, link);
        final Material material = switch (link) {
            case DISCORD -> Material.ECHO_SHARD;
            case TUTORIAL -> Material.WRITTEN_BOOK;
            default -> Material.GOLD_NUGGET;
        };
        if (!valid(url)) {
            return Menu.icon(material, "<" + link.color + ">" + link.title, "<gray>" + link.about, "", "<dark_gray>即將開放");
        }
        return Menu.icon(material, "<" + link.color + ">" + link.title, "<gray>" + link.about, "<dark_gray>" + ItemRenderer.escape(url), "",
                "<yellow>左鍵：在聊天欄送出連結<dark_gray>（/" + link.key + "）");
    }

    /** 在聊天欄送一行可以點的連結。 */
    public static void send(final ExileCore plugin, final CommandSender sender, final Link link) {
        final String url = url(plugin, link);
        if (!valid(url)) {
            sender.sendMessage(plugin.text().prefixed("<gray>" + link.title + "即將開放，敬請期待"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<" + link.color + ">" + link.title + "<gray>：")
                .append(plugin.text().parse("<" + link.color + "><u>" + ItemRenderer.escape(url) + "</u>")
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(plugin.text().parse("<yellow>點一下打開")))));
    }

    /** /discord、/store、/tutorial。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(command(plugin, Link.DISCORD), command(plugin, Link.STORE), command(plugin, Link.TUTORIAL));
    }

    private static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin, final Link link) {
        final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(link.key)
                .executes(context -> {
                    send(plugin, context.getSource().getSender(), link);
                    return Command.SINGLE_SUCCESS;
                });
        if (link == Link.DISCORD) {
            // 2026-10：/discord link 代碼、/discord unlink、/discord status、（管理員）/discord panel 頻道ID
            root.then(Commands.literal("link").then(Commands.argument("code", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(context -> discord(plugin, context.getSource().getSender(),
                                    (bridge, player) -> bridge.link().link(player, com.mojang.brigadier.arguments.StringArgumentType.getString(context, "code"))))))
                    .then(Commands.literal("unlink").executes(context -> discord(plugin, context.getSource().getSender(),
                            (bridge, player) -> bridge.link().unlink(player))))
                    .then(Commands.literal("status").executes(context -> discord(plugin, context.getSource().getSender(),
                            (bridge, player) -> bridge.link().status(player))))
                    .then(Commands.literal("panel").requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                            .then(Commands.argument("channel", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(context -> {
                                final org.bukkit.command.CommandSender sender = context.getSource().getSender();
                                final tw.exilecore.discord.DiscordBridge bridge = plugin.discord();
                                if (bridge == null || !bridge.link().enabledForCommands()) {
                                    sender.sendMessage(plugin.text().prefixed("<red>Discord 連結沒有開（要有 bot-token 與 guild-id）"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                bridge.link().postPanel(com.mojang.brigadier.arguments.StringArgumentType.getString(context, "channel"),
                                        text -> plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.text().prefixed(text))));
                                return Command.SINGLE_SUCCESS;
                            })));
        }
        return root.build();
    }

    private static int discord(final ExileCore plugin, final org.bukkit.command.CommandSender sender,
                               final java.util.function.BiConsumer<tw.exilecore.discord.DiscordBridge, org.bukkit.entity.Player> action) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final tw.exilecore.discord.DiscordBridge bridge = plugin.discord();
        if (bridge == null || !bridge.link().enabledForCommands()) {
            player.sendMessage(plugin.text().prefixed("<red>Discord 連結現在沒有開放"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(bridge, player);
        return Command.SINGLE_SUCCESS;
    }
}
