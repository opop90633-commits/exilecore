package tw.exilecore.party;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.monster.DamageLedger;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 組隊：名單規則、多人設定（怪物生命、經驗分配）、傷害紀錄與掉落分份。 */
class PartyTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final long INVITE = 1200;

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    // ---- 名單 ----

    @Test
    void inviteAndAcceptFormsAParty() {
        final PartyRoster roster = new PartyRoster(6, INVITE);
        final UUID a = id(1);
        final UUID b = id(2);
        assertEquals(PartyRoster.Result.OK, roster.invite(a, b, 0));
        assertNull(roster.partyOf(a), "接受之前還沒有隊伍");
        assertEquals(PartyRoster.Result.OK, roster.accept(b, null, 10));
        final PartyRoster.Party party = roster.partyOf(a);
        assertNotNull(party);
        assertEquals(a, party.leader());
        assertEquals(List.of(a, b), List.copyOf(party.members()));
        assertTrue(roster.sameGroup(a, b));
        assertEquals(roster.groupOf(a), roster.groupOf(b));
        assertTrue(roster.sameGroup(id(9), id(9)), "自己跟自己算同一組");
        assertFalse(roster.sameGroup(a, id(9)));
        assertEquals(id(9), roster.groupOf(id(9)), "沒隊伍的人自己一組");
    }

    @Test
    void onlyLeaderInvitesAndPartiesFillUp() {
        final PartyRoster roster = new PartyRoster(3, INVITE);
        final UUID a = id(1);
        roster.invite(a, id(2), 0);
        roster.accept(id(2), a, 0);
        assertEquals(PartyRoster.Result.NOT_LEADER, roster.invite(id(2), id(5), 0));
        assertEquals(PartyRoster.Result.SELF, roster.invite(a, a, 0));
        assertEquals(PartyRoster.Result.ALREADY_MEMBER, roster.invite(a, id(2), 0));
        roster.invite(a, id(3), 0);
        roster.invite(a, id(4), 0);
        assertEquals(PartyRoster.Result.OK, roster.accept(id(3), a, 0));
        assertEquals(PartyRoster.Result.FULL, roster.accept(id(4), a, 0), "滿了（3 人）");
        assertEquals(PartyRoster.Result.FULL, roster.invite(a, id(6), 0));
        // 別隊的人不能邀
        roster.invite(id(7), id(8), 0);
        roster.accept(id(8), id(7), 0);
        assertEquals(PartyRoster.Result.IN_OTHER_PARTY, roster.invite(a, id(8), 0));
    }

    @Test
    void invitesExpireAndLatestIsAccepted() {
        final PartyRoster roster = new PartyRoster(6, INVITE);
        final UUID target = id(9);
        roster.invite(id(1), target, 0);
        roster.invite(id(2), target, 100);
        assertEquals(2, roster.invitesFor(target, 200).size());
        // 第一筆在 1200 過期，第二筆在 1300
        assertEquals(1, roster.invitesFor(target, 1250).size());
        final Map<UUID, List<PartyRoster.Invite>> expired = roster.expire(1250);
        assertEquals(List.of(id(1)), expired.get(target).stream().map(PartyRoster.Invite::inviter).toList());
        assertEquals(PartyRoster.Result.NO_INVITE, roster.accept(target, id(1), 1250));
        assertEquals(PartyRoster.Result.OK, roster.accept(target, null, 1250), "不指定就接受最新的");
        assertEquals(id(2), roster.partyOf(target).leader());
        assertEquals(PartyRoster.Result.NO_INVITE, roster.accept(id(10), null, 0));
    }

    @Test
    void declineRemovesTheInvite() {
        final PartyRoster roster = new PartyRoster(6, INVITE);
        roster.invite(id(1), id(2), 0);
        final PartyRoster.Invite declined = roster.decline(id(2), null, 5);
        assertNotNull(declined);
        assertEquals(id(1), declined.inviter());
        assertEquals(PartyRoster.Result.NO_INVITE, roster.accept(id(2), null, 5));
        assertNull(roster.decline(id(2), null, 5));
    }

    @Test
    void leaderLeavingPassesLeadAndTwoPersonPartyDisbands() {
        final PartyRoster roster = new PartyRoster(6, INVITE);
        final UUID a = id(1);
        for (int i = 2; i <= 4; i++) {
            roster.invite(a, id(i), 0);
            roster.accept(id(i), a, 0);
        }
        final PartyRoster.Departure left = roster.leave(a);
        assertEquals(PartyRoster.Result.OK, left.result());
        assertEquals(id(2), left.newLeader(), "最早加入的接任");
        assertFalse(left.disbanded());
        assertNull(roster.partyOf(a));
        assertEquals(id(2), roster.partyOf(id(3)).leader());

        assertEquals(PartyRoster.Result.NOT_LEADER, roster.kick(id(3), id(4)).result());
        assertEquals(PartyRoster.Result.SELF, roster.kick(id(2), id(2)).result());
        assertEquals(PartyRoster.Result.NOT_MEMBER, roster.kick(id(2), id(9)).result());
        assertEquals(PartyRoster.Result.OK, roster.promote(id(2), id(4)));
        assertEquals(id(4), roster.partyOf(id(2)).leader());
        final PartyRoster.Departure kicked = roster.kick(id(4), id(3));
        assertEquals(PartyRoster.Result.OK, kicked.result());
        assertNull(roster.partyOf(id(3)));

        // 剩兩個人，一個走了：解散
        final PartyRoster.Departure last = roster.leave(id(2));
        assertTrue(last.disbanded());
        assertNull(roster.partyOf(id(4)));
        assertTrue(roster.parties().isEmpty());
        assertEquals(PartyRoster.Result.NOT_IN_PARTY, roster.leave(id(4)).result());
    }

    @Test
    void staleInviteFromNonLeaderIsVoid() {
        final PartyRoster roster = new PartyRoster(6, INVITE);
        // 1 邀請 9，然後 1 自己加入 2 的隊伍（不是隊長）：給 9 的邀請作廢
        roster.invite(id(1), id(9), 0);
        roster.invite(id(2), id(1), 0);
        roster.accept(id(1), id(2), 0);
        assertEquals(PartyRoster.Result.NO_INVITE, roster.accept(id(9), id(1), 0));
        assertNull(roster.partyOf(id(9)));
        assertEquals(PartyRoster.Result.ALREADY_IN_PARTY, roster.accept(id(1), null, 0));
    }

    // ---- 多人設定 ----

    @Test
    void partyFileLoadsWithTheAgreedNumbers() throws IOException {
        final PartyConfig config = new PartyConfig();
        config.load(DATA);
        assertEquals(6, config.maxSize());
        assertEquals(0.05, config.shareThreshold(), 1e-9);
        // 2026-10-27 照 PoE2：每多一人 基本 +50% ＋ 稀有度 魔法 10／稀有 30／傳奇 85（相加）
        //   這次之前普通、魔法、稀有都 +50%（6 人 ×3.5）、傳奇 +100%（6 人 ×6）
        final double[][] expected = {{1.5, 3.5}, {1.6, 4.0}, {1.8, 5.0}};
        final MonsterRarity[] rarities = {MonsterRarity.NORMAL, MonsterRarity.MAGIC, MonsterRarity.RARE};
        for (int i = 0; i < rarities.length; i++) {
            assertEquals(1.0, config.lifeFactor(rarities[i], 1), 1e-9);
            assertEquals(expected[i][0], config.lifeFactor(rarities[i], 2), 1e-9);
            assertEquals(expected[i][1], config.lifeFactor(rarities[i], 6), 1e-9);
            assertEquals(expected[i][1], config.lifeFactor(rarities[i], 9), 1e-9, "最多算 6 人");
        }
        assertEquals(2.35, config.lifeFactor(MonsterRarity.UNIQUE, 2), 1e-9);
        assertEquals(7.75, config.lifeFactor(MonsterRarity.UNIQUE, 6), 1e-9);
        final PartyConfig defaults = new PartyConfig();
        for (final MonsterRarity rarity : MonsterRarity.values()) {
            assertEquals(defaults.lifeFactor(rarity, 6), config.lifeFactor(rarity, 6), 1e-9, "程式的預設值跟出貨的 party.yml 一樣：" + rarity);
        }
        assertEquals(1.0, config.lifeFactor(MonsterRarity.UNIQUE, 0), 1e-9, "至少算 1 人");
        assertEquals(1.2, config.partyBonus(3), 1e-9);
    }

    @Test
    void onlyNearbyMembersCount() throws IOException {
        // 2026-10-26：「附近」＝水平 40 格（一間 Boss 房的直徑）；2026-10-27 起上下也要在 24 格內
        final PartyConfig config = new PartyConfig();
        config.load(DATA);
        assertEquals(40, config.nearbyRadius(), 1e-9);
        assertTrue(config.withinNearby(0, 0));
        assertTrue(config.withinNearby(24, 32), "剛好 40 格算");
        assertFalse(config.withinNearby(30, 30), "42 格不算");
        assertFalse(config.withinNearby(64, 0), "以前經驗的 64 格現在不算");
        // 2026-10-27：上下差超過 24 格不算（這次之前高度不管）
        assertTrue(config.withinNearby(10, 24, 10), "上下剛好 24 格算");
        assertTrue(config.withinNearby(10, -24, 10));
        assertFalse(config.withinNearby(0, 25, 0), "正上方 25 格不算");
        assertFalse(config.withinNearby(0, -40, 0), "正下方很深的洞不算");
        assertFalse(config.withinNearby(30, 0, 30), "水平還是照 40 格");
        // 經驗的多人倍率：刷怪時 4 人，倒下時附近只剩 2 人 → 算 2 人；附近沒人也至少 1 人
        assertEquals(2, PartyConfig.experiencePlayers(4, 2));
        assertEquals(3, PartyConfig.experiencePlayers(3, 5), "後來才走進來的人不算（生命沒有照他加）");
        assertEquals(1, PartyConfig.experiencePlayers(4, 0));
        // 範圍可以在 party.yml 改；0 或負的不行
        final PartyConfig custom = PartyConfig.of(tw.exilecore.core.config.YamlNode.load("party.yml",
                new java.io.StringReader("party:\n  nearby_radius: 48\n")));
        assertEquals(48, custom.nearbyRadius(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> PartyConfig.of(tw.exilecore.core.config.YamlNode.load("party.yml",
                new java.io.StringReader("party:\n  nearby_radius: 0\n"))));
    }

    @Test
    void badPartyFileKeepsOldSettings(@TempDir final Path dir) throws IOException {
        final PartyConfig config = new PartyConfig();
        config.load(DATA);
        Files.writeString(dir.resolve(PartyConfig.FILE), "loot:\n  share_threshold: 5\n");
        assertThrows(IllegalArgumentException.class, () -> config.load(dir), "5 不是 0–1 之間");
        assertEquals(0.05, config.shareThreshold(), 1e-9, "寫錯時保留原本的設定");
        Files.writeString(dir.resolve(PartyConfig.FILE), "monsters:\n  life_per_extra_player:\n    boss: 100\n");
        assertThrows(IllegalArgumentException.class, () -> config.load(dir));
    }

    @Test
    void experienceIsSplitByLevelWeight() {
        final Map<UUID, Integer> equal = new LinkedHashMap<>();
        equal.put(id(1), 40);
        equal.put(id(2), 40);
        final Map<UUID, Double> half = PartyConfig.experienceShares(equal, 2.71);
        assertEquals(0.5, half.get(id(1)), 1e-9);
        assertEquals(0.5, half.get(id(2)), 1e-9);

        // 60 級帶 1 級：1 級只分到很少（防帶練），加起來還是 1
        final Map<UUID, Integer> carry = new LinkedHashMap<>();
        carry.put(id(1), 60);
        carry.put(id(2), 1);
        final Map<UUID, Double> shares = PartyConfig.experienceShares(carry, 2.71);
        assertTrue(shares.get(id(2)) < 0.01, "1 級分到 " + shares.get(id(2)));
        assertEquals(1.0, shares.get(id(1)) + shares.get(id(2)), 1e-9);

        // 兩個同等的人一起打一隻 +50% 生命的怪：各拿 1.5 × 1.1 ÷ 2 = 0.825 份，打一隻怪各出一半力（0.75 份）
        final PartyConfig config = new PartyConfig();
        final double each = config.lifeFactor(MonsterRarity.NORMAL, 2) * config.partyBonus(2) * half.get(id(1));
        assertEquals(0.825, each, 1e-9);
    }

    // ---- 傷害紀錄與掉落分份 ----

    @Test
    void fivePercentOfDamageEarnsAShare() {
        final DamageLedger ledger = new DamageLedger();
        final UUID monster = id(100);
        ledger.record(monster, id(1), 900);
        ledger.record(monster, id(2), 50);   // 剛好 5%
        ledger.record(monster, id(3), 40);   // 4%
        ledger.record(monster, id(4), 10);   // 1%
        ledger.record(monster, id(5), 0);
        ledger.record(monster, id(5), -3);
        ledger.record(monster, id(5), Double.NaN);
        final Map<UUID, Double> damage = ledger.damageTo(monster);
        assertEquals(4, damage.size(), "0、負的、NaN 不記");
        assertEquals(List.of(id(1), id(2)), DamageLedger.qualifying(damage, 0.05));
        assertEquals(List.of(id(1), id(2), id(3), id(4)), DamageLedger.qualifying(damage, 0));
        ledger.forget(monster);
        assertTrue(ledger.damageTo(monster).isEmpty());
        assertTrue(DamageLedger.qualifying(Map.of(), 0.05).isEmpty());
    }

    @Test
    void experienceGoesToTheGroupThatDidTheMostDamage() {
        final Map<UUID, Double> damage = new LinkedHashMap<>();
        damage.put(id(1), 40.0);   // 路人
        damage.put(id(2), 35.0);   // 隊伍 X
        damage.put(id(3), 30.0);   // 隊伍 X
        final UUID partyX = id(50);
        final UUID best = DamageLedger.topGroup(damage, player -> player.equals(id(1)) ? player : partyX);
        assertEquals(partyX, best, "隊伍加起來 65 > 路人 40");
        assertEquals(id(1), DamageLedger.topGroup(damage, player -> player), "沒有隊伍時打最多的人");
        assertNull(DamageLedger.topGroup(Map.of(), player -> player));
    }
}
