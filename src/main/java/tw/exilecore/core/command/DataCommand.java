package tw.exilecore.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;
import tw.exilecore.core.DataAudit;
import tw.exilecore.core.DataFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ec data}：資料檔的體檢。
 * <pre>
 * /ec data [status]        每個資料檔的狀態：跟預設一樣／改過／有 .new 還沒合併／缺了新版的哪些鍵
 * /ec data diff &lt;檔&gt;       一個檔案缺的鍵全部列出來（新版有、你的沒有）
 * /ec data accept &lt;檔|all&gt;  把 .new 換上來（你的那份改名成 .bak-時間留著），之後 /ec reload
 * </pre>
 * 改過的檔案升級時不會自動換新（{@link DataFiles}），新功能的段落就會缺，程式照預設值跑、不會報錯，
 * 所以這裡把缺的鍵列出來，讓你決定要自己補、還是直接接受新版再把改動搬回去。
 */
public final class DataCommand {

    /** 一行最多列幾個鍵（聊天視窗）。 */
    private static final int KEYS_PER_LINE = 6;

    private DataCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("data")
                .executes(context -> status(plugin, context.getSource().getSender()))
                .then(Commands.literal("status").executes(context -> status(plugin, context.getSource().getSender())))
                .then(Commands.literal("diff")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests(files(plugin, false))
                                .executes(context -> diff(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "file")))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests(files(plugin, true))
                                .executes(context -> accept(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "file")))));
    }

    /** 補全：index 列的檔案（accept 另外加 all）。 */
    private static SuggestionProvider<CommandSourceStack> files(final ExileCore plugin, final boolean withAll) {
        return (context, builder) -> {
            final String remaining = builder.getRemainingLowerCase();
            final List<String> options = new ArrayList<>();
            if (withAll) {
                options.add("all");
            }
            try {
                options.addAll(DataFiles.indexedPaths(plugin));
            } catch (final IOException ignored) {
                // jar 讀不到就只有 all
            }
            for (final String option : options) {
                if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };
    }

    private static List<DataAudit.FileStatus> audit(final ExileCore plugin) throws IOException {
        return DataAudit.audit(plugin.dataFolder(), DataFiles.indexedPaths(plugin), DataFiles.loader(plugin));
    }

    private static int status(final ExileCore plugin, final CommandSender sender) {
        final List<DataAudit.FileStatus> statuses;
        try {
            statuses = audit(plugin);
        } catch (final IOException e) {
            sender.sendMessage(plugin.text().prefixed("<red>讀不到資料檔：" + e.getMessage()));
            return Command.SINGLE_SUCCESS;
        }
        int same = 0;
        int attention = 0;
        for (final DataAudit.FileStatus status : statuses) {
            if (status.state() == DataAudit.State.SAME) {
                same++;
                continue;
            }
            final String colour = status.needsAttention() ? "<yellow>" : "<gray>";
            final StringBuilder line = new StringBuilder(colour).append(status.path()).append("</").append(colour.substring(1))
                    .append(" <gray>").append(status.state().displayName());
            if (status.problem() != null) {
                line.append("<red>；").append(status.problem()).append("</red>");
            }
            if (!status.missingKeys().isEmpty()) {
                line.append("；缺 <white>").append(status.missingKeys().size()).append("</white> 個新版的鍵：")
                        .append(String.join("、", status.missingKeys().subList(0, Math.min(3, status.missingKeys().size()))));
                if (status.missingKeys().size() > 3) {
                    line.append("…（<white>/ec data diff ").append(status.path()).append("</white> 看全部）");
                }
            }
            if (status.needsAttention()) {
                attention++;
            }
            sender.sendMessage(plugin.text().parse(line.toString()));
        }
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>資料檔 <white>%d</white> 個：<white>%d</white> 個跟預設一樣、<white>%d</white> 個改過，<%s>%d</%s> 個要注意（有 .new 或缺新版的鍵）",
                statuses.size(), same, statuses.size() - same, attention > 0 ? "yellow" : "green", attention, attention > 0 ? "yellow" : "green")));
        if (attention > 0) {
            sender.sendMessage(plugin.text().parse("<gray>缺的鍵自己補進去，或 <white>/ec data accept 檔</white> 直接換成新版（你的那份留 .bak，再把改動搬回去），然後 <white>/ec reload</white>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int diff(final ExileCore plugin, final CommandSender sender, final String file) {
        final DataAudit.FileStatus status = find(plugin, sender, file);
        if (status == null) {
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<white>" + status.path() + "</white> <gray>" + status.state().displayName()
                + (status.problem() == null ? "" : "<red>；" + status.problem()) + "</gray>"));
        if (status.state() == DataAudit.State.SAME) {
            sender.sendMessage(plugin.text().parse("<gray>跟 jar 裡的新版預設值一模一樣，不用做什麼"));
            return Command.SINGLE_SUCCESS;
        }
        if (status.state() == DataAudit.State.MISSING) {
            sender.sendMessage(plugin.text().parse("<gray>伺服器上沒有這個檔案，重開（或 /ec reload 前先重啟）會自動複製預設值"));
            return Command.SINGLE_SUCCESS;
        }
        if (status.missingKeys().isEmpty()) {
            sender.sendMessage(plugin.text().parse("<gray>新版有的鍵你的檔案都有（值可能不同，那是你改的）"
                    + (status.state() == DataAudit.State.PENDING ? "；旁邊的 .new 可以刪掉，或 <white>/ec data accept " + status.path() + "</white> 換成新版" : "")));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().parse("<gray>新版有、你的檔案沒有的鍵（<white>" + status.missingKeys().size() + "</white> 個；照預設值跑，不會報錯）："));
        for (int i = 0; i < status.missingKeys().size(); i += KEYS_PER_LINE) {
            final List<String> chunk = status.missingKeys().subList(i, Math.min(i + KEYS_PER_LINE, status.missingKeys().size()));
            sender.sendMessage(plugin.text().parse("<gray>  <white>" + String.join("</white>、<white>", chunk) + "</white>"));
        }
        if (status.state() == DataAudit.State.PENDING) {
            sender.sendMessage(plugin.text().parse("<gray>新版在 <white>" + status.path() + ".new</white>：把這些段落抄進你的檔案，或 <white>/ec data accept "
                    + status.path() + "</white> 整個換成新版（舊的留 .bak）"));
        } else {
            sender.sendMessage(plugin.text().parse("<gray>jar 裡的新版跟上次一樣，旁邊沒有 .new；可以從 jar 的 data/ 抄，或刪掉這個檔案重啟讓它重新複製"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int accept(final ExileCore plugin, final CommandSender sender, final String file) {
        final List<String> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(file.trim())) {
            try {
                for (final DataAudit.FileStatus status : audit(plugin)) {
                    if (status.state() == DataAudit.State.PENDING) {
                        targets.add(status.path());
                    }
                }
            } catch (final IOException e) {
                sender.sendMessage(plugin.text().prefixed("<red>讀不到資料檔：" + e.getMessage()));
                return Command.SINGLE_SUCCESS;
            }
            if (targets.isEmpty()) {
                sender.sendMessage(plugin.text().prefixed("<gray>沒有任何檔案有 .new 等著合併"));
                return Command.SINGLE_SUCCESS;
            }
        } else {
            final DataAudit.FileStatus status = find(plugin, sender, file);
            if (status == null) {
                return Command.SINGLE_SUCCESS;
            }
            if (status.state() != DataAudit.State.PENDING) {
                sender.sendMessage(plugin.text().prefixed("<red>" + status.path() + " 沒有 .new 可以接受</red> <gray>（" + status.state().displayName() + "）"));
                return Command.SINGLE_SUCCESS;
            }
            targets.add(status.path());
        }
        final String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        int done = 0;
        for (final String path : targets) {
            try {
                final Path backup = DataAudit.acceptNew(plugin.dataFolder(), path, timestamp);
                done++;
                sender.sendMessage(plugin.text().prefixed("<green>" + path + " 已換成新版</green> <gray>你的那份留在 <white>"
                        + plugin.dataFolder().relativize(backup) + "</white>"));
            } catch (final IOException e) {
                sender.sendMessage(plugin.text().prefixed("<red>" + path + " 換不了：" + e.getMessage()));
            }
        }
        if (done > 0) {
            sender.sendMessage(plugin.text().parse("<gray>把備份裡你改過的值搬回新檔案後 <white>/ec reload</white>；只是想先試新版就直接 /ec reload"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 找一個檔案的體檢結果；路徑一定要在 index 裡（不接受 ../ 之類的）。 */
    private static DataAudit.FileStatus find(final ExileCore plugin, final CommandSender sender, final String file) {
        final String wanted = normalise(file);
        try {
            for (final DataAudit.FileStatus status : audit(plugin)) {
                if (status.path().equals(wanted)) {
                    return status;
                }
            }
        } catch (final IOException e) {
            sender.sendMessage(plugin.text().prefixed("<red>讀不到資料檔：" + e.getMessage()));
            return null;
        }
        sender.sendMessage(plugin.text().prefixed("<red>沒有這個資料檔：" + file + "</red> <gray>要寫 data/ 開頭的路徑，例如 data/economy.yml（打 /ec data 看清單）"));
        return null;
    }

    /** 接受「economy.yml」「data/economy.yml」「data\economy.yml」「economy.yml.new」。 */
    static String normalise(final String file) {
        String path = file.trim().replace('\\', '/');
        if (path.endsWith(".new")) {
            path = path.substring(0, path.length() - ".new".length());
        }
        if (!path.startsWith("data/")) {
            path = "data/" + path;
        }
        return path;
    }
}
