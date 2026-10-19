package tw.exilecore.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 留存報表（2026-10-19，管理網頁「玩家留存」）：新玩家第 1、7、30 天之後還有沒有回來、每週新玩家各自留下多少、
 * 每天有幾個帳號上線、還在玩的與 7 天以上沒上線的人各停在哪個進度、沒上線的人總共玩了多久。
 * <ul>
 *   <li>「回來」＝最後一次上線（accounts.last_seen 與 account_days 的最後一天，取較晚的）距離建立帳號至少 N 天。
 *       只有建立超過 N 天的帳號才算進第 N 天的分母。舊資料也算得出來（不用等每日紀錄累積）。</li>
 *   <li>每天上線的帳號數來自 account_days（2026-10-19 才開始記：登入、存檔時記一筆）。</li>
 *   <li>進度看帳號裡最遠的角色：打過巔峰／終局、地圖最高階（progress 的 mapbest、atlas.*）、還在劇情就看等級。</li>
 * </ul>
 * 讀資料庫的部分在 {@link #load}；算的部分（{@link #compute}）不碰資料庫，單元測試直接餵資料。
 */
final class RetentionReport {

    /** 幾天沒上線算「沒在玩」。 */
    static final int INACTIVE_DAYS = 7;
    /** 每週新玩家列幾週。 */
    static final int COHORT_WEEKS = 8;
    /** 每天上線列幾天。 */
    static final int DAILY_DAYS = 30;
    /** 終局 Boss 的代號（打過任何一個算「打過終局」）與巔峰。 */
    static final Set<String> FINALS = Set.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel");
    static final String PINNACLE = "awakener";

    /** 進度（照順序越後面越遠）。 */
    enum Stage {
        NO_CHARACTER("沒建角色"),
        CAMPAIGN_1("劇情 Lv.1–19"),
        CAMPAIGN_2("劇情 Lv.20–39"),
        CAMPAIGN_3("劇情 Lv.40 以上（還沒完成地圖）"),
        MAPS_1("地圖 T1–T5"),
        MAPS_2("地圖 T6–T10"),
        MAPS_3("地圖 T11–T15"),
        MAPS_4("地圖 T16"),
        FINAL("打過終局"),
        PINNACLE("打過巔峰");

        final String label;

        Stage(final String label) {
            this.label = label;
        }
    }

    /** 一個帳號：什麼時候建的、最後一次上線、最遠的進度、所有角色加起來玩了幾秒。 */
    record Account(Instant created, Instant lastActive, Stage stage, long playtimeSeconds) {
    }

    /** 一個角色的進度（算 {@link Stage} 用）。 */
    record CharProgress(int level, int bestTier, boolean anyMap, boolean finalDone, boolean pinnacleDone) {

        Stage stage() {
            if (pinnacleDone) {
                return Stage.PINNACLE;
            }
            if (finalDone) {
                return Stage.FINAL;
            }
            if (bestTier > 0 || anyMap) {
                final int tier = Math.max(1, bestTier);
                return tier >= 16 ? Stage.MAPS_4 : tier >= 11 ? Stage.MAPS_3 : tier >= 6 ? Stage.MAPS_2 : Stage.MAPS_1;
            }
            return level >= 40 ? Stage.CAMPAIGN_3 : level >= 20 ? Stage.CAMPAIGN_2 : Stage.CAMPAIGN_1;
        }
    }

    private RetentionReport() {
    }

    // ---- 讀資料庫 ----

    /** 讀帳號、角色、進度、每日上線，算成報表（JSON 用的 Map）。 */
    static Map<String, Object> load(final Connection connection, final Instant now, final ZoneId zone) throws SQLException {
        final Map<String, Instant[]> accounts = new HashMap<>(); // uuid → [建立, 最後上線]
        try (PreparedStatement select = connection.prepareStatement("SELECT uuid, created_at, last_seen FROM accounts");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                accounts.put(result.getString(1), new Instant[] {instant(result.getTimestamp(2)), instant(result.getTimestamp(3))});
            }
        }
        // 每日上線：每個帳號最後一天（補 last_seen：一直沒登出、跨日還在線的人）＋每天幾個帳號
        final Map<LocalDate, Integer> daily = new TreeMap<>();
        LocalDate trackedSince = null;
        try (PreparedStatement select = connection.prepareStatement("SELECT account_uuid, active_day FROM account_days");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                final LocalDate day = parseDay(result.getString(2));
                if (day == null) {
                    continue;
                }
                daily.merge(day, 1, Integer::sum);
                trackedSince = trackedSince == null || day.isBefore(trackedSince) ? day : trackedSince;
                final Instant[] account = accounts.get(result.getString(1));
                if (account != null) {
                    final Instant endOfDay = day.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1);
                    final Instant seen = endOfDay.isAfter(now) ? now : endOfDay;
                    if (account[1] == null || seen.isAfter(account[1])) {
                        account[1] = seen;
                    }
                }
            }
        }
        // 角色：等級、遊玩時間（刪掉的不算）
        final Map<Long, String> owner = new HashMap<>();
        final Map<Long, Integer> levels = new HashMap<>();
        final Map<String, Long> playtime = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, account_uuid, char_level, playtime_seconds FROM characters WHERE deleted_at IS NULL");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                final long id = result.getLong(1);
                owner.put(id, result.getString(2));
                levels.put(id, result.getInt(3));
                playtime.merge(result.getString(2), Math.max(0, result.getLong(4)), Long::sum);
            }
        }
        // 進度：完成過的地圖（atlas.地圖代號）與最高階（mapbest）
        final Map<Long, int[]> progress = new HashMap<>(); // 角色 → [最高階, 有完成地圖, 打過終局, 打過巔峰]
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT character_id, progress_key, progress_value FROM progress WHERE progress_key LIKE 'atlas.%' OR progress_key = 'mapbest'");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                final int[] p = progress.computeIfAbsent(result.getLong(1), id -> new int[4]);
                final String key = result.getString(2);
                if (key.equals("mapbest")) {
                    p[0] = Math.max(p[0], parseInt(result.getString(3)));
                } else {
                    final String map = key.substring("atlas.".length());
                    p[1] = 1;
                    if (FINALS.contains(map)) {
                        p[2] = 1;
                    }
                    if (PINNACLE.equals(map)) {
                        p[3] = 1;
                    }
                }
            }
        }
        final Map<String, Stage> stages = new HashMap<>();
        for (final Map.Entry<Long, String> entry : owner.entrySet()) {
            final int[] p = progress.getOrDefault(entry.getKey(), new int[4]);
            final Stage stage = new CharProgress(levels.getOrDefault(entry.getKey(), 1), p[0], p[1] > 0, p[2] > 0, p[3] > 0).stage();
            stages.merge(entry.getValue(), stage, (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        }
        final List<Account> list = new ArrayList<>();
        for (final Map.Entry<String, Instant[]> entry : accounts.entrySet()) {
            final Instant created = entry.getValue()[0];
            if (created == null) {
                continue;
            }
            final Instant last = entry.getValue()[1] == null ? created : entry.getValue()[1];
            list.add(new Account(created, last, stages.getOrDefault(entry.getKey(), Stage.NO_CHARACTER),
                    playtime.getOrDefault(entry.getKey(), 0L)));
        }
        return compute(list, daily, trackedSince, now, zone);
    }

    // ---- 計算 ----

    /** 照帳號清單與每天上線數算出報表。 */
    static Map<String, Object> compute(final List<Account> accounts, final Map<LocalDate, Integer> daily, final LocalDate trackedSince,
                                       final Instant now, final ZoneId zone) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final LocalDate today = LocalDate.ofInstant(now, zone);
        out.put("generated", now.toString());
        out.put("inactiveDays", INACTIVE_DAYS);

        // 總覽
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accounts", accounts.size());
        summary.put("new7", accounts.stream().filter(a -> a.created().isAfter(now.minus(Duration.ofDays(7)))).count());
        summary.put("new30", accounts.stream().filter(a -> a.created().isAfter(now.minus(Duration.ofDays(30)))).count());
        summary.put("active1", accounts.stream().filter(a -> a.lastActive().isAfter(now.minus(Duration.ofDays(1)))).count());
        summary.put("active7", accounts.stream().filter(a -> a.lastActive().isAfter(now.minus(Duration.ofDays(7)))).count());
        summary.put("active30", accounts.stream().filter(a -> a.lastActive().isAfter(now.minus(Duration.ofDays(30)))).count());
        summary.put("d1", retention(accounts, 1, now));
        summary.put("d7", retention(accounts, 7, now));
        summary.put("d30", retention(accounts, 30, now));
        out.put("summary", summary);

        // 每週新玩家（週一開始，台灣時間）
        final LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        final List<Map<String, Object>> cohorts = new ArrayList<>();
        for (int i = COHORT_WEEKS - 1; i >= 0; i--) {
            final LocalDate start = thisWeek.minusWeeks(i);
            final Instant from = start.atStartOfDay(zone).toInstant();
            final Instant to = start.plusWeeks(1).atStartOfDay(zone).toInstant();
            final List<Account> week = accounts.stream().filter(a -> !a.created().isBefore(from) && a.created().isBefore(to)).toList();
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", start.toString());
            row.put("accounts", week.size());
            row.put("d1", retention(week, 1, now));
            row.put("d7", retention(week, 7, now));
            row.put("d30", retention(week, 30, now));
            row.put("activeNow", week.stream().filter(a -> a.lastActive().isAfter(now.minus(Duration.ofDays(INACTIVE_DAYS)))).count());
            cohorts.add(row);
        }
        out.put("cohorts", cohorts);

        // 每天：上線的帳號數（account_days）、新帳號數
        final Map<LocalDate, Integer> created = new HashMap<>();
        for (final Account account : accounts) {
            created.merge(LocalDate.ofInstant(account.created(), zone), 1, Integer::sum);
        }
        final List<Map<String, Object>> days = new ArrayList<>();
        for (int i = DAILY_DAYS - 1; i >= 0; i--) {
            final LocalDate day = today.minusDays(i);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day.toString());
            row.put("active", trackedSince == null || day.isBefore(trackedSince) ? null : daily.getOrDefault(day, 0));
            row.put("new", created.getOrDefault(day, 0));
            days.add(row);
        }
        out.put("daily", days);
        out.put("trackedSince", trackedSince == null ? null : trackedSince.toString());

        // 進度：還在玩的（7 天內有上線）與沒在玩的各停在哪
        final Instant cutoff = now.minus(Duration.ofDays(INACTIVE_DAYS));
        final List<Map<String, Object>> stages = new ArrayList<>();
        for (final Stage stage : Stage.values()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("label", stage.label);
            row.put("active", accounts.stream().filter(a -> a.stage() == stage && a.lastActive().isAfter(cutoff)).count());
            row.put("inactive", accounts.stream().filter(a -> a.stage() == stage && !a.lastActive().isAfter(cutoff)).count());
            stages.add(row);
        }
        out.put("stages", stages);

        // 沒在玩的人總共玩了多久（所有角色加起來）
        final long[] bounds = {10 * 60, 60 * 60, 5 * 3600, 20 * 3600, 100 * 3600};
        final String[] labels = {"不到 10 分鐘", "10 分鐘～1 小時", "1～5 小時", "5～20 小時", "20～100 小時", "100 小時以上"};
        final long[] counts = new long[labels.length];
        for (final Account account : accounts) {
            if (account.lastActive().isAfter(cutoff)) {
                continue;
            }
            int bucket = 0;
            while (bucket < bounds.length && account.playtimeSeconds() >= bounds[bucket]) {
                bucket++;
            }
            counts[bucket]++;
        }
        final List<Map<String, Object>> playtime = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            playtime.add(Map.of("label", labels[i], "inactive", counts[i]));
        }
        out.put("playtime", playtime);
        return out;
    }

    /** 第 N 天之後還有回來：分母＝建立超過 N 天的帳號，分子＝其中最後上線距離建立至少 N 天的。沒有分母回傳 null。 */
    static Map<String, Object> retention(final List<Account> accounts, final int days, final Instant now) {
        final Duration span = Duration.ofDays(days);
        long eligible = 0;
        long kept = 0;
        for (final Account account : accounts) {
            if (account.created().plus(span).isAfter(now)) {
                continue;
            }
            eligible++;
            if (!account.lastActive().isBefore(account.created().plus(span))) {
                kept++;
            }
        }
        if (eligible == 0) {
            return null;
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("eligible", eligible);
        out.put("kept", kept);
        out.put("rate", (double) kept / eligible);
        return out;
    }

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static LocalDate parseDay(final String text) {
        try {
            return text == null ? null : LocalDate.parse(text.trim());
        } catch (final java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static int parseInt(final String text) {
        try {
            return text == null ? 0 : Math.max(0, Integer.parseInt(text.trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
