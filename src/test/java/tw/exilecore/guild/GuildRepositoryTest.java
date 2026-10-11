package tw.exilecore.guild;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.core.db.Database;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公會的資料表：用記憶體 H2（MariaDB 模式）跑真正的遷移與 SQL。 */
class GuildRepositoryTest {

    static Database database;
    static final GuildRepository repository = new GuildRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_guild_test;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    private static Guild guild(final long id, final String name, final String tag, final UUID leader) {
        // 時間存成 DATETIME（到秒），所以測試用整秒
        final Guild guild = new Guild(id, name, tag, leader, 1, 0, "", GuildRank.MEMBER, 1_700_000_000_000L + id * 1000);
        guild.put(new Guild.Member(leader, "會長" + id, GuildRank.LEADER, 1_700_000_000_000L + id * 1000, 0));
        return guild;
    }

    private static GuildRepository.GuildRow stored(final long id) throws SQLException {
        return database.sync(repository::loadAll).stream().filter(row -> row.id() == id).findFirst().orElse(null);
    }

    @Test
    void saveAndLoadRoundTrip() throws SQLException {
        final UUID leader = UUID.randomUUID();
        final UUID officer = UUID.randomUUID();
        final Guild guild = guild(101, "星骸遠征隊", "星骸", leader);
        guild.put(new Guild.Member(officer, "副手", GuildRank.OFFICER, 1_700_000_900_000L, 0));
        guild.setMotd("週六晚上打世界 Boss");
        guild.setWithdrawRank(GuildRank.OFFICER);
        guild.setBoughtPages(3);
        guild.addExperience(12_345, new GuildConfig());
        guild.contribute(officer, 12_345);
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(guild));
            return null;
        });
        final Guild loaded = stored(101).toGuild();
        assertEquals("星骸遠征隊", loaded.name());
        assertEquals("星骸", loaded.tag());
        assertEquals(leader, loaded.leader());
        assertEquals(1, loaded.level());
        assertEquals(12_345, loaded.experience());
        assertEquals("週六晚上打世界 Boss", loaded.motd());
        assertEquals(GuildRank.OFFICER, loaded.withdrawRank());
        assertEquals(3, loaded.boughtPages(), "用星幣買的倉庫頁");
        assertEquals(guild.createdAt(), loaded.createdAt());
        assertEquals(2, loaded.size());
        assertEquals(GuildRank.LEADER, loaded.rank(leader));
        assertEquals(GuildRank.OFFICER, loaded.rank(officer));
        assertEquals(12_345, loaded.member(officer).contribution());
        assertEquals("副手", loaded.member(officer).name());
        assertEquals(List.of(leader, officer), loaded.members().stream().map(Guild.Member::uuid).toList(), "照加入的時間");
    }

    @Test
    void savingAgainReplacesTheMemberList() throws SQLException {
        final UUID leader = UUID.randomUUID();
        final UUID gone = UUID.randomUUID();
        final Guild guild = guild(102, "第二公會", "第二", leader);
        guild.put(new Guild.Member(gone, "要走的人", GuildRank.MEMBER, 5, 0));
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(guild));
            return null;
        });
        guild.remove(gone);
        guild.addExperience(50, new GuildConfig());
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(guild));
            return null;
        });
        final Guild loaded = stored(102).toGuild();
        assertEquals(1, loaded.size());
        assertFalse(loaded.has(gone));
        assertEquals(50, loaded.experience());
    }

    @Test
    void aMemberWhoMovedIsNotLeftInTheOldGuild() throws SQLException {
        final UUID mover = UUID.randomUUID();
        final Guild from = guild(103, "舊東家", "舊東", UUID.randomUUID());
        from.put(new Guild.Member(mover, "跳槽的人", GuildRank.MEMBER, 5, 0));
        final Guild to = guild(104, "新東家", "新東", UUID.randomUUID());
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(from));
            repository.saveAtomic(c, GuildRepository.GuildRow.of(to));
            return null;
        });
        // 離開舊的、加入新的；新的先存（舊的那一筆還沒存到）也不能撞主鍵
        from.remove(mover);
        to.put(new Guild.Member(mover, "跳槽的人", GuildRank.MEMBER, 9, 0));
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(to));
            return null;
        });
        assertTrue(stored(104).toGuild().has(mover));
        assertFalse(stored(103).toGuild().has(mover), "一個帳號只會在一個公會");
    }

    @Test
    void renamedGuildKeepsItsRowAndMembers() throws SQLException {
        final UUID leader = UUID.randomUUID();
        final Guild guild = guild(109, "舊名字", "舊名", leader);
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(guild));
            return null;
        });
        guild.retitle("新名字", "新名");
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(guild));
            return null;
        });
        final Guild loaded = stored(109).toGuild();
        assertEquals("新名字", loaded.name());
        assertEquals("新名", loaded.tag());
        assertTrue(loaded.has(leader));
    }

    @Test
    void brokenLeaderIsRepairedOnLoad() {
        final UUID first = UUID.randomUUID();
        final UUID officer = UUID.randomUUID();
        final GuildRepository.GuildRow row = new GuildRepository.GuildRow(7, "沒有會長", "沒會", UUID.randomUUID(), 2, 0, "", 0, 1, 0,
                List.of(new GuildRepository.MemberRow(first, "老成員", 0, 0, 10), new GuildRepository.MemberRow(officer, "幹部", 1, 0, 20)));
        final Guild guild = row.toGuild();
        assertEquals(officer, guild.leader(), "階級最高的人接會長");
        assertEquals(GuildRank.LEADER, guild.rank(officer));
        assertEquals(GuildRank.MEMBER, guild.rank(first));
        assertNull(new GuildRepository.GuildRow(8, "空的", "空空", first, 1, 0, "", 0, 1, 0, List.of()).toGuild(), "沒有成員的公會不要");
    }

    @Test
    void stashPagesAndDelete() throws SQLException {
        final Guild guild = guild(105, "有倉庫的", "倉庫", UUID.randomUUID());
        database.sync(c -> {
            repository.saveAtomic(c, GuildRepository.GuildRow.of(guild));
            repository.saveStashPage(c, 105, 0, new byte[] {1, 2, 3});
            repository.saveStashPage(c, 105, 2, new byte[] {9});
            repository.saveStashPage(c, 105, 0, new byte[] {4, 5});
            repository.insertLog(c, 105, 1_000, "某人", "存入", "一把劍");
            return null;
        });
        final Map<Integer, byte[]> pages = database.sync(c -> repository.loadStash(c, 105));
        assertEquals(2, pages.size());
        assertArrayEquals(new byte[] {4, 5}, pages.get(0), "同一頁是覆蓋");
        assertArrayEquals(new byte[] {9}, pages.get(2));
        assertTrue(database.sync(c -> repository.loadStash(c, 999)).isEmpty());
        database.sync(c -> {
            repository.delete(c, 105);
            return null;
        });
        assertNull(stored(105));
        assertTrue(database.sync(c -> repository.loadStash(c, 105)).isEmpty(), "倉庫跟著刪");
        assertTrue(database.sync(c -> repository.loadLog(c, 105, 10)).isEmpty(), "紀錄跟著刪");
    }

    @Test
    void logIsNewestFirstAndTrimmed() throws SQLException {
        database.sync(c -> {
            for (int i = 1; i <= 12; i++) {
                repository.insertLog(c, 106, 1_000L * i, "玩家" + i, "捐獻", "第 " + i + " 筆");
            }
            repository.insertLog(c, 107, 5, "別的公會", "加入", "不要動到我");
            return null;
        });
        final List<GuildRepository.LogRow> newest = database.sync(c -> repository.loadLog(c, 106, 3));
        assertEquals(List.of("第 12 筆", "第 11 筆", "第 10 筆"), newest.stream().map(GuildRepository.LogRow::detail).toList());
        assertEquals(12_000, newest.getFirst().at());
        assertEquals(7, (int) database.sync(c -> repository.trimLog(c, 106, 5)));
        final List<GuildRepository.LogRow> kept = database.sync(c -> repository.loadLog(c, 106, 50));
        assertEquals(5, kept.size());
        assertEquals("第 8 筆", kept.getLast().detail());
        assertEquals(0, (int) database.sync(c -> repository.trimLog(c, 106, 5)), "不用再刪");
        assertEquals(1, database.sync(c -> repository.loadLog(c, 107, 50)).size());
    }

    @Test
    void longTextIsCutToFitTheColumns() throws SQLException {
        final String longDetail = "很長的說明".repeat(80);
        database.sync(c -> {
            repository.insertLog(c, 108, 1, "名字".repeat(40), "超過十六個字的動作名稱超過十六個字", longDetail);
            return null;
        });
        final GuildRepository.LogRow row = database.sync(c -> repository.loadLog(c, 108, 1)).getFirst();
        assertNotNull(row);
        assertEquals(255, row.detail().length());
        assertEquals(32, row.actor().length());
        assertEquals(16, row.action().length());
        assertEquals("ab", GuildRepository.cut("ab😀", 3), "不把一個字切一半");
    }
}
