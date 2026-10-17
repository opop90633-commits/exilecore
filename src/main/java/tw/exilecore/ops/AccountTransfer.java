package tw.exilecore.ops;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.core.db.Database;
import tw.exilecore.core.db.Migrations;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * 轉移帳號（2026-10-17，只能在主控台）：把 A 的整個帳號搬到 B 身上，B 原本的資料清掉（覆蓋），A 變成空帳號。
 * 用在玩家換了 Minecraft 帳號、要把進度帶過去。
 * <pre>
 * ec transfer A B          預覽：兩邊各有什麼、會清掉什麼，給一組確認碼（60 秒內有效）
 * ec transfer confirm 碼   排進「下次開機時轉移」（transfers-pending.txt）
 * ec transfer list         看排著的轉移
 * ec transfer cancel       全部取消
 * </pre>
 * 管理網頁（2026-10-17）的「帳號轉移」分頁做一樣的事：{@link #inspect} 預覽、{@link #enqueue} 排進去、{@link #pending()}、{@link #cancel(int)}。
 * 為什麼等開機才做：角色、倉庫、市集、公會在記憶體裡都有快取，伺服器開著時改資料庫，快取會把舊的寫回去。
 * 開機時資料庫連上之後、其他模組讀資料之前做：先備份一次資料庫（backups/db-時間-transfer.json.gz，失敗就不轉），
 * 每一筆在一個交易裡做完（失敗就整筆退回），最後把原版存檔（位置、原版背包、終界箱、進度、統計）也搬過去。
 * <p>
 * 搬過去的：帳號（金幣、功勳、目前角色）、帳號進度（贊助、通行證、解鎖…）、所有角色（含背包、天賦、技能、任務進度，跟著角色走）、
 * 網頁角色頁、倉庫（頁、分頁、通貨）、市集上架與待領的錢、交易所掛單、公會成員身分（會長也換成 B）、入會申請、
 * 管理員發放（含還沒領的）、贊助訂單、衝等紀錄。
 * 不動的（歷史紀錄）：交易紀錄、經濟報表、交易所成交、星幣券、通貨紀錄、管理員操作紀錄。
 * B 原本的：上面那些全部刪掉（B 的贊助訂單、管理員發放、衝等紀錄留著，是錢與歷史）。B 是有其他成員的公會的會長就不轉（先換會長）。
 */
public final class AccountTransfer {

    /** 確認碼多久有效。 */
    private static final long CODE_MILLIS = 60_000;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String PENDING_FILE = "transfers-pending.txt";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 一個帳號：UUID 與 Minecraft 名字。 */
    record Account(String uuid, String name) {
    }

    /** 排著的一筆轉移。 */
    public record Pending(String from, String fromName, String to, String toName, String admin) {
        String line() {
            return String.join("\t", from, fromName, to, toName, admin);
        }

        static Pending parse(final String line) {
            final String[] parts = line.split("\t");
            return parts.length < 5 ? null : new Pending(parts[0], parts[1], parts[2], parts[3], parts[4]);
        }
    }

    /** 帳號層級、要整個換成 B 的資料表與欄位（B 原本的先刪掉）。 */
    static final List<String[]> ACCOUNT_TABLES = List.of(
            new String[] {"account_progress", "account_uuid"},
            new String[] {"stash_pages", "account_uuid"},
            new String[] {"stash_tabs", "account_uuid"},
            new String[] {"stash_currency", "account_uuid"},
            new String[] {"market_listings", "account_uuid"},
            new String[] {"market_proceeds", "account_uuid"},
            new String[] {"character_profiles", "account_uuid"},
            new String[] {"guild_applications", "account_uuid"},
            new String[] {"guild_members", "account_uuid"});
    /** 帳號層級、A 的搬給 B，但 B 原本的留著（錢、歷史）。 */
    static final List<String[]> KEEP_BOTH_TABLES = List.of(
            new String[] {"admin_deliveries", "account_uuid"},
            new String[] {"shop_orders", "account_uuid"},
            new String[] {"level_race", "account_uuid"});
    /** 跟著角色（character_id）的資料表：B 的角色刪掉時一起刪。 */
    static final List<String> CHARACTER_TABLES = List.of(
            "character_inventory", "passive_allocations", "progress", "character_skills", "character_skill_binds", "character_profiles");

    private final ExileCore plugin;
    private String code;
    private Pending preview;
    private long expires;

    public AccountTransfer(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private boolean console(final CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        sender.sendMessage(plugin.text().prefixed("<red>轉移帳號只能在伺服器主控台執行（面板的 Console 輸入 <white>ec transfer A B</white>）"));
        return false;
    }

    // ---- 指令 ----

    /** 預覽的結果：lines 給人看（純文字）；pending 是 null 就是不能轉（lines 寫原因），warning 是 B 有贊助資料會被蓋掉。 */
    public record Preview(List<String> lines, Pending pending, boolean warning) {
    }

    /** 查兩個帳號、做預覽（在資料庫執行緒上）。指令與管理網頁共用。 */
    public java.util.concurrent.CompletableFuture<Preview> inspect(final String fromText, final String toText, final String admin) {
        return plugin.database().async(connection -> {
            final Account from = find(connection, fromText);
            final Account to = find(connection, toText);
            if (from == null || to == null) {
                return new Preview(List.of("找不到帳號：" + (from == null ? fromText : toText)
                        + "（要寫 Minecraft 名字或 UUID，而且那個人要登入過伺服器一次）"), null, false);
            }
            if (from.uuid().equals(to.uuid())) {
                return new Preview(List.of("A 跟 B 是同一個帳號"), null, false);
            }
            final String blocked = blocked(connection, to);
            if (blocked != null) {
                return new Preview(List.of(blocked), null, false);
            }
            final List<String> lines = new ArrayList<>();
            lines.add("從 A：" + from.name() + "（" + from.uuid() + "）");
            lines.add("　" + summary(connection, from));
            lines.add("到 B：" + to.name() + "（" + to.uuid() + "）");
            lines.add("　" + summary(connection, to));
            lines.add("B 原本的角色、倉庫、金幣、功勳、帳號進度（含贊助的星幣、累積贊助）、市集、公會身分會全部清掉，換成 A 的；A 變成空帳號");
            final boolean warning = count(connection,
                    "SELECT COUNT(*) FROM account_progress WHERE account_uuid = ? AND progress_key LIKE 'sp.%'", to.uuid()) > 0;
            if (warning) {
                lines.add("注意：B 有贊助的資料（星幣、累積贊助、月卡…），轉移後會被 A 的蓋掉");
            }
            lines.add("排進去之後，下次重新啟動伺服器時才轉移（開機先自動備份資料庫）");
            return new Preview(lines, new Pending(from.uuid(), from.name(), to.uuid(), to.name(), admin), warning);
        });
    }

    /** ec transfer A B：預覽，給確認碼。 */
    public void preview(final CommandSender sender, final String fromText, final String toText) {
        if (!console(sender)) {
            return;
        }
        inspect(fromText, toText, sender.getName()).whenComplete((result, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("[轉移帳號] 預覽失敗", error);
                sender.sendMessage(plugin.text().prefixed("<red>預覽失敗（看主控台）"));
                return;
            }
            if (result.pending() == null) {
                sender.sendMessage(plugin.text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(result.lines().get(0))));
                return;
            }
            sender.sendMessage(plugin.text().parse("<gold><bold>轉移帳號預覽"));
            for (final String line : result.lines()) {
                sender.sendMessage(plugin.text().parse((line.startsWith("注意") ? "<red>" : "<gray>") + tw.exilecore.item.ItemRenderer.escape(line)));
            }
            final String next;
            synchronized (this) {
                final StringBuilder chars = new StringBuilder();
                final java.util.random.RandomGenerator random = new java.security.SecureRandom();
                for (int i = 0; i < 6; i++) {
                    chars.append(CHARS.charAt(random.nextInt(CHARS.length())));
                }
                code = chars.toString();
                preview = result.pending();
                expires = System.currentTimeMillis() + CODE_MILLIS;
                next = code;
            }
            sender.sendMessage(plugin.text().parse("<yellow>確定要轉：60 秒內輸入 <white>ec transfer confirm " + next + "</white>"));
        }));
    }

    /** ec transfer confirm 碼：排進下次開機。 */
    public synchronized void confirm(final CommandSender sender, final String typed) {
        if (!console(sender)) {
            return;
        }
        if (code == null || preview == null || System.currentTimeMillis() > expires) {
            code = null;
            sender.sendMessage(plugin.text().prefixed("<red>確認碼過期或還沒預覽：先輸入 ec transfer A B"));
            return;
        }
        if (!code.equalsIgnoreCase(typed.trim())) {
            sender.sendMessage(plugin.text().prefixed("<red>確認碼不對（要輸入預覽時給的那 6 個字）"));
            return;
        }
        final Pending pending = preview;
        code = null;
        preview = null;
        try {
            enqueue(pending);
        } catch (final IllegalArgumentException e) {
            sender.sendMessage(plugin.text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(e.getMessage())));
            return;
        } catch (final IOException e) {
            sender.sendMessage(plugin.text().prefixed("<red>寫不進 " + PENDING_FILE + "（看主控台），沒有排進去"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<green>排好了：<white>" + pending.fromName() + " → " + pending.toName()
                + "</white>。<yellow>重新啟動伺服器時才會轉移（開機先自動備份）。<gray>取消：ec transfer cancel"));
    }

    /**
     * 排進下次開機（主執行緒上呼叫：A、B 在線上的話踢下線）。同一個帳號已經排著（當 A 或 B）就不收。
     */
    public synchronized void enqueue(final Pending pending) throws IOException {
        for (final Pending other : pending()) {
            if (other.from().equals(pending.from()) || other.from().equals(pending.to())
                    || other.to().equals(pending.from()) || other.to().equals(pending.to())) {
                throw new IllegalArgumentException("這兩個帳號之一已經排著一筆轉移（" + other.fromName() + " → " + other.toName() + "），先取消那一筆");
            }
        }
        try {
            Files.writeString(plugin.dataFolder().resolve(PENDING_FILE), pending.line() + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("[轉移帳號] 寫不進 {}", PENDING_FILE, e);
            throw e;
        }
        plugin.getSLF4JLogger().warn("[轉移帳號] {} 排了轉移：{}（{}）→ {}（{}），下次開機時做",
                pending.admin(), pending.fromName(), pending.from(), pending.toName(), pending.to());
        for (final String uuid : List.of(pending.from(), pending.to())) {
            final Player online = plugin.getServer().getPlayer(UUID.fromString(uuid));
            if (online != null) {
                online.kick(plugin.text().parse("<yellow>你的帳號資料要轉移，伺服器重開後才會完成"));
            }
        }
    }

    /** 排著的轉移（照排的順序）。 */
    public List<Pending> pending() {
        return readPending(plugin.dataFolder());
    }

    /** 取消第 index 筆（從 0 算）；回傳取消的那一筆（沒有這筆回傳 null）。 */
    public synchronized Pending cancel(final int index) throws IOException {
        final List<Pending> list = new ArrayList<>(pending());
        if (index < 0 || index >= list.size()) {
            return null;
        }
        final Pending removed = list.remove(index);
        final Path file = plugin.dataFolder().resolve(PENDING_FILE);
        if (list.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            final StringBuilder text = new StringBuilder();
            for (final Pending entry : list) {
                text.append(entry.line()).append('\n');
            }
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        }
        return removed;
    }

    /** ec transfer list。 */
    public void list(final CommandSender sender) {
        if (!console(sender)) {
            return;
        }
        final List<Pending> pending = readPending(plugin.dataFolder());
        if (pending.isEmpty()) {
            sender.sendMessage(plugin.text().prefixed("<gray>沒有排著的轉移"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>下次開機時要轉移的帳號："));
        for (final Pending entry : pending) {
            sender.sendMessage(plugin.text().parse("<gray>  " + entry.fromName() + " → " + entry.toName() + " <dark_gray>（" + entry.admin() + " 排的）"));
        }
    }

    /** ec transfer cancel：全部取消。 */
    public void cancel(final CommandSender sender) {
        if (!console(sender)) {
            return;
        }
        synchronized (this) {
            code = null;
            preview = null;
        }
        try {
            final boolean had = Files.deleteIfExists(plugin.dataFolder().resolve(PENDING_FILE));
            sender.sendMessage(plugin.text().prefixed(had ? "<green>排著的轉移全部取消了，什麼都沒動" : "<gray>沒有排著的轉移"));
        } catch (final IOException e) {
            sender.sendMessage(plugin.text().prefixed("<red>刪不掉 " + PENDING_FILE + "（看主控台）"));
            plugin.getSLF4JLogger().error("[轉移帳號] 刪不掉 {}", PENDING_FILE, e);
        }
    }

    // ---- 開機時 ----

    /**
     * 開機時（資料庫連上、其他模組讀資料之前）：有排著的轉移就先備份資料庫，再一筆一筆轉。
     * 備份失敗就一筆都不轉（檔案留著，下次開機再試）。
     */
    public static void applyPending(final ExileCore plugin, final Database database) {
        final Path dataFolder = plugin.dataFolder();
        final List<Pending> pending = readPending(dataFolder);
        if (pending.isEmpty()) {
            return;
        }
        final org.slf4j.Logger log = plugin.getSLF4JLogger();
        log.warn("[轉移帳號] 有 {} 筆排著的轉移，先備份資料庫", pending.size());
        final Path backup;
        try {
            backup = backup(database, dataFolder);
        } catch (final SQLException | IOException | RuntimeException e) {
            log.error("[轉移帳號] 備份失敗，這次不轉移（{} 留著，下次開機再試）", PENDING_FILE, e);
            return;
        }
        log.warn("[轉移帳號] 備份好了：{}", backup.getFileName());
        final List<Pending> done = new ArrayList<>();
        for (final Pending entry : pending) {
            try {
                final Map<String, Integer> rows = database.sync(connection -> {
                    final boolean autoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        final Map<String, Integer> result = transfer(connection, entry.from(), entry.to());
                        logAdmin(connection, entry, backup, result);
                        connection.commit();
                        return result;
                    } catch (final SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    } finally {
                        connection.setAutoCommit(autoCommit);
                    }
                });
                final int files = moveVanillaFiles(plugin, entry.from(), entry.to());
                log.warn("[轉移帳號] 完成：{} → {}（{}；原版存檔 {} 個）", entry.fromName(), entry.toName(), rows, files);
                done.add(entry);
            } catch (final SQLException | RuntimeException e) {
                log.error("[轉移帳號] {} → {} 失敗，這一筆整個退回（資料庫維持原樣）", entry.fromName(), entry.toName(), e);
            }
        }
        try {
            final Path file = dataFolder.resolve(PENDING_FILE);
            final List<Pending> left = new ArrayList<>(pending);
            left.removeAll(done);
            if (left.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                final StringBuilder text = new StringBuilder();
                for (final Pending entry : left) {
                    text.append(entry.line()).append('\n');
                }
                Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            }
            if (!done.isEmpty()) {
                final StringBuilder text = new StringBuilder();
                for (final Pending entry : done) {
                    text.append(LocalDateTime.now().format(STAMP)).append('\t').append(entry.line()).append('\t').append(backup.getFileName()).append('\n');
                }
                Files.writeString(dataFolder.resolve("transfers-done.txt"), text.toString(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (final IOException e) {
            log.error("[轉移帳號] 更新 {} 失敗：做完的那幾筆請手動從檔案裡拿掉，不然下次開機會再做一次", PENDING_FILE, e);
        }
    }

    static List<Pending> readPending(final Path dataFolder) {
        final Path file = dataFolder.resolve(PENDING_FILE);
        if (!Files.exists(file)) {
            return List.of();
        }
        final List<Pending> list = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    final Pending entry = Pending.parse(line.trim());
                    if (entry != null) {
                        list.add(entry);
                    }
                }
            }
        } catch (final IOException e) {
            return List.of();
        }
        return list;
    }

    private static Path backup(final Database database, final Path dataFolder) throws SQLException, IOException {
        final Path folder = dataFolder.resolve("backups");
        Files.createDirectories(folder);
        final String stamp = LocalDateTime.now().format(STAMP);
        final Path target = folder.resolve("db-" + stamp + "-transfer.json.gz");
        final Path temp = folder.resolve("db-" + stamp + "-transfer.json.gz.part");
        database.sync(connection -> {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                DatabaseDump.dump(connection, Migrations.TABLES, out);
            } catch (final IOException e) {
                throw new SQLException("寫不進備份檔：" + e.getMessage(), e);
            }
            return null;
        });
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    // ---- 資料庫（純 JDBC，測得到）----

    /** 用名字（不分大小寫）或 UUID 找帳號；沒有回傳 null。 */
    static Account find(final Connection connection, final String text) throws SQLException {
        final String key = text.trim();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT uuid, name FROM accounts WHERE uuid = ? OR LOWER(name) = LOWER(?)")) {
            select.setString(1, key);
            select.setString(2, key);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? new Account(result.getString(1), result.getString(2)) : null;
            }
        }
    }

    /** B 不能被覆蓋的原因（是有其他成員的公會的會長）；可以回傳 null。 */
    static String blocked(final Connection connection, final Account to) throws SQLException {
        if (!Tables.exists(connection, "guilds")) {
            return null;
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT g.name, (SELECT COUNT(*) FROM guild_members m WHERE m.guild_id = g.id) FROM guilds g WHERE g.leader_uuid = ?")) {
            select.setString(1, to.uuid());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    if (result.getInt(2) > 1) {
                        return to.name() + " 是公會「" + result.getString(1) + "」的會長而且還有其他成員：先換會長或解散公會再轉";
                    }
                }
            }
        }
        return null;
    }

    private static String summary(final Connection connection, final Account account) throws SQLException {
        final int characters = count(connection, "SELECT COUNT(*) FROM characters WHERE account_uuid = ? AND deleted_at IS NULL", account.uuid());
        int top = 0;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT MAX(char_level) FROM characters WHERE account_uuid = ? AND deleted_at IS NULL")) {
            select.setString(1, account.uuid());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    top = result.getInt(1);
                }
            }
        }
        final int pages = count(connection, "SELECT COUNT(*) FROM stash_pages WHERE account_uuid = ?", account.uuid());
        final int listings = count(connection, "SELECT COUNT(*) FROM market_listings WHERE account_uuid = ?", account.uuid());
        return String.format(Locale.ROOT, "角色 %d 個（最高 Lv.%d）、倉庫 %d 頁、市集上架 %d 件", characters, top, pages, listings);
    }

    private static int count(final Connection connection, final String sql, final String uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, uuid);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /**
     * 把 from 的帳號搬到 to（覆蓋 to 原本的）。呼叫端負責交易（commit／rollback）。回傳每張資料表動了幾列。
     */
    static Map<String, Integer> transfer(final Connection connection, final String from, final String to) throws SQLException {
        final Map<String, Integer> rows = new LinkedHashMap<>();
        final Account target = find(connection, to);
        if (find(connection, from) == null || target == null) {
            throw new SQLException("帳號不見了：" + (target == null ? to : from));
        }
        final String blocked = blocked(connection, target);
        if (blocked != null) {
            throw new SQLException(blocked);
        }
        // 1. 刪掉 B 的角色（含已刪除的）與跟著角色的資料
        final List<Long> old = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM characters WHERE account_uuid = ?")) {
            select.setString(1, to);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    old.add(result.getLong(1));
                }
            }
        }
        for (final String table : CHARACTER_TABLES) {
            if (!Tables.exists(connection, table)) {
                continue;
            }
            int deleted = 0;
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE character_id = ?")) {
                for (final long id : old) {
                    delete.setLong(1, id);
                    deleted += delete.executeUpdate();
                }
            }
            add(rows, "刪 B 的 " + table, deleted);
        }
        add(rows, "刪 B 的 characters", update(connection, "DELETE FROM characters WHERE account_uuid = ?", to));
        // 2. B 的交易所掛單（連成交進度）刪掉
        if (Tables.exists(connection, "exchange_orders")) {
            if (Tables.exists(connection, "exchange_fills")) {
                add(rows, "刪 B 的 exchange_fills", update(connection,
                        "DELETE FROM exchange_fills WHERE order_id IN (SELECT order_id FROM exchange_orders WHERE account_uuid = ?)", to));
            }
            add(rows, "刪 B 的 exchange_orders", update(connection, "DELETE FROM exchange_orders WHERE account_uuid = ?", to));
            add(rows, "exchange_orders", update(connection, "UPDATE exchange_orders SET account_uuid = ? WHERE account_uuid = ?", to, from));
        }
        // 3. 帳號層級：B 的刪掉、A 的換成 B
        for (final String[] table : ACCOUNT_TABLES) {
            if (!Tables.exists(connection, table[0])) {
                continue;
            }
            add(rows, "刪 B 的 " + table[0], update(connection, "DELETE FROM " + table[0] + " WHERE " + table[1] + " = ?", to));
            add(rows, table[0], update(connection, "UPDATE " + table[0] + " SET " + table[1] + " = ? WHERE " + table[1] + " = ?", to, from));
        }
        for (final String[] table : KEEP_BOTH_TABLES) {
            if (Tables.exists(connection, table[0])) {
                add(rows, table[0], update(connection, "UPDATE " + table[0] + " SET " + table[1] + " = ? WHERE " + table[1] + " = ?", to, from));
            }
        }
        // 4. 角色換主人；公會會長、成員名字
        add(rows, "characters", update(connection, "UPDATE characters SET account_uuid = ? WHERE account_uuid = ?", to, from));
        if (Tables.exists(connection, "guilds")) {
            // B 自己一個人的公會（只有 B）：會長換掉之前先刪（B 的成員身分上面已經刪了）
            add(rows, "刪 B 的空公會", update(connection, "DELETE FROM guilds WHERE leader_uuid = ? "
                    + "AND NOT EXISTS (SELECT 1 FROM guild_members m WHERE m.guild_id = guilds.id)", to));
            add(rows, "guilds（會長）", update(connection, "UPDATE guilds SET leader_uuid = ? WHERE leader_uuid = ?", to, from));
        }
        if (Tables.exists(connection, "guild_members")) {
            update(connection, "UPDATE guild_members SET name = ? WHERE account_uuid = ?", target.name(), to);
        }
        // 5. 帳號本身：B 的刪掉，A 的那一列換成 B 的 UUID 與名字
        add(rows, "刪 B 的 accounts", update(connection, "DELETE FROM accounts WHERE uuid = ?", to));
        try (PreparedStatement move = connection.prepareStatement("UPDATE accounts SET uuid = ?, name = ? WHERE uuid = ?")) {
            move.setString(1, to);
            move.setString(2, target.name());
            move.setString(3, from);
            add(rows, "accounts", move.executeUpdate());
        }
        return rows;
    }

    private static void add(final Map<String, Integer> rows, final String key, final int value) {
        if (value > 0) {
            rows.merge(key, value, Integer::sum);
        }
    }

    private static int update(final Connection connection, final String sql, final String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static void logAdmin(final Connection connection, final Pending entry, final Path backup, final Map<String, Integer> rows)
            throws SQLException {
        if (!Tables.exists(connection, "admin_log")) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO admin_log (created_at, admin_name, action, detail) VALUES (?, ?, ?, ?)")) {
            insert.setTimestamp(1, Timestamp.from(Instant.now()));
            insert.setString(2, entry.admin().length() > 32 ? entry.admin().substring(0, 32) : entry.admin());
            insert.setString(3, "transfer");
            String detail = String.format(Locale.ROOT, "轉移帳號 %s（%s）→ %s（%s）：%s；轉移前的備份 %s",
                    entry.fromName(), entry.from(), entry.toName(), entry.to(), rows, backup.getFileName());
            if (detail.length() > 2000) {
                detail = detail.substring(0, 2000);
            }
            insert.setString(4, detail);
            insert.executeUpdate();
        }
    }

    /** 原版存檔（playerdata、advancements、stats）：A 的搬成 B 的（B 原本的蓋掉）。回傳搬了幾個檔。 */
    private static int moveVanillaFiles(final ExileCore plugin, final String from, final String to) {
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
        int moved = 0;
        try (Stream<Path> walk = Files.walk(root, 3)) {
            final List<Path> folders = walk.filter(Files::isDirectory).filter(path -> {
                final String name = path.getFileName().toString();
                return name.equals("playerdata") || name.equals("advancements") || name.equals("stats");
            }).toList();
            for (final Path folder : folders) {
                try (Stream<Path> files = Files.list(folder)) {
                    for (final Path file : files.toList()) {
                        final String name = file.getFileName().toString();
                        if (Files.isRegularFile(file) && name.startsWith(to + ".")) {
                            Files.deleteIfExists(file); // B 原本的（.dat、.dat_old、.json）
                        }
                    }
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (final Path file : files.toList()) {
                        final String name = file.getFileName().toString();
                        if (Files.isRegularFile(file) && name.startsWith(from + ".")) {
                            Files.move(file, folder.resolve(to + name.substring(from.length())), StandardCopyOption.REPLACE_EXISTING);
                            moved++;
                        }
                    }
                }
            }
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("[轉移帳號] 原版存檔沒有搬完（資料庫已經轉好了）：{}", e.toString());
        }
        return moved;
    }

    /** 查資料表在不在（H2 與 MariaDB 的大小寫不一樣）。 */
    static final class Tables {
        private Tables() {
        }

        static boolean exists(final Connection connection, final String table) throws SQLException {
            for (final String name : new String[] {table, table.toUpperCase(Locale.ROOT)}) {
                try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, name, null)) {
                    if (result.next()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
