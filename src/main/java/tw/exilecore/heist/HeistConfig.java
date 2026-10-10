package tw.exilecore.heist;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapLayout;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 劫盜的設定（data/heist.yml）。純資料，不依賴 Bukkit。
 */
public final class HeistConfig {

    /** 警戒值：殺怪、開箱、每秒加多少。 */
    public record Alarm(double normal, double magic, double rare, double chest, double perSecond) {
    }

    /** 封鎖：增援的間隔與數量、大金庫獎勵的倍率。 */
    public record Lockdown(int reinforceSeconds, int reinforceCount, double rewardMultiplier) {
    }

    /**
     * 大金庫的獎勵（gemChance：掉一顆 Lv.21 未切割技能寶石的機率；finaleKeyChance：大劫盜的大金庫掉終局鑰印的機率；
     * finaleGemChance：金庫之主的收藏裡出現 Lv.21 未切割技能寶石的機率）。
     */
    public record Vault(int rolls, int[] currency, int[] unveilStones, double uniqueChance, double blueprintChance, double grandMultiplier,
                        double gemChance, double finaleKeyChance, double finaleGemChance) {
    }

    /**
     * @param contractChance 星圖（一般的地圖，不含劫盜、試煉這些特別的實例）裡的怪物掉劫盜合約的機率：普通、魔法、稀有、Boss
     */
    public record Settings(int rooms, int quantity, int rarity, int minLevel, Alarm alarm, Lockdown lockdown, int[] chests, int chestRolls,
                           int[] chestCurrency, double veiledChance, double unveilStoneChance, double chestBlueprintChance, Vault vault,
                           double[] contractChance) {
    }

    /** 一處據點（寫法跟試煉一樣）。 */
    public record Site(String id, String name, String theme, MapLayout.Style layout, String lore, List<ZoneConfig.SpawnEntry> monsters,
                       MapConfig.Boss boss) {
        public Site {
            monsters = List.copyOf(monsters);
        }

        /** 開實例用的「地圖」。 */
        public MapConfig.MapDef map(final int level) {
            return new MapConfig.MapDef("heist_" + id, name, null, 0, level, theme, 0, 0, List.of(), false, monsters, boss, lore, false, layout);
        }
    }

    public static final String FILE = "heist.yml";

    private Settings settings = defaults();
    private final Map<String, Site> sites = new LinkedHashMap<>();
    /** 劫盜終局（金庫之主）：寫法跟據點一樣；沒寫就開不了。 */
    private Site finale;
    /** 終局打倒之後放幾個寶箱（只能開一個）。 */
    private int finaleChoices = 5;
    /** 終局的怪物等級比開的人的劫盜等級高幾級。 */
    private int finaleLevelBonus = 3;
    /**
     * 終局的警報機關：每 every 秒出現 count 個，time 秒內沒拆完就叫 packs 群增援
     * （每群 rare 隻稀有、magic 隻魔法、normal 隻普通）。
     */
    public record FinaleAlarm(int every, int count, int time, int packs, int rare, int magic, int normal, int perPlayer, int packsPerPlayer) {

        /** 沒拆完叫幾群：基本 packs 群，打王的人每多一個再多 packsPerPlayer 群（最多 15 群）。 */
        public int packsFor(final int players) {
            return Math.min(15, packs + Math.max(0, players - 1) * packsPerPlayer);
        }

        /** 這一輪放幾個：基本 count 個，打王的人每多一個再多 perPlayer 個（最多 16 個）。 */
        public int countFor(final int players) {
            return Math.min(16, count + Math.max(0, players - 1) * perPlayer);
        }
    }

    private FinaleAlarm finaleAlarm = new FinaleAlarm(35, 4, 10, 5, 1, 5, 10, 2, 2);

    public FinaleAlarm finaleAlarm() {
        return finaleAlarm;
    }

    public Site finale() {
        return finale;
    }

    public int finaleChoices() {
        return finaleChoices;
    }

    public int finaleLevelBonus() {
        return finaleLevelBonus;
    }

    public static Settings defaults() {
        return new Settings(7, 40, 30, 30, new Alarm(0.05, 0.15, 0.5, 8, 0.25), new Lockdown(20, 3, 0.5), new int[] {3, 5}, 2,
                new int[] {1, 3}, 0.3, 0.05, 0.01, new Vault(6, new int[] {4, 7}, new int[] {0, 1}, 0.02, 0.10, 3, 0.02, 0.10, 0.10),
                new double[] {0.002, 0.006, 0.025, 0.25});
    }

    public Settings settings() {
        return settings;
    }

    public List<Site> sites() {
        return List.copyOf(sites.values());
    }

    public Site site(final String id) {
        return sites.get(id);
    }

    /** 讀資料夾裡的 heist.yml；沒有這個檔就是空的（劫盜開不了）。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            settings = defaults();
            sites.clear();
            return;
        }
        load(YamlNode.load(file));
    }

    public void load(final YamlNode root) {
        final YamlNode s = root.node("settings");
        final YamlNode alarm = s.node("alarm");
        final YamlNode lockdown = s.node("lockdown");
        final YamlNode vault = s.node("vault");
        final Settings fresh = new Settings(clamp(s.integer("rooms", 7), 4, 12), Math.max(0, s.integer("quantity", 40)),
                Math.max(0, s.integer("rarity", 30)), Math.max(1, s.integer("min_level", 30)),
                new Alarm(nonNegative(alarm.decimal("normal", 0.05)), nonNegative(alarm.decimal("magic", 0.15)), nonNegative(alarm.decimal("rare", 0.5)),
                        nonNegative(alarm.decimal("chest", 8)), nonNegative(alarm.decimal("per_second", 0.25))),
                new Lockdown(clamp(lockdown.integer("reinforce_seconds", 20), 5, 120), clamp(lockdown.integer("reinforce_count", 3), 0, 10),
                        Math.max(0, Math.min(1, lockdown.decimal("reward_multiplier", 0.5)))),
                range(s, "chests", 3, 5), Math.max(0, s.integer("chest_rolls", 2)), range(s, "chest_currency", 1, 3),
                chance(s.decimal("veiled_chance", 0.3)), chance(s.decimal("unveil_stone_chance", 0.05)),
                chance(s.decimal("chest_blueprint_chance", 0.01)),
                new Vault(Math.max(0, vault.integer("rolls", 6)), range(vault, "currency", 4, 7), range(vault, "unveil_stones", 0, 1),
                        chance(vault.decimal("unique_chance", 0.02)), chance(vault.decimal("blueprint_chance", 0.10)),
                        Math.max(1, vault.decimal("grand_multiplier", 3)), chance(vault.decimal("gem_chance", 0.02)), chance(vault.decimal("finale_key_chance", 0.10)),
                        chance(vault.decimal("finale_gem_chance", 0.10))),
                new double[] {chance(s.node("contract_chance").decimal("normal", 0.002)), chance(s.node("contract_chance").decimal("magic", 0.006)),
                        chance(s.node("contract_chance").decimal("rare", 0.025)), chance(s.node("contract_chance").decimal("boss", 0.25))});
        final Map<String, Site> parsed = new LinkedHashMap<>();
        final YamlNode sitesNode = root.node("sites");
        for (final String id : sitesNode.keys()) {
            parsed.put(id, parseSite(id, sitesNode.node(id)));
        }
        final Site parsedFinale = root.has("finale") ? parseSite("finale", root.node("finale")) : null;
        final int choices = Math.max(1, Math.min(9, root.node("finale").integer("choices", 5)));
        final int bonus = Math.max(0, Math.min(20, root.node("finale").integer("level_bonus", 3)));
        final YamlNode f = root.node("finale");
        final YamlNode pack = f.node("alarm_pack");
        final FinaleAlarm alarmDevices = new FinaleAlarm(clamp(f.integer("alarm_every", 35), 0, 600), clamp(f.integer("alarm_count", 4), 0, 16),
                clamp(f.integer("alarm_time", 10), 3, 60), clamp(f.integer("alarm_packs", 5), 0, 10),
                clamp(pack.integer("rare", 1), 0, 5), clamp(pack.integer("magic", 5), 0, 20), clamp(pack.integer("normal", 10), 0, 30),
                clamp(f.integer("alarm_per_player", 2), 0, 8), clamp(f.integer("alarm_packs_per_player", 2), 0, 5));
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(FILE + "：sites 至少要有一處據點");
        }
        settings = fresh;
        sites.clear();
        sites.putAll(parsed);
        finale = parsedFinale;
        finaleChoices = choices;
        finaleLevelBonus = bonus;
        finaleAlarm = alarmDevices;
    }

    private static Site parseSite(final String id, final YamlNode node) {
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(node.path() + "：代號只能用小寫英文、數字、底線");
        }
        final String theme = node.string("theme", "ruins").trim().toLowerCase(Locale.ROOT);
        if (!MapConfig.THEMES.contains(theme)) {
            throw new IllegalArgumentException(node.path() + ".theme：不認得的主題 " + theme + "（可以用 " + MapConfig.THEMES + "）");
        }
        final String layoutText = node.string("layout", "halls");
        final MapLayout.Style layout = MapLayout.Style.parse(layoutText);
        if (layout == null) {
            throw new IllegalArgumentException(node.path() + ".layout：不認得的版面樣式 " + layoutText);
        }
        return new Site(id, node.requireString("name").trim(), theme, layout, node.string("lore", "").trim(),
                MapConfig.parseMonsters(node.node("monsters")), MapConfig.parseBoss(node.node("boss")));
    }

    public void copyFrom(final HeistConfig other) {
        settings = other.settings;
        finale = other.finale;
        finaleChoices = other.finaleChoices;
        finaleLevelBonus = other.finaleLevelBonus;
        finaleAlarm = other.finaleAlarm;
        sites.clear();
        sites.putAll(other.sites);
    }

    private static int[] range(final YamlNode node, final String key, final int min, final int max) {
        if (!node.has(key)) {
            return new int[] {min, max};
        }
        final int[] range = node.range(key);
        if (range[0] < 0 || range[1] < range[0]) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要寫 [最少, 最多]");
        }
        return range;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double nonNegative(final double value) {
        return Math.max(0, value);
    }

    private static double chance(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** 隨機一處據點（不要跟 exclude 裡的重複；全都排除了就隨便一處）。 */
    public Site pick(final java.util.random.RandomGenerator random, final List<String> exclude) {
        final List<Site> pool = new ArrayList<>();
        for (final Site site : sites.values()) {
            if (!exclude.contains(site.id())) {
                pool.add(site);
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(sites.values());
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }
}
