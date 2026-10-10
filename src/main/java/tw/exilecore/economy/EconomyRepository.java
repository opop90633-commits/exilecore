package tw.exilecore.economy;

import tw.exilecore.character.CurrencyPouch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 經濟報表的資料表（純 JDBC，不依賴 Bukkit）：
 * <ul>
 *   <li>economy_flow：每天、每種通貨（或金幣）、每個來源的進出。正的是進到玩家手上（產出），負的是離開遊戲（消耗）。</li>
 *   <li>economy_stock：每天的全服存量（通貨袋＋倉庫通貨頁＋市集待領＋交易所掛單；金幣是所有帳號加起來）。</li>
 *   <li>economy_income：每天、每個帳號：殺了幾隻有份的怪、拿到多少金幣、通貨折合多少金幣（找機器人、複製用）。</li>
 *   <li>exchange_trades：通貨交易所的每一筆成交與買賣雙方的帳號（算參考價值用）。</li>
 *   <li>currency_values：每天算出來的參考價值。</li>
 * </ul>
 * 日期是伺服器時區的 "2026-10-02" 這種字串（字串比大小就是日期先後）。
 */
public final class EconomyRepository {

    /** 金幣在 kind 欄位的寫法（其他是通貨代號）。 */
    public static final String GOLD = "gold";

    /** 一筆進出：amount 正的是產出（進到玩家手上），負的是消耗（離開遊戲）。 */
    public record Flow(String day, String kind, String source, long amount) {
    }

    /** 一個帳號一天的收入（要加上去的量）。 */
    public record Income(String day, UUID account, String name, int kills, long gold, long currencyValue) {
    }

    /**
     * 交易所的一筆成交：吃單的人（taker）付了 giveAmount 個 give 給掛單的人（maker），拿到 wantAmount 個 want。
     * maker、taker 是帳號（不知道時是 null）：同樣兩個帳號之間的成交在參考價值裡只算一票。
     */
    public record Trade(Instant at, String give, int giveAmount, String want, int wantAmount, UUID maker, UUID taker) {
    }

    /** 某一天某種東西的數字（存量、參考價值）。 */
    public record DayValue(String day, String kind, long amount) {
    }

    /** 某一天算出來的參考價值（pairs：每種通貨有幾組不同的買賣雙方成交過）。 */
    public record ValueSnapshot(String day, Map<String, Long> values, Map<String, Integer> pairs) {
        public static final ValueSnapshot EMPTY = new ValueSnapshot(null, Map.of(), Map.of());
    }

    /** 一個帳號一段時間加起來的收入。 */
    public record Earner(UUID account, String name, int days, long kills, long gold, long currencyValue) {
        /** 金幣＋通貨折合的金幣。 */
        public long total() {
            return gold + currencyValue;
        }
    }

    // ---- 寫入 ----

    /** 進出與收入一起寫（同一個交易：全部成功或全部不算，失敗時呼叫端可以整批重來）。 */
    public void addAll(final Connection connection, final Collection<Flow> flows, final Collection<Income> incomes) throws SQLException {
        inTransaction(connection, () -> {
            addFlows(connection, flows);
            addIncome(connection, incomes);
        });
    }

    /** 把進出加到每天的總數上（同一個交易：全部成功或全部不算）。 */
    public void addFlows(final Connection connection, final Collection<Flow> flows) throws SQLException {
        if (flows.isEmpty()) {
            return;
        }
        inTransaction(connection, () -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE economy_flow SET amount = amount + ? WHERE stat_day = ? AND kind = ? AND source = ?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO economy_flow (stat_day, kind, source, amount) VALUES (?, ?, ?, ?)")) {
                for (final Flow flow : flows) {
                    if (flow.amount() == 0) {
                        continue;
                    }
                    final String kind = cut(flow.kind(), 32);
                    final String source = cut(flow.source(), 24);
                    update.setLong(1, flow.amount());
                    update.setString(2, flow.day());
                    update.setString(3, kind);
                    update.setString(4, source);
                    if (update.executeUpdate() == 0) {
                        insert.setString(1, flow.day());
                        insert.setString(2, kind);
                        insert.setString(3, source);
                        insert.setLong(4, flow.amount());
                        insert.executeUpdate();
                    }
                }
            }
        });
    }

    /** 把收入加到每個帳號每天的總數上（同一個交易）。 */
    public void addIncome(final Connection connection, final Collection<Income> incomes) throws SQLException {
        if (incomes.isEmpty()) {
            return;
        }
        inTransaction(connection, () -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE economy_income SET kills = kills + ?, gold = gold + ?, currency_value = currency_value + ?, player_name = ? "
                            + "WHERE stat_day = ? AND account_uuid = ?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO economy_income (stat_day, account_uuid, player_name, kills, gold, currency_value) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (final Income income : incomes) {
                    final String name = cut(income.name(), 16);
                    update.setInt(1, income.kills());
                    update.setLong(2, income.gold());
                    update.setLong(3, income.currencyValue());
                    update.setString(4, name);
                    update.setString(5, income.day());
                    update.setString(6, income.account().toString());
                    if (update.executeUpdate() == 0) {
                        insert.setString(1, income.day());
                        insert.setString(2, income.account().toString());
                        insert.setString(3, name);
                        insert.setInt(4, income.kills());
                        insert.setLong(5, income.gold());
                        insert.setLong(6, income.currencyValue());
                        insert.executeUpdate();
                    }
                }
            }
        });
    }

    /** 記下交易所的成交（跟成交那筆存檔在同一個交易裡呼叫）。 */
    public void insertTrades(final Connection connection, final Collection<Trade> trades) throws SQLException {
        if (trades.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO exchange_trades (traded_at, give_currency, give_amount, want_currency, want_amount, maker_account, taker_account) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (final Trade trade : trades) {
                statement.setTimestamp(1, Timestamp.from(trade.at()));
                statement.setString(2, cut(trade.give(), 32));
                statement.setInt(3, trade.giveAmount());
                statement.setString(4, cut(trade.want(), 32));
                statement.setInt(5, trade.wantAmount());
                statement.setString(6, trade.maker() == null ? null : trade.maker().toString());
                statement.setString(7, trade.taker() == null ? null : trade.taker().toString());
                statement.executeUpdate();
            }
        }
    }

    /** 存一天的參考價值（同一天重算時整天換掉）；pairs：每種通貨有幾組不同的買賣雙方成交過。 */
    public void saveValues(final Connection connection, final String day, final Map<String, Long> values,
                           final Map<String, Integer> pairs) throws SQLException {
        inTransaction(connection, () -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM currency_values WHERE stat_day = ?")) {
                delete.setString(1, day);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO currency_values (stat_day, currency_id, ref_value, pairs) VALUES (?, ?, ?, ?)")) {
                for (final Map.Entry<String, Long> entry : values.entrySet()) {
                    insert.setString(1, day);
                    insert.setString(2, cut(entry.getKey(), 32));
                    insert.setLong(3, entry.getValue());
                    insert.setInt(4, pairs.getOrDefault(entry.getKey(), 0));
                    insert.executeUpdate();
                }
            }
        });
    }

    /** 存一天的全服存量（同一天重算時整天換掉）。 */
    public void saveStock(final Connection connection, final String day, final Map<String, Long> stock) throws SQLException {
        inTransaction(connection, () -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM economy_stock WHERE stat_day = ?")) {
                delete.setString(1, day);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO economy_stock (stat_day, kind, amount) VALUES (?, ?, ?)")) {
                for (final Map.Entry<String, Long> entry : stock.entrySet()) {
                    insert.setString(1, day);
                    insert.setString(2, cut(entry.getKey(), 32));
                    insert.setLong(3, entry.getValue());
                    insert.executeUpdate();
                }
            }
        });
    }

    // ---- 讀 ----

    /** 某天以後（含）的所有進出。 */
    public List<Flow> flowsSince(final Connection connection, final String fromDay) throws SQLException {
        final List<Flow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT stat_day, kind, source, amount FROM economy_flow WHERE stat_day >= ? ORDER BY stat_day")) {
            statement.setString(1, fromDay);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Flow(result.getString(1), result.getString(2), result.getString(3), result.getLong(4)));
                }
            }
        }
        return rows;
    }

    /** 某天以後（含）每天的存量。 */
    public List<DayValue> stockSince(final Connection connection, final String fromDay) throws SQLException {
        return dayValues(connection, "SELECT stat_day, kind, amount FROM economy_stock WHERE stat_day >= ? ORDER BY stat_day", fromDay);
    }

    /** 某天以後（含）每天的參考價值。 */
    public List<DayValue> valuesSince(final Connection connection, final String fromDay) throws SQLException {
        return dayValues(connection,
                "SELECT stat_day, currency_id, ref_value FROM currency_values WHERE stat_day >= ? ORDER BY stat_day", fromDay);
    }

    private static List<DayValue> dayValues(final Connection connection, final String sql, final String fromDay) throws SQLException {
        final List<DayValue> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fromDay);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new DayValue(result.getString(1), result.getString(2), result.getLong(3)));
                }
            }
        }
        return rows;
    }

    /** 最近一次算出來的參考價值；還沒算過回傳 {@link ValueSnapshot#EMPTY}。 */
    public ValueSnapshot latestValues(final Connection connection) throws SQLException {
        return valuesOf(connection, "SELECT MAX(stat_day) FROM currency_values", null);
    }

    /** day 之前（不含）最近一次算出來的參考價值（同一天重算時從這裡算起，一天只走一步）；沒有回傳 {@link ValueSnapshot#EMPTY}。 */
    public ValueSnapshot valuesBefore(final Connection connection, final String day) throws SQLException {
        return valuesOf(connection, "SELECT MAX(stat_day) FROM currency_values WHERE stat_day < ?", day);
    }

    private static ValueSnapshot valuesOf(final Connection connection, final String daySql, final String parameter) throws SQLException {
        String day = null;
        try (PreparedStatement statement = connection.prepareStatement(daySql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    day = result.getString(1);
                }
            }
        }
        if (day == null) {
            return ValueSnapshot.EMPTY;
        }
        final Map<String, Long> values = new LinkedHashMap<>();
        final Map<String, Integer> pairs = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT currency_id, ref_value, pairs FROM currency_values WHERE stat_day = ? ORDER BY currency_id")) {
            statement.setString(1, day);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.put(result.getString(1), result.getLong(2));
                    pairs.put(result.getString(1), result.getInt(3));
                }
            }
        }
        return new ValueSnapshot(day, values, pairs);
    }

    /** 某個時間以後的成交（舊的在前）。 */
    public List<Trade> tradesSince(final Connection connection, final Instant since) throws SQLException {
        final List<Trade> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT traded_at, give_currency, give_amount, want_currency, want_amount, maker_account, taker_account FROM exchange_trades "
                        + "WHERE traded_at >= ? ORDER BY traded_at, id")) {
            statement.setTimestamp(1, Timestamp.from(since));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Trade(result.getTimestamp(1).toInstant(), result.getString(2), result.getInt(3),
                            result.getString(4), result.getInt(5), uuid(result.getString(6)), uuid(result.getString(7))));
                }
            }
        }
        return rows;
    }

    /** 一個角色通貨袋裡的通貨（刪角色時記成消耗用）；沒有回傳空的。 */
    public Map<String, Integer> pouchOf(final Connection connection, final long characterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT bag_currency FROM character_inventory WHERE character_id = ?")) {
            statement.setLong(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getString(1) != null) {
                    return new LinkedHashMap<>(CurrencyPouch.decode(result.getString(1)).all());
                }
            }
        }
        return Map.of();
    }

    /** 某天以後（含）每個帳號的收入加總（金幣＋通貨折合金幣多的在前）。 */
    public List<Earner> earnersSince(final Connection connection, final String fromDay) throws SQLException {
        final List<Earner> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_uuid, MAX(player_name), COUNT(*), SUM(kills), SUM(gold), SUM(currency_value) FROM economy_income "
                        + "WHERE stat_day >= ? GROUP BY account_uuid")) {
            statement.setString(1, fromDay);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final UUID account;
                    try {
                        account = UUID.fromString(result.getString(1));
                    } catch (final IllegalArgumentException e) {
                        continue;
                    }
                    rows.add(new Earner(account, result.getString(2), result.getInt(3), result.getLong(4), result.getLong(5),
                            result.getLong(6)));
                }
            }
        }
        rows.sort((a, b) -> Long.compare(b.total(), a.total()));
        return rows;
    }

    /** 某天以後（含）每天有殺怪的帳號數。 */
    public Map<String, Integer> activeAccountsSince(final Connection connection, final String fromDay) throws SQLException {
        final Map<String, Integer> rows = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT stat_day, COUNT(*) FROM economy_income WHERE stat_day >= ? AND kills > 0 GROUP BY stat_day")) {
            statement.setString(1, fromDay);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getString(1), result.getInt(2));
                }
            }
        }
        return rows;
    }

    /**
     * 現在全服的存量：通貨＝所有角色的通貨袋＋倉庫通貨頁＋市集待領的收入＋交易所掛單裡還沒成交的；金幣＝所有帳號加起來。
     * 背包格子、倉庫物品頁裡一顆一顆的通貨不算（存在物品資料裡，要解開才數得到；撿到的通貨會先進通貨袋）。
     * 在線玩家以最後一次存檔為準。
     */
    public Map<String, Long> computeStock(final Connection connection) throws SQLException {
        final Map<String, Long> stock = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ci.bag_currency FROM character_inventory ci JOIN characters c ON c.id = ci.character_id "
                        + "WHERE c.deleted_at IS NULL AND ci.bag_currency IS NOT NULL");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                for (final Map.Entry<String, Integer> entry : CurrencyPouch.decode(result.getString(1)).all().entrySet()) {
                    stock.merge(entry.getKey(), (long) entry.getValue(), Long::sum);
                }
            }
        }
        sumInto(connection, "SELECT currency_id, SUM(amount) FROM stash_currency GROUP BY currency_id", stock);
        sumInto(connection, "SELECT currency_id, SUM(amount) FROM market_proceeds GROUP BY currency_id", stock);
        sumInto(connection, "SELECT o.give_currency, SUM(o.give_amount - COALESCE(f.taken, 0)) FROM exchange_orders o "
                + "LEFT JOIN (SELECT order_id, SUM(give_taken) AS taken FROM exchange_fills GROUP BY order_id) f ON f.order_id = o.order_id "
                + "GROUP BY o.give_currency", stock);
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(gold), 0) FROM accounts");
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                stock.put(GOLD, result.getLong(1));
            }
        }
        stock.values().removeIf(amount -> amount == null);
        return stock;
    }

    private static void sumInto(final Connection connection, final String sql, final Map<String, Long> stock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                final String id = result.getString(1);
                final long amount = result.getLong(2);
                if (id != null && amount > 0) {
                    stock.merge(id, amount, Long::sum);
                }
            }
        }
    }

    // ---- 小工具 ----

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException;
    }

    /** 包在一個交易裡（呼叫端已經在交易裡就直接做，交給外面的交易）。 */
    private static void inTransaction(final Connection connection, final Work work) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        if (!autoCommit) {
            work.run();
            return;
        }
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (final SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static UUID uuid(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static String cut(final String text, final int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
