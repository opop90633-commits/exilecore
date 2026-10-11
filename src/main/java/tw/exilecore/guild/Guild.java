package tw.exilecore.guild;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一個公會（純資料與規則，不依賴 Bukkit）：名稱、標籤、會長、等級與經驗、公告、誰能從倉庫拿東西、成員。
 * 成員照帳號算（一個帳號的所有角色都在同一個公會，跟金幣、倉庫一樣）。只在主執行緒使用。
 */
public final class Guild {

    /**
     * 一個成員。
     *
     * @param name         顯示用的名字（上次看到的角色名稱）
     * @param contribution 貢獻的公會經驗（殺怪、捐獻）
     */
    public record Member(UUID uuid, String name, GuildRank rank, long joinedAt, long contribution) {
        public Member withRank(final GuildRank next) {
            return new Member(uuid, name, next, joinedAt, contribution);
        }

        public Member withName(final String next) {
            return new Member(uuid, next, rank, joinedAt, contribution);
        }

        public Member withContribution(final long next) {
            return new Member(uuid, name, rank, joinedAt, Math.max(0, next));
        }
    }

    private final long id;
    private String name;
    private String tag;
    private final long createdAt;
    /** 用星幣多買的倉庫頁（跟等級給的分開算）。 */
    private int boughtPages;
    private UUID leader;
    private int level;
    private long experience;
    private String motd;
    private GuildRank withdrawRank;
    private final Map<UUID, Member> members = new LinkedHashMap<>();

    public Guild(final long id, final String name, final String tag, final UUID leader, final int level, final long experience,
                 final String motd, final GuildRank withdrawRank, final long createdAt) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.leader = leader;
        this.level = Math.max(1, level);
        this.experience = Math.max(0, experience);
        this.motd = motd == null ? "" : motd;
        this.withdrawRank = withdrawRank == null ? GuildRank.MEMBER : withdrawRank;
        this.createdAt = createdAt;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String tag() {
        return tag;
    }

    public long createdAt() {
        return createdAt;
    }

    /** 改名（公會改名卡）：名稱與標籤一起換。規則在 {@link GuildRoster#rename}。 */
    void retitle(final String newName, final String newTag) {
        this.name = newName;
        this.tag = newTag;
    }

    /** 用星幣多買的倉庫頁。 */
    public int boughtPages() {
        return boughtPages;
    }

    public void setBoughtPages(final int pages) {
        this.boughtPages = Math.max(0, pages);
    }

    public UUID leader() {
        return leader;
    }

    public int level() {
        return level;
    }

    /** 這一級累積的經驗（升級後歸零重算；滿級之後照樣累積，排行榜比這個）。 */
    public long experience() {
        return experience;
    }

    public String motd() {
        return motd;
    }

    public void setMotd(final String text) {
        this.motd = text == null ? "" : text;
    }

    /** 這個階級（含）以上才能從倉庫拿東西；放東西進去每個成員都可以。 */
    public GuildRank withdrawRank() {
        return withdrawRank;
    }

    public void setWithdrawRank(final GuildRank rank) {
        this.withdrawRank = rank == null ? GuildRank.MEMBER : rank;
    }

    public int size() {
        return members.size();
    }

    /** 成員（加入的順序；直接是內部的資料，不要改）。 */
    public List<Member> members() {
        return Collections.unmodifiableList(new ArrayList<>(members.values()));
    }

    public Member member(final UUID uuid) {
        return members.get(uuid);
    }

    public boolean has(final UUID uuid) {
        return members.containsKey(uuid);
    }

    /** 這個人的階級；不是成員回傳 null。 */
    public GuildRank rank(final UUID uuid) {
        final Member member = members.get(uuid);
        return member == null ? null : member.rank();
    }

    /** 名字對得上的成員（不分大小寫）；沒有回傳 null。 */
    public Member memberByName(final String name) {
        if (name == null) {
            return null;
        }
        final String wanted = name.strip();
        for (final Member member : members.values()) {
            if (member.name().equalsIgnoreCase(wanted)) {
                return member;
            }
        }
        return null;
    }

    void put(final Member member) {
        members.put(member.uuid(), member);
    }

    Member remove(final UUID uuid) {
        return members.remove(uuid);
    }

    void setLeader(final UUID uuid) {
        this.leader = uuid;
    }

    /** 改成員顯示的名字（換角色、改名）；有改回傳 true。 */
    public boolean rename(final UUID uuid, final String name) {
        final Member member = members.get(uuid);
        if (member == null || name == null || name.isBlank() || member.name().equals(name)) {
            return false;
        }
        members.put(uuid, member.withName(name));
        return true;
    }

    /** 記這個成員貢獻了多少公會經驗。 */
    public void contribute(final UUID uuid, final long amount) {
        final Member member = members.get(uuid);
        if (member != null && amount > 0) {
            members.put(uuid, member.withContribution(member.contribution() + amount));
        }
    }

    /**
     * 加公會經驗，夠了就升級（可以連升好幾級）。滿級之後經驗照樣累積。
     *
     * @return 升了幾級
     */
    public int addExperience(final long amount, final GuildConfig config) {
        if (amount <= 0) {
            return 0;
        }
        experience += amount;
        int gained = 0;
        while (level < config.maxLevel()) {
            final long need = config.xpToNext(level);
            if (need <= 0 || experience < need) {
                break;
            }
            experience -= need;
            level++;
            gained++;
        }
        return gained;
    }

    /** 管理員直接設等級（1 到滿級；這一級累積的經驗歸零）。 */
    void setLevel(final int next, final GuildConfig config) {
        this.level = Math.max(1, Math.min(config.maxLevel(), next));
        this.experience = 0;
    }

    /** 從一級到現在總共拿過多少經驗（排行榜、同級比大小）。 */
    public long totalExperience(final GuildConfig config) {
        long total = experience;
        for (int i = 1; i < level; i++) {
            total += config.xpToNext(i);
        }
        return total;
    }
}
