package tw.exilecore.guild;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公會的規則（純邏輯）：建立、邀請、加入、離開、請出、階級、交出會長、解散、升級、排行。 */
class GuildRosterTest {

    private final GuildConfig config = new GuildConfig();
    private final GuildRoster roster = new GuildRoster();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private Guild create(final UUID leader, final String name, final String tag) {
        final GuildRoster.Joined joined = roster.create(leader, "會長" + name, name, tag, 1_000, config);
        assertTrue(joined.ok(), () -> "建立失敗：" + joined.result());
        return joined.guild();
    }

    private void join(final UUID inviter, final UUID target, final String name) {
        assertEquals(GuildRoster.Result.OK, roster.invite(inviter, target, 2_000, config));
        assertTrue(roster.accept(target, name, null, 2_500, config).ok());
    }

    @Test
    void namesAllowChineseLettersAndDigitsOnly() {
        assertNull(GuildRoster.textProblem("星骸遠征隊", 2, 12));
        assertNull(GuildRoster.textProblem("Star7", 2, 12));
        assertNotNull(GuildRoster.textProblem("星", 2, 12), "太短");
        assertNotNull(GuildRoster.textProblem("星 骸", 2, 12), "不能有空白");
        assertNotNull(GuildRoster.textProblem("<red>星骸", 2, 12), "不能有符號（MiniMessage 的標籤也擋掉）");
        assertNotNull(GuildRoster.textProblem("一二三四五", 2, 4), "太長");
        assertNotNull(GuildRoster.textProblem(null, 2, 4));
    }

    @Test
    void createMakesTheFounderLeaderAndRejectsDuplicates() {
        final Guild guild = create(alice, "星骸遠征隊", "星骸");
        assertEquals(alice, guild.leader());
        assertEquals(GuildRank.LEADER, guild.rank(alice));
        assertSame(guild, roster.guildOf(alice));
        assertSame(guild, roster.byName("星骸"), "標籤也找得到");
        assertEquals(GuildRoster.Result.ALREADY_IN_GUILD, roster.create(alice, "A", "別的公會", "別的", 1, config).result());
        assertEquals(GuildRoster.Result.NAME_TAKEN, roster.create(bob, "B", "星骸遠征隊", "遠征", 1, config).result());
        assertEquals(GuildRoster.Result.TAG_TAKEN, roster.create(bob, "B", "另一個公會", "星骸", 1, config).result());
        assertEquals(GuildRoster.Result.BAD_TAG, roster.create(bob, "B", "另一個公會", "太長的標籤", 1, config).result());
        assertNull(roster.guildOf(bob), "失敗的不會留下東西");
    }

    @Test
    void inviteNeedsOfficerAndExpires() {
        final Guild guild = create(alice, "星骸遠征隊", "星骸");
        join(alice, bob, "阿寶");
        assertEquals(GuildRank.MEMBER, guild.rank(bob));
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.invite(bob, carol, 3_000, config), "成員不能邀請");
        assertEquals(GuildRoster.Result.OK, roster.setRank(alice, bob, GuildRank.OFFICER));
        assertEquals(GuildRoster.Result.OK, roster.invite(bob, carol, 3_000, config), "幹部可以");
        assertEquals(GuildRoster.Result.TARGET_IN_GUILD, roster.invite(alice, bob, 3_000, config));
        assertEquals(GuildRoster.Result.SELF, roster.invite(alice, alice, 3_000, config));
        final long expires = 3_000 + config.inviteSeconds() * 1000L;
        assertEquals(1, roster.invitesFor(carol, expires - 1).size());
        assertTrue(roster.invitesFor(carol, expires).isEmpty(), "過期了");
        assertEquals(GuildRoster.Result.NO_INVITE, roster.accept(carol, "小卡", null, expires, config).result());
        assertEquals(List.of(carol), List.copyOf(roster.expire(expires).keySet()), "過期的要通知");
        assertTrue(roster.expire(expires + 1).isEmpty(), "只通知一次");
    }

    @Test
    void acceptPicksTheNamedGuildOrTheNewest() {
        final Guild first = create(alice, "第一公會", "第一");
        final Guild second = create(bob, "第二公會", "第二");
        assertEquals(GuildRoster.Result.OK, roster.invite(alice, carol, 1_000, config));
        assertEquals(GuildRoster.Result.OK, roster.invite(bob, carol, 1_100, config));
        final GuildRoster.Joined joined = roster.accept(carol, "小卡", first.id(), 1_200, config);
        assertTrue(joined.ok());
        assertSame(first, joined.guild());
        assertTrue(roster.invitesFor(carol, 1_300).isEmpty(), "加入之後其他邀請作廢");
        assertFalse(second.has(carol));
        assertEquals(GuildRoster.Result.ALREADY_IN_GUILD, roster.accept(carol, "小卡", null, 1_300, config).result());
    }

    @Test
    void fullGuildRefusesNewMembers() {
        final GuildConfig small = GuildConfig.of(tw.exilecore.core.config.YamlNode.load("test", new java.io.StringReader("guild:\n  max_members: 2\n")));
        final GuildRoster.Joined created = roster.create(alice, "A", "小公會", "小小", 1, small);
        assertTrue(created.ok());
        assertEquals(GuildRoster.Result.OK, roster.invite(alice, bob, 2, small));
        assertEquals(GuildRoster.Result.OK, roster.invite(alice, carol, 2, small), "還沒滿就能邀");
        assertTrue(roster.accept(bob, "B", null, 3, small).ok());
        assertEquals(GuildRoster.Result.FULL, roster.accept(carol, "C", null, 3, small).result(), "先加入的人把位置佔了");
        assertEquals(GuildRoster.Result.FULL, roster.invite(alice, UUID.randomUUID(), 4, small));
    }

    @Test
    void leaderCannotLeaveWhileOthersRemain() {
        final Guild guild = create(alice, "星骸遠征隊", "星骸");
        join(alice, bob, "阿寶");
        assertEquals(GuildRoster.Result.LEADER_MUST_TRANSFER, roster.leave(alice));
        assertEquals(GuildRoster.Result.OK, roster.transfer(alice, bob));
        assertEquals(bob, guild.leader());
        assertEquals(GuildRank.LEADER, guild.rank(bob));
        assertEquals(GuildRank.OFFICER, guild.rank(alice), "原本的會長變成幹部");
        assertEquals(GuildRoster.Result.OK, roster.leave(alice));
        assertNull(roster.guildOf(alice));
        assertEquals(GuildRoster.Result.DISBANDED, roster.leave(bob), "最後一個人離開就解散");
        assertTrue(roster.all().isEmpty());
        assertNull(roster.guildOf(bob));
    }

    @Test
    void kickFollowsRanks() {
        final Guild guild = create(alice, "星骸遠征隊", "星骸");
        join(alice, bob, "阿寶");
        join(alice, carol, "小卡");
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.kick(bob, carol), "成員不能請人");
        roster.setRank(alice, bob, GuildRank.OFFICER);
        roster.setRank(alice, carol, GuildRank.OFFICER);
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.kick(bob, carol), "幹部不能請幹部");
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.kick(bob, alice), "也不能請會長");
        assertEquals(GuildRoster.Result.ALREADY_RANK, roster.setRank(alice, carol, GuildRank.OFFICER));
        assertEquals(GuildRoster.Result.OK, roster.setRank(alice, carol, GuildRank.MEMBER));
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.setRank(bob, carol, GuildRank.OFFICER), "只有會長能升降階");
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.setRank(alice, carol, GuildRank.LEADER), "會長要用交出會長");
        assertEquals(GuildRoster.Result.OK, roster.kick(bob, carol));
        assertFalse(guild.has(carol));
        assertNull(roster.guildOf(carol));
        assertEquals(GuildRoster.Result.NOT_MEMBER, roster.kick(alice, carol));
        assertEquals(GuildRoster.Result.SELF, roster.kick(alice, alice));
    }

    @Test
    void disbandFreesEveryoneAndDropsInvites() {
        create(alice, "星骸遠征隊", "星骸");
        join(alice, bob, "阿寶");
        assertEquals(GuildRoster.Result.OK, roster.invite(alice, carol, 5_000, config));
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.disband(bob));
        assertEquals(GuildRoster.Result.OK, roster.disband(alice));
        assertNull(roster.guildOf(alice));
        assertNull(roster.guildOf(bob));
        assertTrue(roster.invitesFor(carol, 5_001).isEmpty());
        assertTrue(create(bob, "星骸遠征隊", "星骸").id() > 0, "名字空出來了");
    }

    @Test
    void renameIsLeaderOnlyAndKeepsNamesUnique() {
        final Guild guild = create(alice, "星骸遠征隊", "星骸");
        create(bob, "第二公會", "第二");
        join(alice, carol, "小卡");
        assertEquals(GuildRoster.Result.NO_PERMISSION, roster.rename(carol, "新名字", "新名", config), "只有會長");
        assertEquals(GuildRoster.Result.SAME_NAME, roster.rename(alice, "星骸遠征隊", "星骸", config));
        assertEquals(GuildRoster.Result.NAME_TAKEN, roster.rename(alice, "第二公會", "星骸", config));
        assertEquals(GuildRoster.Result.TAG_TAKEN, roster.rename(alice, "星骸遠征隊", "第二", config));
        assertEquals(GuildRoster.Result.BAD_TAG, roster.rename(alice, "星骸遠征隊", "太長的標籤", config));
        assertEquals(GuildRoster.Result.BAD_NAME, roster.rename(alice, "有 空白", "星骸", config));
        assertEquals("星骸遠征隊", guild.name(), "失敗的不會改到");
        assertEquals(GuildRoster.Result.OK, roster.rename(alice, "星骸遠征隊", "遠征", config), "可以只改標籤");
        assertEquals("遠征", guild.tag());
        assertEquals(GuildRoster.Result.OK, roster.rename(alice, "晨星", "晨星", config));
        assertSame(guild, roster.byName("晨星"));
        assertNull(roster.byName("星骸遠征隊"), "舊名字空出來了");
        assertSame(guild, roster.guildOf(carol), "成員還在");
    }

    @Test
    void experienceLevelsUpAndStopsAtTheCap() {
        final Guild guild = create(alice, "星骸遠征隊", "星骸");
        final long first = config.xpToNext(1);
        assertEquals(0, guild.addExperience(first - 1, config));
        assertEquals(1, guild.addExperience(1, config));
        assertEquals(2, guild.level());
        assertEquals(0, guild.experience());
        assertEquals(first, guild.totalExperience(config));
        final int gained = guild.addExperience(Long.MAX_VALUE / 4, config);
        assertEquals(config.maxLevel(), guild.level());
        assertEquals(config.maxLevel() - 2, gained);
        assertEquals(0, config.xpToNext(config.maxLevel()), "滿級沒有下一級");
        final long before = guild.experience();
        assertEquals(0, guild.addExperience(500, config));
        assertEquals(before + 500, guild.experience(), "滿級之後照樣累積（排行用）");
        guild.contribute(alice, 700);
        assertEquals(700, guild.member(alice).contribution());
    }

    @Test
    void topSortsByLevelThenExperience() {
        final Guild a = create(alice, "第一公會", "第一");
        final Guild b = create(bob, "第二公會", "第二");
        final Guild c = create(carol, "第三公會", "第三");
        b.addExperience(config.xpToNext(1), config);
        c.addExperience(10, config);
        assertEquals(List.of(b, c, a), roster.top(config));
    }

    @Test
    void loadedGuildsKeepTheirIdsAndNewOnesContinueAfter() {
        final Guild stored = new Guild(41, "舊公會", "舊的", alice, 3, 12, "公告", GuildRank.OFFICER, 99);
        stored.put(new Guild.Member(alice, "A", GuildRank.LEADER, 99, 5));
        roster.load(stored);
        assertSame(stored, roster.guildOf(alice));
        assertEquals(42, create(bob, "新公會", "新的").id());
    }
}
