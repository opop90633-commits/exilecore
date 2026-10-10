package tw.exilecore.bounty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 帳號共用的資料（accounts.merits 與 account_progress）。純 JDBC，不依賴 Bukkit，可以用記憶體 H2 測試。
 * 寫入不自己開交易：完成委託、兌換東西時跟角色存檔在同一個交易裡（功勳與東西同時成功或同時失敗）。
 */
public final class AccountRepository {

    /**
     * 一個帳號的資料。
     *
     * @param merits   功勳
     * @param progress 帳號進度（每日委託、每週回響、兌換紀錄、稱號）
     */
    public record Data(long merits, Map<String, String> progress) {
        public Data {
            progress = Map.copyOf(progress);
        }
    }

    /** 讀一個帳號（帳號還沒建立的話功勳是 0、沒有進度）。 */
    public Data load(final Connection connection, final UUID account) throws SQLException {
        long merits = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT merits FROM accounts WHERE uuid = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    merits = result.getLong(1);
                }
            }
        }
        final Map<String, String> progress = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT progress_key, progress_value FROM account_progress WHERE account_uuid = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    progress.put(result.getString(1), result.getString(2));
                }
            }
        }
        return new Data(merits, progress);
    }

    public void setMerits(final Connection connection, final UUID account, final long merits) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE accounts SET merits = ? WHERE uuid = ?")) {
            statement.setLong(1, Math.max(0, merits));
            statement.setString(2, account.toString());
            statement.executeUpdate();
        }
    }

    /** 寫一筆帳號進度（value 為 null = 刪掉）。 */
    public void setProgress(final Connection connection, final UUID account, final String key, final String value) throws SQLException {
        if (value == null) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM account_progress WHERE account_uuid = ? AND progress_key = ?")) {
                delete.setString(1, account.toString());
                delete.setString(2, key);
                delete.executeUpdate();
            }
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE account_progress SET progress_value = ? WHERE account_uuid = ? AND progress_key = ?")) {
            update.setString(1, value);
            update.setString(2, account.toString());
            update.setString(3, key);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO account_progress (account_uuid, progress_key, progress_value) VALUES (?, ?, ?)")) {
            insert.setString(1, account.toString());
            insert.setString(2, key);
            insert.setString(3, value);
            insert.executeUpdate();
        }
    }

    /** 一批帳號進度（同一個交易裡，照順序）。 */
    public void setProgress(final Connection connection, final UUID account, final Map<String, String> values) throws SQLException {
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            setProgress(connection, account, entry.getKey(), entry.getValue());
        }
    }
}
