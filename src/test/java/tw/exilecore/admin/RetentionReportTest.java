package tw.exilecore.admin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.core.db.Database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-19 留存報表：第 N 天留存的算法、進度分段、每日上線紀錄（記憶體 H2 跑真正的遷移）。 */
class RetentionReportTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    static Database database;

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_retention;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    private static RetentionReport.Account account(final int createdDaysAgo, final int lastDaysAgo, final RetentionReport.Stage stage, final long hours) {
        return new RetentionReport.Account(NOW.minus(Duration.ofDays(createdDaysAgo)), NOW.minus(Duration.ofDays(lastDaysAgo)), stage, hours * 3600);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retentionCountsOnlyAccountsOldEnough() {
        final List<RetentionReport.Account> accounts = List.of(
                account(40, 0, RetentionReport.Stage.MAPS_4, 120),   // 一直在玩
                account(40, 35, RetentionReport.Stage.CAMPAIGN_1, 0), // 第 5 天就沒回來
                account(10, 1, RetentionReport.Stage.MAPS_1, 6),     // 10 天前建、昨天還在
                account(3, 3, RetentionReport.Stage.NO_CHARACTER, 0)); // 3 天前建、沒回來
        final Map<String, Object> report = RetentionReport.compute(accounts, Map.of(), null, NOW, ZONE);
        final Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        final Map<String, Object> d1 = (Map<String, Object>) summary.get("d1");
        assertEquals(4L, d1.get("eligible"));
        assertEquals(3L, d1.get("kept"), "第 1 天：只有 3 天前建、當天就沒回來的那個沒留下");
        final Map<String, Object> d7 = (Map<String, Object>) summary.get("d7");
        assertEquals(3L, d7.get("eligible"), "3 天前建的不算進第 7 天");
        assertEquals(2L, d7.get("kept"));
        final Map<String, Object> d30 = (Map<String, Object>) summary.get("d30");
        assertEquals(2L, d30.get("eligible"));
        assertEquals(1L, d30.get("kept"));
        assertNull(RetentionReport.retention(List.of(account(2, 0, RetentionReport.Stage.MAPS_1, 1)), 7, NOW), "沒有分母是 null");
        assertEquals(2L, summary.get("active7"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stagesAndPlaytimeSplitByActivity() {
        final List<RetentionReport.Account> accounts = List.of(
                account(40, 0, RetentionReport.Stage.MAPS_4, 120),
                account(40, 35, RetentionReport.Stage.CAMPAIGN_1, 0),
                account(20, 15, RetentionReport.Stage.CAMPAIGN_1, 2));
        final Map<String, Object> report = RetentionReport.compute(accounts, Map.of(), null, NOW, ZONE);
        final List<Map<String, Object>> stages = (List<Map<String, Object>>) report.get("stages");
        final Map<String, Object> campaign = stages.stream().filter(s -> s.get("stage").equals("CAMPAIGN_1")).findFirst().orElseThrow();
        assertEquals(0L, campaign.get("active"));
        assertEquals(2L, campaign.get("inactive"));
        final List<Map<String, Object>> playtime = (List<Map<String, Object>>) report.get("playtime");
        assertEquals(1L, playtime.get(0).get("inactive"), "不到 10 分鐘的一個");
        assertEquals(1L, playtime.get(2).get("inactive"), "1～5 小時的一個");
        final List<Map<String, Object>> daily = (List<Map<String, Object>>) report.get("daily");
        assertEquals(RetentionReport.DAILY_DAYS, daily.size());
        assertNull(daily.get(0).get("active"), "還沒開始記的日子是 null");
    }

    @Test
    void characterStageFollowsFurthestProgress() {
        assertEquals(RetentionReport.Stage.CAMPAIGN_1, new RetentionReport.CharProgress(5, 0, false, false, false).stage());
        assertEquals(RetentionReport.Stage.CAMPAIGN_3, new RetentionReport.CharProgress(60, 0, false, false, false).stage());
        assertEquals(RetentionReport.Stage.MAPS_1, new RetentionReport.CharProgress(70, 0, true, false, false).stage());
        assertEquals(RetentionReport.Stage.MAPS_3, new RetentionReport.CharProgress(85, 13, true, false, false).stage());
        assertEquals(RetentionReport.Stage.MAPS_4, new RetentionReport.CharProgress(90, 16, true, false, false).stage());
        assertEquals(RetentionReport.Stage.FINAL, new RetentionReport.CharProgress(90, 16, true, true, false).stage());
        assertEquals(RetentionReport.Stage.PINNACLE, new RetentionReport.CharProgress(95, 16, true, true, true).stage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsFromTheDatabase() throws Exception {
        final CharacterRepository repository = new CharacterRepository();
        final UUID player = UUID.randomUUID();
        database.sync(c -> repository.loadOrCreateAccount(c, player, "Tester"));
        database.sync(c -> {
            repository.loadOrCreateAccount(c, player, "Tester"); // 同一天第二次：不重複記
            try (PreparedStatement update = c.prepareStatement("UPDATE accounts SET created_at = ? WHERE uuid = ?")) {
                update.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(10))));
                update.setString(2, player.toString());
                update.executeUpdate();
            }
            final tw.exilecore.character.CharacterRecord record = new tw.exilecore.character.CharacterRecord(0, player, 1, "測試",
                    tw.exilecore.character.CharacterClass.RANGER);
            repository.createCharacter(c, record, new CharacterRepository.InventoryBlob(null, null), Map.of("atlas.wreck_bay", "1", "mapbest", "12"));
            try (PreparedStatement update = c.prepareStatement("UPDATE characters SET char_level = 88, playtime_seconds = 7200 WHERE id = ?")) {
                update.setLong(1, record.id());
                update.executeUpdate();
            }
            return null;
        });
        final int days = database.sync(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT COUNT(*) FROM account_days WHERE account_uuid = ?")) {
                select.setString(1, player.toString());
                try (java.sql.ResultSet result = select.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
        assertEquals(1, days, "一天只記一筆");
        final Map<String, Object> report = database.sync(c -> RetentionReport.load(c, Instant.now(), ZONE));
        final List<Map<String, Object>> stages = (List<Map<String, Object>>) report.get("stages");
        final Map<String, Object> maps = stages.stream().filter(s -> s.get("stage").equals("MAPS_3")).findFirst().orElseThrow();
        assertEquals(1L, maps.get("active"), "T12 的角色算地圖 T11–T15、今天有上線");
        assertEquals(LocalDate.now(ZONE).toString(), report.get("trackedSince"));
        final Map<String, Object> d7 = (Map<String, Object>) ((Map<String, Object>) report.get("summary")).get("d7");
        assertTrue((Long) d7.get("kept") >= 1, "10 天前建、今天還在：算第 7 天留下");
    }
}
