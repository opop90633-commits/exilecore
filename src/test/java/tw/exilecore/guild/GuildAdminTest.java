package tw.exilecore.guild;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理員對公會的操作（2026-10）：不看階級、不用邀請，但公會一定要有會長、不能撞名、人數上限照算。 */
class GuildAdminTest {

    private final GuildConfig config = new GuildConfig();
    private final GuildRoster roster = new GuildRoster();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private Guild guild() {
        final Guild guild = roster.create(alice, "愛麗絲", "星骸遠征隊", "星骸", 1_000, config).guild();
        assertEquals(GuildRoster.Result.OK, roster.invite(alice, bob, 2_000, config));
        assertTrue(roster.accept(bob, "鮑伯", null, 2_500, config).ok());
        return guild;
    }

    @Test
    void adminCanReplaceTheLeader() {
        final Guild guild = guild();
        assertEquals(GuildRoster.Result.OK, roster.adminLeader(guild.id(), bob));
        assertEquals(bob, guild.leader());
        assertEquals(GuildRank.LEADER, guild.rank(bob));
        assertEquals(GuildRank.OFFICER, guild.rank(alice), "原本的會長變成幹部");
        assertEquals(GuildRoster.Result.ALREADY_RANK, roster.adminLeader(guild.id(), bob));
        assertEquals(GuildRoster.Result.NOT_MEMBER, roster.adminLeader(guild.id(), carol));
        assertEquals(GuildRoster.Result.NOT_IN_GUILD, roster.adminLeader(999, bob));
    }

    @Test
    void adminRankNeverTouchesTheLeader() {
        final Guild guild = guild();
        assertEquals(GuildRoster.Result.OK, roster.adminRank(guild.id(), bob, GuildRank.OFFICER));
        assertEquals(GuildRank.OFFICER, guild.rank(bob));
        assertEquals(GuildRoster.Result.ALREADY_RANK, roster.adminRank(guild.id(), bob, GuildRank.OFFICER));
        assertEquals(GuildRoster.Result.LEADER_MUST_TRANSFER, roster.adminRank(guild.id(), alice, GuildRank.MEMBER));
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.adminRank(guild.id(), bob, GuildRank.LEADER), "會長用 adminLeader");
        assertEquals(alice, guild.leader());
    }

    @Test
    void adminKickRemovesMembersButNotTheLeader() {
        final Guild guild = guild();
        assertEquals(GuildRoster.Result.LEADER_MUST_TRANSFER, roster.adminKick(guild.id(), alice));
        assertEquals(GuildRoster.Result.OK, roster.adminKick(guild.id(), bob));
        assertNull(roster.guildOf(bob));
        assertEquals(1, guild.size());
        assertEquals(GuildRoster.Result.NOT_MEMBER, roster.adminKick(guild.id(), bob));
    }

    @Test
    void adminAddNeedsNoInviteButOneGuildPerAccount() {
        final Guild guild = guild();
        final GuildRoster.Joined joined = roster.adminAdd(guild.id(), carol, "卡蘿", 3_000, config);
        assertTrue(joined.ok());
        assertSame(guild, roster.guildOf(carol));
        assertEquals(GuildRank.MEMBER, guild.rank(carol));
        assertEquals(GuildRoster.Result.TARGET_IN_GUILD, roster.adminAdd(guild.id(), carol, "卡蘿", 3_000, config).result());
        assertEquals(GuildRoster.Result.NOT_IN_GUILD, roster.adminAdd(999, UUID.randomUUID(), "誰", 3_000, config).result());
    }

    @Test
    void adminAddRespectsTheMemberCap() {
        final Guild guild = guild();
        for (int i = guild.size(); i < config.maxMembers(); i++) {
            assertTrue(roster.adminAdd(guild.id(), UUID.randomUUID(), "成員" + i, 3_000, config).ok());
        }
        assertEquals(GuildRoster.Result.FULL, roster.adminAdd(guild.id(), carol, "卡蘿", 3_000, config).result());
        assertNull(roster.guildOf(carol));
    }

    @Test
    void adminRenameFollowsTheSameRulesAsTheLeader() {
        final Guild guild = guild();
        final UUID dave = UUID.randomUUID();
        roster.create(dave, "戴夫", "另一個公會", "另一", 1_000, config);
        assertEquals(GuildRoster.Result.NAME_TAKEN, roster.adminRename(guild.id(), "另一個公會", "星骸", config));
        assertEquals(GuildRoster.Result.TAG_TAKEN, roster.adminRename(guild.id(), "星骸遠征隊", "另一", config));
        assertEquals(GuildRoster.Result.BAD_NAME, roster.adminRename(guild.id(), "有 空白", "星骸", config));
        assertEquals(GuildRoster.Result.SAME_NAME, roster.adminRename(guild.id(), "星骸遠征隊", "星骸", config));
        assertEquals(GuildRoster.Result.OK, roster.adminRename(guild.id(), "新的名字", "新名", config));
        assertSame(guild, roster.byName("新名"));
        assertEquals("新的名字", guild.name());
    }

    @Test
    void adminLevelClampsAndResetsProgress() {
        final Guild guild = guild();
        guild.addExperience(5, config);
        assertEquals(1, roster.adminLevel(guild.id(), 3, config));
        assertEquals(3, guild.level());
        assertEquals(0, guild.experience());
        roster.adminLevel(guild.id(), 9_999, config);
        assertEquals(config.maxLevel(), guild.level(), "不會超過滿級");
        assertEquals(0, roster.adminLevel(999, 2, config), "沒有這個公會");
    }

    @Test
    void membersAreFoundAcrossGuildsByStoredName() {
        final Guild guild = guild();
        final GuildRoster.Membership found = roster.memberByName("鮑伯");
        assertSame(guild, found.guild());
        assertEquals(bob, found.member().uuid());
        assertNull(roster.memberByName("沒有這個人"));
    }
}
