package tw.exilecore.character;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.List;
import java.util.Optional;

/**
 * 玩家指令（不需要權限）：
 * <pre>
 * /char                  角色選擇視窗
 * /char panel            角色面板（屬性、飾品）
 * /char select &lt;格&gt;      直接切換到某一格的角色
 * /char rename &lt;名稱&gt;    目前角色改名
 * /char profile [on|off] 網頁角色頁（像 poe.ninja）：連結、公開或不公開（預設公開）
 * /char witch            原本的混沌術士：選一次要當元素法師還是留在混沌術士
 * /panel                 角色面板
 * /tree                  天賦樹
 * /tree find &lt;關鍵字&gt;    搜尋天賦並標出
 * /tree web              網頁天賦規劃器的連結（帶著現在的配點）
 * /tree import [代碼]    匯入規劃器的代碼（先預覽要配哪些、退哪些）
 * /stats                 文字版屬性
 * /stats &lt;名字&gt;          看在線的別人的屬性（角色名稱或玩家 ID；沒有金幣）
 * </pre>
 */
public final class CharacterCommand {

    private CharacterCommand() {
    }

    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        final LiteralCommandNode<CommandSourceStack> character = Commands.literal("char")
                .executes(context -> run(plugin, context, player -> plugin.characterMenus().openSelect(player)))
                .then(Commands.literal("panel")
                        .executes(context -> run(plugin, context, player -> plugin.characterMenus().openPanel(player))))
                .then(Commands.literal("select")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                .executes(context -> run(plugin, context, player -> select(plugin, player, IntegerArgumentType.getInteger(context, "slot"))))))
                .then(Commands.literal("profile")
                        .executes(context -> run(plugin, context, player -> profile(plugin, player, null)))
                        .then(Commands.literal("on").executes(context -> run(plugin, context, player -> profile(plugin, player, true))))
                        .then(Commands.literal("off").executes(context -> run(plugin, context, player -> profile(plugin, player, false)))))
                .then(Commands.literal("witch")
                        .executes(context -> run(plugin, context, player -> plugin.characters().offerWitchSplit(player))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, player -> plugin.characters().rename(player,
                                        StringArgumentType.getString(context, "name"),
                                        error -> player.sendMessage(plugin.text().prefixed("<red>" + error)))))))
                .build();
        final LiteralCommandNode<CommandSourceStack> panel = Commands.literal("panel")
                .executes(context -> run(plugin, context, player -> plugin.characterMenus().openPanel(player)))
                .build();
        final LiteralCommandNode<CommandSourceStack> tree = Commands.literal("tree")
                .executes(context -> run(plugin, context, player -> plugin.characterMenus().openTree(player)))
                .then(Commands.literal("find")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, player -> plugin.characterMenus().openTreeSearch(player,
                                        StringArgumentType.getString(context, "text"))))))
                .then(Commands.literal("web")
                        .executes(context -> run(plugin, context, player -> withTree(plugin, player,
                                passives -> tw.exilecore.passive.TreeActions.sendWebLink(plugin, player, passives)))))
                .then(Commands.literal("import")
                        .executes(context -> run(plugin, context, player -> withTree(plugin, player,
                                passives -> tw.exilecore.passive.TreeActions.promptImport(plugin, player, passives,
                                        () -> plugin.characterMenus().openTree(player)))))
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context, player -> withTree(plugin, player,
                                        passives -> tw.exilecore.passive.TreeActions.preview(plugin, player, passives,
                                                StringArgumentType.getString(context, "code"), () -> plugin.characterMenus().openTree(player)))))))
                .build();
        final LiteralCommandNode<CommandSourceStack> stats = Commands.literal("stats")
                .executes(context -> run(plugin, context, player -> showStats(plugin, player, null)))
                .then(Commands.argument("角色名稱或玩家", StringArgumentType.greedyString())
                        .suggests(tw.exilecore.chat.ChatModule.targets(plugin))
                        .executes(context -> {
                            showStats(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "角色名稱或玩家"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
        return List.of(character, panel, tree, stats);
    }

    /**
     * 把屬性表印給 viewer：query 是 null 就看自己（viewer 要是玩家），否則找那個在線的角色（角色名稱或玩家 ID，像私訊一樣）。
     * 看別人的沒有金幣、沒有開天賦樹那排按鈕；後台也可以用（/ec stats 名字）。
     */
    public static void showStats(final ExileCore plugin, final CommandSender viewer, final String query) {
        final Player target;
        if (query == null || query.isBlank()) {
            if (!(viewer instanceof Player self)) {
                viewer.sendMessage(plugin.text().prefixed("<red>後台請打名字：/ec stats 角色名稱或玩家"));
                return;
            }
            target = self;
        } else if (plugin.chat() != null) {
            target = plugin.chat().findOrTell(viewer, query.trim());
        } else {
            target = plugin.getServer().getPlayerExact(query.trim());
            if (target == null) {
                viewer.sendMessage(plugin.text().prefixed("<red>找不到在線的玩家「" + tw.exilecore.item.ItemRenderer.escape(query.trim()) + "」"));
            }
        }
        if (target == null) {
            return;
        }
        final boolean self = viewer instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        for (final String line : StatsReport.all(plugin, target, self)) {
            viewer.sendMessage(plugin.text().parse(line));
        }
    }

    /** 網頁角色頁（2026-10）：給連結；on／off 設定公不公開。 */
    private static void profile(final ExileCore plugin, final Player player, final Boolean visible) {
        final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
        if (profiles == null || !plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed(profiles == null ? "<red>網頁沒有開（config.yml 的 web.enabled）" : "<red>先建立角色：/char"));
            return;
        }
        final boolean open = visible == null ? profiles.isPublic(player) : profiles.setPublic(player, visible);
        if (visible == null) {
            profiles.snapshot(player);
        }
        final String name = plugin.players().get(player).character().name();
        final String url = profiles.url(name);
        if (!open) {
            player.sendMessage(plugin.text().prefixed("<gray>網頁角色頁：<red>不公開</red>（別人在網頁上看不到這個角色）<dark_gray>　/char profile on 打開"));
            return;
        }
        player.sendMessage(plugin.text().prefixed("<gray>網頁角色頁：<green>公開</green> "
                + (url == null ? "" : "<click:open_url:'" + url.replace("'", "%27") + "'><hover:show_text:'<gray>" + tw.exilecore.item.ItemRenderer.escape(url)
                + "'><#77ccff><u>[打開我的角色頁]</u></hover></click>") + " <dark_gray>　/char profile off 不公開"));
    }

    private static void withTree(final ExileCore plugin, final Player player,
                                 final java.util.function.Consumer<tw.exilecore.passive.PassiveTree> action) {
        final tw.exilecore.passive.PassiveTree tree = plugin.passives().tree();
        if (tree == null) {
            player.sendMessage(plugin.text().prefixed("<red>天賦樹沒有載入（看主控台的錯誤）"));
            return;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色：/char"));
            return;
        }
        action.accept(tree);
    }

    private static void select(final ExileCore plugin, final Player player, final int slot) {
        final Optional<CharacterRecord> record = plugin.characters().rosterEntry(player, slot);
        if (record.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>第 " + slot + " 格沒有角色"));
            return;
        }
        plugin.characters().switchTo(player, record.get().id(), error -> player.sendMessage(plugin.text().prefixed("<red>" + error)));
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final java.util.function.Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (executor instanceof Player player) {
            action.accept(player);
        } else {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
        }
        return Command.SINGLE_SUCCESS;
    }
}
