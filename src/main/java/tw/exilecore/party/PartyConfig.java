package tw.exilecore.party;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * data/party.yml：隊伍的人數與邀請期限、掉落怎麼分、經驗怎麼分、人多時怪物變多硬。純資料，不依賴 Bukkit。
 * 沒有這個檔（或隊伍模組沒啟用）時用這裡的預設值。
 */
public final class PartyConfig {

    public static final String FILE = "party.yml";

    /** 預設：傷害佔 5% 以上就有一份掉落。 */
    public static final double DEFAULT_SHARE_THRESHOLD = 0.05;
    /**
     * 2026-10-26 照站長要求（PoE 的做法：不在附近的隊友拿不到經驗、不算隊伍加成）：「在附近」＝同一個世界、水平距離在一間 Boss 房以內。
     * 地圖的 Boss 房是半徑 20 格的圓（{@code MapLayout.BOSS_RADIUS}），直徑 40：同一間 Boss 房裡任兩個位置都在範圍內。
     * 這次之前：掉落 128 格、經驗 64 格，在地圖裡整張地圖都算。
     */
    public static final double DEFAULT_NEARBY_RADIUS = 40;

    private int maxSize;
    private int inviteSeconds;
    private double shareThreshold;
    private double nearbyRadius;
    private double levelWeightExponent;
    private double bonusPerMember;
    private double countRadius;
    private int maxCountedPlayers;
    private final Map<MonsterRarity, Double> lifePerExtraPlayer = new EnumMap<>(MonsterRarity.class);

    public PartyConfig() {
        reset();
    }

    private void reset() {
        maxSize = 6;
        inviteSeconds = 60;
        shareThreshold = DEFAULT_SHARE_THRESHOLD;
        nearbyRadius = DEFAULT_NEARBY_RADIUS;
        levelWeightExponent = 2.71;
        bonusPerMember = 0.1;
        countRadius = 48;
        maxCountedPlayers = 6;
        lifePerExtraPlayer.clear();
        // 2026-10-27 照 PoE2：基本 50 ＋ 稀有度的 10／30／85（相加）；這次之前 50／50／50／100
        lifePerExtraPlayer.put(MonsterRarity.NORMAL, 50.0);
        lifePerExtraPlayer.put(MonsterRarity.MAGIC, 60.0);
        lifePerExtraPlayer.put(MonsterRarity.RARE, 80.0);
        lifePerExtraPlayer.put(MonsterRarity.UNIQUE, 135.0);
    }

    /** 讀 party.yml；先讀進一份新的，全部檢查過才換上去（寫錯時保留原本的設定）。 */
    public void load(final Path dataFolder) throws IOException {
        final PartyConfig fresh = new PartyConfig();
        final Path file = dataFolder.resolve(FILE);
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    /** 從 YAML 讀（測試用）。 */
    public static PartyConfig of(final YamlNode root) {
        final PartyConfig config = new PartyConfig();
        config.parse(root);
        return config;
    }

    private void copyFrom(final PartyConfig other) {
        maxSize = other.maxSize;
        inviteSeconds = other.inviteSeconds;
        shareThreshold = other.shareThreshold;
        nearbyRadius = other.nearbyRadius;
        levelWeightExponent = other.levelWeightExponent;
        bonusPerMember = other.bonusPerMember;
        countRadius = other.countRadius;
        maxCountedPlayers = other.maxCountedPlayers;
        lifePerExtraPlayer.clear();
        lifePerExtraPlayer.putAll(other.lifePerExtraPlayer);
    }

    private void parse(final YamlNode root) {
        final YamlNode party = root.node("party");
        maxSize = party.integer("max_size", maxSize);
        if (maxSize < 2 || maxSize > 12) {
            throw new IllegalArgumentException(party.path() + ".max_size：要在 2 到 12 之間，卻是 " + maxSize);
        }
        inviteSeconds = Math.max(10, party.integer("invite_seconds", inviteSeconds));
        nearbyRadius = positive(party, "nearby_radius", nearbyRadius);

        final YamlNode loot = root.node("loot");
        shareThreshold = loot.decimal("share_threshold", shareThreshold);
        if (shareThreshold < 0 || shareThreshold > 1) {
            throw new IllegalArgumentException(loot.path() + ".share_threshold：要在 0 到 1 之間（0.05 = 5%），卻是 " + shareThreshold);
        }

        final YamlNode experience = root.node("experience");
        levelWeightExponent = Math.max(0, experience.decimal("level_weight_exponent", levelWeightExponent));
        bonusPerMember = Math.max(0, experience.decimal("bonus_per_member", bonusPerMember));

        final YamlNode monsters = root.node("monsters");
        countRadius = positive(monsters, "count_radius", countRadius);
        maxCountedPlayers = Math.max(1, monsters.integer("max_players", maxCountedPlayers));
        final YamlNode life = monsters.node("life_per_extra_player");
        for (final String key : life.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(life.path() + "：不認得的稀有度「" + key + "」（normal、magic、rare、unique）");
            }
            final double value = life.decimal(key, 0);
            if (value < 0) {
                throw new IllegalArgumentException(life.path() + "." + key + "：不能是負的");
            }
            lifePerExtraPlayer.put(rarity, value);
        }
    }

    private static double positive(final YamlNode node, final String key, final double def) {
        final double value = node.decimal(key, def);
        if (!(value > 0)) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要大於 0，卻是 " + value);
        }
        return value;
    }

    // ---- 設定值 ----

    public int maxSize() {
        return maxSize;
    }

    public int inviteSeconds() {
        return inviteSeconds;
    }

    public double shareThreshold() {
        return shareThreshold;
    }

    /** 「在附近」的水平半徑（格）：經驗、掉落、隊伍加成都只算這個範圍內的隊員（2026-10-26）。 */
    public double nearbyRadius() {
        return nearbyRadius;
    }

    /** 水平距離（dx、dz）在 nearby_radius 內（含邊界；高度不管，跟 Boss 房一樣只看平面）。 */
    public boolean withinNearby(final double dx, final double dz) {
        return dx * dx + dz * dz <= nearbyRadius * nearbyRadius;
    }

    /** 2026-10-27：上下最多差幾格還算附近（這次之前高度不管，躲在正下方很深的洞裡也分得到經驗、掉落）。 */
    public static final double NEARBY_VERTICAL = 24;

    /** 2026-10-27：水平照 {@link #withinNearby(double, double)}，再加上下差不超過 {@link #NEARBY_VERTICAL} 格。 */
    public boolean withinNearby(final double dx, final double dy, final double dz) {
        return Math.abs(dy) <= NEARBY_VERTICAL && withinNearby(dx, dz);
    }

    public double levelWeightExponent() {
        return levelWeightExponent;
    }

    public double bonusPerMember() {
        return bonusPerMember;
    }

    public double countRadius() {
        return countRadius;
    }

    public int maxCountedPlayers() {
        return maxCountedPlayers;
    }

    /** 每多一個玩家，這種稀有度的怪物生命多幾 %。 */
    public double lifePerExtraPlayer(final MonsterRarity rarity) {
        return lifePerExtraPlayer.getOrDefault(rarity, 0.0);
    }

    // ---- 計算 ----

    /** 刷怪時附近有 players 個玩家：生命的倍率（1 人 = 1；最多算 max_players 人）。 */
    public double lifeFactor(final MonsterRarity rarity, final int players) {
        final int counted = Math.max(1, Math.min(maxCountedPlayers, players));
        return 1 + lifePerExtraPlayer(rarity) / 100.0 * (counted - 1);
    }

    /**
     * 經驗的多人倍率算幾人（2026-10-26）：怪物刷出來時算進生命的人數（spawned），但最多只算怪物倒下時真的在附近的玩家（nearby）。
     * 這次之前直接用刷怪時的人數：隊友在別的房間（地圖裡整張地圖都算）也讓經驗變多。
     */
    public static int experiencePlayers(final int spawned, final int nearby) {
        return Math.max(1, Math.min(spawned, nearby));
    }

    /** 隊伍一起分到的經驗倍率：每多一個分經驗的隊友 +bonus_per_member（分經驗的人只有在附近的隊員，2026-10-26）。 */
    public double partyBonus(final int recipients) {
        return 1 + bonusPerMember * Math.max(0, recipients - 1);
    }

    /**
     * 照等級分經驗（PoE 的做法）：每個人的權重 = (等級 + 10) ^ exponent，回傳每個人分到的比例（加起來是 1）。
     * 等級差越多，低等的人分到越少（防止帶練）。
     */
    public static Map<UUID, Double> experienceShares(final Map<UUID, Integer> levels, final double exponent) {
        final Map<UUID, Double> shares = new LinkedHashMap<>();
        double total = 0;
        for (final Map.Entry<UUID, Integer> entry : levels.entrySet()) {
            final double weight = Math.pow(Math.max(1, entry.getValue()) + 10.0, exponent);
            shares.put(entry.getKey(), weight);
            total += weight;
        }
        if (total <= 0) {
            return shares;
        }
        for (final Map.Entry<UUID, Double> entry : shares.entrySet()) {
            entry.setValue(entry.getValue() / total);
        }
        return shares;
    }
}
