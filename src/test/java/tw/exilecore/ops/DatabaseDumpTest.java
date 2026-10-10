package tw.exilecore.ops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.core.db.Database;
import tw.exilecore.core.db.Migrations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 備份與還原：寫出去再讀回來，中文、換行、二進位、NULL、時間都一樣。 */
class DatabaseDumpTest {

    static Database database;
    static final CharacterRepository characters = new CharacterRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_dump;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void dumpAndRestoreGiveBackTheSameData() throws Exception {
        final UUID account = UUID.randomUUID();
        final CharacterRecord record = new CharacterRecord(0, account, 1, "備份測試", CharacterClass.WITCH);
        database.sync(c -> {
            characters.loadOrCreateAccount(c, account, "Backup");
            characters.insertCharacter(c, record);
            record.setExperience(12345);
            record.setLastZone(null);
            characters.saveAll(c, java.util.List.of(new CharacterRepository.SaveJob(account, record,
                    new CharacterRepository.InventoryBlob(new byte[] {0, 1, 2, (byte) 255}, null, null, "glimmer_orb=3"),
                    Set.of("node_a"), null, 4321L, null)), connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO currency_log (character_id, player_name, item_uuid, currency, before_json, after_json, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                    statement.setLong(1, record.id());
                    statement.setString(2, "Backup");
                    statement.setString(3, "uuid-1");
                    statement.setString(4, "zenith_orb");
                    statement.setString(5, "{\"a\":\"第一行\\n第二行\"}");
                    statement.setString(6, "多行\n文字'與\"引號");
                    statement.executeUpdate();
                }
            });
            return null;
        });

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int rows = database.sync(c -> {
            try {
                return DatabaseDump.dump(c, Migrations.TABLES, out);
            } catch (final IOException e) {
                throw new SQLException(e);
            }
        });
        assertTrue(rows >= 4, "至少有帳號、角色、背包、紀錄：" + rows);

        // 改壞資料，再還原
        database.sync(c -> {
            characters.updateAccountGold(c, account, 1);
            try (PreparedStatement statement = c.prepareStatement("DELETE FROM currency_log")) {
                statement.executeUpdate();
            }
            return null;
        });
        final DatabaseDump.Restored restored = database.sync(c -> {
            c.setAutoCommit(false);
            try {
                final DatabaseDump.Restored result = DatabaseDump.restore(c, new ByteArrayInputStream(out.toByteArray()));
                c.commit();
                return result;
            } catch (final IOException e) {
                c.rollback();
                throw new SQLException(e);
            } finally {
                c.setAutoCommit(true);
            }
        });
        assertEquals(rows, restored.rows());
        assertEquals(0, restored.skippedColumns());
        assertEquals(4321, (long) database.sync(c -> characters.accountGold(c, account)), "帳號金幣回來了");
        final CharacterRepository.Loaded loaded = database.sync(c -> characters.loadCharacter(c, record.id())).orElseThrow();
        assertEquals(12345, loaded.record().experience());
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, loaded.inventory().contents(), "二進位原樣");
        assertEquals("glimmer_orb=3", loaded.inventory().bagCurrency());
        assertEquals(Set.of("node_a"), loaded.passives());
        final String after = database.sync(c -> {
            try (PreparedStatement statement = c.prepareStatement("SELECT after_json FROM currency_log WHERE item_uuid = 'uuid-1'");
                 java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        });
        assertEquals("多行\n文字'與\"引號", after, "換行與引號原樣");
    }

    /** 備份之後才新增的資料表（備份檔裡沒有）：還原時要清空，不然會留下跟還原的資料對不上的列。 */
    @Test
    void tablesMissingFromTheBackupAreCleared() throws Exception {
        final java.util.List<String> oldTables = Migrations.TABLES.stream().filter(table -> !table.equals("stash_tabs")).toList();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        database.sync(c -> {
            try {
                return DatabaseDump.dump(c, oldTables, out);
            } catch (final IOException e) {
                throw new SQLException(e);
            }
        });
        database.sync(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO stash_tabs (account_uuid, page, name, category) VALUES (?, 0, '新的', 'WEAPON')")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.executeUpdate();
            }
            return null;
        });
        database.sync(c -> {
            try {
                return DatabaseDump.restore(c, new ByteArrayInputStream(out.toByteArray()), Migrations.TABLES);
            } catch (final IOException e) {
                throw new SQLException(e);
            }
        });
        final int left = database.sync(c -> {
            try (PreparedStatement statement = c.prepareStatement("SELECT COUNT(*) FROM stash_tabs");
                 java.sql.ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        });
        assertEquals(0, left);
        assertTrue(Migrations.currentVersion() >= 8);
        final int versions = database.sync(c -> {
            try (PreparedStatement statement = c.prepareStatement("SELECT COUNT(*) FROM schema_version");
                 java.sql.ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        });
        assertTrue(versions > 0, "schema_version 不清");
    }

    @Test
    void notABackupIsRejected() {
        final SQLException error = assertThrows(SQLException.class, () -> database.sync(c -> {
            try {
                return DatabaseDump.restore(c, new ByteArrayInputStream("{\"hello\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (final IOException e) {
                throw new SQLException(e);
            }
        }));
        assertTrue(error.getCause() instanceof IOException, "不是備份檔要拒絕：" + error);
    }
}
