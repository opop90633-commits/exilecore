package tw.exilecore.guild;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 所有公會與邀請（純邏輯，不依賴 Bukkit，可以單元測試）：建立、邀請、加入、離開、請出、升降階、交出會長、解散。
 * 這裡只管規則與記憶體裡的資料；存檔、訊息、金幣在 {@link GuildService}。只在主執行緒使用。
 */
public final class GuildRoster {

    /** 一個動作的結果。 */
    public enum Result {
        OK,
        /** 最後一個人離開：公會解散了。 */
        DISBANDED,
        BAD_NAME, BAD_TAG, NAME_TAKEN, TAG_TAKEN,
        ALREADY_IN_GUILD, NOT_IN_GUILD, NO_PERMISSION, FULL, SELF,
        /** 對方不是這個公會的成員。 */
        NOT_MEMBER,
        /** 對方已經在別的（或這個）公會裡。 */
        TARGET_IN_GUILD,
        NO_INVITE,
        /** 會長要先交出會長（或解散）才能離開。 */
        LEADER_MUST_TRANSFER,
        /** 已經是那個階級了。 */
        ALREADY_RANK,
        /** 改名：名稱與標籤都跟原本一樣。 */
        SAME_NAME
    }

    /** 一筆邀請。 */
    public record Invite(long guildId, UUID inviter, long expiresAt) {
    }

    /** 建立、加入的結果：成功時 guild 是那個公會。 */
    public record Joined(Result result, Guild guild) {
        public boolean ok() {
            return result == Result.OK;
        }
    }

    private final Map<Long, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, Long> memberOf = new HashMap<>();
    /** 被邀請的人 → 公會 → 邀請（照收到的順序）。 */
    private final Map<UUID, Map<Long, Invite>> invites = new HashMap<>();
    private long nextId = 1;

    // ---- 查詢 ----

    public Collection<Guild> all() {
        return Collections.unmodifiableCollection(guilds.values());
    }

    public Guild guild(final long id) {
        return guilds.get(id);
    }

    /** 這個帳號的公會；沒有回傳 null。 */
    public Guild guildOf(final UUID account) {
        final Long id = memberOf.get(account);
        return id == null ? null : guilds.get(id);
    }

    /** 名稱或標籤對得上的公會（不分大小寫）；沒有回傳 null。 */
    public Guild byName(final String text) {
        if (text == null) {
            return null;
        }
        final String wanted = text.strip();
        for (final Guild guild : guilds.values()) {
            if (guild.name().equalsIgnoreCase(wanted)) {
                return guild;
            }
        }
        for (final Guild guild : guilds.values()) {
            if (guild.tag().equalsIgnoreCase(wanted)) {
                return guild;
            }
        }
        return null;
    }

    /** 讀檔時放進來（成員也一起登記）。 */
    public void load(final Guild guild) {
        guilds.put(guild.id(), guild);
        for (final Guild.Member member : guild.members()) {
            memberOf.put(member.uuid(), guild.id());
        }
        nextId = Math.max(nextId, guild.id() + 1);
    }

    public void clear() {
        guilds.clear();
        memberOf.clear();
        invites.clear();
        nextId = 1;
    }

    // ---- 名稱 ----

    /** 名稱（或標籤）哪裡不行；可以回傳 null。只能用文字與數字（中文、英文、數字），不能有空白與符號。 */
    public static String textProblem(final String text, final int min, final int max) {
        if (text == null) {
            return "不能是空的";
        }
        final int length = text.codePointCount(0, text.length());
        if (length < min || length > max) {
            return "要 " + min + " 到 " + max + " 個字（現在 " + length + " 個）";
        }
        final boolean allowed = text.codePoints().allMatch(Character::isLetterOrDigit);
        return allowed ? null : "只能用文字與數字（不能有空白、符號）";
    }

    // ---- 建立 ----

    /** 建立公會：建立的人是會長。 */
    public Joined create(final UUID leader, final String leaderName, final String name, final String tag, final long now,
                         final GuildConfig config) {
        if (memberOf.containsKey(leader)) {
            return new Joined(Result.ALREADY_IN_GUILD, null);
        }
        if (textProblem(name, config.nameMin(), config.nameMax()) != null) {
            return new Joined(Result.BAD_NAME, null);
        }
        if (textProblem(tag, config.tagMin(), config.tagMax()) != null) {
            return new Joined(Result.BAD_TAG, null);
        }
        final String lowerName = name.toLowerCase(Locale.ROOT);
        final String lowerTag = tag.toLowerCase(Locale.ROOT);
        for (final Guild other : guilds.values()) {
            if (other.name().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return new Joined(Result.NAME_TAKEN, null);
            }
            if (other.tag().toLowerCase(Locale.ROOT).equals(lowerTag)) {
                return new Joined(Result.TAG_TAKEN, null);
            }
        }
        final Guild guild = new Guild(nextId++, name, tag, leader, 1, 0, "", config.defaultWithdrawRank(), now);
        guild.put(new Guild.Member(leader, leaderName, GuildRank.LEADER, now, 0));
        guilds.put(guild.id(), guild);
        memberOf.put(leader, guild.id());
        invites.remove(leader);
        return new Joined(Result.OK, guild);
    }

    // ---- 邀請 ----

    /** 邀請（幹部以上）。同一個人再邀一次就是延長期限。 */
    public Result invite(final UUID inviter, final UUID target, final long now, final GuildConfig config) {
        final Guild guild = guildOf(inviter);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (inviter.equals(target)) {
            return Result.SELF;
        }
        if (!guild.rank(inviter).atLeast(GuildRank.OFFICER)) {
            return Result.NO_PERMISSION;
        }
        if (memberOf.containsKey(target)) {
            return Result.TARGET_IN_GUILD;
        }
        if (guild.size() >= config.maxMembers()) {
            return Result.FULL;
        }
        final Map<Long, Invite> mine = invites.computeIfAbsent(target, key -> new LinkedHashMap<>());
        mine.remove(guild.id()); // 重新邀請：排到最後（最新）
        mine.put(guild.id(), new Invite(guild.id(), inviter, now + config.inviteSeconds() * 1000L));
        return Result.OK;
    }

    /** 這個人手上還有效的邀請（舊的在前）。 */
    public List<Invite> invitesFor(final UUID player, final long now) {
        final Map<Long, Invite> mine = invites.get(player);
        if (mine == null) {
            return List.of();
        }
        final List<Invite> list = new ArrayList<>();
        for (final Invite invite : mine.values()) {
            if (invite.expiresAt() > now && guilds.containsKey(invite.guildId())) {
                list.add(invite);
            }
        }
        return list;
    }

    /** 接受邀請（guildId 是 null = 最新的一筆）。 */
    public Joined accept(final UUID player, final String playerName, final Long guildId, final long now, final GuildConfig config) {
        if (memberOf.containsKey(player)) {
            return new Joined(Result.ALREADY_IN_GUILD, null);
        }
        final List<Invite> valid = invitesFor(player, now);
        Invite chosen = null;
        for (final Invite invite : valid) {
            if (guildId == null || invite.guildId() == guildId) {
                chosen = invite; // 沒指定：留到最後一筆（最新）
            }
        }
        if (chosen == null) {
            return new Joined(Result.NO_INVITE, null);
        }
        final Guild guild = guilds.get(chosen.guildId());
        if (guild.size() >= config.maxMembers()) {
            return new Joined(Result.FULL, guild);
        }
        guild.put(new Guild.Member(player, playerName, GuildRank.MEMBER, now, 0));
        memberOf.put(player, guild.id());
        invites.remove(player);
        return new Joined(Result.OK, guild);
    }

    /** 拒絕邀請（guildId 是 null = 全部）。 */
    public Result decline(final UUID player, final Long guildId) {
        final Map<Long, Invite> mine = invites.get(player);
        if (mine == null || mine.isEmpty()) {
            return Result.NO_INVITE;
        }
        if (guildId == null) {
            invites.remove(player);
            return Result.OK;
        }
        if (mine.remove(guildId) == null) {
            return Result.NO_INVITE;
        }
        if (mine.isEmpty()) {
            invites.remove(player);
        }
        return Result.OK;
    }

    /** 拿掉過期的邀請：被邀請的人 → 過期的那幾筆（給雙方說一聲）。 */
    public Map<UUID, List<Invite>> expire(final long now) {
        final Map<UUID, List<Invite>> expired = new LinkedHashMap<>();
        final Iterator<Map.Entry<UUID, Map<Long, Invite>>> iterator = invites.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Map<Long, Invite>> entry = iterator.next();
            final Iterator<Invite> each = entry.getValue().values().iterator();
            while (each.hasNext()) {
                final Invite invite = each.next();
                if (invite.expiresAt() <= now || !guilds.containsKey(invite.guildId())) {
                    each.remove();
                    if (guilds.containsKey(invite.guildId())) {
                        expired.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(invite);
                    }
                }
            }
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        return expired;
    }

    // ---- 離開、請出 ----

    /** 離開公會。會長要先交出去；公會只剩會長一個人時離開就是解散。 */
    public Result leave(final UUID player) {
        final Guild guild = guildOf(player);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (guild.leader().equals(player)) {
            if (guild.size() > 1) {
                return Result.LEADER_MUST_TRANSFER;
            }
            remove(guild.id());
            return Result.DISBANDED;
        }
        guild.remove(player);
        memberOf.remove(player);
        return Result.OK;
    }

    /** 請出公會：幹部可以請成員，會長可以請幹部與成員。 */
    public Result kick(final UUID actor, final UUID target) {
        final Guild guild = guildOf(actor);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (actor.equals(target)) {
            return Result.SELF;
        }
        final GuildRank mine = guild.rank(actor);
        final GuildRank theirs = guild.rank(target);
        if (theirs == null) {
            return Result.NOT_MEMBER;
        }
        if (!mine.atLeast(GuildRank.OFFICER) || mine.code() <= theirs.code()) {
            return Result.NO_PERMISSION;
        }
        guild.remove(target);
        memberOf.remove(target);
        return Result.OK;
    }

    /** 改階級（只有會長；只能在成員與幹部之間換，會長用 {@link #transfer}）。 */
    public Result setRank(final UUID actor, final UUID target, final GuildRank rank) {
        final Guild guild = guildOf(actor);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (!guild.leader().equals(actor) || rank == GuildRank.LEADER) {
            return Result.NO_PERMISSION;
        }
        if (actor.equals(target)) {
            return Result.SELF;
        }
        final Guild.Member member = guild.member(target);
        if (member == null) {
            return Result.NOT_MEMBER;
        }
        if (member.rank() == rank) {
            return Result.ALREADY_RANK;
        }
        guild.put(member.withRank(rank));
        return Result.OK;
    }

    /** 交出會長：原本的會長變成幹部。 */
    public Result transfer(final UUID actor, final UUID target) {
        final Guild guild = guildOf(actor);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (!guild.leader().equals(actor)) {
            return Result.NO_PERMISSION;
        }
        if (actor.equals(target)) {
            return Result.SELF;
        }
        final Guild.Member next = guild.member(target);
        if (next == null) {
            return Result.NOT_MEMBER;
        }
        guild.put(guild.member(actor).withRank(GuildRank.OFFICER));
        guild.put(next.withRank(GuildRank.LEADER));
        guild.setLeader(target);
        return Result.OK;
    }

    /** 改名（只有會長）：名稱與標籤一起換（可以只換其中一個）；不能跟別的公會撞名。 */
    public Result rename(final UUID actor, final String name, final String tag, final GuildConfig config) {
        final Guild guild = guildOf(actor);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (!guild.leader().equals(actor)) {
            return Result.NO_PERMISSION;
        }
        return retitle(guild, name, tag, config);
    }

    /** 改名的規則（會長與管理員共用）：長度、只能文字與數字、不能跟別的公會撞名。 */
    private Result retitle(final Guild guild, final String name, final String tag, final GuildConfig config) {
        if (textProblem(name, config.nameMin(), config.nameMax()) != null) {
            return Result.BAD_NAME;
        }
        if (textProblem(tag, config.tagMin(), config.tagMax()) != null) {
            return Result.BAD_TAG;
        }
        if (guild.name().equals(name) && guild.tag().equals(tag)) {
            return Result.SAME_NAME;
        }
        final String lowerName = name.toLowerCase(Locale.ROOT);
        final String lowerTag = tag.toLowerCase(Locale.ROOT);
        for (final Guild other : guilds.values()) {
            if (other == guild) {
                continue;
            }
            if (other.name().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return Result.NAME_TAKEN;
            }
            if (other.tag().toLowerCase(Locale.ROOT).equals(lowerTag)) {
                return Result.TAG_TAKEN;
            }
        }
        guild.retitle(name, tag);
        return Result.OK;
    }

    /** 解散（只有會長）。 */
    public Result disband(final UUID actor) {
        final Guild guild = guildOf(actor);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (!guild.leader().equals(actor)) {
            return Result.NO_PERMISSION;
        }
        remove(guild.id());
        return Result.OK;
    }

    /** 整個公會拿掉（解散、管理員）：成員都變成沒有公會，發出去的邀請作廢。 */
    public Guild remove(final long guildId) {
        final Guild guild = guilds.remove(guildId);
        if (guild == null) {
            return null;
        }
        for (final Guild.Member member : guild.members()) {
            memberOf.remove(member.uuid());
        }
        for (final Map<Long, Invite> mine : invites.values()) {
            mine.remove(guildId);
        }
        invites.values().removeIf(Map::isEmpty);
        return guild;
    }

    // ---- 管理員（不看階級、不用邀請；2026-10） ----

    /** 一個成員跟他的公會。 */
    public record Membership(Guild guild, Guild.Member member) {
    }

    /** 記下來的名字對得上的成員（所有公會裡找，不分大小寫）；沒有回傳 null。 */
    public Membership memberByName(final String name) {
        for (final Guild guild : guilds.values()) {
            final Guild.Member member = guild.memberByName(name);
            if (member != null) {
                return new Membership(guild, member);
            }
        }
        return null;
    }

    /** 管理員指定會長（會長不上線了、帳號被停權）：原本的會長變成幹部。 */
    public Result adminLeader(final long guildId, final UUID target) {
        final Guild guild = guilds.get(guildId);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        final Guild.Member next = guild.member(target);
        if (next == null) {
            return Result.NOT_MEMBER;
        }
        if (guild.leader().equals(target)) {
            return Result.ALREADY_RANK;
        }
        final Guild.Member old = guild.member(guild.leader());
        if (old != null) {
            guild.put(old.withRank(GuildRank.OFFICER));
        }
        guild.put(next.withRank(GuildRank.LEADER));
        guild.setLeader(target);
        return Result.OK;
    }

    /** 管理員改階級（成員、幹部之間；會長用 {@link #adminLeader}）。 */
    public Result adminRank(final long guildId, final UUID target, final GuildRank rank) {
        final Guild guild = guilds.get(guildId);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (rank == GuildRank.LEADER) {
            return Result.NO_PERMISSION;
        }
        final Guild.Member member = guild.member(target);
        if (member == null) {
            return Result.NOT_MEMBER;
        }
        if (guild.leader().equals(target)) {
            return Result.LEADER_MUST_TRANSFER;
        }
        if (member.rank() == rank) {
            return Result.ALREADY_RANK;
        }
        guild.put(member.withRank(rank));
        return Result.OK;
    }

    /** 管理員把人請出公會（會長不行：先指定別的會長，或解散）。 */
    public Result adminKick(final long guildId, final UUID target) {
        final Guild guild = guilds.get(guildId);
        if (guild == null) {
            return Result.NOT_IN_GUILD;
        }
        if (!guild.has(target)) {
            return Result.NOT_MEMBER;
        }
        if (guild.leader().equals(target)) {
            return Result.LEADER_MUST_TRANSFER;
        }
        guild.remove(target);
        memberOf.remove(target);
        return Result.OK;
    }

    /** 管理員把人直接放進公會（不用邀請；人數上限照算）。 */
    public Joined adminAdd(final long guildId, final UUID player, final String playerName, final long now, final GuildConfig config) {
        final Guild guild = guilds.get(guildId);
        if (guild == null) {
            return new Joined(Result.NOT_IN_GUILD, null);
        }
        if (memberOf.containsKey(player)) {
            return new Joined(Result.TARGET_IN_GUILD, guildOf(player));
        }
        if (guild.size() >= config.maxMembers()) {
            return new Joined(Result.FULL, guild);
        }
        guild.put(new Guild.Member(player, playerName, GuildRank.MEMBER, now, 0));
        memberOf.put(player, guild.id());
        invites.remove(player);
        return new Joined(Result.OK, guild);
    }

    /** 管理員改公會的名稱與標籤（不雅的名字；規則跟會長改名一樣，不用改名卡）。 */
    public Result adminRename(final long guildId, final String name, final String tag, final GuildConfig config) {
        final Guild guild = guilds.get(guildId);
        return guild == null ? Result.NOT_IN_GUILD : retitle(guild, name, tag, config);
    }

    /** 管理員設等級（1 到滿級；這一級的經驗歸零）。回傳原本的等級；沒有這個公會回傳 0。 */
    public int adminLevel(final long guildId, final int level, final GuildConfig config) {
        final Guild guild = guilds.get(guildId);
        if (guild == null) {
            return 0;
        }
        final int before = guild.level();
        guild.setLevel(level, config);
        return before;
    }

    // ---- 排行 ----

    /** 排行榜：等級高、總經驗多、人多、成立早的在前。 */
    public List<Guild> top(final GuildConfig config) {
        final List<Guild> list = new ArrayList<>(guilds.values());
        list.sort(Comparator.comparingInt(Guild::level).reversed()
                .thenComparing(Comparator.comparingLong((Guild guild) -> guild.totalExperience(config)).reversed())
                .thenComparing(Comparator.comparingInt(Guild::size).reversed())
                .thenComparingLong(Guild::createdAt));
        return list;
    }
}
