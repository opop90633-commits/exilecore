package tw.exilecore.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 管理網頁的資料表（純 JDBC，不依賴 Bukkit）：
 * <ul>
 *   <li>admin_deliveries：發給玩家的物資。一列一個帳號的一樣東西；amount 是還沒領的數量（放不下的部分留著），
 *       領完記 claimed_at，管理員取消記 cancelled_at。</li>
 *   <li>admin_log：管理員做了什麼（發放、取消、改設定、公告、維護）。</li>
 * </ul>
 * 找人（發給離線的玩家）：帳號名稱或角色名稱都可以。
 */
public final class AdminRepository {

    static final int LABEL_MAX = 255;
    static final int NOTE_MAX = 255;
    static final int NAME_MAX = 32;
    static final int DETAIL_MAX = 1000;

    /** admin_deliveries 的一列。 */
    public record Delivery(long id, String batchId, UUID account, String playerName, String kind, String ref, String rarity,
                           int itemLevel, long amount, long original, String label, String note, String createdBy,
                           Instant createdAt, Instant claimedAt, String claimedBy, Instant cancelledAt, String cancelledBy,
                           String affixes) {

        /** 還沒領完、沒被取消。 */
        public boolean pending() {
            return claimedAt == null && cancelledAt == null;
        }
    }

    /** 要寫進去的一筆（給誰、什麼東西、幾個）。 */
    public record NewDelivery(UUID account, String playerName, String kind, String ref, String rarity, int itemLevel, long amount,
                              String label, String affixes) {
    }

    /** 一個帳號。 */
    public record Account(UUID uuid, String name) {
    }

    /** 找人的結果：帳號與底下的角色。 */
    public record Found(UUID uuid, String name, Instant lastSeen, List<String> characters) {
    }

    /** admin_log 的一列。 */
    public record LogEntry(Instant at, String admin, String action, String detail) {
    }

    // ---- 發放 ----

    /** 寫一批（同一個 batchId，一次送出）；回傳每一筆的編號（照寫進去的順序）。 */
    public List<Long> insert(final Connection connection, final String batchId, final List<NewDelivery> rows, final String note,
                             final String createdBy) throws SQLException {
        final Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO admin_deliveries (batch_id, account_uuid, player_name, kind, ref, rarity, item_level, amount, original, label, "
                        + "note, created_by, created_at, affixes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int pending = 0;
            for (final NewDelivery row : rows) {
                insert.setString(1, batchId);
                insert.setString(2, row.account().toString());
                insert.setString(3, clip(row.playerName(), NAME_MAX));
                insert.setString(4, row.kind());
                insert.setString(5, row.ref());
                insert.setString(6, row.rarity());
                insert.setInt(7, row.itemLevel());
                insert.setLong(8, row.amount());
                insert.setLong(9, row.amount());
                insert.setString(10, clip(row.label(), LABEL_MAX));
                insert.setString(11, clip(note == null ? "" : note, NOTE_MAX));
                insert.setString(12, clip(createdBy, NAME_MAX));
                insert.setTimestamp(13, now);
                insert.setString(14, row.affixes() == null || row.affixes().isBlank() ? null : row.affixes());
                insert.addBatch();
                if (++pending >= 500) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        }
        final List<Long> ids = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM admin_deliveries WHERE batch_id = ? ORDER BY id")) {
            select.setString(1, batchId);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
        }
        return ids;
    }

    /** 這一批寫過了嗎（同一個請求重送：不要再發一次）；寫過的話回傳發給了哪些帳號，沒有回傳 null。 */
    public List<UUID> batchAccounts(final Connection connection, final String batchId) throws SQLException {
        final List<UUID> accounts = new ArrayList<>();
        boolean any = false;
        try (PreparedStatement select = connection.prepareStatement("SELECT DISTINCT account_uuid FROM admin_deliveries WHERE batch_id = ?")) {
            select.setString(1, batchId);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    any = true;
                    accounts.add(UUID.fromString(result.getString(1)));
                }
            }
        }
        return any ? accounts : null;
    }

    /** 這個帳號還沒領的（舊的先）。 */
    public List<Delivery> pending(final Connection connection, final UUID account, final int limit) throws SQLException {
        return pending(connection, account, 0, limit);
    }

    /** 這個帳號還沒領的、編號大於 afterId 的（一頁一頁讀：前面一直領不走的不會擋住後面的）。 */
    public List<Delivery> pending(final Connection connection, final UUID account, final long afterId, final int limit) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM admin_deliveries WHERE account_uuid = ? "
                + "AND claimed_at IS NULL AND cancelled_at IS NULL AND id > ? ORDER BY id LIMIT ?")) {
            select.setString(1, account.toString());
            select.setLong(2, afterId);
            select.setInt(3, limit);
            return read(select);
        }
    }

    /** 這個帳號還有幾筆沒領。 */
    public int pendingCount(final Connection connection, final UUID account) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM admin_deliveries WHERE account_uuid = ? "
                + "AND claimed_at IS NULL AND cancelled_at IS NULL")) {
            select.setString(1, account.toString());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** 領完了（還沒領、沒取消的才改）；回傳改了幾列。 */
    public int markClaimed(final Connection connection, final long id, final String claimedBy) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE admin_deliveries SET amount = 0, claimed_at = ?, claimed_by = ? "
                + "WHERE id = ? AND claimed_at IS NULL AND cancelled_at IS NULL")) {
            update.setTimestamp(1, Timestamp.from(Instant.now()));
            update.setString(2, clip(claimedBy, NAME_MAX));
            update.setLong(3, id);
            return update.executeUpdate();
        }
    }

    /** 領了一部分：剩下的數量（還沒領、沒取消的才改）。 */
    public int setRemaining(final Connection connection, final long id, final long remaining) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE admin_deliveries SET amount = ? "
                + "WHERE id = ? AND claimed_at IS NULL AND cancelled_at IS NULL")) {
            update.setLong(1, remaining);
            update.setLong(2, id);
            return update.executeUpdate();
        }
    }

    /** 取消（還沒領完的才取消，領了一部分的取消剩下的；同一個交易、一次送出）；回傳取消了哪些。 */
    public List<Long> cancel(final Connection connection, final Collection<Long> ids, final String by) throws SQLException {
        final List<Long> order = new ArrayList<>(ids);
        final List<Long> cancelled = new ArrayList<>();
        if (order.isEmpty()) {
            return cancelled;
        }
        final Timestamp now = Timestamp.from(Instant.now());
        final boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement("UPDATE admin_deliveries SET cancelled_at = ?, cancelled_by = ? "
                    + "WHERE id = ? AND claimed_at IS NULL AND cancelled_at IS NULL")) {
                for (int start = 0; start < order.size(); start += 500) {
                    final List<Long> chunk = order.subList(start, Math.min(order.size(), start + 500));
                    for (final long id : chunk) {
                        update.setTimestamp(1, now);
                        update.setString(2, clip(by, NAME_MAX));
                        update.setLong(3, id);
                        update.addBatch();
                    }
                    final int[] counts = update.executeBatch();
                    for (int i = 0; i < chunk.size(); i++) {
                        // SUCCESS_NO_INFO（-2）：驅動程式沒說改了幾列，條件擋掉了不該改的，當成取消了
                        if (i >= counts.length || counts[i] > 0 || counts[i] == Statement.SUCCESS_NO_INFO) {
                            cancelled.add(chunk.get(i));
                        }
                    }
                }
            }
            connection.commit();
        } catch (final SQLException | RuntimeException | Error e) {
            try {
                connection.rollback();
            } catch (final SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
        return cancelled;
    }

    /** 一批裡還沒領的編號（批次代號寫完整的，或至少前 8 碼）。 */
    public List<Long> pendingInBatch(final Connection connection, final String batchId) throws SQLException {
        final List<Long> ids = new ArrayList<>();
        final String key = batchId == null ? "" : batchId.trim().toLowerCase(Locale.ROOT);
        if (key.length() < 8) {
            return ids;
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM admin_deliveries WHERE batch_id LIKE ? ESCAPE '!' "
                + "AND claimed_at IS NULL AND cancelled_at IS NULL ORDER BY id")) {
            select.setString(1, like(key) + "%");
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
        }
        return ids;
    }

    /** 這些編號屬於哪些帳號（取消前檢查用）。 */
    public Map<Long, UUID> owners(final Connection connection, final Collection<Long> ids) throws SQLException {
        final Map<Long, UUID> owners = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT account_uuid FROM admin_deliveries WHERE id = ?")) {
            for (final long id : ids) {
                select.setLong(1, id);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        owners.put(id, UUID.fromString(result.getString(1)));
                    }
                }
            }
        }
        return owners;
    }

    /**
     * 管理網頁的清單（新的先）。
     *
     * @param status pending（還沒領）、claimed（領完）、cancelled（取消）、其他 = 全部
     * @param query  玩家名稱的一部分或批次代號（空的 = 不篩）
     */
    public List<Delivery> list(final Connection connection, final String status, final String query, final int limit) throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT * FROM admin_deliveries WHERE 1 = 1");
        switch (status == null ? "" : status) {
            case "pending" -> sql.append(" AND claimed_at IS NULL AND cancelled_at IS NULL");
            case "claimed" -> sql.append(" AND claimed_at IS NOT NULL");
            case "cancelled" -> sql.append(" AND cancelled_at IS NOT NULL");
            default -> {
            }
        }
        final String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            sql.append(" AND (LOWER(player_name) LIKE ? ESCAPE '!' OR batch_id LIKE ? ESCAPE '!')");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (!q.isEmpty()) {
                select.setString(index++, "%" + like(q.toLowerCase(Locale.ROOT)) + "%");
                select.setString(index++, like(q.toLowerCase(Locale.ROOT)) + "%"); // 批次代號：前 8 碼就找得到
            }
            select.setInt(index, limit);
            return read(select);
        }
    }

    private static List<Delivery> read(final PreparedStatement select) throws SQLException {
        final List<Delivery> rows = new ArrayList<>();
        try (ResultSet result = select.executeQuery()) {
            while (result.next()) {
                rows.add(new Delivery(result.getLong("id"), result.getString("batch_id"), UUID.fromString(result.getString("account_uuid")),
                        result.getString("player_name"), result.getString("kind"), result.getString("ref"), result.getString("rarity"),
                        result.getInt("item_level"), result.getLong("amount"), result.getLong("original"), result.getString("label"),
                        result.getString("note"), result.getString("created_by"), instant(result.getTimestamp("created_at")),
                        instant(result.getTimestamp("claimed_at")), result.getString("claimed_by"),
                        instant(result.getTimestamp("cancelled_at")), result.getString("cancelled_by"), result.getString("affixes")));
            }
        }
        return rows;
    }

    // ---- 找人 ----

    /** 照名字找帳號：先找帳號名稱（玩家 ID），再找角色名稱（刪掉的不算）；都不分大小寫。找不到回傳 null。 */
    public Account resolve(final Connection connection, final String name) throws SQLException {
        final String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT uuid, name FROM accounts WHERE LOWER(name) = ? "
                + "ORDER BY last_seen DESC LIMIT 1")) {
            select.setString(1, key);
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    return new Account(UUID.fromString(result.getString(1)), result.getString(2));
                }
            }
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT a.uuid, a.name FROM characters c JOIN accounts a ON a.uuid = c.account_uuid "
                + "WHERE LOWER(c.name) = ? AND c.deleted_at IS NULL ORDER BY c.id LIMIT 1")) {
            select.setString(1, key);
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    return new Account(UUID.fromString(result.getString(1)), result.getString(2));
                }
            }
        }
        return null;
    }

    /** 這個 UUID 的帳號（沒有回傳 null）。 */
    public Account account(final Connection connection, final UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT uuid, name FROM accounts WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? new Account(UUID.fromString(result.getString(1)), result.getString(2)) : null;
            }
        }
    }

    /** 所有帳號（「發給所有帳號」用），最多 limit 個。 */
    public List<Account> allAccounts(final Connection connection, final int limit) throws SQLException {
        final List<Account> accounts = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT uuid, name FROM accounts ORDER BY created_at LIMIT ?")) {
            select.setInt(1, limit);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    accounts.add(new Account(UUID.fromString(result.getString(1)), result.getString(2)));
                }
            }
        }
        return accounts;
    }

    /** 有幾個帳號。 */
    public int countAccounts(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    /** 名字裡有這段字的帳號（帳號名稱或角色名稱），最近上線的先；每個帳號附上角色（「名稱 Lv.等級」）。 */
    public List<Found> search(final Connection connection, final String query, final int limit) throws SQLException {
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final Map<UUID, Found> found = new LinkedHashMap<>();
        if (q.isEmpty()) {
            return List.of();
        }
        final String pattern = "%" + like(q) + "%";
        try (PreparedStatement select = connection.prepareStatement("SELECT DISTINCT a.uuid, a.name, a.last_seen FROM accounts a "
                + "LEFT JOIN characters c ON c.account_uuid = a.uuid AND c.deleted_at IS NULL "
                + "WHERE LOWER(a.name) LIKE ? ESCAPE '!' OR LOWER(c.name) LIKE ? ESCAPE '!' ORDER BY a.last_seen DESC LIMIT ?")) {
            select.setString(1, pattern);
            select.setString(2, pattern);
            select.setInt(3, limit);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    final UUID uuid = UUID.fromString(result.getString(1));
                    found.put(uuid, new Found(uuid, result.getString(2), instant(result.getTimestamp(3)), new ArrayList<>()));
                }
            }
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT name, char_level FROM characters WHERE account_uuid = ? "
                + "AND deleted_at IS NULL ORDER BY slot")) {
            for (final Found account : found.values()) {
                select.setString(1, account.uuid().toString());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        account.characters().add(result.getString(1) + " Lv." + result.getInt(2));
                    }
                }
            }
        }
        final List<Found> list = new ArrayList<>();
        for (final Found account : found.values()) {
            list.add(new Found(account.uuid(), account.name(), account.lastSeen(), List.copyOf(account.characters())));
        }
        return list;
    }

    // ---- 操作紀錄 ----

    public void log(final Connection connection, final String admin, final String action, final String detail) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO admin_log (created_at, admin_name, action, detail) VALUES (?, ?, ?, ?)")) {
            insert.setTimestamp(1, Timestamp.from(Instant.now()));
            insert.setString(2, clip(admin, NAME_MAX));
            insert.setString(3, clip(action, 32));
            insert.setString(4, clip(detail, DETAIL_MAX));
            insert.executeUpdate();
        }
    }

    /** 最近的操作（新的先）。 */
    public List<LogEntry> log(final Connection connection, final int limit) throws SQLException {
        final List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT created_at, admin_name, action, detail FROM admin_log "
                + "ORDER BY id DESC LIMIT ?")) {
            select.setInt(1, limit);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    entries.add(new LogEntry(instant(result.getTimestamp(1)), result.getString(2), result.getString(3), result.getString(4)));
                }
            }
        }
        return entries;
    }

    // ---- 工具 ----

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** LIKE 的萬用字元當成一般的字（跳脫字元是 !）。 */
    static String like(final String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    static String clip(final String text, final int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        final int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max; // 不要切在表情符號的中間
        return text.substring(0, end);
    }
}
