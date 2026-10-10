package tw.exilecore.market;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.core.db.Database;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 市集的資料表：上架、改價、下架、收入、掛單與成交。 */
class MarketRepositoryTest {

    static Database database;
    static final MarketRepository repository = new MarketRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_market;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void listingsRoundTrip() throws Exception {
        final UUID seller = UUID.randomUUID();
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final MarketRepository.ListingRow row = new MarketRepository.ListingRow("l1", seller, "Seller", new byte[] {1, 2, 3},
                "item-uuid", "稀有 長劍\n+20% 火焰抗性", "zenith_orb", 3, now);
        database.sync(c -> {
            repository.insertListing(c, row);
            repository.insertListing(c, row); // 重試也只有一筆
            return null;
        });
        List<MarketRepository.ListingRow> rows = database.sync(repository::loadListings);
        assertEquals(1, rows.size());
        assertArrayEquals(new byte[] {1, 2, 3}, rows.getFirst().item());
        assertEquals("稀有 長劍\n+20% 火焰抗性", rows.getFirst().searchText(), "換行與中文原樣存");
        assertEquals(now, rows.getFirst().listedAt());

        final Instant later = now.plusSeconds(60);
        database.sync(c -> {
            repository.updatePrice(c, "l1", "bounty_orb", 40, later);
            return null;
        });
        rows = database.sync(repository::loadListings);
        assertEquals("bounty_orb", rows.getFirst().priceCurrency());
        assertEquals(40, rows.getFirst().priceAmount());
        assertEquals(later, rows.getFirst().listedAt());

        assertEquals(1, (int) database.sync(c -> repository.deleteListing(c, "l1")));
        assertEquals(0, (int) database.sync(c -> repository.deleteListing(c, "l1")), "已經刪掉的再刪是 0");
        assertTrue(database.sync(repository::loadListings).isEmpty());
    }

    @Test
    void proceedsAreOneRowEach() throws Exception {
        final UUID seller = UUID.randomUUID();
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        database.sync(c -> {
            repository.insertProceed(c, new MarketRepository.ProceedRow("p1", seller, "zenith_orb", 3, "market", "賣出 長劍", now));
            repository.insertProceed(c, new MarketRepository.ProceedRow("p2", seller, "zenith_orb", 2, "exchange", "交易所", now));
            return null;
        });
        assertEquals(2, database.sync(repository::loadProceeds).stream().filter(row -> row.account().equals(seller)).count(),
                "兩筆收入各自一列，不會互相蓋掉");
        database.sync(c -> {
            repository.deleteProceeds(c, List.of("p1"));
            return null;
        });
        final List<MarketRepository.ProceedRow> left = database.sync(repository::loadProceeds).stream()
                .filter(row -> row.account().equals(seller)).toList();
        assertEquals(1, left.size());
        assertEquals("p2", left.getFirst().id());
        assertEquals(2, left.getFirst().amount());
    }

    @Test
    void ordersRememberWhatWasTaken() throws Exception {
        final UUID maker = UUID.randomUUID();
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        database.sync(c -> {
            repository.insertOrder(c, new MarketRepository.OrderRow("o1", maker, "Maker", "zenith_orb", 10, "bounty_orb", 30, now, 0));
            repository.insertFill(c, "f1", "o1", 3);
            repository.insertFill(c, "f2", "o1", 2);
            repository.insertFill(c, "f2", "o1", 2); // 重試同一筆成交不會算兩次
            return null;
        });
        MarketRepository.OrderRow order = database.sync(repository::loadOrders).stream()
                .filter(row -> row.id().equals("o1")).findFirst().orElseThrow();
        assertEquals(5, order.taken());
        final ExchangeBook.Order loaded = new ExchangeBook.Order(order.id(), order.account(), order.playerName(), order.give(),
                order.giveAmount(), order.want(), order.wantAmount(), 0, order.giveAmount() - order.taken(), true, null);
        assertEquals(5, loaded.remaining());
        assertEquals(15, loaded.remainingWant());
        database.sync(c -> {
            repository.deleteOrder(c, "o1");
            return null;
        });
        assertTrue(database.sync(repository::loadOrders).stream().noneMatch(row -> row.id().equals("o1")));
        // 成交紀錄也一起刪掉了：同一個代號再掛一次是全新的
        database.sync(c -> {
            repository.insertOrder(c, new MarketRepository.OrderRow("o1", maker, "Maker", "zenith_orb", 10, "bounty_orb", 30, now, 0));
            return null;
        });
        order = database.sync(repository::loadOrders).stream().filter(row -> row.id().equals("o1")).findFirst().orElseThrow();
        assertEquals(0, order.taken());
    }
}
