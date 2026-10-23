package tw.exilecore.guild;

import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * data/guilds.yml：建立公會的門檻、人數、公會等級與經驗、每一級的加成、公會倉庫。純資料，不依賴 Bukkit。
 * 沒有這個檔時用這裡的預設值；寫錯時保留原本的設定（先讀進一份新的，全部檢查過才換上去）。
 */
public final class GuildConfig {

    public static final String FILE = "guilds.yml";
    /** 加成的來源（屬性表上看得到）。 */
    public static final String SOURCE = "公會加成";

    /** 公會到這一級（含）以上就有的加成。 */
    public record Bonus(int level, List<Modifier> stats) {
        public Bonus {
            stats = List.copyOf(stats);
        }
    }

    private int createLevel;
    private long createCost;
    private int maxMembers;
    private int nameMin;
    private int nameMax;
    private int tagMin;
    private int tagMax;
    private int inviteSeconds;
    private int motdLength;
    private int maxLevel;
    private double xpBase;
    private double xpExponent;
    private final Map<MonsterRarity, Integer> killXp = new EnumMap<>(MonsterRarity.class);
    private int bossXp;
    private int killLevelGap;
    private double donateXpPerGold;
    private long donateMin;
    private final List<Bonus> bonuses = new ArrayList<>();
    private int stashBasePages;
    private final List<Integer> stashPageLevels = new ArrayList<>();
    private GuildRank defaultWithdrawRank;

    public GuildConfig() {
        reset();
    }

    private void reset() {
        createLevel = 30;
        createCost = 50_000;
        maxMembers = 40;
        nameMin = 2;
        nameMax = 12;
        tagMin = 2;
        tagMax = 4;
        inviteSeconds = 120;
        motdLength = 60;
        maxLevel = 20;
        xpBase = 20_000;
        xpExponent = 1.8;
        killXp.clear();
        killXp.put(MonsterRarity.NORMAL, 1);
        killXp.put(MonsterRarity.MAGIC, 3);
        killXp.put(MonsterRarity.RARE, 10);
        killXp.put(MonsterRarity.UNIQUE, 50);
        bossXp = 200;
        killLevelGap = 10;
        donateXpPerGold = 0.01;
        donateMin = 1_000;
        bonuses.clear();
        stashBasePages = 1;
        stashPageLevels.clear();
        stashPageLevels.addAll(List.of(5, 10, 15, 20));
        defaultWithdrawRank = GuildRank.MEMBER;
    }

    /** 讀 guilds.yml；先讀進一份新的，全部檢查過才換上去。 */
    public void load(final Path dataFolder) throws IOException {
        final GuildConfig fresh = new GuildConfig();
        final Path file = dataFolder.resolve(FILE);
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    /** 從 YAML 讀（測試用）。 */
    public static GuildConfig of(final YamlNode root) {
        final GuildConfig config = new GuildConfig();
        config.parse(root);
        return config;
    }

    private void copyFrom(final GuildConfig other) {
        createLevel = other.createLevel;
        createCost = other.createCost;
        maxMembers = other.maxMembers;
        nameMin = other.nameMin;
        nameMax = other.nameMax;
        tagMin = other.tagMin;
        tagMax = other.tagMax;
        inviteSeconds = other.inviteSeconds;
        motdLength = other.motdLength;
        maxLevel = other.maxLevel;
        xpBase = other.xpBase;
        xpExponent = other.xpExponent;
        killXp.clear();
        killXp.putAll(other.killXp);
        bossXp = other.bossXp;
        killLevelGap = other.killLevelGap;
        donateXpPerGold = other.donateXpPerGold;
        donateMin = other.donateMin;
        bonuses.clear();
        bonuses.addAll(other.bonuses);
        stashBasePages = other.stashBasePages;
        stashPageLevels.clear();
        stashPageLevels.addAll(other.stashPageLevels);
        defaultWithdrawRank = other.defaultWithdrawRank;
    }

    private void parse(final YamlNode root) {
        final YamlNode guild = root.node("guild");
        createLevel = Math.max(1, guild.integer("create_level", createLevel));
        createCost = Math.max(0, Math.round(guild.decimal("create_cost", createCost)));
        maxMembers = guild.integer("max_members", maxMembers);
        if (maxMembers < 2 || maxMembers > 200) {
            throw new IllegalArgumentException(guild.path() + ".max_members：要在 2 到 200 之間，卻是 " + maxMembers);
        }
        final int[] name = lengths(guild, "name_length", nameMin, nameMax, 24);
        nameMin = name[0];
        nameMax = name[1];
        final int[] tag = lengths(guild, "tag_length", tagMin, tagMax, 8);
        tagMin = tag[0];
        tagMax = tag[1];
        inviteSeconds = Math.max(10, guild.integer("invite_seconds", inviteSeconds));
        motdLength = Math.max(10, Math.min(120, guild.integer("motd_length", motdLength)));

        final YamlNode level = root.node("level");
        maxLevel = level.integer("max", maxLevel);
        if (maxLevel < 1 || maxLevel > 100) {
            throw new IllegalArgumentException(level.path() + ".max：要在 1 到 100 之間，卻是 " + maxLevel);
        }
        xpBase = level.decimal("xp_base", xpBase);
        xpExponent = level.decimal("xp_exponent", xpExponent);
        if (!(xpBase >= 1) || xpExponent < 0 || xpExponent > 5) {
            throw new IllegalArgumentException(level.path() + "：xp_base 要 ≥ 1、xp_exponent 要在 0 到 5 之間");
        }
        final YamlNode kills = level.node("xp_per_kill");
        for (final String key : kills.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(kills.path() + "：不認得的稀有度「" + key + "」（normal、magic、rare、unique）");
            }
            killXp.put(rarity, Math.max(0, kills.integer(key, 0)));
        }
        bossXp = Math.max(0, level.integer("boss_xp", bossXp));
        killLevelGap = Math.max(0, level.integer("xp_level_gap", killLevelGap));
        donateXpPerGold = Math.max(0, level.decimal("donate_xp_per_gold", donateXpPerGold));
        donateMin = Math.max(1, Math.round(level.decimal("donate_min", donateMin)));

        if (root.has("bonuses")) {
            bonuses.clear();
            for (final YamlNode node : root.list("bonuses")) {
                final int at = node.integer("level", 0);
                if (at < 1 || at > maxLevel) {
                    throw new IllegalArgumentException(node.path() + ".level：要在 1 到 " + maxLevel + " 之間，卻是 " + at);
                }
                final List<Modifier> stats = StatLines.parse(node.list("stats"), SOURCE);
                if (stats.isEmpty()) {
                    throw new IllegalArgumentException(node.path() + ".stats：至少要有一條加成");
                }
                for (final Modifier stat : stats) {
                    final String problem = StatLines.problem(stat);
                    if (problem != null) {
                        throw new IllegalArgumentException(node.path() + ".stats：" + problem);
                    }
                }
                bonuses.add(new Bonus(at, stats));
            }
            bonuses.sort(Comparator.comparingInt(Bonus::level));
        }

        final YamlNode stash = root.node("stash");
        stashBasePages = Math.max(1, Math.min(20, stash.integer("pages", stashBasePages))); // 2026-10-18：下限 0 → 1（0 頁時打開倉庫 IndexOutOfBounds）
        if (stash.has("page_levels")) {
            stashPageLevels.clear();
            for (final String text : stash.strings("page_levels")) {
                try {
                    stashPageLevels.add(Integer.parseInt(text.trim()));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(stash.path() + ".page_levels：要是公會等級（數字），卻是「" + text + "」");
                }
            }
        }
        final String withdraw = stash.string("withdraw_rank", null);
        if (withdraw != null) {
            final GuildRank rank = GuildRank.parse(withdraw, null);
            if (rank == null) {
                throw new IllegalArgumentException(stash.path() + ".withdraw_rank：要是 member、officer 或 leader，卻是「" + withdraw + "」");
            }
            defaultWithdrawRank = rank;
        }
    }

    private static int[] lengths(final YamlNode node, final String key, final int min, final int max, final int cap) {
        if (!node.has(key)) {
            return new int[] {min, max};
        }
        final int[] range = node.range(key);
        if (range[0] < 1 || range[1] < range[0] || range[1] > cap) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要寫 [最少, 最多]，1 到 " + cap + " 個字，卻是 [" + range[0] + ", " + range[1] + "]");
        }
        return range;
    }

    // ---- 設定值 ----

    /** 建立公會的角色要幾級。 */
    public int createLevel() {
        return createLevel;
    }

    /** 建立公會要付多少金幣。 */
    public long createCost() {
        return createCost;
    }

    public int maxMembers() {
        return maxMembers;
    }

    public int nameMin() {
        return nameMin;
    }

    public int nameMax() {
        return nameMax;
    }

    public int tagMin() {
        return tagMin;
    }

    public int tagMax() {
        return tagMax;
    }

    public int inviteSeconds() {
        return inviteSeconds;
    }

    public int motdLength() {
        return motdLength;
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** 從 level 級升到下一級要多少公會經驗；已經滿級回傳 0。 */
    public long xpToNext(final int level) {
        if (level >= maxLevel) {
            return 0;
        }
        return Math.max(1, Math.round(xpBase * Math.pow(Math.max(1, level), xpExponent)));
    }

    /** 殺一隻怪給公會多少經驗（Boss 另外算）。 */
    public int killXp(final MonsterRarity rarity, final boolean boss) {
        return boss ? bossXp : killXp.getOrDefault(rarity, 0);
    }

    /** 怪物比自己低超過幾級就不給公會經驗（0 = 不限制）。 */
    public int killLevelGap() {
        return killLevelGap;
    }

    /** 捐 gold 金幣換多少公會經驗（無條件捨去）。 */
    public long donateXp(final long gold) {
        return (long) Math.floor(Math.max(0, gold) * donateXpPerGold);
    }

    public double donateXpPerGold() {
        return donateXpPerGold;
    }

    /** 一次最少捐多少金幣。 */
    public long donateMin() {
        return donateMin;
    }

    /** 全部的加成（照等級排）。 */
    public List<Bonus> bonuses() {
        return List.copyOf(bonuses);
    }

    /** level 級的公會，成員身上有哪些加成（同一種加起來）。 */
    public List<Modifier> modifiers(final int level) {
        final List<Modifier> all = new ArrayList<>();
        for (final Bonus bonus : bonuses) {
            if (bonus.level() <= level) {
                all.addAll(bonus.stats());
            }
        }
        return all.isEmpty() ? List.of() : StatLines.merge(all, SOURCE);
    }

    /** level 級的公會有幾頁倉庫。 */
    public int stashPages(final int level) {
        int pages = stashBasePages;
        for (final int at : stashPageLevels) {
            if (level >= at) {
                pages++;
            }
        }
        return pages;
    }

    /** 新公會預設誰能從倉庫拿東西。 */
    public GuildRank defaultWithdrawRank() {
        return defaultWithdrawRank;
    }
}
