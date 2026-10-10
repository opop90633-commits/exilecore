package tw.exilecore.bounty;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.core.db.Database;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 帳號的功勳與帳號進度（第 10 版的資料表）：讀、寫、覆蓋、刪除，以及跟其他東西同一個交易。 */
class AccountRepositoryTest {

    static Database database;
    static final AccountRepository accounts = new AccountRepository();
    static final CharacterRepository characters = new CharacterRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_account_test;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void newAccountHasNothing() throws SQLException {
        final AccountRepository.Data data = database.sync(c -> accounts.load(c, UUID.randomUUID()));
        assertEquals(0, data.merits());
        assertTrue(data.progress().isEmpty());
    }

    @Test
    void meritsAndProgressRoundTrip() throws SQLException {
        final UUID account = UUID.randomUUID();
        database.sync(c -> characters.loadOrCreateAccount(c, account, "Merit"));
        database.sync(c -> {
            accounts.setMerits(c, account, 135);
            accounts.setProgress(c, account, "bounty.d0", "2026-10-03|slayer|12");
            accounts.setProgress(c, account, "shop.stash", "1");
            return null;
        });
        AccountRepository.Data data = database.sync(c -> accounts.load(c, account));
        assertEquals(135, data.merits());
        assertEquals(Map.of("bounty.d0", "2026-10-03|slayer|12", "shop.stash", "1"), data.progress());
        // 覆蓋、刪除（null）、功勳不會變負的
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("bounty.d0", "2026-10-03|slayer|150|done");
        values.put("shop.stash", null);
        values.put("title", "hunter");
        database.sync(c -> {
            accounts.setProgress(c, account, values);
            accounts.setMerits(c, account, -5);
            return null;
        });
        data = database.sync(c -> accounts.load(c, account));
        assertEquals(0, data.merits());
        assertEquals(Map.of("bounty.d0", "2026-10-03|slayer|150|done", "title", "hunter"), data.progress());
        // 別的帳號看不到
        assertTrue(database.sync(c -> accounts.load(c, UUID.randomUUID())).progress().isEmpty());
    }

    @Test
    void failedWritesRollBackTogether() throws SQLException {
        final UUID account = UUID.randomUUID();
        database.sync(c -> characters.loadOrCreateAccount(c, account, "Atomic"));
        database.sync(c -> {
            accounts.setMerits(c, account, 50);
            return null;
        });
        // 同一個交易裡：功勳扣了、紀錄寫了，接著失敗（鍵太長）→ 都不算
        assertThrows(Exception.class, () -> database.sync(c -> {
            final boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                accounts.setMerits(c, account, 10);
                accounts.setProgress(c, account, "shop.stash", "1");
                accounts.setProgress(c, account, "x".repeat(100), "1");
                c.commit();
            } catch (final SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        }));
        final AccountRepository.Data data = database.sync(c -> accounts.load(c, account));
        assertEquals(50, data.merits());
        assertTrue(data.progress().isEmpty());
    }
}
