package tw.exilecore.market;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 市集的資料表：上架（market_listings）、收入（market_proceeds）、通貨交易所的掛單與成交（exchange_orders、exchange_fills）。
 * 純 JDBC，不依賴 Bukkit；寫入都不自己開交易，由 CharacterService.commit 跟玩家的存檔一起提交。
 * <p>
 * 每一筆操作只新增或刪除「自己的」列（收入一筆一列、成交一筆一列），不改別人也會改的列，
 * 所以兩個操作的寫入不會互相蓋掉。
 */
public final class MarketRepository {

    /** 一件上架的東西。 */
    public record ListingRow(String id, UUID account, String sellerName, byte[] item, String itemUuid, String searchText,
                             String priceCurrency, int priceAmount, Instant listedAt) {
    }

    /** 一筆等著領的收入。 */
    public record ProceedRow(String id, UUID account, String currencyId, int amount, String source, String note, Instant createdAt) {
    }

    /**
     * 一張掛單。
     *
     * @param taken 已經成交掉的數量（掛出去的通貨）
     */
    public record OrderRow(String id, UUID account, String playerName, String give, int giveAmount, String want, int wantAmount,
                           Instant createdAt, int taken) {
    }

    // ---- 上架 ----

    public List<ListingRow> loadListings(final Connection connection) throws SQLException {
        final List<ListingRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM market_listings ORDER BY listed_at");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new ListingRow(result.getString("listing_id"), UUID.fromString(result.getString("account_uuid")),
                        result.getString("seller_name"), result.getBytes("item"), result.getString("item_uuid"),
                        result.getString("search_text"), result.getString("price_currency"), result.getInt("price_amount"),
                        instant(result.getTimestamp("listed_at"))));
            }
        }
        return rows;
    }

    public void insertListing(final Connection connection, final ListingRow row) throws SQLException {
        deleteListing(connection, row.id());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO market_listings (listing_id, account_uuid, seller_name, item, item_uuid, search_text, price_currency, "
                        + "price_amount, listed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, row.id());
            statement.setString(2, row.account().toString());
            statement.setString(3, cut(row.sellerName(), 16));
            statement.setBytes(4, row.item());
            statement.setString(5, row.itemUuid());
            statement.setString(6, row.searchText());
            statement.setString(7, row.priceCurrency());
            statement.setInt(8, row.priceAmount());
            statement.setTimestamp(9, Timestamp.from(row.listedAt()));
            statement.executeUpdate();
        }
    }

    /** 刪掉一件上架（買走或下架）；回傳刪了幾列。 */
    public int deleteListing(final Connection connection, final String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM market_listings WHERE listing_id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate();
        }
    }

    /** 2026-10-26：上架中的物品照新資料補正（詞綴換算、重畫）後寫回。 */
    public void updateItem(final Connection connection, final String id, final byte[] item, final String searchText) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE market_listings SET item = ?, search_text = ? WHERE listing_id = ?")) {
            statement.setBytes(1, item);
            statement.setString(2, searchText);
            statement.setString(3, id);
            statement.executeUpdate();
        }
    }

    public void updatePrice(final Connection connection, final String id, final String currency, final int amount,
                            final Instant listedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE market_listings SET price_currency = ?, price_amount = ?, listed_at = ? WHERE listing_id = ?")) {
            statement.setString(1, currency);
            statement.setInt(2, amount);
            statement.setTimestamp(3, Timestamp.from(listedAt));
            statement.setString(4, id);
            statement.executeUpdate();
        }
    }

    // ---- 收入 ----

    public List<ProceedRow> loadProceeds(final Connection connection) throws SQLException {
        final List<ProceedRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM market_proceeds ORDER BY created_at");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new ProceedRow(result.getString("proceed_id"), UUID.fromString(result.getString("account_uuid")),
                        result.getString("currency_id"), result.getInt("amount"), result.getString("source"),
                        result.getString("note"), instant(result.getTimestamp("created_at"))));
            }
        }
        return rows;
    }

    public void insertProceed(final Connection connection, final ProceedRow row) throws SQLException {
        deleteProceeds(connection, List.of(row.id()));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO market_proceeds (proceed_id, account_uuid, currency_id, amount, source, note, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, row.id());
            statement.setString(2, row.account().toString());
            statement.setString(3, row.currencyId());
            statement.setInt(4, row.amount());
            statement.setString(5, row.source());
            statement.setString(6, cut(row.note(), 255));
            statement.setTimestamp(7, Timestamp.from(row.createdAt()));
            statement.executeUpdate();
        }
    }

    public void deleteProceeds(final Connection connection, final Collection<String> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM market_proceeds WHERE proceed_id = ?")) {
            for (final String id : ids) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
        }
    }

    // ---- 通貨交易所 ----

    /** 所有掛單，taken 是已經成交掉的數量（exchange_fills 加總）。 */
    public List<OrderRow> loadOrders(final Connection connection) throws SQLException {
        final Map<String, Integer> taken = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT order_id, SUM(give_taken) FROM exchange_fills GROUP BY order_id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                taken.put(result.getString(1), result.getInt(2));
            }
        }
        final List<OrderRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM exchange_orders ORDER BY created_at");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                final String id = result.getString("order_id");
                rows.add(new OrderRow(id, UUID.fromString(result.getString("account_uuid")), result.getString("player_name"),
                        result.getString("give_currency"), result.getInt("give_amount"), result.getString("want_currency"),
                        result.getInt("want_amount"), instant(result.getTimestamp("created_at")), taken.getOrDefault(id, 0)));
            }
        }
        return rows;
    }

    public void insertOrder(final Connection connection, final OrderRow row) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM exchange_orders WHERE order_id = ?")) {
            delete.setString(1, row.id());
            delete.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO exchange_orders (order_id, account_uuid, player_name, give_currency, give_amount, want_currency, "
                        + "want_amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, row.id());
            statement.setString(2, row.account().toString());
            statement.setString(3, cut(row.playerName(), 16));
            statement.setString(4, row.give());
            statement.setInt(5, row.giveAmount());
            statement.setString(6, row.want());
            statement.setInt(7, row.wantAmount());
            statement.setTimestamp(8, Timestamp.from(row.createdAt()));
            statement.executeUpdate();
        }
    }

    /** 一筆成交：掛單 orderId 被拿走 taken 個（掛出去的通貨）。 */
    public void insertFill(final Connection connection, final String fillId, final String orderId, final int taken) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM exchange_fills WHERE fill_id = ?")) {
            delete.setString(1, fillId);
            delete.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO exchange_fills (fill_id, order_id, give_taken, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, fillId);
            statement.setString(2, orderId);
            statement.setInt(3, taken);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    /** 取消或全部成交的掛單：掛單與它的成交紀錄一起刪掉。 */
    public void deleteOrder(final Connection connection, final String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM exchange_fills WHERE order_id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM exchange_orders WHERE order_id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private static String cut(final String text, final int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
