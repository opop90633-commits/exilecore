package tw.exilecore.character;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.core.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 經驗表改版時舊角色的等級換算（照殺怪的量）與資料庫遷移 12；全服經驗加倍之後補等級（遷移 13）。 */
class LevelConversionTest {

    static Database database;

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_level_conversion;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    private static double progress(final LevelConversion.Result result) {
        return result.experience() / (double) CharacterConfig.DEFAULT_EXPERIENCE[result.level() - 1];
    }

    @Test
    void convertsByMonstersKilled() {
        // 伺服器上的角色（10/05 的備份）：100 → 65、90 → 51、87 → 50、73 → 41、60 → 34、36 → 23、28 → 19、24 → 16
        final LevelConversion.Result hundred = LevelConversion.convert(100, 0);
        assertEquals(65, hundred.level());
        assertEquals(0.46, progress(hundred), 0.01);
        assertEquals(51, LevelConversion.convert(90, 94_974).level());
        assertEquals(50, LevelConversion.convert(87, 399_212).level());
        assertEquals(41, LevelConversion.convert(73, 165_105).level());
        assertEquals(34, LevelConversion.convert(60, 0).level());
        assertEquals(23, LevelConversion.convert(36, 12_377).level());
        assertEquals(19, LevelConversion.convert(28, 5_354).level());
        assertEquals(16, LevelConversion.convert(24, 919).level());
        assertEquals(new LevelConversion.Result(1, 0), LevelConversion.convert(1, 0));
        // 新的每級要殺的怪比舊的多：60 級以前的舊角色換完都低於原本的等級
        assertTrue(LevelConversion.newKills(60) > LevelConversion.oldKills(60) * 5, "60 級：新 " + LevelConversion.newKills(60)
                + "、舊 " + LevelConversion.oldKills(60));
    }

    @Test
    void conversionKeepsTheOrderAndNeverOverflowsTheLevel() {
        LevelConversion.Result previous = new LevelConversion.Result(1, 0);
        for (int level = 1; level <= 100; level++) {
            for (final double fraction : new double[] {0, 0.5, 0.999}) {
                final long experience = level >= 100 ? 0 : (long) (LevelConversion.OLD_EXPERIENCE[level - 1] * fraction);
                final LevelConversion.Result result = LevelConversion.convert(level, experience);
                assertTrue(result.level() > previous.level()
                        || (result.level() == previous.level() && result.experience() >= previous.experience()),
                        "以前比較高的角色換完不能比較低：" + level + " " + fraction);
                assertTrue(result.level() >= 1 && result.level() <= 100);
                if (result.level() < 100) {
                    assertTrue(result.experience() < CharacterConfig.DEFAULT_EXPERIENCE[result.level() - 1], "經驗不到下一級");
                }
                if (level >= 3) {
                    assertTrue(result.level() <= level, "不會換到比原本高：" + level);
                }
                previous = result;
            }
        }
    }

    @Test
    void alreadyConvertedNumbersAreLeftAlone() {
        // 經驗比舊表那一級要的還多：不可能是舊的數字，不換
        final long tooMuch = LevelConversion.OLD_EXPERIENCE[49] + 1;
        assertEquals(new LevelConversion.Result(50, tooMuch), LevelConversion.convert(50, tooMuch));
        assertArrayEquals(new int[] {100, 65}, LevelConversion.parseNote("100>65"));
        assertNull(LevelConversion.parseNote("壞掉"));
        assertNull(LevelConversion.parseNote(null));
    }

    /** 遷移 12 換算完再照加倍後的速度補（遷移 13）。 */
    private static LevelConversion.Result both(final int level, final long experience) {
        final LevelConversion.Result converted = LevelConversion.convert(level, experience);
        return LevelConversion.boost(converted.level(), converted.experience());
    }

    @Test
    void boostFollowsTheDoubledRate() {
        // 伺服器上的角色（10/05 的備份）：100 → 65 → 78、90 → 51 → 66、87 → 64、73 → 53、60 → 46、36 → 33、28 → 27
        final LevelConversion.Result hundred = both(100, 0);
        assertEquals(78, hundred.level());
        assertEquals(0.74, progress(hundred), 0.01);
        assertEquals(66, both(90, 94_974).level());
        assertEquals(64, both(87, 399_212).level());
        assertEquals(53, both(73, 165_105).level());
        assertEquals(46, both(60, 10).level());
        assertEquals(33, both(36, 12_377).level());
        assertEquals(27, both(28, 5_354).level());
        // 27 級（這一級 77%）：照算是 27 級 9%（比原本低一點）
        final LevelConversion.Result twentySeven = both(27, 8_986);
        assertEquals(27, twentySeven.level());
        assertTrue(progress(twentySeven) < 8_986 / 11_700.0);
        // 低等級加倍之後比舊的快：不會比換算前高，補回原本的等級與這一級的進度
        final LevelConversion.Result low = both(24, 919);
        assertEquals(24, low.level(), "24 級（這一級 10%）照算會到 24 級 30%");
        assertEquals(919 / 9_200.0, progress(low), 0.001);
        assertEquals(7, both(7, 177).level());
        assertEquals(5, both(5, 83).level(), "遷移 12 換成 4 級的：補回 5 級");
        assertEquals(new LevelConversion.Result(1, 0), both(1, 0));
        // 整級（經驗 0）的低等級角色（24 級以前加倍之後比舊的快，照原本的等級）：補回原本那一級，
        // 不能因為遷移 12 的經驗無條件捨去，差幾點經驗變成上一級
        for (int level = 3; level <= 24; level++) {
            assertEquals(level, both(level, 0).level(), level + " 級（經驗 0）");
        }
        assertEquals(24, both(25, 0).level(), "25 級起加倍之後還是比舊的慢：照算");
    }

    @Test
    void boostNeverLowersAndNeverPassesTheOldLevel() {
        LevelConversion.Result previous = new LevelConversion.Result(1, 0);
        for (int level = 1; level <= 100; level++) {
            for (final double fraction : new double[] {0, 0.5, 0.999}) {
                final long experience = level >= 100 ? 0 : (long) (LevelConversion.OLD_EXPERIENCE[level - 1] * fraction);
                final LevelConversion.Result converted = LevelConversion.convert(level, experience);
                final LevelConversion.Result boosted = LevelConversion.boost(converted.level(), converted.experience());
                assertTrue(boosted.level() > converted.level()
                        || (boosted.level() == converted.level() && boosted.experience() >= converted.experience()), "不會比遷移 12 低：" + level);
                assertTrue(boosted.level() <= Math.max(level, converted.level()), "不會比換算前高：" + level + " " + fraction);
                assertTrue(boosted.level() > previous.level()
                        || (boosted.level() == previous.level() && boosted.experience() >= previous.experience()),
                        "以前比較高的角色補完不能比較低：" + level + " " + fraction);
                if (boosted.level() < 100) {
                    assertTrue(boosted.experience() < CharacterConfig.DEFAULT_EXPERIENCE[boosted.level() - 1]);
                }
                previous = boosted;
            }
        }
        assertEquals(new LevelConversion.Result(100, 0), LevelConversion.boost(100, 0), "滿級不動");
        assertEquals(new LevelConversion.Result(1, 0), LevelConversion.boost(1, 0));
    }

    @Test
    void migrationLowersLevelsAndResetsPassives() throws Exception {
        final Map<Long, int[]> before = new HashMap<>();
        before.put(7001L, new int[] {100, 0});
        before.put(7002L, new int[] {60, 35_000});
        before.put(7003L, new int[] {1, 0});
        final String summary = database.sync(connection -> {
            insertAccount(connection);
            for (final Map.Entry<Long, int[]> entry : before.entrySet()) {
                insertCharacter(connection, entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO passive_allocations (character_id, node_id) VALUES (?, ?)")) {
                    for (final String node : new String[] {"a", "b"}) {
                        statement.setLong(1, entry.getKey());
                        statement.setString(2, node);
                        statement.executeUpdate();
                    }
                }
            }
            return LevelConversion.migrate(connection);
        });
        assertTrue(summary.contains("2 個降級"), summary);
        database.sync(connection -> {
            assertArrayEquals(new long[] {65, LevelConversion.convert(100, 0).experience()}, levelOf(connection, 7001));
            assertArrayEquals(new long[] {LevelConversion.convert(60, 35_000).level(), LevelConversion.convert(60, 35_000).experience()},
                    levelOf(connection, 7002));
            assertArrayEquals(new long[] {1, 0}, levelOf(connection, 7003), "1 級沒經驗的不動");
            assertEquals(0, passives(connection, 7001), "降級的天賦全部退回");
            assertEquals(0, passives(connection, 7002));
            assertEquals(2, passives(connection, 7003), "沒變的不動");
            assertEquals("100>65", note(connection, 7001));
            assertNull(note(connection, 7003));
            return null;
        });
    }

    @Test
    void bonusMigrationRaisesConvertedCharacters() throws Exception {
        // 自己一個資料庫：上面的遷移 12 測試會換算資料庫裡所有的角色（測試的順序不固定）
        final Database database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_level_boost;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try {
            bonusMigration(database);
        } finally {
            database.close();
        }
    }

    private static void bonusMigration(final Database database) throws Exception {
        final Timestamp cutoff = Timestamp.from(Instant.parse("2026-10-05T05:03:02Z"));
        final Timestamp before = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        final Timestamp after = Timestamp.from(Instant.parse("2026-10-05T06:00:00Z"));
        final LevelConversion.Result hundred = LevelConversion.convert(100, 0);   // 65
        final LevelConversion.Result ninety = LevelConversion.convert(90, 94_974); // 51
        final LevelConversion.Result five = LevelConversion.convert(5, 83);       // 4
        final String summary = database.sync(connection -> {
            insertAccount(connection, "acct-boost");
            // 8001：還沒登入看過換算說明（100>65）；8002：看過了；8003：遷移 12 之後才建的角色；8004：刪掉的；8005：5 級換成 4 級
            insertCharacter(connection, "acct-boost", 8001, hundred.level(), hundred.experience(), before, false);
            insertCharacter(connection, "acct-boost", 8002, ninety.level(), ninety.experience(), before, false);
            insertCharacter(connection, "acct-boost", 8003, 30, 1_000, after, false);
            insertCharacter(connection, "acct-boost", 8004, hundred.level(), hundred.experience(), before, true);
            insertCharacter(connection, "acct-boost", 8005, five.level(), five.experience(), before, false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO progress (character_id, progress_key, progress_value) VALUES (?, ?, ?)")) {
                for (final Object[] row : new Object[][] {{8001L, "100>65"}, {8005L, "5>4"}}) {
                    statement.setLong(1, (Long) row[0]);
                    statement.setString(2, LevelConversion.PROGRESS_KEY);
                    statement.setString(3, (String) row[1]);
                    statement.executeUpdate();
                }
            }
            for (final long id : new long[] {8001, 8002}) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO passive_allocations (character_id, node_id) VALUES (?, 'x')")) {
                    statement.setLong(1, id);
                    statement.executeUpdate();
                }
            }
            return LevelConversion.migrateBonus(connection, cutoff);
        });
        assertTrue(summary.contains("3 個角色") && summary.contains("3 個等級變高"), summary);
        database.sync(connection -> {
            assertEquals(78, levelOf(connection, 8001)[0]);
            assertEquals("100>78", note(connection, 8001), "還沒看過說明：直接改成補完的等級");
            assertNull(boostNote(connection, 8001));
            assertEquals(66, levelOf(connection, 8002)[0]);
            assertNull(note(connection, 8002));
            assertEquals("51>66", boostNote(connection, 8002), "看過了：另外記一筆");
            assertArrayEquals(new long[] {30, 1_000}, levelOf(connection, 8003), "遷移 12 之後才建的不動");
            assertEquals(78, levelOf(connection, 8004)[0], "刪掉的也補");
            assertNull(boostNote(connection, 8004));
            assertEquals(5, levelOf(connection, 8005)[0], "4 級補回原本的 5 級");
            assertEquals("5>5", note(connection, 8005), "等級一樣：登入時說「等級一樣是 Lv.5」（遷移 12 退過天賦）");
            assertEquals(1, passives(connection, 8001), "不動天賦（等級只會變高）");
            assertEquals(1, passives(connection, 8002));
            return null;
        });
    }

    private static String boostNote(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT progress_value FROM progress WHERE character_id = ? AND progress_key = ?")) {
            statement.setLong(1, id);
            statement.setString(2, LevelConversion.BOOST_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void insertAccount(final Connection connection, final String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts (uuid, name, active_character, created_at, last_seen) VALUES (?, 'Boost', NULL, ?, ?)")) {
            statement.setString(1, uuid);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void insertCharacter(final Connection connection, final String account, final long id, final int level, final long experience,
                                        final Timestamp created, final boolean deleted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO characters (id, account_uuid, slot, name, class_id, char_level, experience, bonus_points, refund_points, "
                        + "deaths, playtime_seconds, life, mana, energy_shield, last_zone, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, 'c', 'ranger', ?, ?, 0, 0, 0, 0, 1, 1, 0, NULL, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, account);
            statement.setInt(3, (int) (id % 100));
            statement.setInt(4, level);
            statement.setLong(5, experience);
            statement.setTimestamp(6, created);
            statement.setTimestamp(7, created);
            statement.setTimestamp(8, deleted ? created : null);
            statement.executeUpdate();
        }
    }

    private static void insertAccount(final Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts (uuid, name, active_character, created_at, last_seen) VALUES ('acct-conv', 'Conv', NULL, ?, ?)")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void insertCharacter(final Connection connection, final long id, final int level, final long experience) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO characters (id, account_uuid, slot, name, class_id, char_level, experience, bonus_points, refund_points, "
                        + "deaths, playtime_seconds, life, mana, energy_shield, last_zone, created_at, updated_at, deleted_at) "
                        + "VALUES (?, 'acct-conv', ?, 'c', 'ranger', ?, ?, 0, 0, 0, 0, 1, 1, 0, NULL, ?, ?, NULL)")) {
            statement.setLong(1, id);
            statement.setInt(2, (int) (id % 100));
            statement.setInt(3, level);
            statement.setLong(4, experience);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static long[] levelOf(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT char_level, experience FROM characters WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new long[] {result.getInt(1), result.getLong(2)};
            }
        }
    }

    private static int passives(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM passive_allocations WHERE character_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String note(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT progress_value FROM progress WHERE character_id = ? AND progress_key = ?")) {
            statement.setLong(1, id);
            statement.setString(2, LevelConversion.PROGRESS_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
