package tw.exilecore.party;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 隊伍的名單與規則（純邏輯，時間用 tick 傳進來）：
 * <ul>
 *   <li>隊長邀請（還沒有隊伍的人邀請時，對方接受才成隊，自己當隊長）；邀請在期限內有效。</li>
 *   <li>接受時自己不能已經在別的隊伍裡；隊伍滿了就不能加入。</li>
 *   <li>隊長離開時由最早加入的隊員接任；剩一個人時隊伍解散。</li>
 *   <li>只有隊長能踢人、交出隊長。</li>
 * </ul>
 * 名單只在記憶體裡（伺服器重開就散了，跟 PoE 一樣不存檔）；離線的隊員還在隊伍裡。
 */
public final class PartyRoster {

    public enum Result {
        OK,
        /** 邀請自己。 */
        SELF,
        /** 對方已經在你的隊伍裡。 */
        ALREADY_MEMBER,
        /** 對方在別的隊伍裡。 */
        IN_OTHER_PARTY,
        /** 你在隊伍裡但不是隊長。 */
        NOT_LEADER,
        /** 隊伍滿了。 */
        FULL,
        /** 沒有這個邀請（或已經過期）。 */
        NO_INVITE,
        /** 你已經在一個隊伍裡（要先離開）。 */
        ALREADY_IN_PARTY,
        /** 你不在隊伍裡。 */
        NOT_IN_PARTY,
        /** 對方不是你的隊員。 */
        NOT_MEMBER
    }

    /** 一個隊伍。members 依加入順序（第一個是最早的）。 */
    public static final class Party {
        private final UUID id;
        private UUID leader;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        Party(final UUID id, final UUID leader) {
            this.id = id;
            this.leader = leader;
            this.members.add(leader);
        }

        public UUID id() {
            return id;
        }

        public UUID leader() {
            return leader;
        }

        /** 隊員（依加入順序，唯讀）。 */
        public Set<UUID> members() {
            return Collections.unmodifiableSet(members);
        }

        public int size() {
            return members.size();
        }

        public boolean contains(final UUID player) {
            return members.contains(player);
        }
    }

    /** 一筆邀請：誰邀請的、哪個 tick 過期。 */
    public record Invite(UUID inviter, long expiresAt) {
    }

    /** 離開（或被踢）之後的結果：原本的隊伍、新隊長（沒換是 null）、隊伍是不是解散了。 */
    public record Departure(Result result, Party party, UUID newLeader, boolean disbanded) {
    }

    private int maxSize;
    private long inviteTicks;
    private final Map<UUID, Party> byMember = new HashMap<>();
    private final Map<UUID, Party> byId = new LinkedHashMap<>();
    /** 被邀請的人 → 邀請他的人 → 邀請。 */
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<>();

    public PartyRoster(final int maxSize, final long inviteTicks) {
        this.maxSize = Math.max(2, maxSize);
        this.inviteTicks = Math.max(20, inviteTicks);
    }

    public int maxSize() {
        return maxSize;
    }

    /** 設定重載：人數上限與邀請期限換新（已經超過上限的隊伍不會被拆散，只是不能再加人）。 */
    public void setLimits(final int maxSize, final long inviteTicks) {
        this.maxSize = Math.max(2, maxSize);
        this.inviteTicks = Math.max(20, inviteTicks);
    }

    // ---- 查詢 ----

    /** 這個玩家的隊伍；沒有回傳 null。 */
    public Party partyOf(final UUID player) {
        return byMember.get(player);
    }

    public Collection<Party> parties() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** 同一個人，或在同一個隊伍裡。 */
    public boolean sameGroup(final UUID a, final UUID b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        final Party party = byMember.get(a);
        return party != null && party.contains(b);
    }

    /** 群組代號：隊伍的代號，沒有隊伍就是玩家自己。 */
    public UUID groupOf(final UUID player) {
        final Party party = byMember.get(player);
        return party == null ? player : party.id();
    }

    /** 同一組的人（有隊伍是全部隊員，沒有就只有自己）。 */
    public List<UUID> groupMembers(final UUID player) {
        final Party party = byMember.get(player);
        return party == null ? List.of(player) : List.copyOf(party.members);
    }

    /** 這個人收到、還沒過期的邀請（新的在後）。 */
    public List<Invite> invitesFor(final UUID player, final long now) {
        final Map<UUID, Invite> map = invites.get(player);
        if (map == null) {
            return List.of();
        }
        final List<Invite> result = new ArrayList<>();
        for (final Invite invite : map.values()) {
            if (invite.expiresAt() > now) {
                result.add(invite);
            }
        }
        return result;
    }

    // ---- 操作 ----

    /** from 邀請 to。 */
    public Result invite(final UUID from, final UUID to, final long now) {
        if (from.equals(to)) {
            return Result.SELF;
        }
        final Party party = byMember.get(from);
        if (party != null && party.contains(to)) {
            return Result.ALREADY_MEMBER;
        }
        if (byMember.containsKey(to)) {
            return Result.IN_OTHER_PARTY;
        }
        if (party != null && !party.leader().equals(from)) {
            return Result.NOT_LEADER;
        }
        if (party != null && party.size() >= maxSize) {
            return Result.FULL;
        }
        final Map<UUID, Invite> map = invites.computeIfAbsent(to, key -> new LinkedHashMap<>());
        map.remove(from); // 重新邀請：移到最後、重算期限
        map.put(from, new Invite(from, now + inviteTicks));
        return Result.OK;
    }

    /**
     * to 接受 from 的邀請（from 為 null 時接受最新的一筆）。
     *
     * @return OK 時隊伍可以用 {@link #partyOf} 拿到
     */
    public Result accept(final UUID to, final UUID from, final long now) {
        if (byMember.containsKey(to)) {
            return Result.ALREADY_IN_PARTY;
        }
        final Invite invite = findInvite(to, from, now);
        if (invite == null) {
            return Result.NO_INVITE;
        }
        final UUID inviter = invite.inviter();
        Party party = byMember.get(inviter);
        if (party != null && !party.leader().equals(inviter)) {
            // 邀請的人後來加入了別人的隊伍（不是隊長）：這筆邀請作廢
            removeInvite(to, inviter);
            return Result.NO_INVITE;
        }
        if (party != null && party.size() >= maxSize) {
            return Result.FULL;
        }
        removeInvite(to, inviter);
        if (party == null) {
            party = new Party(UUID.randomUUID(), inviter);
            byId.put(party.id(), party);
            byMember.put(inviter, party);
        }
        party.members.add(to);
        byMember.put(to, party);
        // 加入之後，其他人給他的邀請都作廢
        invites.remove(to);
        return Result.OK;
    }

    /** to 拒絕 from 的邀請（from 為 null 時拒絕最新的一筆）。回傳被拒絕的邀請；沒有回傳 null。 */
    public Invite decline(final UUID to, final UUID from, final long now) {
        final Invite invite = findInvite(to, from, now);
        if (invite != null) {
            removeInvite(to, invite.inviter());
        }
        return invite;
    }

    /** 自己離開。 */
    public Departure leave(final UUID player) {
        final Party party = byMember.get(player);
        if (party == null) {
            return new Departure(Result.NOT_IN_PARTY, null, null, false);
        }
        return remove(party, player);
    }

    /** 隊長踢人。 */
    public Departure kick(final UUID leader, final UUID target) {
        final Party party = byMember.get(leader);
        if (party == null) {
            return new Departure(Result.NOT_IN_PARTY, null, null, false);
        }
        if (!party.leader().equals(leader)) {
            return new Departure(Result.NOT_LEADER, party, null, false);
        }
        if (leader.equals(target)) {
            return new Departure(Result.SELF, party, null, false);
        }
        if (!party.contains(target)) {
            return new Departure(Result.NOT_MEMBER, party, null, false);
        }
        return remove(party, target);
    }

    /** 隊長把隊長交給另一個隊員。 */
    public Result promote(final UUID leader, final UUID target) {
        final Party party = byMember.get(leader);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.leader().equals(leader)) {
            return Result.NOT_LEADER;
        }
        if (leader.equals(target)) {
            return Result.SELF;
        }
        if (!party.contains(target)) {
            return Result.NOT_MEMBER;
        }
        party.leader = target;
        return Result.OK;
    }

    /** 清掉過期的邀請；回傳過期的（被邀請的人 → 邀請），讓呼叫端通知。 */
    public Map<UUID, List<Invite>> expire(final long now) {
        final Map<UUID, List<Invite>> expired = new LinkedHashMap<>();
        final Iterator<Map.Entry<UUID, Map<UUID, Invite>>> outer = invites.entrySet().iterator();
        while (outer.hasNext()) {
            final Map.Entry<UUID, Map<UUID, Invite>> entry = outer.next();
            final Iterator<Invite> inner = entry.getValue().values().iterator();
            while (inner.hasNext()) {
                final Invite invite = inner.next();
                if (invite.expiresAt() <= now) {
                    expired.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(invite);
                    inner.remove();
                }
            }
            if (entry.getValue().isEmpty()) {
                outer.remove();
            }
        }
        return expired;
    }

    /** 全部清掉（模組停用）。 */
    public void clear() {
        byMember.clear();
        byId.clear();
        invites.clear();
    }

    // ---- 內部 ----

    private Departure remove(final Party party, final UUID player) {
        party.members.remove(player);
        byMember.remove(player);
        // 離開的人發出的邀請作廢（例如隊長走了：他之前的邀請不能再把人拉進一個新的隊伍）
        dropInvitesBy(player);
        UUID newLeader = null;
        if (party.members.size() <= 1) {
            // 剩一個人：隊伍解散
            for (final UUID rest : party.members) {
                byMember.remove(rest);
            }
            byId.remove(party.id());
            // 這個隊伍發出、還沒回應的邀請也作廢
            dropInvitesFrom(party);
            return new Departure(Result.OK, party, null, true);
        }
        if (party.leader().equals(player)) {
            newLeader = party.members.iterator().next();
            party.leader = newLeader;
        }
        return new Departure(Result.OK, party, newLeader, false);
    }

    private void dropInvitesBy(final UUID inviter) {
        final Iterator<Map.Entry<UUID, Map<UUID, Invite>>> outer = invites.entrySet().iterator();
        while (outer.hasNext()) {
            final Map<UUID, Invite> map = outer.next().getValue();
            map.remove(inviter);
            if (map.isEmpty()) {
                outer.remove();
            }
        }
    }

    private void dropInvitesFrom(final Party party) {
        final Iterator<Map.Entry<UUID, Map<UUID, Invite>>> outer = invites.entrySet().iterator();
        while (outer.hasNext()) {
            final Map<UUID, Invite> map = outer.next().getValue();
            map.keySet().removeIf(inviter -> party.contains(inviter) || party.leader().equals(inviter));
            if (map.isEmpty()) {
                outer.remove();
            }
        }
    }

    private Invite findInvite(final UUID to, final UUID from, final long now) {
        final Map<UUID, Invite> map = invites.get(to);
        if (map == null) {
            return null;
        }
        if (from != null) {
            final Invite invite = map.get(from);
            return invite != null && invite.expiresAt() > now ? invite : null;
        }
        Invite latest = null;
        for (final Invite invite : map.values()) {
            if (invite.expiresAt() > now) {
                latest = invite; // LinkedHashMap：後面的比較新
            }
        }
        return latest;
    }

    private void removeInvite(final UUID to, final UUID from) {
        final Map<UUID, Invite> map = invites.get(to);
        if (map != null) {
            map.remove(from);
            if (map.isEmpty()) {
                invites.remove(to);
            }
        }
    }
}
