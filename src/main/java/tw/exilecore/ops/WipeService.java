package tw.exilecore.ops;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.core.db.Migrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 清檔（2026-10，只能在主控台）：
 * <pre>
 * ec wipe              預覽「清進度」要清什麼、留什麼，給一組確認碼（60 秒內有效）
 * ec wipe all          預覽「全部清空」（連帳號、贊助都清，開服前清掉測試資料用）
 * ec wipe confirm 碼   真的清：擋登入、踢所有人 → 先備份一次資料庫 → 一個交易裡清掉 → 刪玩家的原版存檔 → 要重開伺服器
 * ec wipe cancel       取消還沒確認的清檔
 * </pre>
 * 「清進度」留下帳號與贊助：星幣、累積贊助、買的格子、月卡、外觀、登入天數（帳號進度裡 sp.、login. 開頭的），
 * 還沒兌換的星幣券退回給開券的人（下次登入加回星幣，用星幣券原本的補發流程），還沒領的管理員發放只留星幣類的。
 * 其他的（角色、背包、倉庫、天賦、任務、星圖、市集、交易所、紀錄、經濟報表、金幣、功勳、稱號、公會）全部清掉。
 * 公會用星幣買的倉庫頁（2026-10-17）：公會清掉了，買的人照目前的價格退回星幣（帳號進度 sp.guild_stash 記著每個人買了幾頁）。
 * 清之前一定先備份（失敗就不清），要反悔用 ec backup restore 那個檔。
 */
public final class WipeService {

    /** 確認碼多久有效。 */
    private static final long CODE_MILLIS = 60_000;
    /** 帳號進度裡要留下的（贊助、登入天數）。 */
    static final List<String> KEEP_ACCOUNT_PREFIXES = List.of("sp.", "login.");
    /** 清進度：整張清掉的資料表。 */
    static final List<String> PROGRESS_TABLES = List.of(
            "character_inventory", "passive_allocations", "progress", "character_skills", "character_skill_binds",
            "character_profiles", "characters",
            "stash_pages", "stash_currency", "stash_tabs",
            "market_listings", "market_proceeds", "exchange_orders", "exchange_fills", "exchange_trades",
            "trade_log", "currency_log",
            "economy_flow", "economy_stock", "economy_income", "currency_values",
            "guild_applications", "guild_stash", "guild_log", "guild_members", "guilds", // 2026-10：公會（倉庫裡是物品、等級是進度）也清
            "level_race"); // 2026-10：衝等活動（新賽季重新搶）
    /** 全部清空時也留著的（資料表版本、管理員操作紀錄）。 */
    static final List<String> NEVER_WIPED = List.of("schema_version", "admin_log");
    private static final Pattern PLAYER_FILE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\..*");
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public enum Mode {
        PROGRESS("清進度"),
        ALL("全部清空");

        private final String label;

        Mode(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 清了幾列（給人看）。 */
    record Result(Map<String, Integer> rows, int vouchers, int deliveries) {
        int total() {
            return rows.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final ExileCore plugin;
    private String code;
    private Mode pending;
    private long expires;
    private boolean running;

    public WipeService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private boolean console(final CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        sender.sendMessage(plugin.text().prefixed("<red>清檔只能在伺服器主控台執行（面板的 Console 輸入 <white>ec wipe</white>）"));
        return false;
    }

    /** ec wipe [all]：預覽，給確認碼。 */
    public void preview(final CommandSender sender, final Mode mode) {
        if (!console(sender)) {
            return;
        }
        if (running) {
            sender.sendMessage(plugin.text().prefixed("<yellow>已經在清檔了（或清完了等重開）"));
            return;
        }
        final StringBuilder next = new StringBuilder();
        final java.util.random.RandomGenerator random = new java.security.SecureRandom();
        for (int i = 0; i < 6; i++) {
            next.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        code = next.toString();
        pending = mode;
        expires = System.currentTimeMillis() + CODE_MILLIS;
        sender.sendMessage(plugin.text().prefixed("<red><bold>清檔預覽：" + mode.label()));
        for (final String line : describe(mode)) {
            sender.sendMessage(plugin.text().parse(line));
        }
        sender.sendMessage(plugin.text().parse("<gray>會先：擋登入、踢所有在線的人、備份一次資料庫（備份失敗就不清）。清完要重新啟動伺服器。"));
        sender.sendMessage(plugin.text().parse("<yellow>確定要清：60 秒內輸入 <white>ec wipe confirm " + code + "</white>（取消：ec wipe cancel）"));
    }

    static List<String> describe(final Mode mode) {
        final List<String> lines = new ArrayList<>();
        if (mode == Mode.PROGRESS) {
            lines.add("<gray>清掉：<white>所有角色（等級、背包、裝備、天賦、技能、任務、星圖、昇華、試煉）、倉庫、市集與交易所、"
                    + "帳號金幣與功勳、稱號與每日委託、交易與通貨紀錄、經濟報表、網頁角色頁");
            lines.add("<gray>留下：<white>帳號、贊助（星幣、累積贊助、買的格子、月卡、外觀、抽獎保底）、登入天數、管理員操作紀錄、"
                    + "區域版面、資料檔與 config.yml");
            lines.add("<gray>星幣券：還沒兌換的退回給開券的人（下次登入加回星幣）；管理員發放：還沒領的只留星幣類，其他取消");
            lines.add("<gray>公會：整個清掉；用星幣買的公會倉庫頁照現在的價格退回星幣給買的人");
        } else {
            lines.add("<gray>清掉：<white>資料庫裡所有的玩家資料，包括帳號、贊助（星幣、月卡、外觀）、星幣券、管理員發放");
            lines.add("<gray>留下：<white>資料表版本、管理員操作紀錄、區域版面、資料檔與 config.yml、全服加倍（sponsor-boosts.yml）");
            lines.add("<red>開服前清掉測試資料用；正式開服後請用 ec wipe（清進度），不要清掉玩家的贊助");
        }
        lines.add("<gray>原版存檔：主世界的玩家資料（位置、原版背包、終界箱）、進度、統計一起刪掉");
        return lines;
    }

    public void cancel(final CommandSender sender) {
        if (!console(sender)) {
            return;
        }
        final boolean had = code != null;
        code = null;
        pending = null;
        sender.sendMessage(plugin.text().prefixed(had ? "<green>取消了，什麼都沒動" : "<gray>沒有等待確認的清檔"));
    }

    /** ec wipe confirm 碼。 */
    public void confirm(final CommandSender sender, final String typed) {
        if (!console(sender)) {
            return;
        }
        if (running) {
            sender.sendMessage(plugin.text().prefixed("<yellow>已經在清檔了（或清完了等重開）"));
            return;
        }
        if (code == null || System.currentTimeMillis() > expires) {
            code = null;
            sender.sendMessage(plugin.text().prefixed("<red>確認碼過期或還沒預覽：先輸入 ec wipe（或 ec wipe all）"));
            return;
        }
        if (!code.equalsIgnoreCase(typed.trim())) {
            sender.sendMessage(plugin.text().prefixed("<red>確認碼不對（要輸入預覽時給的那 6 個字）"));
            return;
        }
        final BackupService backups = plugin.ops() == null ? null : plugin.ops().backups();
        if (backups == null || !plugin.database().isConnected()) {
            sender.sendMessage(plugin.text().prefixed("<red>維運模組沒有啟用或資料庫沒有連線，不能清檔"));
            return;
        }
        if (backups.restoring()) {
            sender.sendMessage(plugin.text().prefixed("<red>正在還原備份（或還原完了等重開）：重新啟動伺服器後再清"));
            return;
        }
        final Mode mode = pending;
        code = null;
        pending = null;
        running = true;
        backups.wiping(true);
        for (final Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            player.kick(plugin.text().parse("<yellow>伺服器正在清檔，重新啟動後才能登入"));
        }
        plugin.getSLF4JLogger().warn("[清檔] {} 開始清檔（{}）：已擋登入、踢所有人", sender.getName(), mode.label());
        sender.sendMessage(plugin.text().prefixed("<yellow>所有人已經踢下線，等登出存檔寫完後先備份資料庫…"));
        // 等一下，讓登出的存檔先排進資料庫執行緒（備份排在它們後面，備份到的是清檔前最後的樣子）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> backups.run(sender, backup -> {
            if (backup == null) {
                running = false;
                backups.wiping(false);
                sender.sendMessage(plugin.text().prefixed("<red>備份沒有成功，沒有清檔（資料庫維持原樣），現在開放登入"));
                return;
            }
            sender.sendMessage(plugin.text().prefixed("<gray>備份好了：<white>" + backup.getFileName() + "</white>，開始清檔…"));
            wipe(sender, mode, backup);
        }), 40);
    }

    private void wipe(final CommandSender sender, final Mode mode, final Path backup) {
        final String admin = sender.getName();
        final long guildPagePrice = plugin.sponsors() == null ? 0 : plugin.sponsors().config().guildStashPrice();
        plugin.database().async(connection -> {
            // 在資料庫執行緒上暫停存檔：排在前面的都寫完了，之後的都不寫（不能讓記憶體裡的舊資料寫回去）
            plugin.characters().pauseWrites();
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                final Result result = mode == Mode.ALL ? wipeAll(connection) : wipeProgress(connection, guildPagePrice);
                log(connection, admin, mode, backup, result);
                connection.commit();
                return result;
            } catch (final SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (final SQLException ignored) {
                    // 原本的錯比較重要
                }
                throw new SQLException("清檔失敗：" + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }).whenComplete((result, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("[清檔] 失敗（資料庫維持原樣）", error);
                sender.sendMessage(plugin.text().prefixed("<red>清檔失敗，資料庫維持原樣（看主控台）。現在開放登入"));
                running = false;
                plugin.characters().resumeWrites();
                if (plugin.ops() != null && plugin.ops().backups() != null) {
                    plugin.ops().backups().wiping(false);
                }
                return;
            }
            final int files = deletePlayerFiles();
            plugin.getSLF4JLogger().warn("[清檔] 完成（{}）：{} 列，星幣券退回 {} 張，取消發放 {} 筆，原版玩家檔 {} 個；備份 {}",
                    mode.label(), result.total(), result.vouchers(), result.deliveries(), files, backup.getFileName());
            for (final Map.Entry<String, Integer> entry : result.rows().entrySet()) {
                if (entry.getValue() > 0) {
                    sender.sendMessage(plugin.text().parse("  <gray>" + entry.getKey() + "：<white>" + entry.getValue()));
                }
            }
            sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                    "<green>清檔完成（%s）：清掉 %,d 列%s%s，原版玩家檔 %d 個。<yellow>請重新啟動伺服器（重開前不能登入）",
                    mode.label(), result.total(),
                    result.vouchers() > 0 ? "，星幣券退回 " + result.vouchers() + " 張" : "",
                    result.deliveries() > 0 ? "，取消 " + result.deliveries() + " 筆還沒領的發放" : "", files)));
            sender.sendMessage(plugin.text().parse("<gray>要反悔：重開後在主控台輸入 <white>ec backup restore " + backup.getFileName()));
        }));
    }

    /** 清進度：角色、倉庫、市集…整張清；帳號留著（金幣、功勳歸零），帳號進度只留贊助。 */
    static Result wipeProgress(final Connection connection) throws SQLException {
        return wipeProgress(connection, 0);
    }

    /** 帳號進度：每個人用星幣買了幾頁公會倉庫（SponsorService.GUILD_STASH）。 */
    static final String GUILD_PAGES_KEY = "sp.guild_stash";
    /** 帳號進度：星幣（SponsorService.COINS）。 */
    static final String COINS_KEY = "sp.coins";

    /**
     * 公會倉庫頁退回星幣（2026-10-17）：每頁照 pagePrice 加回買的人的星幣，買了幾頁的紀錄歸零。回傳退了幾個帳號。
     * pagePrice ≤ 0 就不退（贊助模組沒開）。
     */
    static int refundGuildPages(final Connection connection, final long pagePrice) throws SQLException {
        if (pagePrice <= 0 || !exists(connection, "account_progress")) {
            return 0;
        }
        final Map<String, Long> bought = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT account_uuid, progress_value FROM account_progress WHERE progress_key = ?")) {
            select.setString(1, GUILD_PAGES_KEY);
            try (java.sql.ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    long pages;
                    try {
                        pages = Long.parseLong(result.getString(2).trim());
                    } catch (final RuntimeException e) {
                        pages = 0;
                    }
                    if (pages > 0) {
                        bought.put(result.getString(1), pages);
                    }
                }
            }
        }
        for (final Map.Entry<String, Long> entry : bought.entrySet()) {
            long coins = 0;
            boolean has = false;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT progress_value FROM account_progress WHERE account_uuid = ? AND progress_key = ?")) {
                select.setString(1, entry.getKey());
                select.setString(2, COINS_KEY);
                try (java.sql.ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        has = true;
                        try {
                            coins = Long.parseLong(result.getString(1).trim());
                        } catch (final RuntimeException ignored) {
                            coins = 0;
                        }
                    }
                }
            }
            final String value = String.valueOf(coins + entry.getValue() * pagePrice);
            try (PreparedStatement write = connection.prepareStatement(has
                    ? "UPDATE account_progress SET progress_value = ? WHERE account_uuid = ? AND progress_key = ?"
                    : "INSERT INTO account_progress (progress_value, account_uuid, progress_key) VALUES (?, ?, ?)")) {
                write.setString(1, value);
                write.setString(2, entry.getKey());
                write.setString(3, COINS_KEY);
                write.executeUpdate();
            }
            try (PreparedStatement reset = connection.prepareStatement(
                    "UPDATE account_progress SET progress_value = '0' WHERE account_uuid = ? AND progress_key = ?")) {
                reset.setString(1, entry.getKey());
                reset.setString(2, GUILD_PAGES_KEY);
                reset.executeUpdate();
            }
        }
        return bought.size();
    }

    static Result wipeProgress(final Connection connection, final long guildPagePrice) throws SQLException {
        final Map<String, Integer> rows = new LinkedHashMap<>();
        rows.put("公會倉庫頁退回星幣（帳號）", refundGuildPages(connection, guildPagePrice));
        final Timestamp now = Timestamp.from(Instant.now());
        int vouchers = 0;
        int deliveries = 0;
        if (exists(connection, "star_vouchers")) {
            // 還沒兌換的券（可能在背包、倉庫、市集裡，等一下就沒了）：當作開券的人自己兌換，下次登入照補發流程加回星幣
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE star_vouchers SET redeemed_uuid = issuer_uuid, redeemed_name = ?, redeemed_at = ?, credited = 0, delivered = 1 "
                            + "WHERE redeemed_at IS NULL")) {
                update.setString(1, "清檔退回");
                update.setTimestamp(2, now);
                vouchers = update.executeUpdate();
            }
        }
        if (exists(connection, "admin_deliveries")) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE admin_deliveries SET cancelled_at = ?, cancelled_by = ? "
                            + "WHERE claimed_at IS NULL AND cancelled_at IS NULL AND kind <> ?")) {
                update.setTimestamp(1, now);
                update.setString(2, "清檔");
                update.setString(3, "sponsor");
                deliveries = update.executeUpdate();
            }
        }
        for (final String table : PROGRESS_TABLES) {
            rows.put(table, deleteAll(connection, table));
        }
        if (exists(connection, "account_progress")) {
            final StringBuilder sql = new StringBuilder("DELETE FROM account_progress WHERE 1 = 1");
            for (int i = 0; i < KEEP_ACCOUNT_PREFIXES.size(); i++) {
                sql.append(" AND progress_key NOT LIKE ?");
            }
            try (PreparedStatement delete = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < KEEP_ACCOUNT_PREFIXES.size(); i++) {
                    delete.setString(i + 1, KEEP_ACCOUNT_PREFIXES.get(i) + "%");
                }
                rows.put("account_progress（贊助以外）", delete.executeUpdate());
            }
        }
        if (exists(connection, "accounts")) {
            try (Statement statement = connection.createStatement()) {
                rows.put("accounts（金幣、功勳歸零）", statement.executeUpdate(
                        "UPDATE accounts SET gold = 0, merits = 0, active_character = NULL"));
            }
        }
        return new Result(rows, vouchers, deliveries);
    }

    /** 全部清空：除了資料表版本與管理員操作紀錄，每張都清。 */
    static Result wipeAll(final Connection connection) throws SQLException {
        final Map<String, Integer> rows = new LinkedHashMap<>();
        for (final String table : Migrations.TABLES) {
            if (!NEVER_WIPED.contains(table)) {
                rows.put(table, deleteAll(connection, table));
            }
        }
        return new Result(rows, 0, 0);
    }

    private static int deleteAll(final Connection connection, final String table) throws SQLException {
        if (!exists(connection, table)) {
            return 0;
        }
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("DELETE FROM " + table);
        }
    }

    private static boolean exists(final Connection connection, final String table) throws SQLException {
        for (final String name : new String[] {table, table.toUpperCase(Locale.ROOT)}) {
            try (java.sql.ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, name, null)) {
                if (result.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void log(final Connection connection, final String admin, final Mode mode, final Path backup, final Result result)
            throws SQLException {
        if (!exists(connection, "admin_log")) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO admin_log (created_at, admin_name, action, detail) VALUES (?, ?, ?, ?)")) {
            insert.setTimestamp(1, Timestamp.from(Instant.now()));
            insert.setString(2, admin.length() > 32 ? admin.substring(0, 32) : admin);
            insert.setString(3, "wipe");
            insert.setString(4, String.format(Locale.ROOT, "清檔（%s）：%d 列，星幣券退回 %d 張，取消發放 %d 筆；清檔前的備份 %s",
                    mode.label(), result.total(), result.vouchers(), result.deliveries(), backup.getFileName()));
            insert.executeUpdate();
        }
    }

    /**
     * 刪主世界的原版玩家檔（玩家資料、進度、統計）：檔名是 UUID 開頭的。主世界資料夾往上找到有 level.dat 的那層，
     * 在裡面三層內找 playerdata／advancements／stats（新版的 players/data 等也算）。回傳刪了幾個。
     */
    private int deletePlayerFiles() {
        if (plugin.getServer().getWorlds().isEmpty()) {
            return 0;
        }
        final World main = plugin.getServer().getWorlds().get(0);
        Path root = main.getWorldFolder().toPath().toAbsolutePath();
        for (int i = 0; i < 4 && root != null && !Files.exists(root.resolve("level.dat")); i++) {
            root = root.getParent();
        }
        if (root == null) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(root, 3)) {
            final List<Path> folders = walk.filter(Files::isDirectory).filter(path -> {
                final String name = path.getFileName().toString();
                final String parent = path.getParent() == null || path.getParent().getFileName() == null ? ""
                        : path.getParent().getFileName().toString();
                return name.equals("playerdata") || name.equals("advancements") || name.equals("stats")
                        || parent.equals("players") && (name.equals("data") || name.equals("advancements") || name.equals("stats"));
            }).toList();
            for (final Path folder : folders) {
                try (Stream<Path> files = Files.list(folder)) {
                    for (final Path file : files.toList()) {
                        if (Files.isRegularFile(file) && PLAYER_FILE.matcher(file.getFileName().toString()).matches()) {
                            Files.deleteIfExists(file);
                            deleted++;
                        }
                    }
                }
            }
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("[清檔] 刪原版玩家檔時出錯（資料庫已經清好了）：{}", e.getMessage());
        }
        return deleted;
    }
}
