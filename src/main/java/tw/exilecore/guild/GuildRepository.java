package tw.exilecore.guild;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公會的資料表（guilds、guild_members、guild_stash、guild_log）。純 JDBC，不依賴 Bukkit，可以用記憶體 H2 測試。
 * <p>
 * 公會的代號由程式給（{@link GuildRoster}），不是資料庫的自動編號：建立公會那一刻記憶體裡就要有代號。
 * 存公會是「整個公會一起存」（公會那一列＋全部成員）：最多幾十個人，換來不會只寫了一半。
 */
public final class GuildRepository {

    private static final int NAME_MAX = 32;
    private static final int TAG_MAX = 16;
    private static final int MOTD_MAX = 255;
    private static final int ACTION_MAX = 16;
    private static final int DETAIL_MAX = 255;

    /** 一個成員（資料庫的樣子）。 */
    public record MemberRow(UUID uuid, String name, int rank, long contribution, long joinedAt) {
    }

    /** 一個公會（資料庫的樣子），含全部成員。 */
    public record GuildRow(long id, String name, String tag, UUID leader, int level, long experience, String motd,
                           int withdrawRank, long createdAt, int boughtPages, List<MemberRow> members) {
        public GuildRow {
            members = List.copyOf(members);
        }

        /** 記憶體裡的公會照一份（主執行緒呼叫，之後可以交給資料庫執行緒）。 */
        public static GuildRow of(final Guild guild) {
            final List<MemberRow> members = new ArrayList<>();
            for (final Guild.Member member : guild.members()) {
                members.add(new MemberRow(member.uuid(), member.name(), member.rank().code(), member.contribution(), member.joinedAt()));
            }
            return new GuildRow(guild.id(), guild.name(), guild.tag(), guild.leader(), guild.level(), guild.experience(),
                    guild.motd(), guild.withdrawRank().code(), guild.createdAt(), guild.boughtPages(), members);
        }

        /**
         * 變回記憶體裡的公會。資料不對的自己補正：沒有成員回傳 null；會長不在成員裡（或有好幾個會長）時，
         * 階級最高、最早加入的人當會長。
         */
        public Guild toGuild() {
            if (members.isEmpty()) {
                return null;
            }
            MemberRow chief = null;
            for (final MemberRow member : members) {
                if (member.uuid().equals(leader)) {
                    chief = member;
                }
            }
            if (chief == null) {
                for (final MemberRow member : members) {
                    if (chief == null || member.rank() > chief.rank()
                            || member.rank() == chief.rank() && member.joinedAt() < chief.joinedAt()) {
                        chief = member;
                    }
                }
            }
            final Guild guild = new Guild(id, name, tag, chief.uuid(), level, experience, motd, GuildRank.of(withdrawRank), createdAt);
            guild.setBoughtPages(boughtPages);
            for (final MemberRow member : members) {
                final boolean isChief = member.uuid().equals(chief.uuid());
                GuildRank rank = GuildRank.of(member.rank());
                if (isChief) {
                    rank = GuildRank.LEADER;
                } else if (rank == GuildRank.LEADER) {
                    rank = GuildRank.OFFICER;
                }
                guild.put(new Guild.Member(member.uuid(), member.name(), rank, member.joinedAt(), member.contribution()));
            }
            return guild;
        }
    }

    /** 一筆公會紀錄。 */
    public record LogRow(long at, String actor, String action, String detail) {
    }

    // ---- 公會與成員 ----

    /** 全部的公會（含成員；成員照加入的時間）。 */
    public List<GuildRow> loadAll(final Connection connection) throws SQLException {
        final Map<Long, List<MemberRow>> members = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT guild_id, account_uuid, name, guild_rank, contribution, joined_at FROM guild_members ORDER BY joined_at, account_uuid");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                final UUID uuid;
                try {
                    uuid = UUID.fromString(rows.getString(2));
                } catch (final IllegalArgumentException e) {
                    continue; // 壞掉的一列：跳過，不要讓整個模組起不來
                }
                members.computeIfAbsent(rows.getLong(1), key -> new ArrayList<>()).add(new MemberRow(uuid, rows.getString(3),
                        rows.getInt(4), rows.getLong(5), millis(rows.getTimestamp(6))));
            }
        }
        final List<GuildRow> guilds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, tag, leader_uuid, guild_level, experience, motd, withdraw_rank, created_at, bought_pages FROM guilds ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID leader = null;
                try {
                    leader = UUID.fromString(rows.getString(4));
                } catch (final IllegalArgumentException e) {
                    // 會長壞掉：toGuild 會挑一個
                }
                final long id = rows.getLong(1);
                guilds.add(new GuildRow(id, rows.getString(2), rows.getString(3), leader, rows.getInt(5), rows.getLong(6),
                        rows.getString(7), rows.getInt(8), millis(rows.getTimestamp(9)), rows.getInt(10), members.getOrDefault(id, List.of())));
            }
        }
        return guilds;
    }

    /**
     * 存一個公會（公會那一列＋全部成員）。不自己開交易：在呼叫端的交易裡跑（建立公會時跟扣金幣的角色存檔一起），
     * 單獨存用 {@link #saveAtomic}。
     */
    public void save(final Connection connection, final GuildRow guild) throws SQLException {
        int updated;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE guilds SET name = ?, tag = ?, leader_uuid = ?, guild_level = ?, experience = ?, motd = ?, withdraw_rank = ?, bought_pages = ? "
                        + "WHERE id = ?")) {
            update.setString(1, cut(guild.name(), NAME_MAX));
            update.setString(2, cut(guild.tag(), TAG_MAX));
            update.setString(3, guild.leader().toString());
            update.setInt(4, guild.level());
            update.setLong(5, guild.experience());
            update.setString(6, cut(guild.motd(), MOTD_MAX));
            update.setInt(7, guild.withdrawRank());
            update.setInt(8, guild.boughtPages());
            update.setLong(9, guild.id());
            updated = update.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO guilds (id, name, tag, leader_uuid, guild_level, experience, motd, withdraw_rank, created_at, bought_pages) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, guild.id());
                insert.setString(2, cut(guild.name(), NAME_MAX));
                insert.setString(3, cut(guild.tag(), TAG_MAX));
                insert.setString(4, guild.leader().toString());
                insert.setInt(5, guild.level());
                insert.setLong(6, guild.experience());
                insert.setString(7, cut(guild.motd(), MOTD_MAX));
                insert.setInt(8, guild.withdrawRank());
                insert.setTimestamp(9, Timestamp.from(Instant.ofEpochMilli(guild.createdAt())));
                insert.setInt(10, guild.boughtPages());
                insert.executeUpdate();
            }
        }
        // 成員整批換掉：這個公會原本的，加上這些人在別的公會留下的舊資料（一個帳號只會在一個公會）
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_members WHERE guild_id = ?")) {
            delete.setLong(1, guild.id());
            delete.executeUpdate();
        }
        if (guild.members().isEmpty()) {
            return;
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_members WHERE account_uuid = ?")) {
            for (final MemberRow member : guild.members()) {
                delete.setString(1, member.uuid().toString());
                delete.addBatch();
            }
            delete.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO guild_members (account_uuid, guild_id, name, guild_rank, contribution, joined_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (final MemberRow member : guild.members()) {
                insert.setString(1, member.uuid().toString());
                insert.setLong(2, guild.id());
                insert.setString(3, cut(member.name(), NAME_MAX));
                insert.setInt(4, member.rank());
                insert.setLong(5, member.contribution());
                insert.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(member.joinedAt())));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** 單獨存一個公會：自己一個交易（全部寫進去或全部沒寫）。 */
    public void saveAtomic(final Connection connection, final GuildRow guild) throws SQLException {
        atomic(connection, () -> save(connection, guild));
    }

    /** 刪掉一個公會：公會、成員、倉庫、紀錄（一個交易）。 */
    public void delete(final Connection connection, final long guildId) throws SQLException {
        atomic(connection, () -> {
            for (final String sql : List.of("DELETE FROM guild_members WHERE guild_id = ?", "DELETE FROM guild_stash WHERE guild_id = ?",
                    "DELETE FROM guild_log WHERE guild_id = ?", "DELETE FROM guild_applications WHERE guild_id = ?", "DELETE FROM guilds WHERE id = ?")) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, guildId);
                    statement.executeUpdate();
                }
            }
        });
    }

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException;
    }

    private static void atomic(final Connection connection, final Work work) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (final SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (final SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    // ---- 入會申請（2026-10） ----

    /** 一筆入會申請：申請的人（帳號）、當時的角色名稱與等級、留言。 */
    public record ApplicationRow(long guildId, UUID uuid, String name, int level, String message, long createdAt) {
    }

    /** 全部的申請（舊的在前）。 */
    public List<ApplicationRow> loadApplications(final Connection connection) throws SQLException {
        final List<ApplicationRow> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT guild_id, account_uuid, name, char_level, message, created_at FROM guild_applications ORDER BY created_at");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                final UUID uuid;
                try {
                    uuid = UUID.fromString(rows.getString(2));
                } catch (final IllegalArgumentException e) {
                    continue;
                }
                list.add(new ApplicationRow(rows.getLong(1), uuid, rows.getString(3), rows.getInt(4), rows.getString(5),
                        millis(rows.getTimestamp(6))));
            }
        }
        return list;
    }

    /** 存一筆申請（同一個人對同一個公會再申請一次 = 蓋掉）。 */
    public void saveApplication(final Connection connection, final ApplicationRow row) throws SQLException {
        atomic(connection, () -> {
            deleteApplication(connection, row.guildId(), row.uuid());
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO guild_applications (guild_id, account_uuid, name, char_level, message, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, row.guildId());
                insert.setString(2, row.uuid().toString());
                insert.setString(3, cut(row.name(), NAME_MAX));
                insert.setInt(4, row.level());
                insert.setString(5, cut(row.message(), 100));
                insert.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(row.createdAt())));
                insert.executeUpdate();
            }
        });
    }

    /** 刪一筆申請。 */
    public void deleteApplication(final Connection connection, final long guildId, final UUID uuid) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_applications WHERE guild_id = ? AND account_uuid = ?")) {
            delete.setLong(1, guildId);
            delete.setString(2, uuid.toString());
            delete.executeUpdate();
        }
    }

    /** 刪掉這個人的全部申請（加入公會了）。 */
    public void deleteApplications(final Connection connection, final UUID uuid) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_applications WHERE account_uuid = ?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }
    }

    // ---- 倉庫 ----

    /** 公會倉庫：頁碼 → 序列化的物品（沒存過的頁不在裡面）。 */
    public Map<Integer, byte[]> loadStash(final Connection connection, final long guildId) throws SQLException {
        final Map<Integer, byte[]> pages = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT page, contents FROM guild_stash WHERE guild_id = ? ORDER BY page")) {
            statement.setLong(1, guildId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    final byte[] contents = rows.getBytes(2);
                    if (contents != null) {
                        pages.put(rows.getInt(1), contents);
                    }
                }
            }
        }
        return pages;
    }

    /** 存倉庫的一頁（覆蓋）。在呼叫端的交易裡跑：跟放東西、拿東西的那個人的存檔一起。 */
    public void saveStashPage(final Connection connection, final long guildId, final int page, final byte[] contents) throws SQLException {
        final Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE guild_stash SET contents = ?, updated_at = ? WHERE guild_id = ? AND page = ?")) {
            update.setBytes(1, contents);
            update.setTimestamp(2, now);
            update.setLong(3, guildId);
            update.setInt(4, page);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO guild_stash (guild_id, page, contents, updated_at) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, guildId);
            insert.setInt(2, page);
            insert.setBytes(3, contents);
            insert.setTimestamp(4, now);
            insert.executeUpdate();
        }
    }

    /** 一次存好幾頁（整理全部頁、頁籤設定）：一個交易，全部寫進去或全部沒寫。 */
    public void saveStashPages(final Connection connection, final long guildId, final Map<Integer, byte[]> pages) throws SQLException {
        atomic(connection, () -> {
            for (final Map.Entry<Integer, byte[]> entry : pages.entrySet()) {
                saveStashPage(connection, guildId, entry.getKey(), entry.getValue());
            }
        });
    }

    // ---- 紀錄 ----

    public void insertLog(final Connection connection, final long guildId, final long at, final String actor, final String action,
                          final String detail) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO guild_log (guild_id, created_at, actor, action, detail) VALUES (?, ?, ?, ?, ?)")) {
            insert.setLong(1, guildId);
            insert.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(at)));
            insert.setString(3, cut(actor, NAME_MAX));
            insert.setString(4, cut(action, ACTION_MAX));
            insert.setString(5, cut(detail, DETAIL_MAX));
            insert.executeUpdate();
        }
    }

    /** 最近的 limit 筆紀錄（新的在前）。 */
    public List<LogRow> loadLog(final Connection connection, final long guildId, final int limit) throws SQLException {
        final List<LogRow> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT created_at, actor, action, detail FROM guild_log WHERE guild_id = ? ORDER BY id DESC LIMIT ?")) {
            statement.setLong(1, guildId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    list.add(new LogRow(millis(rows.getTimestamp(1)), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
        }
        return list;
    }

    /** 每個公會只留最新的 keep 筆紀錄；回傳刪了幾筆。 */
    public int trimLog(final Connection connection, final long guildId, final int keep) throws SQLException {
        Long oldestKept = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM guild_log WHERE guild_id = ? ORDER BY id DESC LIMIT 1 OFFSET ?")) {
            statement.setLong(1, guildId);
            statement.setInt(2, Math.max(0, keep));
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    oldestKept = rows.getLong(1);
                }
            }
        }
        if (oldestKept == null) {
            return 0;
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_log WHERE guild_id = ? AND id <= ?")) {
            delete.setLong(1, guildId);
            delete.setLong(2, oldestKept);
            return delete.executeUpdate();
        }
    }

    // ---- 工具 ----

    private static long millis(final Timestamp timestamp) {
        return timestamp == null ? 0 : timestamp.getTime();
    }

    /** 截到欄位放得下的長度（不把一個字切一半）。 */
    static String cut(final String text, final int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
