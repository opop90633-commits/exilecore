package tw.exilecore.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * trade_log：東西從誰到誰（面對面交易、市集成交、通貨交易所成交、商人與賭博）。
 * 查作弊與複製靠它：同一個物品 UUID 的來龍去脈。純 JDBC，不依賴 Bukkit。
 */
public final class TradeLogRepository {

    /** kind：面對面交易。 */
    public static final String TRADE = "trade";
    /** kind：市集成交（物品給買家、通貨給賣家）。 */
    public static final String MARKET = "market";
    /** kind：市集上架（物品進商店頁）、下架（回到玩家）。 */
    public static final String LIST = "list";
    public static final String UNLIST = "unlist";
    /** kind：通貨交易所成交。 */
    public static final String EXCHANGE = "exchange";
    /** kind：商人與賭博（從 NPC 買到）。 */
    public static final String VENDOR = "vendor";
    /** kind：丟進垃圾桶刪掉（到 null、「垃圾桶」）。 */
    public static final String TRASH = "trash";
    /** kind：功勳兌換（從軍需官換到）。 */
    public static final String MERITS = "merits";
    /** kind：管理員發放（管理網頁；從誰 = 管理員）。 */
    public static final String ADMIN = "admin";
    /** kind：傳奇三換一（拆解台；放進去的從玩家到「三換一」，換到的從「三換一」到玩家）。 */
    public static final String RECIPE = "recipe";

    private static final int DESC_MAX = 255;
    private static final int NAME_MAX = 16;
    /** trade_id 的長度上限（VARCHAR(36)：一個 UUID）。 */
    public static final int TRADE_ID_MAX = 36;

    /** 新的一次操作的代號（trade_id）：就是一個 UUID，不能加前綴（超過 36 個字寫不進去，整筆存檔會失敗）。 */
    public static String newTradeId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 一筆紀錄。
     *
     * @param tradeId     同一次交易的代號（一次交易好幾筆）
     * @param kind        種類（上面的常數）
     * @param itemUuid    物品 UUID（裝備、地圖；通貨沒有）
     * @param itemDesc    給人看的說明（名稱、數量）
     * @param amount      數量
     * @param fromAccount 從誰（NPC 是 null）
     * @param fromName    從誰的名字
     * @param toAccount   到誰（NPC、商店頁是 null）
     * @param toName      到誰的名字
     */
    public record Entry(String tradeId, String kind, String itemUuid, String itemDesc, int amount,
                        UUID fromAccount, String fromName, UUID toAccount, String toName) {
    }

    /** 查詢結果（多了時間）。 */
    public record Row(Instant at, Entry entry) {
    }

    /** 寫一批（在呼叫端的交易裡）。同一個 tradeId 先刪再寫，所以重試也不會重複。 */
    public void insert(final Connection connection, final List<Entry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        final java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (final Entry entry : entries) {
            ids.add(entry.tradeId());
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM trade_log WHERE trade_id = ?")) {
            for (final String id : ids) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
        }
        final Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_log (trade_id, kind, item_uuid, item_desc, amount, from_account, from_name, to_account, to_name, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (final Entry entry : entries) {
                insert.setString(1, entry.tradeId());
                insert.setString(2, entry.kind());
                insert.setString(3, entry.itemUuid());
                insert.setString(4, cut(entry.itemDesc(), DESC_MAX));
                insert.setInt(5, entry.amount());
                insert.setString(6, entry.fromAccount() == null ? null : entry.fromAccount().toString());
                insert.setString(7, cut(entry.fromName(), NAME_MAX));
                insert.setString(8, entry.toAccount() == null ? null : entry.toAccount().toString());
                insert.setString(9, cut(entry.toName(), NAME_MAX));
                insert.setTimestamp(10, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** 一個物品的所有紀錄（舊的在前）。 */
    public List<Row> byItem(final Connection connection, final String itemUuid, final int limit) throws SQLException {
        return query(connection, "SELECT * FROM trade_log WHERE item_uuid = ? ORDER BY id LIMIT ?", itemUuid, limit);
    }

    /** 跟這個名字有關的最近紀錄（新的在前）。 */
    public List<Row> byName(final Connection connection, final String name, final int limit) throws SQLException {
        final List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM trade_log WHERE from_name = ? OR to_name = ? ORDER BY id DESC LIMIT ?")) {
            statement.setString(1, name);
            statement.setString(2, name);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        }
        return rows;
    }

    private List<Row> query(final Connection connection, final String sql, final String key, final int limit) throws SQLException {
        final List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        }
        return rows;
    }

    private static Row read(final ResultSet result) throws SQLException {
        final String from = result.getString("from_account");
        final String to = result.getString("to_account");
        final Timestamp at = result.getTimestamp("created_at");
        return new Row(at == null ? Instant.EPOCH : at.toInstant(), new Entry(result.getString("trade_id"), result.getString("kind"),
                result.getString("item_uuid"), result.getString("item_desc"), result.getInt("amount"),
                from == null ? null : UUID.fromString(from), result.getString("from_name"),
                to == null ? null : UUID.fromString(to), result.getString("to_name")));
    }

    static String cut(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
