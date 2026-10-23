package tw.exilecore.stash;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.core.db.Database;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 倉庫的資料表：存、讀、覆蓋，以及跟角色存檔同一個交易。 */
class StashRepositoryTest {

    static Database database;
    static final StashRepository stash = new StashRepository();
    static final CharacterRepository characters = new CharacterRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_stash_test;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void emptyStashLoadsEmpty() throws SQLException {
        final StashRepository.Snapshot snapshot = database.sync(c -> stash.load(c, UUID.randomUUID()));
        assertTrue(snapshot.pages().isEmpty());
        assertTrue(snapshot.currency().isEmpty());
    }

    @Test
    void saveLoadAndOverwrite() throws SQLException {
        final UUID account = UUID.randomUUID();
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(
                    Map.of(0, new byte[] {1, 2, 3}, 3, new byte[] {9}),
                    Map.of("glimmer_orb", 12, "zenith_orb", 1, "annul_orb", 0)));
            return null;
        });
        StashRepository.Snapshot loaded = database.sync(c -> stash.load(c, account));
        assertArrayEquals(new byte[] {1, 2, 3}, loaded.pages().get(0));
        assertArrayEquals(new byte[] {9}, loaded.pages().get(3));
        assertEquals(Map.of("glimmer_orb", 12, "zenith_orb", 1), loaded.currency(), "數量 0 的不存");

        // 第二次存：頁面覆蓋、快照裡沒有的頁刪掉、通貨整批換掉（拿光的那種要消失）
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(
                    Map.of(0, new byte[] {4}), Map.of("glimmer_orb", 5000)));
            return null;
        });
        loaded = database.sync(c -> stash.load(c, account));
        assertArrayEquals(new byte[] {4}, loaded.pages().get(0));
        assertNull(loaded.pages().get(3), "快照裡沒有的頁刪掉（2026-10：收回的頁留在資料庫會被讀回來＝複製）");
        assertEquals(Map.of("glimmer_orb", 5000), loaded.currency());
    }

    @Test
    void mapShardsReplaceTheOldWholeStoreAndShrink() throws SQLException {
        // 2026-10-23：地圖頁分段存在負數頁碼；存過新格式之後舊的整份（MAP_STORE_KEY）刪掉、段數變少的段也刪掉
        final UUID account = UUID.randomUUID();
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(Map.of(Stash.MAP_STORE_KEY, new byte[] {1}), Map.of()));
            return null;
        });
        StashRepository.Snapshot loaded = database.sync(c -> stash.load(c, account));
        assertArrayEquals(new byte[] {1}, loaded.pages().get(Stash.MAP_STORE_KEY), "舊格式還沒轉之前留著");
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(
                    Map.of(Stash.mapShardKey(0), new byte[] {2}, Stash.mapShardKey(1), new byte[] {3}), Map.of()));
            return null;
        });
        loaded = database.sync(c -> stash.load(c, account));
        assertNull(loaded.pages().get(Stash.MAP_STORE_KEY), "存過分段之後舊的整份刪掉");
        assertArrayEquals(new byte[] {2}, loaded.pages().get(Stash.mapShardKey(0)));
        assertArrayEquals(new byte[] {3}, loaded.pages().get(Stash.mapShardKey(1)));
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(Map.of(Stash.mapShardKey(0), new byte[] {4}), Map.of()));
            return null;
        });
        loaded = database.sync(c -> stash.load(c, account));
        assertArrayEquals(new byte[] {4}, loaded.pages().get(Stash.mapShardKey(0)));
        assertNull(loaded.pages().get(Stash.mapShardKey(1)), "地圖變少：多出來的段刪掉");
        assertEquals(1, Stash.mapShardOf(Stash.mapShardKey(1)));
        assertEquals(-1, Stash.mapShardOf(5));
        assertEquals(-1, Stash.pageOfKey(Stash.mapShardKey(3)), "負數頁碼不是倉庫頁");
    }

    @Test
    void tabsAreSavedAndReplaced() throws SQLException {
        final UUID account = UUID.randomUUID();
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(Map.of(), Map.of(),
                    Map.of(0, new StashRepository.Tab("武器", "WEAPON"), 2, new StashRepository.Tab("", "MAP"))));
            return null;
        });
        StashRepository.Snapshot loaded = database.sync(c -> stash.load(c, account));
        assertEquals(Map.of(0, new StashRepository.Tab("武器", "WEAPON"), 2, new StashRepository.Tab("", "MAP")), loaded.tabs());
        // 改回預設的頁籤要消失
        database.sync(c -> {
            stash.save(c, account, new StashRepository.Snapshot(Map.of(), Map.of(), Map.of(2, new StashRepository.Tab("地圖", "MAP"))));
            return null;
        });
        loaded = database.sync(c -> stash.load(c, account));
        assertEquals(Map.of(2, new StashRepository.Tab("地圖", "MAP")), loaded.tabs());
    }

    /** 倉庫跟角色存檔在同一個交易：倉庫那段失敗時，角色（背包）也不會寫進去。 */
    @Test
    void stashFailureRollsBackCharacterSave() throws Exception {
        final UUID account = UUID.randomUUID();
        database.sync(c -> characters.loadOrCreateAccount(c, account, "Stasher"));
        final CharacterRecord record = new CharacterRecord(0, account, 1, "倉庫測試", CharacterClass.RANGER);
        database.sync(c -> characters.insertCharacter(c, record));
        record.setExperience(777);

        final CharacterRepository.ExtraWork failing = connection -> {
            stash.save(connection, account, new StashRepository.Snapshot(Map.of(1, new byte[] {7}), Map.of()));
            throw new SQLException("模擬倉庫寫入失敗");
        };
        assertThrows(SQLException.class, () -> database.sync(c -> {
            characters.saveCharacter(c, record, new CharacterRepository.InventoryBlob(new byte[] {1}, null), Set.of(), failing);
            return null;
        }));
        assertEquals(0, database.sync(c -> characters.findCharacter(c, record.id())).orElseThrow().experience(), "角色也要回滾");
        assertTrue(database.sync(c -> stash.load(c, account)).pages().isEmpty(), "倉庫也要回滾");

        // 正常的情況一起寫進去
        final CharacterRepository.ExtraWork ok = connection ->
                stash.save(connection, account, new StashRepository.Snapshot(Map.of(1, new byte[] {7}), Map.of("whetstone", 3)));
        database.sync(c -> {
            characters.saveCharacter(c, record, new CharacterRepository.InventoryBlob(new byte[] {1}, null), Set.of(), ok);
            return null;
        });
        assertEquals(777, database.sync(c -> characters.findCharacter(c, record.id())).orElseThrow().experience());
        assertEquals(Map.of("whetstone", 3), database.sync(c -> stash.load(c, account)).currency());
    }
}
