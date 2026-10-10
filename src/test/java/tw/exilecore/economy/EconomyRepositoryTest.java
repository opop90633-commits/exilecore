package tw.exilecore.economy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.core.db.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 經濟報表的資料表：流水帳累加、收入、成交、參考價值、全服存量。 */
class EconomyRepositoryTest {

    static Database database;
    static final EconomyRepository repository = new EconomyRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_economy_report;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void flowsAccumulatePerDayKindAndSource() throws Exception {
        database.sync(c -> {
            repository.addAll(c, List.of(
                    new EconomyRepository.Flow("2026-10-01", "glimmer_orb", "drop", 10),
                    new EconomyRepository.Flow("2026-10-01", "glimmer_orb", "craft", -4),
                    new EconomyRepository.Flow("2026-10-01", "gold", "drop", 500)), List.of());
            repository.addAll(c, List.of(
                    new EconomyRepository.Flow("2026-10-01", "glimmer_orb", "drop", 5),
                    new EconomyRepository.Flow("2026-10-02", "glimmer_orb", "drop", 7),
                    new EconomyRepository.Flow("2026-10-02", "gold", "gamble", -300)), List.of());
            return null;
        });
        final List<EconomyRepository.Flow> rows = database.sync(c -> repository.flowsSince(c, "2026-10-01"));
        assertEquals(5, rows.size());
        assertTrue(rows.contains(new EconomyRepository.Flow("2026-10-01", "glimmer_orb", "drop", 15)), "同一天同一個來源加起來");
        assertTrue(rows.contains(new EconomyRepository.Flow("2026-10-01", "glimmer_orb", "craft", -4)));
        assertTrue(rows.contains(new EconomyRepository.Flow("2026-10-02", "gold", "gamble", -300)));
        assertEquals(2, database.sync(c -> repository.flowsSince(c, "2026-10-02")).size(), "只拿那天以後的");
    }

    @Test
    void incomeAccumulatesAndEarnersSortByTotal() throws Exception {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        database.sync(c -> {
            repository.addAll(c, List.of(), List.of(
                    new EconomyRepository.Income("2026-09-01", a, "Alice", 10, 1000, 200),
                    new EconomyRepository.Income("2026-09-01", b, "Bob", 3, 100, 0)));
            repository.addAll(c, List.of(), List.of(
                    new EconomyRepository.Income("2026-09-01", a, "Alice2", 5, 500, 100),
                    new EconomyRepository.Income("2026-09-02", b, "Bob", 50, 9000, 4000)));
            return null;
        });
        final List<EconomyRepository.Earner> earners = database.sync(c -> repository.earnersSince(c, "2026-09-01"));
        assertEquals(2, earners.size());
        assertEquals(b, earners.getFirst().account(), "多的在前");
        assertEquals(13_100, earners.getFirst().total());
        assertEquals(2, earners.getFirst().days());
        final EconomyRepository.Earner alice = earners.get(1);
        assertEquals(15, alice.kills());
        assertEquals(1500, alice.gold());
        assertEquals(300, alice.currencyValue());
        assertEquals("Alice2", alice.name(), "名字用最新的");
        final Map<String, Integer> active = database.sync(c -> repository.activeAccountsSince(c, "2026-09-01"));
        assertEquals(2, active.get("2026-09-01"));
        assertEquals(1, active.get("2026-09-02"));
    }

    @Test
    void valuesReplaceTheWholeDayAndLatestWins() throws Exception {
        assertNull(database.sync(repository::latestValues).day(), "還沒算過");
        database.sync(c -> {
            repository.saveValues(c, "2026-08-01", Map.of("bounty_orb", 50L, "zenith_orb", 1500L), Map.of("bounty_orb", 9));
            repository.saveValues(c, "2026-08-02", Map.of("bounty_orb", 55L), Map.of());
            repository.saveValues(c, "2026-08-02", Map.of("bounty_orb", 58L, "zenith_orb", 1500L), Map.of("bounty_orb", 12));
            return null;
        });
        final EconomyRepository.ValueSnapshot latest = database.sync(repository::latestValues);
        assertEquals("2026-08-02", latest.day());
        assertEquals(58, latest.values().get("bounty_orb"), "同一天重算整天換掉");
        assertEquals(12, latest.pairs().get("bounty_orb"));
        final EconomyRepository.ValueSnapshot before = database.sync(c -> repository.valuesBefore(c, "2026-08-02"));
        assertEquals("2026-08-01", before.day(), "前一天的（同一天重算從這裡算起）");
        assertEquals(50, before.values().get("bounty_orb"));
        assertNull(database.sync(c -> repository.valuesBefore(c, "2026-08-01")).day());
        assertEquals(4, database.sync(c -> repository.valuesSince(c, "2026-08-01")).size());
    }

    @Test
    void tradesAreReadBackInOrder() throws Exception {
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final UUID maker = UUID.randomUUID();
        final UUID taker = UUID.randomUUID();
        database.sync(c -> {
            repository.insertTrades(c, List.of(
                    new EconomyRepository.Trade(now.minus(10, ChronoUnit.DAYS), "zenith_orb", 1, "bounty_orb", 20, maker, taker),
                    new EconomyRepository.Trade(now.minus(1, ChronoUnit.DAYS), "zenith_orb", 1, "bounty_orb", 25, maker, taker),
                    new EconomyRepository.Trade(now, "bounty_orb", 30, "zenith_orb", 1, null, null)));
            return null;
        });
        final List<EconomyRepository.Trade> recent = database.sync(c -> repository.tradesSince(c, now.minus(7, ChronoUnit.DAYS)));
        assertEquals(2, recent.size(), "7 天前的不算");
        assertEquals(25, recent.getFirst().wantAmount());
        assertEquals(maker, recent.getFirst().maker());
        assertEquals(taker, recent.getFirst().taker());
        assertEquals("bounty_orb", recent.get(1).give());
        assertNull(recent.get(1).maker(), "不知道是誰的");
    }

    @Test
    void stockCountsPouchesStashProceedsEscrowAndGold() throws Exception {
        final UUID account = UUID.randomUUID();
        database.sync(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO accounts (uuid, name, active_character, created_at, last_seen, gold) VALUES (?, 'Stock', NULL, ?, ?, 777)")) {
                statement.setString(1, account.toString());
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            for (final Object[] row : new Object[][] {{9001L, null}, {9002L, Timestamp.from(Instant.now())}}) {
                try (PreparedStatement statement = c.prepareStatement(
                        "INSERT INTO characters (id, account_uuid, slot, name, class_id, char_level, experience, bonus_points, refund_points, "
                                + "deaths, playtime_seconds, life, mana, energy_shield, last_zone, created_at, updated_at, deleted_at) "
                                + "VALUES (?, ?, 1, 'c', 'ranger', 1, 0, 0, 0, 0, 0, 1, 1, 0, NULL, ?, ?, ?)")) {
                    statement.setLong(1, (Long) row[0]);
                    statement.setString(2, account.toString());
                    statement.setTimestamp(3, Timestamp.from(Instant.now()));
                    statement.setTimestamp(4, Timestamp.from(Instant.now()));
                    statement.setTimestamp(5, (Timestamp) row[1]);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = c.prepareStatement(
                        "INSERT INTO character_inventory (character_id, updated_at, bag_currency) VALUES (?, ?, 'glimmer_orb=12,bounty_orb=3')")) {
                    statement.setLong(1, (Long) row[0]);
                    statement.setTimestamp(2, Timestamp.from(Instant.now()));
                    statement.executeUpdate();
                }
            }
            c.createStatement().executeUpdate("INSERT INTO stash_currency (account_uuid, currency_id, amount) VALUES ('" + account + "', 'glimmer_orb', 100)");
            c.createStatement().executeUpdate("INSERT INTO market_proceeds (proceed_id, account_uuid, currency_id, amount, source, note, created_at) "
                    + "VALUES ('p1', '" + account + "', 'zenith_orb', 2, 'market', '', CURRENT_TIMESTAMP)");
            c.createStatement().executeUpdate("INSERT INTO exchange_orders (order_id, account_uuid, player_name, give_currency, give_amount, "
                    + "want_currency, want_amount, created_at) VALUES ('o1', '" + account + "', 'Stock', 'bounty_orb', 50, 'zenith_orb', 2, CURRENT_TIMESTAMP)");
            c.createStatement().executeUpdate("INSERT INTO exchange_fills (fill_id, order_id, give_taken, created_at) VALUES ('f1', 'o1', 20, CURRENT_TIMESTAMP)");
            return null;
        });
        final Map<String, Long> stock = database.sync(repository::computeStock);
        assertEquals(Map.of("glimmer_orb", 12, "bounty_orb", 3), database.sync(c -> repository.pouchOf(c, 9001L)), "刪角色時記成消耗用");
        assertEquals(Map.of(), database.sync(c -> repository.pouchOf(c, 123_456L)));
        assertEquals(112, stock.get("glimmer_orb"), "刪掉的角色的通貨袋不算：12 ＋ 倉庫 100");
        assertEquals(33, stock.get("bounty_orb"), "通貨袋 3 ＋ 掛單還沒成交的 30");
        assertEquals(2, stock.get("zenith_orb"), "市集待領");
        assertTrue(stock.get(EconomyRepository.GOLD) >= 777);
        database.sync(c -> {
            repository.saveStock(c, "2026-07-01", stock);
            repository.saveStock(c, "2026-07-01", Map.of("glimmer_orb", 1L));
            return null;
        });
        final List<EconomyRepository.DayValue> saved = database.sync(c -> repository.stockSince(c, "2026-07-01"));
        assertEquals(List.of(new EconomyRepository.DayValue("2026-07-01", "glimmer_orb", 1)), saved, "同一天重算整天換掉");
    }
}
