package tw.exilecore.ladder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/** ladder 模組：排行榜（/ladder 與網頁 /ladder）。要在角色模組之後啟用。 */
public final class LadderModule implements Module {

    /** 聊天欄印前幾名。 */
    static final int CHAT_COUNT = 10;

    private final ExileCore plugin;
    private LadderService service;
    private BukkitTask task;

    public LadderModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "ladder";
    }

    @Override
    public void enable() {
        service = new LadderService(plugin);
        service.refresh();
        task = plugin.repeat("ladder", service::tick, 40, 40); // 2026-10-18：每 2 秒更新網頁用的在線快照；重讀仍是到了一分鐘才真的讀
    }

    @Override
    public void reload() {
        if (service != null) {
            service.refresh();
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 排行榜；模組沒啟用時 null。 */
    public LadderService service() {
        return service;
    }

    /** /ladder [level|maps|deathless|gold|guild] */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("ladder")
                .executes(context -> show(plugin, context.getSource().getSender(), null))
                .then(Commands.argument("board", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (final String option : List.of("level", "maps", "deathless", "gold", "guild")) {
                                if (option.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(option);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> show(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "board"))))
                .build();
    }

    private static int show(final ExileCore plugin, final CommandSender sender, final String board) {
        final LadderService service = plugin.ladder();
        if (service == null) {
            sender.sendMessage(plugin.text().prefixed("<red>排行榜模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final String wanted = board == null ? "" : board.toLowerCase(java.util.Locale.ROOT);
        if (wanted.equals("gold") || wanted.equals("money")) {
            // 2026-10：金錢榜（以前只有網頁有）
            for (final String line : service.goldLines(CHAT_COUNT, sender instanceof Player player ? player : null)) {
                sender.sendMessage(plugin.text().parse(line));
            }
            return Command.SINGLE_SUCCESS;
        }
        if (wanted.equals("guild") || wanted.equals("guilds")) {
            if (plugin.guilds() == null) {
                sender.sendMessage(plugin.text().prefixed("<red>公會模組沒有啟用"));
            } else {
                plugin.guilds().top(sender);
            }
            return Command.SINGLE_SUCCESS;
        }
        for (final String line : service.lines(Ladder.Board.parse(board), CHAT_COUNT, sender instanceof Player player ? player : null)) {
            sender.sendMessage(plugin.text().parse(line));
        }
        return Command.SINGLE_SUCCESS;
    }
}
