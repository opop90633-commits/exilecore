package tw.exilecore.stash;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 倉庫的資料表（stash_pages、stash_currency、stash_tabs）。純 JDBC，不依賴 Bukkit，可以用記憶體 H2 測試。
 * 存檔不自己開交易：由角色存檔的交易一起提交（背包與倉庫同時成功或同時失敗，物品不會複製或消失）。
 */
public final class StashRepository {

    /** 一頁的頁籤：名稱（空字串 = 預設）、分類（StashCategory 的名稱）。 */
    public record Tab(String name, String category) {
    }

    /**
     * 倉庫的資料快照。
     *
     * @param pages    頁碼 → 序列化的物品（沒存過的頁不在裡面）
     * @param currency 通貨代號 → 數量
     * @param tabs     頁碼 → 頁籤（預設的頁籤不在裡面）
     */
    public record Snapshot(Map<Integer, byte[]> pages, Map<String, Integer> currency, Map<Integer, Tab> tabs) {
        public Snapshot {
            pages = Map.copyOf(pages);
            currency = Map.copyOf(currency);
            tabs = Map.copyOf(tabs);
        }

        public Snapshot(final Map<Integer, byte[]> pages, final Map<String, Integer> currency) {
            this(pages, currency, Map.of());
        }
    }

    public Snapshot load(final Connection connection, final UUID account) throws SQLException {
        final Map<Integer, byte[]> pages = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT page, contents FROM stash_pages WHERE account_uuid = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final byte[] contents = result.getBytes(2);
                    if (contents != null) {
                        pages.put(result.getInt(1), contents);
                    }
                }
            }
        }
        final Map<String, Integer> currency = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT currency_id, amount FROM stash_currency WHERE account_uuid = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    currency.put(result.getString(1), result.getInt(2));
                }
            }
        }
        final Map<Integer, Tab> tabs = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT page, name, category FROM stash_tabs WHERE account_uuid = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tabs.put(result.getInt(1), new Tab(result.getString(2), result.getString(3)));
                }
            }
        }
        return new Snapshot(pages, currency, tabs);
    }

    /** 寫入整個倉庫（每一頁覆蓋，通貨整批換掉）。在呼叫端的交易裡執行。 */
    public void save(final Connection connection, final UUID account, final Snapshot snapshot) throws SQLException {
        final Timestamp now = Timestamp.from(Instant.now());
        for (final Map.Entry<Integer, byte[]> page : snapshot.pages().entrySet()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE stash_pages SET contents = ?, updated_at = ? WHERE account_uuid = ? AND page = ?")) {
                update.setBytes(1, page.getValue());
                update.setTimestamp(2, now);
                update.setString(3, account.toString());
                update.setInt(4, page.getKey());
                if (update.executeUpdate() > 0) {
                    continue;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO stash_pages (account_uuid, page, contents, updated_at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, account.toString());
                insert.setInt(2, page.getKey());
                insert.setBytes(3, page.getValue());
                insert.setTimestamp(4, now);
                insert.executeUpdate();
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM stash_currency WHERE account_uuid = ?")) {
            delete.setString(1, account.toString());
            delete.executeUpdate();
        }
        boolean any = false;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stash_currency (account_uuid, currency_id, amount) VALUES (?, ?, ?)")) {
            for (final Map.Entry<String, Integer> entry : snapshot.currency().entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                insert.setString(1, account.toString());
                insert.setString(2, entry.getKey());
                insert.setInt(3, entry.getValue());
                insert.addBatch();
                any = true;
            }
            if (any) {
                insert.executeBatch();
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM stash_tabs WHERE account_uuid = ?")) {
            delete.setString(1, account.toString());
            delete.executeUpdate();
        }
        if (!snapshot.tabs().isEmpty()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO stash_tabs (account_uuid, page, name, category) VALUES (?, ?, ?, ?)")) {
                for (final Map.Entry<Integer, Tab> tab : snapshot.tabs().entrySet()) {
                    insert.setString(1, account.toString());
                    insert.setInt(2, tab.getKey());
                    insert.setString(3, tab.getValue().name() == null ? "" : tab.getValue().name());
                    insert.setString(4, tab.getValue().category() == null ? "ALL" : tab.getValue().category());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }
}
