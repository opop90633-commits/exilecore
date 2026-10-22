package tw.exilecore.ops;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.core.Perf;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.module.Module;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ops 模組（封測用的維運工具）：
 * <ul>
 *   <li>/ec perf：TPS、每個定時工作與戰鬥事件花多少時間、資料庫排隊、各世界的實體數。</li>
 *   <li>/ec backup [list|verify [檔名]|restore 檔名]：備份、檢查、還原（每天自動備份一次，見 config.yml 的 backup）。</li>
 *   <li>/ec trace 物品UUID｜player 名字：一件裝備的交易與通貨紀錄、一個玩家最近的交易。</li>
 *   <li>/ec dupes：找同一個 UUID 出現在兩個地方的裝備（複製）。</li>
 *   <li>/ec wipe [all|confirm 碼|cancel]：清檔（只能在主控台，見 {@link WipeService}）。</li>
 *   <li>/ec transfer A B|confirm 碼|list|cancel：轉移帳號（A 覆蓋到 B，只能在主控台，下次開機時做；見 {@link AccountTransfer}）。</li>
 *   <li>/ec economy：交易系統的狀態與經濟報表（見 {@link tw.exilecore.economy.EconomyCommand}）。</li>
 * </ul>
 */
public final class OpsModule implements Module, Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ExileCore plugin;
    private BackupService backups;
    private WipeService wipe;
    private AccountTransfer transfer;
    private BukkitTask rollTask;
    private BukkitTask backupTask;

    public OpsModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "ops";
    }

    @Override
    public void enable() {
        backups = new BackupService(plugin);
        wipe = new WipeService(plugin);
        transfer = new AccountTransfer(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        rollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, plugin.perf()::roll, 20, 20);
        backupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, backups::tick, 20L * 60, 20L * 60);
    }

    @Override
    public void reload() {
        if (backups != null) {
            backups.reload();
        }
    }

    @Override
    public void disable() {
        if (rollTask != null) {
            rollTask.cancel();
            rollTask = null;
        }
        if (backupTask != null) {
            backupTask.cancel();
            backupTask = null;
        }
    }

    public BackupService backups() {
        return backups;
    }

    public WipeService wipe() {
        return wipe;
    }

    /** 轉移帳號（2026-10-17）。 */
    public AccountTransfer transfer() {
        return transfer;
    }

    /** 還原中（或還原完了等重開）：不讓人登入。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        if (backups != null && backups.restoring()) {
            final Player player = event.getPlayer();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.kick(plugin.text().parse(backups.lockMessage())));
        }
    }

    // ---- 指令（掛在 /ec 底下） ----

    /** 壓力測試（刷出來的怪跨指令重新註冊也記得）。 */
    private static StressTest stress;

    public static List<LiteralArgumentBuilder<CommandSourceStack>> nodes(final ExileCore plugin) {
        final List<LiteralArgumentBuilder<CommandSourceStack>> nodes = new ArrayList<>();
        nodes.add(Commands.literal("perf").executes(context -> perf(plugin, context.getSource().getSender()))
                .then(Commands.literal("displays").executes(context -> displays(plugin, context.getSource().getSender()))));
        if (stress == null || stress.plugin() != plugin) {
            stress = new StressTest(plugin);
        }
        nodes.add(stress.node());
        nodes.add(tw.exilecore.economy.EconomyCommand.node(plugin));
        nodes.add(Commands.literal("dupes").executes(context -> {
            new DupeScanner(plugin, context.getSource().getSender()).start();
            return Command.SINGLE_SUCCESS;
        }));
        nodes.add(Commands.literal("trace")
                .then(Commands.literal("player")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    // 2026-10：按 TAB 補在線玩家的 ID（離線的照樣可以自己打）
                                    final String remaining = builder.getRemainingLowerCase();
                                    for (final org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
                                        if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                                            builder.suggest(online.getName());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> tracePlayer(plugin, context.getSource().getSender(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(context -> traceItem(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "uuid")))));
        nodes.add(Commands.literal("backup")
                .executes(context -> withBackups(plugin, context.getSource().getSender(), service -> service.run(context.getSource().getSender())))
                .then(Commands.literal("list").executes(context -> withBackups(plugin, context.getSource().getSender(),
                        service -> listBackups(plugin, service, context.getSource().getSender()))))
                .then(Commands.literal("verify")
                        .executes(context -> withBackups(plugin, context.getSource().getSender(), service -> {
                            // 沒寫檔名：檢查最新的資料庫備份
                            final Path latest = service.list().stream().filter(path -> path.getFileName().toString().endsWith(".json.gz"))
                                    .findFirst().orElse(null);
                            if (latest == null) {
                                context.getSource().getSender().sendMessage(plugin.text().prefixed("<gray>還沒有資料庫備份（/ec backup 馬上備份一次）"));
                            } else {
                                service.verify(context.getSource().getSender(), latest.getFileName().toString());
                            }
                        }))
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests((context, builder) -> suggestBackups(plugin, builder, false))
                                .executes(context -> withBackups(plugin, context.getSource().getSender(),
                                        service -> service.verify(context.getSource().getSender(), StringArgumentType.getString(context, "file"))))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests((context, builder) -> suggestBackups(plugin, builder, true))
                                .executes(context -> withBackups(plugin, context.getSource().getSender(),
                                        service -> service.restore(context.getSource().getSender(), StringArgumentType.getString(context, "file")))))));
        // 2026-10：清檔（只能在主控台；先預覽拿確認碼）
        nodes.add(Commands.literal("wipe")
                .executes(context -> withWipe(plugin, context.getSource().getSender(),
                        wipe -> wipe.preview(context.getSource().getSender(), WipeService.Mode.PROGRESS)))
                .then(Commands.literal("all").executes(context -> withWipe(plugin, context.getSource().getSender(),
                        wipe -> wipe.preview(context.getSource().getSender(), WipeService.Mode.ALL))))
                .then(Commands.literal("cancel").executes(context -> withWipe(plugin, context.getSource().getSender(),
                        wipe -> wipe.cancel(context.getSource().getSender()))))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(context -> withWipe(plugin, context.getSource().getSender(),
                                        wipe -> wipe.confirm(context.getSource().getSender(), StringArgumentType.getString(context, "code")))))));
        // 2026-10-17：轉移帳號（A 的整個帳號搬到 B、覆蓋 B；只能在主控台，下次開機時做）
        nodes.add(Commands.literal("transfer")
                .then(Commands.literal("list").executes(context -> withTransfer(plugin, context.getSource().getSender(),
                        transfer -> transfer.list(context.getSource().getSender()))))
                .then(Commands.literal("cancel").executes(context -> withTransfer(plugin, context.getSource().getSender(),
                        transfer -> transfer.cancel(context.getSource().getSender()))))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(context -> withTransfer(plugin, context.getSource().getSender(),
                                        transfer -> transfer.confirm(context.getSource().getSender(), StringArgumentType.getString(context, "code"))))))
                .then(Commands.argument("from", StringArgumentType.word())
                        .then(Commands.argument("to", StringArgumentType.word())
                                .executes(context -> withTransfer(plugin, context.getSource().getSender(),
                                        transfer -> transfer.preview(context.getSource().getSender(),
                                                StringArgumentType.getString(context, "from"), StringArgumentType.getString(context, "to")))))));
        return nodes;
    }

    private static int withWipe(final ExileCore plugin, final CommandSender sender, final java.util.function.Consumer<WipeService> action) {
        if (plugin.ops() == null || plugin.ops().wipe() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>維運模組沒有啟用（看主控台的錯誤訊息）"));
        } else {
            action.accept(plugin.ops().wipe());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int withTransfer(final ExileCore plugin, final CommandSender sender, final java.util.function.Consumer<AccountTransfer> action) {
        if (plugin.ops() == null || plugin.ops().transfer() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>維運模組沒有啟用（看主控台的錯誤訊息）"));
        } else {
            action.accept(plugin.ops().transfer());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int withBackups(final ExileCore plugin, final CommandSender sender, final java.util.function.Consumer<BackupService> action) {
        if (plugin.ops() == null || plugin.ops().backups() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>維運模組沒有啟用（看主控台的錯誤訊息）"));
        } else {
            action.accept(plugin.ops().backups());
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 備份檔名的自動完成（還原只列資料庫備份）。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestBackups(
            final ExileCore plugin, final com.mojang.brigadier.suggestion.SuggestionsBuilder builder, final boolean databaseOnly) {
        if (plugin.ops() != null && plugin.ops().backups() != null) {
            final String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (final Path file : plugin.ops().backups().list()) {
                final String name = file.getFileName().toString();
                if ((!databaseOnly || name.endsWith(".json.gz")) && name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }

    private static void listBackups(final ExileCore plugin, final BackupService service, final CommandSender sender) {
        final List<Path> files = service.list();
        if (files.isEmpty()) {
            sender.sendMessage(plugin.text().prefixed("<gray>還沒有備份（/ec backup 馬上備份一次）"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>備份（plugins/ExileCore/backups/，新的在前）："));
        for (final Path file : files.subList(0, Math.min(15, files.size()))) {
            sender.sendMessage(plugin.text().parse("  <white>" + file.getFileName() + " <dark_gray>" + BackupService.size(file)));
        }
        sender.sendMessage(plugin.text().parse("<gray>檢查備份（不動現在的資料）：<white>/ec backup verify</white> [檔名]（不寫檔名 = 最新的）"));
        sender.sendMessage(plugin.text().parse("<dark_gray>還原資料庫：在主控台輸入 ec backup restore db-時間.json.gz（會踢所有人，還原完要重開）"));
    }

    /**
     * 2026-10-22：/ec perf displays——展示實體（名牌、標記、模型…）是哪些東西、各幾個（spark：全服將近一半的實體是展示實體，
     * 實體同步佔主執行緒 8%）。物品展示照「物品＋模型代號」分，文字展示照標籤或開頭的字分。
     */
    static int displays(final ExileCore plugin, final CommandSender sender) {
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int total = 0;
        for (final World world : plugin.getServer().getWorlds()) {
            for (final Entity entity : world.getEntities()) {
                if (!(entity instanceof Display display)) {
                    continue;
                }
                total++;
                final String tag = display.getScoreboardTags().isEmpty() ? "" : " #" + String.join(",", new java.util.TreeSet<>(display.getScoreboardTags()));
                final String kind;
                if (display instanceof org.bukkit.entity.ItemDisplay item) {
                    final org.bukkit.inventory.ItemStack stack = item.getItemStack();
                    String model = "";
                    if (stack != null && stack.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA)) {
                        final java.util.List<String> strings = stack.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA).strings();
                        model = strings.isEmpty() ? "" : " " + strings.getFirst();
                    }
                    kind = "物品 " + (stack == null ? "空" : stack.getType().getKey().getKey()) + model;
                } else if (display instanceof org.bukkit.entity.TextDisplay text) {
                    final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text.text())
                            .replace('\n', ' ').strip();
                    kind = "文字 " + (tag.isEmpty() ? (plain.length() > 6 ? plain.substring(0, 6) + "…" : plain) : "");
                } else {
                    kind = "方塊 " + ((org.bukkit.entity.BlockDisplay) display).getBlock().getMaterial().getKey().getKey();
                }
                counts.merge(world.getName().replace("exilecore_", "") + "｜" + kind + tag, 1, Integer::sum);
            }
        }
        final List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(java.util.Map.Entry.<String, Integer>comparingByValue().reversed());
        sender.sendMessage(plugin.text().prefixed("<gray>展示實體共 <white>" + total + "</white> 個，最多的 20 種（世界｜種類 #標籤）："));
        for (final java.util.Map.Entry<String, Integer> entry : sorted.subList(0, Math.min(20, sorted.size()))) {
            sender.sendMessage(plugin.text().parse("  <white>" + entry.getValue() + "</white> <gray>" + entry.getKey().replace("<", "\\<")));
        }
        return Command.SINGLE_SUCCESS;
    }

    static int perf(final ExileCore plugin, final CommandSender sender) {
        final double[] tps = plugin.getServer().getTPS();
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>TPS <white>%.1f / %.1f / %.1f</white>（1、5、15 分鐘）　每 tick 平均 <white>%.1f</white> 毫秒（上限 50）",
                tps[0], tps[1], tps[2], plugin.getServer().getAverageTickTime())));
        final List<Perf.Stat> stats = plugin.perf().stats(10);
        if (stats.isEmpty()) {
            sender.sendMessage(plugin.text().parse("<gray>還在收集資料，過幾秒再看"));
        } else {
            sender.sendMessage(plugin.text().parse("<gray>最近 10 秒，ExileCore 各部分（每 tick 毫秒｜每秒次數｜最慢一次）："));
            for (final Perf.Stat stat : stats.subList(0, Math.min(12, stats.size()))) {
                sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "  <white>%-14s</white> <gray>%.2f ms｜%.0f 次｜%.1f ms",
                        stat.name(), stat.msPerTick(), stat.callsPerSecond(), stat.maxMs())));
            }
        }
        sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT,
                "<gray>資料庫：排隊 <white>%d</white>　平均 <white>%.1f</white> 毫秒　最近最慢 <white>%.0f</white> 毫秒",
                plugin.database().queued(), plugin.database().averageMillis(), plugin.database().slowestMillis())));
        sender.sendMessage(plugin.text().parse("<gray>背包比對優化：<white>" + tw.exilecore.core.SlotSyncHealer.status()));
        for (final World world : plugin.getServer().getWorlds()) {
            int living = 0;
            int items = 0;
            int displays = 0;
            int total = 0;
            for (final Entity entity : world.getEntities()) {
                total++;
                if (entity instanceof Item) {
                    items++;
                } else if (entity instanceof Display) {
                    displays++;
                } else if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                    living++;
                }
            }
            sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT,
                    "  <white>%s</white> <gray>實體 %d（生物 %d、掉落物 %d、顯示 %d）、區塊 %d、玩家 %d、視距 %d／模擬 %d",
                    world.getName(), total, living, items, displays, world.getLoadedChunks().length, world.getPlayers().size(),
                    world.getViewDistance(), world.getSimulationDistance())));
        }
        sender.sendMessage(plugin.text().parse("<dark_gray>更細的剖析可以裝 spark（/spark profiler）"));
        return Command.SINGLE_SUCCESS;
    }

    /** 一件物品的交易與通貨紀錄（/ec trace；管理網頁也用）。結果過一下才送給 sender（要查資料庫）。 */
    public static int traceItem(final ExileCore plugin, final CommandSender sender, final String uuid) {
        final TradeLogRepository log = plugin.economy().tradeLog();
        plugin.database().async(connection -> {
            final List<String> lines = new ArrayList<>();
            for (final TradeLogRepository.Row row : log.byItem(connection, uuid, 50)) {
                lines.add(describe(row));
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_name, currency, created_at FROM currency_log WHERE item_uuid = ? ORDER BY id LIMIT 50")) {
                statement.setString(1, uuid);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        lines.add("<dark_gray>" + TIME.format(result.getTimestamp(3).toInstant()) + " <gray>通貨 <white>"
                                + result.getString(2) + "</white>（" + result.getString(1) + "）");
                    }
                }
            }
            return lines;
        }).whenComplete((lines, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                sender.sendMessage(plugin.text().prefixed("<red>查詢失敗：" + error.getMessage()));
                return;
            }
            sender.sendMessage(plugin.text().prefixed("<gray>物品 <white>" + uuid + "</white> 的紀錄（" + lines.size() + " 筆）："));
            if (lines.isEmpty()) {
                sender.sendMessage(plugin.text().parse("  <dark_gray>沒有交易與通貨紀錄（掉落後沒有交易過、也沒有改造過）"));
            }
            for (final String line : lines) {
                sender.sendMessage(plugin.text().parse("  " + line));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    /** 一個玩家最近的交易（/ec trace player；管理網頁也用）。 */
    public static int tracePlayer(final ExileCore plugin, final CommandSender sender, final String name) {
        final TradeLogRepository log = plugin.economy().tradeLog();
        plugin.database().async(connection -> log.byName(connection, name, 30)).whenComplete((rows, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(plugin.text().prefixed("<red>查詢失敗：" + error.getMessage()));
                        return;
                    }
                    sender.sendMessage(plugin.text().prefixed("<gray>" + name + " 最近的交易（" + rows.size() + " 筆，新的在前）："));
                    for (final TradeLogRepository.Row row : rows) {
                        sender.sendMessage(plugin.text().parse("  " + describe(row)));
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static String describe(final TradeLogRepository.Row row) {
        final TradeLogRepository.Entry entry = row.entry();
        final String kind = switch (entry.kind()) {
            case TradeLogRepository.TRADE -> "交易";
            case TradeLogRepository.MARKET -> "市集";
            case TradeLogRepository.LIST -> "上架";
            case TradeLogRepository.UNLIST -> "下架";
            case TradeLogRepository.EXCHANGE -> "交易所";
            case TradeLogRepository.VENDOR -> "商人";
            case TradeLogRepository.TRASH -> "垃圾桶";
            case TradeLogRepository.MERITS -> "功勳兌換";
            case TradeLogRepository.ADMIN -> "管理員發放";
            case TradeLogRepository.RECIPE -> "傳奇換一";
            default -> entry.kind();
        };
        return "<dark_gray>" + TIME.format(row.at()) + " <gray>" + kind + " <white>" + escape(entry.itemDesc())
                + (entry.amount() > 1 ? " ×" + entry.amount() : "") + "</white> <gray>" + escape(nullToDash(entry.fromName()))
                + " → " + escape(nullToDash(entry.toName())) + (entry.itemUuid() == null ? "" : " <dark_gray>" + entry.itemUuid());
    }

    private static String nullToDash(final String text) {
        return text == null ? "—" : text;
    }

    private static String escape(final String text) {
        return text == null ? "" : text.replace("<", "\\<");
    }
}
