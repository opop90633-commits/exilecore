package tw.exilecore.economy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /ec economy（管理員）：
 * <pre>
 * /ec economy [天數]                交易系統狀態；最近幾天（預設 7）每種通貨與金幣的產出、消耗、存量、參考價值的漲跌
 * /ec economy flow &lt;通貨|gold&gt; [天數] 一種東西從哪裡來、到哪裡去
 * /ec economy players [天數]        賺最多的帳號（金幣＋通貨折合金幣），跟中位數比（找機器人、複製）
 * /ec economy values                參考價值：設定值、現在的值、用到幾筆成交
 * /ec economy update                馬上做每天的工作（存量、重算參考價值）
 * </pre>
 */
public final class EconomyCommand {

    /** 超過中位數幾倍的帳號標紅。 */
    private static final double SUSPICIOUS = 5.0;

    private EconomyCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("economy")
                .executes(context -> summary(plugin, context.getSource().getSender(), 7))
                .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                        .executes(context -> summary(plugin, context.getSource().getSender(), IntegerArgumentType.getInteger(context, "days"))))
                .then(Commands.literal("flow")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    final String remaining = builder.getRemainingLowerCase();
                                    if (EconomyLedger.GOLD.startsWith(remaining)) {
                                        builder.suggest(EconomyLedger.GOLD);
                                    }
                                    for (final String id : plugin.economy().config().values().keySet()) {
                                        if (id.startsWith(remaining)) {
                                            builder.suggest(id);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> flow(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "kind"), 7))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                        .executes(context -> flow(plugin, context.getSource().getSender(),
                                                StringArgumentType.getString(context, "kind"), IntegerArgumentType.getInteger(context, "days"))))))
                .then(Commands.literal("players")
                        .executes(context -> players(plugin, context.getSource().getSender(), 7))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                .executes(context -> players(plugin, context.getSource().getSender(), IntegerArgumentType.getInteger(context, "days")))))
                .then(Commands.literal("values").executes(context -> values(plugin, context.getSource().getSender())))
                .then(Commands.literal("update").executes(context -> update(plugin, context.getSource().getSender())));
    }

    // ---- /ec economy [天數] ----

    private static int summary(final ExileCore plugin, final CommandSender sender, final int days) {
        sender.sendMessage(plugin.text().prefixed(plugin.characters().economyFrozen()
                ? "<red>交易系統暫停中：有操作寫入資料庫失敗，每 15 秒重試（看主控台）"
                : "<green>交易系統正常"));
        if (plugin.market() != null) {
            sender.sendMessage(plugin.text().parse("<gray>市集：上架 <white>" + plugin.market().listingCount() + "</white> 件、收入待領 <white>"
                    + plugin.market().proceedCount() + "</white> 筆、掛單 <white>" + plugin.market().orderCount() + "</white> 張"));
        }
        final EconomyConfig config = plugin.economy().config();
        final List<EconomyReportJson.CurrencyMeta> currencies = plugin.economy().reports().currencyMeta();
        plugin.economy().reports().totals(days).whenComplete((totals, error) -> sync(plugin, () -> {
            if (error != null) {
                sender.sendMessage(plugin.text().prefixed("<red>查詢失敗：" + error.getMessage()));
                return;
            }
            sender.sendMessage(plugin.text().prefixed("<white>經濟（最近 " + days + " 天，" + shortDay(totals.fromDay()) + " 到 "
                    + shortDay(totals.toDay()) + "）"));
            final long goldIn = totals.produced(EconomyLedger.GOLD);
            final long goldOut = totals.consumed(EconomyLedger.GOLD);
            sender.sendMessage(plugin.text().parse("<#f5b942>金幣<gray>：產出 <white>" + number(goldIn) + "</white>　消耗 <white>"
                    + number(goldOut) + "</white>" + ratio(goldOut, goldIn) + "　全服 <white>"
                    + (totals.stock().containsKey(EconomyLedger.GOLD) ? number(totals.stock().get(EconomyLedger.GOLD)) : "—")));
            long valueIn = 0;
            long valueOut = 0;
            final List<String[]> rows = new ArrayList<>();
            final List<Long> order = new ArrayList<>();
            for (final EconomyReportJson.CurrencyMeta meta : currencies) {
                final long in = totals.produced(meta.id());
                final long out = totals.consumed(meta.id());
                final Long stock = totals.stock().get(meta.id());
                final long value = config.value(meta.id());
                valueIn += in * value;
                valueOut += out * value;
                if (in == 0 && out == 0 && (stock == null || stock == 0)) {
                    continue;
                }
                final Long then = totals.valuesThen().get(meta.id());
                String change = "";
                if (then != null && then > 0 && then != value) {
                    final double percent = (value - then) * 100.0 / then;
                    change = String.format(Locale.ROOT, "（%s%.0f%%）", percent > 0 ? "+" : "", percent);
                }
                rows.add(new String[] {meta.name(), number(in), number(out), ratio(out, in), stock == null ? "—" : number(stock),
                        number(value) + change, Boolean.toString(in > 0 && out * 2 < in)});
                order.add(in * value);
            }
            sender.sendMessage(plugin.text().parse("<gray>通貨（折合金幣）：產出 <white>" + number(valueIn) + "</white>　消耗 <white>"
                    + number(valueOut) + "</white>" + ratio(valueOut, valueIn)));
            final List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                indexes.add(i);
            }
            indexes.sort(Comparator.comparingLong((Integer i) -> order.get(i)).reversed());
            if (rows.isEmpty()) {
                sender.sendMessage(plugin.text().parse("<dark_gray>這幾天還沒有通貨的紀錄"));
            } else {
                sender.sendMessage(plugin.text().parse("<dark_gray>通貨｜產出｜消耗（佔產出）｜全服存量｜參考價值（這幾天的漲跌）；黃色＝消耗不到產出的一半"));
            }
            for (final int i : indexes) {
                final String[] row = rows.get(i);
                final String color = Boolean.parseBoolean(row[6]) ? "<yellow>" : "<white>";
                sender.sendMessage(plugin.text().parse(" " + color + ItemRenderer.escape(row[0]) + "<gray>｜" + row[1] + "｜" + row[2] + row[3]
                        + "｜" + row[4] + "｜" + row[5]));
            }
            if (totals.stockDay() != null) {
                sender.sendMessage(plugin.text().parse("<dark_gray>存量是 " + totals.stockDay()
                        + " 算的（通貨袋＋倉庫通貨頁＋市集待領＋交易所掛單；背包格子裡的不算）"));
            }
            sender.sendMessage(plugin.text().parse("<gray>更多：<white>/ec economy flow 通貨|gold</white>（來源）、<white>players</white>（誰賺最多）、"
                    + "<white>values</white>（參考價值）、<white>update</white>（馬上重算）"));
            if (plugin.web() != null && plugin.web().available()) {
                sender.sendMessage(plugin.text().parse("<gray>網頁：<white>" + plugin.web().publicUrl() + "/economy"));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    // ---- /ec economy flow ----

    private static int flow(final ExileCore plugin, final CommandSender sender, final String kind, final int days) {
        final boolean gold = EconomyLedger.GOLD.equals(kind);
        final String name;
        if (gold) {
            name = "金幣";
        } else {
            final var type = plugin.currencies().repository().get(kind);
            if (type == null) {
                sender.sendMessage(plugin.text().prefixed("<red>沒有這種通貨：" + ItemRenderer.escape(kind) + "（gold＝金幣）"));
                return Command.SINGLE_SUCCESS;
            }
            name = type.name();
        }
        plugin.economy().reports().totals(days).whenComplete((totals, error) -> sync(plugin, () -> {
            if (error != null) {
                sender.sendMessage(plugin.text().prefixed("<red>查詢失敗：" + error.getMessage()));
                return;
            }
            final Map<String, Long> sources = totals.flows().getOrDefault(kind, Map.of());
            sender.sendMessage(plugin.text().prefixed("<white>" + ItemRenderer.escape(name) + "（最近 " + days + " 天）"));
            final List<String> in = new ArrayList<>();
            final List<String> out = new ArrayList<>();
            sources.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.comparingLong(Math::abs)).reversed())
                    .forEach(entry -> {
                        final String text = EconomyLedger.sourceName(entry.getKey()) + " <white>" + number(Math.abs(entry.getValue())) + "</white>";
                        if (entry.getValue() > 0) {
                            in.add(text);
                        } else if (entry.getValue() < 0) {
                            out.add(text);
                        }
                    });
            sender.sendMessage(plugin.text().parse("<gray>產出：" + (in.isEmpty() ? "<dark_gray>沒有" : String.join("<gray>、", in))));
            sender.sendMessage(plugin.text().parse("<gray>消耗：" + (out.isEmpty() ? "<dark_gray>沒有" : String.join("<gray>、", out))));
            final long net = totals.produced(kind) - totals.consumed(kind);
            final Long stock = totals.stock().get(kind);
            sender.sendMessage(plugin.text().parse("<gray>淨增加 <white>" + (net > 0 ? "+" : "") + number(net) + "</white>"
                    + (stock == null ? "" : "　全服存量 <white>" + number(stock) + "</white><dark_gray>（" + totals.stockDay() + "）")));
        }));
        return Command.SINGLE_SUCCESS;
    }

    // ---- /ec economy players ----

    private static int players(final ExileCore plugin, final CommandSender sender, final int days) {
        plugin.economy().reports().earners(days).whenComplete((earners, error) -> sync(plugin, () -> {
            if (error != null) {
                sender.sendMessage(plugin.text().prefixed("<red>查詢失敗：" + error.getMessage()));
                return;
            }
            if (earners.isEmpty()) {
                sender.sendMessage(plugin.text().prefixed("<gray>最近 " + days + " 天沒有殺怪的紀錄"));
                return;
            }
            final List<Long> totals = new ArrayList<>();
            for (final EconomyRepository.Earner earner : earners) {
                totals.add(earner.total());
            }
            final long median = median(totals);
            sender.sendMessage(plugin.text().prefixed("<white>最近 " + days + " 天賺最多的帳號<gray>（金幣＋通貨折合金幣；中位數 <white>"
                    + number(median) + "</white>，" + earners.size() + " 個帳號）"));
            for (int i = 0; i < Math.min(10, earners.size()); i++) {
                final EconomyRepository.Earner earner = earners.get(i);
                final double times = median <= 0 ? 0 : (double) earner.total() / median;
                final String color = times >= SUSPICIOUS ? "<red>" : "<white>";
                sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT,
                        " <gray>%d. %s%s <gray>%s（中位數的 %.1f 倍）　殺怪 %s　金幣 %s　通貨 %s　%d 天",
                        i + 1, color, ItemRenderer.escape(earner.name()), number(earner.total()), times, number(earner.kills()),
                        number(earner.gold()), number(earner.currencyValue()), earner.days())));
            }
            sender.sendMessage(plugin.text().parse("<dark_gray>紅色＝超過中位數 " + (int) SUSPICIOUS
                    + " 倍；用 /ec trace player 名字 看他的交易。人少的時候中位數不準，只當參考"));
        }));
        return Command.SINGLE_SUCCESS;
    }

    // ---- /ec economy values ----

    private static int values(final ExileCore plugin, final CommandSender sender) {
        final EconomyConfig config = plugin.economy().config();
        final ReferenceValues.Settings settings = config.reference();
        if (!settings.enabled()) {
            sender.sendMessage(plugin.text().prefixed("<gray>參考價值沒有跟著市價走（economy.yml 的 reference.follow_market: false），一律用 values 的設定值"));
            return Command.SINGLE_SUCCESS;
        }
        final var anchor = plugin.currencies().repository().get(settings.anchor());
        sender.sendMessage(plugin.text().prefixed("<white>參考價值<gray>（跟著交易所最近 " + settings.days() + " 天的成交價走；基準："
                + ItemRenderer.escape(anchor == null ? settings.anchor() : anchor.name()) + " = " + number(config.configuredValue(settings.anchor()))
                + " 金幣；少於 " + settings.minPairs() + " 組不同的買賣雙方不動（同兩個帳號之間成交幾次都只算一組），一天最多變 "
                + Math.round(settings.maxStep() * 100) + "%）"));
        plugin.database().async(connection -> plugin.economy().repository().latestValues(connection)).whenComplete((snapshot, error) ->
                sync(plugin, () -> {
                    for (final EconomyReportJson.CurrencyMeta meta : plugin.economy().reports().currencyMeta()) {
                        final long configured = meta.configured();
                        final long now = config.value(meta.id());
                        final int pairs = error == null ? snapshot.pairs().getOrDefault(meta.id(), 0) : 0;
                        final String change = configured == now ? "" : String.format(Locale.ROOT, "（%s%.0f%%）",
                                now > configured ? "+" : "", (now - configured) * 100.0 / configured);
                        sender.sendMessage(plugin.text().parse(" <white>" + ItemRenderer.escape(meta.name()) + "<gray>：設定 " + number(configured)
                                + " → 現在 <white>" + number(now) + "</white>" + change + "<dark_gray>，" + pairs + " 組買賣雙方"));
                    }
                    sender.sendMessage(plugin.text().parse("<dark_gray>最近一次計算：" + (error != null || snapshot.day() == null ? "還沒有" : snapshot.day())
                            + "；每天換日後自動重算，<white>/ec economy update</white> 馬上重算"));
                }));
        return Command.SINGLE_SUCCESS;
    }

    // ---- /ec economy update ----

    private static int update(final ExileCore plugin, final CommandSender sender) {
        if (plugin.economy().reports().running()) {
            sender.sendMessage(plugin.text().prefixed("<gray>正在算了，等一下再看（/ec economy values）"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>計算存量與參考價值中…（參考價值從昨天的值算起，同一天重算幾次都只走一步）"));
        plugin.economy().reports().runDaily().whenComplete((daily, error) -> sync(plugin, () -> {
            if (error != null) {
                sender.sendMessage(plugin.text().prefixed("<red>失敗：" + error.getMessage()));
                return;
            }
            if (daily == null) {
                sender.sendMessage(plugin.text().prefixed("<red>還原備份中，什麼都沒寫（重開伺服器後再算）"));
                return;
            }
            int changed = 0;
            for (final Map.Entry<String, Long> entry : daily.values().values().entrySet()) {
                if (entry.getValue() != plugin.economy().config().configuredValue(entry.getKey())) {
                    changed++;
                }
            }
            sender.sendMessage(plugin.text().prefixed("<green>算好了<gray>：" + daily.stock().size() + " 種東西的存量；"
                    + changed + " 種通貨的參考價值跟設定值不一樣（/ec economy values 看）"));
        }));
        return Command.SINGLE_SUCCESS;
    }

    // ---- 小工具 ----

    private static long median(final List<Long> values) {
        final List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        if (sorted.isEmpty()) {
            return 0;
        }
        final int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static String ratio(final long out, final long in) {
        return in <= 0 ? "" : String.format(Locale.ROOT, "（%.0f%%）", out * 100.0 / in);
    }

    private static String number(final long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String shortDay(final String day) {
        return day != null && day.length() == 10 ? day.substring(5) : String.valueOf(day);
    }

    private static void sync(final ExileCore plugin, final Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
