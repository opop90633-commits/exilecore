package tw.exilecore.admin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.core.db.Database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理網頁的資料表（記憶體 H2，MariaDB 模式，跑真正的遷移）：發放、領取、取消、找人、操作紀錄。 */
class AdminRepositoryTest {

    static Database database;
    static final AdminRepository repository = new AdminRepository();
    static final CharacterRepository characters = new CharacterRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_admin;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    private static UUID account(final String name) throws SQLException {
        final UUID uuid = UUID.randomUUID();
        database.sync(c -> characters.loadOrCreateAccount(c, uuid, name));
        return uuid;
    }

    private static long character(final UUID account, final int slot, final String name) throws SQLException {
        final CharacterRecord record = new CharacterRecord(0, account, slot, name, CharacterClass.RANGER);
        database.sync(c -> {
            characters.createCharacter(c, record, new CharacterRepository.InventoryBlob(new byte[0], null), Map.of());
            return null;
        });
        return record.id();
    }

    private static AdminRepository.NewDelivery row(final UUID account, final String name, final String kind, final long amount) {
        return new AdminRepository.NewDelivery(account, name, kind, kind.equals("currency") ? "zenith_orb" : null, null, 0, amount,
                kind + " ×" + amount, null);
    }

    @Test
    void deliverClaimPartiallyAndCancel() throws SQLException {
        final UUID steve = account("Steve");
        final List<Long> ids = database.sync(c -> repository.insert(c, "bx-claim-0001", List.of(row(steve, "Steve", "currency", 30),
                row(steve, "Steve", "gold", 5000), row(steve, "Steve", "merits", 10)), "維修補償", "Admin"));
        assertEquals(3, ids.size());
        assertTrue(ids.stream().allMatch(id -> id > 0));
        List<AdminRepository.Delivery> pending = database.sync(c -> repository.pending(c, steve, 100));
        assertEquals(3, pending.size());
        assertEquals(ids.get(0), pending.get(0).id(), "舊的先");
        assertEquals("維修補償", pending.get(0).note());
        assertEquals(30, pending.get(0).original());
        assertTrue(pending.get(0).pending());
        assertEquals(3, (int) database.sync(c -> repository.pendingCount(c, steve)));

        // 通貨放不下一部分：剩 12
        assertEquals(1, (int) database.sync(c -> repository.setRemaining(c, ids.get(0), 12)));
        // 金幣領完
        assertEquals(1, (int) database.sync(c -> repository.markClaimed(c, ids.get(1), "烈焰術士")));
        assertEquals(0, (int) database.sync(c -> repository.markClaimed(c, ids.get(1), "烈焰術士")), "領過的不能再領");
        // 取消：功勳（還沒領）與金幣（已領完，不能取消）
        final List<Long> cancelled = database.sync(c -> repository.cancel(c, List.of(ids.get(2), ids.get(1)), "Admin"));
        assertEquals(List.of(ids.get(2)), cancelled);
        assertEquals(0, (int) database.sync(c -> repository.markClaimed(c, ids.get(2), "烈焰術士")), "取消了的不能領");
        assertEquals(0, (int) database.sync(c -> repository.setRemaining(c, ids.get(2), 1)));

        pending = database.sync(c -> repository.pending(c, steve, 100));
        assertEquals(1, pending.size());
        assertEquals(12, pending.get(0).amount());
        assertEquals(List.of(ids.get(0)), database.sync(c -> repository.pendingInBatch(c, "bx-claim-0001")));

        final List<AdminRepository.Delivery> claimed = database.sync(c -> repository.list(c, "claimed", "", 50));
        assertTrue(claimed.stream().anyMatch(d -> d.id() == ids.get(1) && "烈焰術士".equals(d.claimedBy()) && d.amount() == 0));
        final List<AdminRepository.Delivery> cancelledRows = database.sync(c -> repository.list(c, "cancelled", "steve", 50));
        assertTrue(cancelledRows.stream().anyMatch(d -> d.id() == ids.get(2) && "Admin".equals(d.cancelledBy()) && d.cancelledAt() != null));
        assertEquals(3, database.sync(c -> repository.list(c, "all", "bx-claim-0001", 50)).size(), "批次代號也找得到");
        assertEquals(3, database.sync(c -> repository.list(c, "all", "bx-claim", 50)).size(), "前幾碼就找得到");
        assertEquals(List.of(steve), database.sync(c -> repository.batchAccounts(c, "bx-claim-0001")), "同一個請求重送：知道已經寫過了");
        assertNull(database.sync(c -> repository.batchAccounts(c, "batch-none")));
        assertTrue(database.sync(c -> repository.list(c, "all", "nobody", 50)).isEmpty());
        final Map<Long, UUID> owners = database.sync(c -> repository.owners(c, ids));
        assertEquals(steve, owners.get(ids.get(0)));
    }

    @Test
    void pendingIsReadPageByPage() throws SQLException {
        final UUID otto = account("Otto");
        final java.util.ArrayList<AdminRepository.NewDelivery> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row(otto, "Otto", "gold", 100 + i));
        }
        final List<Long> ids = database.sync(c -> repository.insert(c, "batch-page", rows, "", "Admin"));
        assertEquals(5, ids.size());
        final List<AdminRepository.Delivery> first = database.sync(c -> repository.pending(c, otto, 0, 2));
        assertEquals(List.of(ids.get(0), ids.get(1)), first.stream().map(AdminRepository.Delivery::id).toList());
        final long after = first.get(1).id();
        final List<AdminRepository.Delivery> second = database.sync(c -> repository.pending(c, otto, after, 2));
        assertEquals(List.of(ids.get(2), ids.get(3)), second.stream().map(AdminRepository.Delivery::id).toList(),
                "前面一直領不走的不會擋住後面的");
        // 整批取消（前 8 碼也可以）
        final List<Long> pending = database.sync(c -> repository.pendingInBatch(c, "batch-pa"));
        assertEquals(ids, pending);
        assertTrue(database.sync(c -> repository.pendingInBatch(c, "batch")).isEmpty(), "太短的代號不算");
        assertEquals(ids, database.sync(c -> repository.cancel(c, pending, "Admin")));
        assertTrue(database.sync(c -> repository.cancel(c, pending, "Admin")).isEmpty(), "取消過的不會再算一次");
        assertEquals(0, (int) database.sync(c -> repository.pendingCount(c, otto)));
    }

    @Test
    void findPlayersByAccountOrCharacterName() throws SQLException {
        final UUID alex = account("AlexTheGreat");
        character(alex, 1, "阿明的劍士");
        final long deleted = character(alex, 2, "刪掉的角色");
        database.sync(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE characters SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                update.setLong(1, deleted);
                update.executeUpdate();
            }
            return null;
        });
        final UUID other = account("Percent%_Guy");

        assertEquals(alex, database.sync(c -> repository.resolve(c, "alexthegreat")).uuid(), "帳號名稱不分大小寫");
        assertEquals("AlexTheGreat", database.sync(c -> repository.resolve(c, "阿明的劍士")).name(), "角色名稱也可以");
        assertNull(database.sync(c -> repository.resolve(c, "刪掉的角色")), "刪掉的角色不算");
        assertNull(database.sync(c -> repository.resolve(c, "  ")));
        assertNull(database.sync(c -> repository.resolve(c, "nobody")));
        assertEquals(alex, database.sync(c -> repository.account(c, alex)).uuid());
        assertNull(database.sync(c -> repository.account(c, UUID.randomUUID())));

        final List<AdminRepository.Found> found = database.sync(c -> repository.search(c, "劍士", 20));
        assertEquals(1, found.size());
        assertEquals(List.of("阿明的劍士 Lv.1"), found.get(0).characters(), "刪掉的角色不列");
        assertEquals(1, database.sync(c -> repository.search(c, "%_", 20)).size(), "% 與 _ 當成一般的字");
        assertEquals(other, database.sync(c -> repository.search(c, "%_", 20)).get(0).uuid());
        assertTrue(database.sync(c -> repository.search(c, "", 20)).isEmpty());
        assertTrue(database.sync(c -> repository.allAccounts(c, 1000)).stream().anyMatch(a -> a.uuid().equals(alex)));
        assertTrue(database.sync(repository::countAccounts) >= 2);
    }

    @Test
    void claimLogFitsTheTradeLog() throws SQLException {
        // 領取時寫的交易紀錄（kind = admin）：trade_id 是 VARCHAR(36)，太長的話整筆存檔會失敗、交易系統一直暫停
        final String opId = tw.exilecore.economy.TradeLogRepository.newTradeId();
        assertTrue(opId.length() <= tw.exilecore.economy.TradeLogRepository.TRADE_ID_MAX);
        final tw.exilecore.economy.TradeLogRepository log = new tw.exilecore.economy.TradeLogRepository();
        final String itemUuid = UUID.randomUUID().toString();
        database.sync(c -> {
            log.insert(c, List.of(new tw.exilecore.economy.TradeLogRepository.Entry(opId, tw.exilecore.economy.TradeLogRepository.ADMIN,
                    itemUuid, "稀有 隕鐵指環".repeat(60), 3, null, "管理員的名字很長很長很長很長很長很長", UUID.randomUUID(), "Steve")));
            return null;
        });
        final List<tw.exilecore.economy.TradeLogRepository.Row> rows = database.sync(c -> log.byItem(c, itemUuid, 10));
        assertEquals(1, rows.size());
        assertEquals("admin", rows.get(0).entry().kind());
    }

    @Test
    void logAndLongText() throws SQLException {
        final UUID uuid = account("LongName");
        final String longLabel = "很長".repeat(200);
        final String emoji = "a".repeat(AdminRepository.NOTE_MAX - 1) + "😀";
        final List<Long> ids = database.sync(c -> repository.insert(c, "batch-long", List.of(new AdminRepository.NewDelivery(uuid,
                "LongName", "item", "short_sword", "rare", 80, 1, longLabel, null)), emoji, "x".repeat(60)));
        final AdminRepository.Delivery stored = database.sync(c -> repository.pending(c, uuid, 10)).get(0);
        assertEquals(ids.get(0), stored.id());
        assertEquals(AdminRepository.LABEL_MAX, stored.label().length(), "超過的切掉，不會寫不進去");
        assertFalse(Character.isHighSurrogate(stored.note().charAt(stored.note().length() - 1)), "不會切在表情符號中間");
        assertEquals(AdminRepository.NAME_MAX, stored.createdBy().length());
        assertEquals("rare", stored.rarity());
        assertEquals(80, stored.itemLevel());

        database.sync(c -> {
            repository.log(c, "Steve", "發放", "給 Alex：金幣 50,000");
            repository.log(c, "Steve", "改設定", "經驗倍數：改成 3 倍".repeat(100));
            return null;
        });
        final List<AdminRepository.LogEntry> log = database.sync(c -> repository.log(c, 10));
        assertEquals("改設定", log.get(0).action(), "新的先");
        assertEquals(AdminRepository.DETAIL_MAX, log.get(0).detail().length());
        assertNotNull(log.get(1).at());
    }
}
