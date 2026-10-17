package tw.exilecore.map;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * data/map_events.yml：地圖事件（神龕、寶箱、裂隙、流寇）與 PoE2 的兩個機制（祭壇、幻霧）的設定。純資料，不依賴 Bukkit。
 */
public final class MapEventConfig {

    public enum Kind { SHRINE, STRONGBOX, BREACH, EXILE }

    /** 祭壇與幻霧的獎勵種類（reward_weights 的鍵）。 */
    public enum Reward {
        OMEN("星籤"), CURRENCY("通貨"), RARE("稀有裝備"), ESSENCE("星魄"), JEWEL("珠寶"), MAP("地圖"), CATALYST("催化劑"), UNIQUE("傳奇"),
        EMOTION("蒸餾情緒");

        private final String displayName;

        Reward(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * 祭壇（PoE2 的 Ritual）。
     *
     * @param chance        每張地圖出現的機率
     * @param altars        幾座（各在不同房間）
     * @param radius        儀式的圓幾格
     * @param minWaves      每座幾波
     * @param minWave       一波幾隻
     * @param waveSeconds   一波最多幾秒（殺光就直接下一波）
     * @param timeout       一座祭壇最多幾秒
     * @param tribute       殺一隻各稀有度給多少貢品
     * @param altarBonus    每後一座祭壇的貢品多幾成
     * @param rewards       開獎幾件
     * @param rerollCost    重擲花「總共拿到的貢品」的幾成
     * @param rerollGrowth  每重擲一次費用乘多少
     * @param rewardWeights 各種獎勵的權重
     * @param prices        各種獎勵的貢品價格範圍
     */
    public record Ritual(double chance, int altars, double radius, int minWaves, int maxWaves, int minWave, int maxWave,
                         double waveSeconds, double timeout, Map<tw.exilecore.monster.MonsterRarity, Integer> tribute, double altarBonus,
                         int rewards, double rerollCost, double rerollGrowth, Map<Reward, Integer> rewardWeights, Map<Reward, int[]> prices) {
        public Ritual {
            tribute = Map.copyOf(tribute);
            rewardWeights = Map.copyOf(rewardWeights);
            prices = Map.copyOf(prices);
        }

        /** 殺一隻的貢品（第 index 座祭壇，0 起）。 */
        public int tributeFor(final tw.exilecore.monster.MonsterRarity rarity, final int index) {
            return (int) Math.round(tribute.getOrDefault(rarity, 0) * (1 + altarBonus * index));
        }
    }

    /**
     * 幻霧（PoE2 的 Delirium）。
     *
     * @param chance         每張地圖出現的機率
     * @param duration       霧跟著你幾秒
     * @param rampSeconds    難度在幾秒內升到最高
     * @param maxDifficulty  最高：怪物生命與傷害 +%（小數）
     * @param spawnSeconds   每幾秒在每個玩家附近刷一小群幻霧怪
     * @param minSpawn       一次幾隻
     * @param progress       殺一隻各稀有度給多少獎勵進度
     * @param rewards        幾條獎勵
     * @param thresholds     進度到幾就多一份
     * @param rewardWeights  各種獎勵的權重
     * @param emotions       蒸餾情緒的通貨代號，照階級排（注入離中心越遠的核心要越後面的）
     * @param emotionWeights 獎勵掉哪一階情緒的權重（跟 emotions 對齊）
     */
    public record Delirium(double chance, double duration, double rampSeconds, double maxDifficulty, double spawnSeconds, int minSpawn, int maxSpawn,
                           Map<tw.exilecore.monster.MonsterRarity, Integer> progress, int rewards, List<Integer> thresholds,
                           Map<Reward, Integer> rewardWeights, List<String> emotions, List<Integer> emotionWeights) {
        public Delirium {
            progress = Map.copyOf(progress);
            thresholds = List.copyOf(thresholds);
            rewardWeights = Map.copyOf(rewardWeights);
            emotions = List.copyOf(emotions);
            emotionWeights = List.copyOf(emotionWeights);
        }

        /** 這個進度拿幾份。 */
        public int sharesFor(final int points) {
            int shares = 0;
            for (final int threshold : thresholds) {
                if (points >= threshold) {
                    shares++;
                }
            }
            return shares;
        }

        /** 開始後 elapsed 秒的難度（小數，0 到 maxDifficulty）。 */
        public double difficultyAt(final double elapsed) {
            if (rampSeconds <= 0) {
                return maxDifficulty;
            }
            return maxDifficulty * Math.max(0, Math.min(1, elapsed / rampSeconds));
        }

        /** 第 tier 階（1 起）的蒸餾情緒代號；沒有這一階用最後一個；沒有設定回傳 null。 */
        public String emotion(final int tier) {
            if (emotions.isEmpty()) {
                return null;
            }
            return emotions.get(Math.max(0, Math.min(emotions.size() - 1, tier - 1)));
        }
    }

    /** 一種神龕。 */
    public record Shrine(String id, String name, String color, List<Modifier> mods) {
        public Shrine {
            mods = List.copyOf(mods);
        }
    }

    /** 一種寶箱。 */
    public record Box(String id, String name, int weight, int rolls, double quantityBonus, double rarityBonus,
                      int minCurrency, int maxCurrency, int minMaps, int maxMaps, int minEssences, int maxEssences) {
    }

    private int minPerMap = 2;
    private int maxPerMap = 3;
    private final Map<Kind, Integer> weights = new LinkedHashMap<>();
    /** 每種事件掉它專屬傳奇的機率（每個有份的人各擲一次；uniques.yml 的 sources 寫 shrine、strongbox、breach、exile）。 */
    private final Map<Kind, Double> uniqueChance = new java.util.EnumMap<>(Kind.class);
    private double approach = 20;
    // 神龕
    private int shrineDuration = 40;
    private int minGuards = 3;
    private int maxGuards = 5;
    private double shrineReach = 12;
    private final List<Shrine> shrines = new ArrayList<>();
    // 寶箱
    private int minAmbush = 6;
    private int maxAmbush = 9;
    private double boxRadius = 20;
    private final List<Box> boxes = new ArrayList<>();
    // 裂隙
    private double breachDuration = 25;
    private double waveSeconds = 2.5;
    private int minWave = 3;
    private int maxWave = 4;
    private double breachStart = 4;
    private double breachEnd = 14;
    private double breachQuantity = 50;
    /** 裂隙怪死掉時掉催化劑的機率。 */
    private double catalystChance = 0.06;
    // 流寇
    private double exileLife = 4.0;
    private double exileDamage = 1.3;
    private List<String> exileMods = List.of("slammer", "charger", "swift");
    private int exileDrops = 2;
    private int minOmens = 1;
    private int maxOmens = 1;
    private List<String> exileNames = List.of("伊薇特", "柯林", "瑞瓦", "托爾加", "安雅", "莫瑟");
    // PoE2 的機制
    private Ritual ritual;
    private Delirium delirium;
    /** 地圖變化（2026-10，{@link MapTwists}）。 */
    private Twists twists;

    /**
     * 地圖變化（2026-10，map_events.yml 的 twists）：讓同一張圖每次打起來不一樣。機率都是 0–1，權重是整數，0 = 關掉。
     *
     * @param roomThemeChance  每個沒有事件的房間有主題的機率
     * @param roomThemes       主題的權重：elite 精英巢穴、swarm 怪物潮、dark 黑暗房、vault 寶庫
     * @param swarmSize        怪物潮的群大小倍數
     * @param mixChance        這張圖混進第二種怪物族群的機率
     * @param mixShare         混進來之後，每一群用第二族群的機率
     * @param wandererChance   這張圖有一隻流浪精英的機率
     * @param wandererLife     流浪精英的生命倍數（照稀有怪再乘）
     * @param wandererDamage   流浪精英的傷害倍數
     * @param wandererQuantity 流浪精英的物品數量 +%
     * @param stackChance      每個事件旁邊多疊一個不同事件的機率
     * @param bossVariantChance 一般地圖 Boss（不含終局、極境、巔峰、回響、試煉）是變體的機率
     * @param bossVariants     變體的權重：empowered 強化、twin 雙生
     * @param bossRoomChance   一般地圖的 Boss 房有干擾的機率
     * @param bossRooms        干擾的權重：meteors 墜星、hands 冥河之手、embers 流火、reinforce 援軍、pillar 守護之柱
     * @param forkChance       這張圖有命運岔路（二選一的石碑）的機率
     * @param cursedChance     這張圖有詛咒寶匣的機率
     * @param cursedPower      接受詛咒之後，之後刷的怪生命與傷害 +%
     * @param timedChance      第一次走進一個普通房間時，變成限時挑戰的機率（一張圖最多 2 次）
     * @param timedSeconds     限時挑戰的秒數
     * @param hiddenChance     這張圖有隱藏房（裂開的牆後面）的機率
     */
    public record Twists(double roomThemeChance, Map<String, Integer> roomThemes, double swarmSize, double mixChance, double mixShare,
                         double wandererChance, double wandererLife, double wandererDamage, double wandererQuantity,
                         double stackChance, double bossVariantChance, Map<String, Integer> bossVariants,
                         double bossRoomChance, Map<String, Integer> bossRooms, double forkChance, double cursedChance, double cursedPower,
                         double timedChance, double timedSeconds, double hiddenChance) {
        public Twists {
            roomThemes = Map.copyOf(roomThemes);
            bossVariants = Map.copyOf(bossVariants);
            bossRooms = Map.copyOf(bossRooms);
        }

        static Twists defaults() {
            return new Twists(0.3, Map.of("elite", 30, "swarm", 30, "dark", 25, "vault", 15), 2.5, 0.5, 0.35,
                    0.35, 4.0, 1.3, 200, 0.12, 0.15, Map.of("empowered", 60, "twin", 40),
                    0.25, Map.of("meteors", 25, "hands", 25, "embers", 20, "reinforce", 15, "pillar", 15),
                    0.25, 0.2, 25, 0.15, 50, 0.25);
        }

        /** 照權重抽一個鍵；權重全是 0 回傳 null。 */
        public static String pick(final Map<String, Integer> weights, final java.util.random.RandomGenerator random) {
            int total = 0;
            for (final int weight : weights.values()) {
                total += Math.max(0, weight);
            }
            if (total <= 0) {
                return null;
            }
            int roll = random.nextInt(total);
            for (final Map.Entry<String, Integer> entry : new java.util.TreeMap<>(weights).entrySet()) {
                roll -= Math.max(0, entry.getValue());
                if (roll < 0) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }

    public MapEventConfig() {
        defaults();
    }

    private void defaults() {
        minPerMap = 2;
        maxPerMap = 3;
        weights.clear();
        weights.put(Kind.SHRINE, 35);
        weights.put(Kind.STRONGBOX, 35);
        weights.put(Kind.BREACH, 15);
        weights.put(Kind.EXILE, 15);
        uniqueChance.clear();
        for (final Kind kind : Kind.values()) {
            uniqueChance.put(kind, MapConfig.DEFAULT_UNIQUE_CHANCE);
        }
        approach = 20;
        shrineDuration = 40;
        minGuards = 3;
        maxGuards = 5;
        shrineReach = 12;
        shrines.clear();
        shrines.add(new Shrine("acceleration", "疾速神龕", "#7fd4ff", List.of(
                Modifier.increased("movement.speed", 0.30, "神龕"), Modifier.increased("attack.speed", 0.30, "神龕"), Modifier.increased("cast.speed", 0.30, "神龕"))));
        shrines.add(new Shrine("brutal", "兇猛神龕", "#ff7070", List.of(Modifier.increased("damage", 0.40, "神龕"))));
        shrines.add(new Shrine("regeneration", "再生神龕", "#7fe07f", List.of(Modifier.flat("life.regen_percent", 3, "神龕"))));
        minAmbush = 6;
        maxAmbush = 9;
        boxRadius = 20;
        boxes.clear();
        boxes.add(new Box("plain", "寶箱", 60, 3, 0.5, 1.0, 0, 0, 0, 0, 1, 1));
        boxes.add(new Box("artisan", "匠心寶箱", 25, 1, 0, 0.5, 3, 6, 0, 0, 2, 3));
        boxes.add(new Box("cartographer", "星圖寶箱", 15, 1, 0, 0.5, 0, 0, 1, 2, 1, 1));
        breachDuration = 25;
        waveSeconds = 2.5;
        minWave = 3;
        maxWave = 4;
        breachStart = 4;
        breachEnd = 14;
        breachQuantity = 50;
        catalystChance = 0.06;
        exileLife = 4.0;
        exileDamage = 1.3;
        exileMods = List.of("slammer", "charger", "swift");
        exileDrops = 2;
        minOmens = 1;
        maxOmens = 1;
        exileNames = List.of("伊薇特", "柯林", "瑞瓦", "托爾加", "安雅", "莫瑟");
        ritual = new Ritual(0.3, 3, 9, 3, 4, 4, 6, 12, 150,
                Map.of(tw.exilecore.monster.MonsterRarity.NORMAL, 12, tw.exilecore.monster.MonsterRarity.MAGIC, 35,
                        tw.exilecore.monster.MonsterRarity.RARE, 120, tw.exilecore.monster.MonsterRarity.UNIQUE, 300),
                0.25, 7, 0.2, 1.5,
                Map.of(Reward.OMEN, 30, Reward.CURRENCY, 25, Reward.RARE, 15, Reward.ESSENCE, 10, Reward.JEWEL, 8, Reward.MAP, 6,
                        Reward.CATALYST, 4, Reward.UNIQUE, 1),
                Map.of(Reward.OMEN, new int[] {260, 420}, Reward.CURRENCY, new int[] {40, 160}, Reward.RARE, new int[] {140, 260},
                        Reward.ESSENCE, new int[] {110, 190}, Reward.JEWEL, new int[] {180, 320}, Reward.MAP, new int[] {150, 260},
                        Reward.CATALYST, new int[] {120, 200}, Reward.UNIQUE, new int[] {520, 880}));
        delirium = new Delirium(0.3, 75, 60, 0.6, 4, 2, 3,
                Map.of(tw.exilecore.monster.MonsterRarity.NORMAL, 1, tw.exilecore.monster.MonsterRarity.MAGIC, 3,
                        tw.exilecore.monster.MonsterRarity.RARE, 10, tw.exilecore.monster.MonsterRarity.UNIQUE, 25),
                2, List.of(20, 50, 100, 170),
                Map.of(Reward.EMOTION, 40, Reward.CURRENCY, 25, Reward.ESSENCE, 10, Reward.JEWEL, 10, Reward.MAP, 10, Reward.CATALYST, 5,
                        Reward.UNIQUE, 1),
                List.of("distilled_ire", "distilled_guilt", "distilled_greed", "distilled_paranoia"), List.of(50, 30, 15, 5));
        twists = Twists.defaults();
    }

    public void load(final Path dataFolder) throws IOException {
        // 2026-10-18：先讀到新的一份，成功才換上（以前讀到一半出錯會留下半套設定，例如神龕清空但權重還在）
        final MapEventConfig fresh = new MapEventConfig();
        fresh.parse(dataFolder);
        copyFrom(fresh);
    }

    private void copyFrom(final MapEventConfig other) {
        minPerMap = other.minPerMap;
        maxPerMap = other.maxPerMap;
        weights.clear();
        weights.putAll(other.weights);
        uniqueChance.clear();
        uniqueChance.putAll(other.uniqueChance);
        approach = other.approach;
        shrineDuration = other.shrineDuration;
        minGuards = other.minGuards;
        maxGuards = other.maxGuards;
        shrineReach = other.shrineReach;
        shrines.clear();
        shrines.addAll(other.shrines);
        minAmbush = other.minAmbush;
        maxAmbush = other.maxAmbush;
        boxRadius = other.boxRadius;
        boxes.clear();
        boxes.addAll(other.boxes);
        breachDuration = other.breachDuration;
        waveSeconds = other.waveSeconds;
        minWave = other.minWave;
        maxWave = other.maxWave;
        breachStart = other.breachStart;
        breachEnd = other.breachEnd;
        breachQuantity = other.breachQuantity;
        catalystChance = other.catalystChance;
        exileLife = other.exileLife;
        exileDamage = other.exileDamage;
        exileMods = other.exileMods;
        exileDrops = other.exileDrops;
        minOmens = other.minOmens;
        maxOmens = other.maxOmens;
        exileNames = other.exileNames;
        ritual = other.ritual;
        delirium = other.delirium;
        twists = other.twists;
    }

    private void parse(final Path dataFolder) throws IOException {
        defaults();
        final Path file = dataFolder.resolve("map_events.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode settings = root.node("settings");
        if (settings.has("per_map")) {
            final int[] range = settings.range("per_map");
            minPerMap = Math.max(0, Math.min(range[0], range[1]));
            maxPerMap = Math.min(8, Math.max(range[0], range[1]));
        }
        if (settings.has("weights")) {
            weights.clear();
            final YamlNode node = settings.node("weights");
            for (final String key : node.keys()) {
                weights.put(kind(node.path() + "." + key, key), Math.max(0, node.integer(key, 0)));
            }
        }
        approach = clamp(settings.decimal("approach", approach), 4, 64);

        final YamlNode shrine = root.node("shrine");
        shrineDuration = (int) clamp(shrine.integer("duration", shrineDuration), 5, 600);
        if (shrine.has("guards")) {
            final int[] range = shrine.range("guards");
            minGuards = Math.max(0, Math.min(range[0], range[1]));
            maxGuards = Math.min(20, Math.max(range[0], range[1]));
        }
        shrineReach = clamp(shrine.decimal("reach", shrineReach), 2, 48);
        uniqueChance.put(Kind.SHRINE, clamp(shrine.decimal("unique_chance", uniqueChance.get(Kind.SHRINE)), 0, 1));
        if (shrine.has("kinds")) {
            shrines.clear();
            final YamlNode kinds = shrine.node("kinds");
            for (final String id : kinds.keys()) {
                final YamlNode node = kinds.node(id);
                final List<Modifier> mods = new ArrayList<>();
                for (final YamlNode mod : node.list("mods")) {
                    mods.add(modifier(mod));
                }
                if (mods.isEmpty()) {
                    throw new IllegalArgumentException(node.path() + "：神龕要有至少一條 mods");
                }
                shrines.add(new Shrine(id, node.string("name", id), node.string("color", "#ffffff"), mods));
            }
        }
        if (shrines.isEmpty()) {
            weights.put(Kind.SHRINE, 0);
        }

        final YamlNode box = root.node("strongbox");
        if (box.has("ambush")) {
            final int[] range = box.range("ambush");
            minAmbush = Math.max(0, Math.min(range[0], range[1]));
            maxAmbush = Math.min(30, Math.max(range[0], range[1]));
        }
        boxRadius = clamp(box.decimal("radius", boxRadius), 4, 64);
        uniqueChance.put(Kind.STRONGBOX, clamp(box.decimal("unique_chance", uniqueChance.get(Kind.STRONGBOX)), 0, 1));
        if (box.has("kinds")) {
            boxes.clear();
            final YamlNode kinds = box.node("kinds");
            for (final String id : kinds.keys()) {
                final YamlNode node = kinds.node(id);
                final int[] currency = node.has("currency") ? node.range("currency") : new int[] {0, 0};
                final int[] maps = node.has("maps") ? node.range("maps") : new int[] {0, 0};
                final int[] essences = node.has("essences") ? node.range("essences") : new int[] {0, 0};
                boxes.add(new Box(id, node.string("name", id), Math.max(0, node.integer("weight", 1)),
                        (int) clamp(node.integer("rolls", 1), 0, 20), clamp(node.decimal("quantity_bonus", 0), -100, 1000) / 100.0,
                        clamp(node.decimal("rarity_bonus", 0), -100, 1000) / 100.0,
                        Math.max(0, Math.min(currency[0], currency[1])), Math.min(50, Math.max(currency[0], currency[1])),
                        Math.max(0, Math.min(maps[0], maps[1])), Math.min(10, Math.max(maps[0], maps[1])),
                        Math.max(0, Math.min(essences[0], essences[1])), Math.min(10, Math.max(essences[0], essences[1]))));
            }
        }
        if (boxes.isEmpty()) {
            weights.put(Kind.STRONGBOX, 0);
        }

        final YamlNode breach = root.node("breach");
        breachDuration = clamp(breach.decimal("duration", breachDuration), 5, 300);
        waveSeconds = clamp(breach.decimal("wave_seconds", waveSeconds), 0.5, 60);
        if (breach.has("wave")) {
            final int[] range = breach.range("wave");
            minWave = Math.max(1, Math.min(range[0], range[1]));
            maxWave = Math.min(20, Math.max(range[0], range[1]));
        }
        if (breach.has("radius")) {
            final int[] range = breach.range("radius");
            breachStart = Math.max(2, Math.min(range[0], range[1]));
            breachEnd = Math.min(40, Math.max(range[0], range[1]));
        }
        breachQuantity = clamp(breach.decimal("quantity_bonus", breachQuantity), 0, 1000);
        catalystChance = clamp(breach.decimal("catalyst_chance", catalystChance), 0, 1);
        uniqueChance.put(Kind.BREACH, clamp(breach.decimal("unique_chance", uniqueChance.get(Kind.BREACH)), 0, 1));

        final YamlNode exile = root.node("exile");
        exileLife = clamp(exile.decimal("life", exileLife), 0.5, 50);
        exileDamage = clamp(exile.decimal("damage", exileDamage), 0.1, 10);
        if (exile.has("mods")) {
            exileMods = List.copyOf(exile.strings("mods"));
        }
        exileDrops = (int) clamp(exile.integer("drops", exileDrops), 0, 10);
        uniqueChance.put(Kind.EXILE, clamp(exile.decimal("unique_chance", uniqueChance.get(Kind.EXILE)), 0, 1));
        if (exile.has("omens")) {
            final int[] range = exile.range("omens");
            minOmens = Math.max(0, Math.min(range[0], range[1]));
            maxOmens = Math.min(10, Math.max(range[0], range[1]));
        }
        if (exile.has("names") && !exile.strings("names").isEmpty()) {
            exileNames = List.copyOf(exile.strings("names"));
        }

        if (root.has("ritual")) {
            final YamlNode node = root.node("ritual");
            final int[] waves = node.has("waves") ? node.range("waves") : new int[] {ritual.minWaves(), ritual.maxWaves()};
            final int[] wave = node.has("wave") ? node.range("wave") : new int[] {ritual.minWave(), ritual.maxWave()};
            final YamlNode reroll = node.node("reroll");
            ritual = new Ritual(clamp(node.decimal("chance", ritual.chance()), 0, 1), (int) clamp(node.integer("altars", ritual.altars()), 1, 6),
                    clamp(node.decimal("radius", ritual.radius()), 4, 20),
                    Math.max(1, Math.min(waves[0], waves[1])), Math.min(10, Math.max(waves[0], waves[1])),
                    Math.max(1, Math.min(wave[0], wave[1])), Math.min(20, Math.max(wave[0], wave[1])),
                    clamp(node.decimal("wave_seconds", ritual.waveSeconds()), 2, 120), clamp(node.decimal("timeout", ritual.timeout()), 20, 900),
                    node.has("tribute") ? rarityInts(node.node("tribute")) : ritual.tribute(),
                    clamp(node.decimal("altar_bonus", ritual.altarBonus()), 0, 5), (int) clamp(node.integer("rewards", ritual.rewards()), 1, 27),
                    clamp(reroll.decimal("cost", ritual.rerollCost()), 0, 1), clamp(reroll.decimal("growth", ritual.rerollGrowth()), 1, 5),
                    node.has("reward_weights") ? rewardInts(node.node("reward_weights")) : ritual.rewardWeights(),
                    node.has("prices") ? rewardRanges(node.node("prices")) : ritual.prices());
        }
        if (root.has("delirium")) {
            final YamlNode node = root.node("delirium");
            final int[] spawn = node.has("spawn") ? node.range("spawn") : new int[] {delirium.minSpawn(), delirium.maxSpawn()};
            final List<Integer> thresholds = new ArrayList<>();
            if (node.has("thresholds")) {
                for (final String text : node.strings("thresholds")) {
                    thresholds.add(Integer.parseInt(text.trim()));
                }
                thresholds.sort(null);
            }
            final List<Integer> emotionWeights = new ArrayList<>();
            if (node.has("emotion_weights")) {
                for (final String text : node.strings("emotion_weights")) {
                    emotionWeights.add(Math.max(0, Integer.parseInt(text.trim())));
                }
            }
            final List<String> emotions = node.has("emotions") ? List.copyOf(node.strings("emotions")) : delirium.emotions();
            delirium = new Delirium(clamp(node.decimal("chance", delirium.chance()), 0, 1), clamp(node.decimal("duration", delirium.duration()), 10, 600),
                    clamp(node.decimal("ramp_seconds", delirium.rampSeconds()), 0, 600), clamp(node.decimal("max_difficulty", delirium.maxDifficulty() * 100), 0, 500) / 100.0,
                    clamp(node.decimal("spawn_seconds", delirium.spawnSeconds()), 1, 60),
                    Math.max(0, Math.min(spawn[0], spawn[1])), Math.min(10, Math.max(spawn[0], spawn[1])),
                    node.has("progress") ? rarityInts(node.node("progress")) : delirium.progress(),
                    (int) clamp(node.integer("rewards", delirium.rewards()), 1, 6), thresholds.isEmpty() ? delirium.thresholds() : thresholds,
                    node.has("reward_weights") ? rewardInts(node.node("reward_weights")) : delirium.rewardWeights(),
                    emotions, emotionWeights.isEmpty() ? delirium.emotionWeights() : emotionWeights);
            if (delirium.emotionWeights().size() != delirium.emotions().size()) {
                throw new IllegalArgumentException(node.path() + "：emotion_weights 要跟 emotions 一樣長");
            }
        }
        if (root.has("twists")) {
            twists = parseTwists(root.node("twists"), twists);
        }
    }

    /** map_events.yml 的 twists（沒寫的照預設）。 */
    static Twists parseTwists(final YamlNode node, final Twists base) {
        final YamlNode wanderer = node.node("wanderer");
        return new Twists(
                clamp(node.decimal("room_theme_chance", base.roomThemeChance()), 0, 1),
                node.has("room_themes") ? weights(node.node("room_themes"), Set.of("elite", "swarm", "dark", "vault")) : base.roomThemes(),
                clamp(node.decimal("swarm_size", base.swarmSize()), 1, 5),
                clamp(node.decimal("mix_chance", base.mixChance()), 0, 1),
                clamp(node.decimal("mix_share", base.mixShare()), 0, 1),
                clamp(node.decimal("wanderer_chance", base.wandererChance()), 0, 1),
                clamp(wanderer.decimal("life", base.wandererLife()), 1, 20),
                clamp(wanderer.decimal("damage", base.wandererDamage()), 0.5, 5),
                clamp(wanderer.decimal("quantity", base.wandererQuantity()), 0, 1000),
                clamp(node.decimal("stack_chance", base.stackChance()), 0, 1),
                clamp(node.decimal("boss_variant_chance", base.bossVariantChance()), 0, 1),
                node.has("boss_variants") ? weights(node.node("boss_variants"), Set.of("empowered", "twin")) : base.bossVariants(),
                clamp(node.decimal("boss_room_chance", base.bossRoomChance()), 0, 1),
                node.has("boss_rooms") ? weights(node.node("boss_rooms"), Set.of("meteors", "hands", "embers", "reinforce", "pillar")) : base.bossRooms(),
                clamp(node.decimal("fork_chance", base.forkChance()), 0, 1),
                clamp(node.decimal("cursed_chance", base.cursedChance()), 0, 1),
                clamp(node.decimal("cursed_power", base.cursedPower()), 0, 200),
                clamp(node.decimal("timed_chance", base.timedChance()), 0, 1),
                clamp(node.decimal("timed_seconds", base.timedSeconds()), 15, 300),
                clamp(node.decimal("hidden_chance", base.hiddenChance()), 0, 1));
    }

    private static Map<String, Integer> weights(final YamlNode node, final Set<String> known) {
        final Map<String, Integer> map = new LinkedHashMap<>();
        for (final String key : node.keys()) {
            final String id = key.trim().toLowerCase(Locale.ROOT);
            if (!known.contains(id)) {
                throw new IllegalArgumentException(node.path() + "：不認得的 " + key + "（可以寫 " + String.join("、", new java.util.TreeSet<>(known)) + "）");
            }
            map.put(id, Math.max(0, node.integer(key, 0)));
        }
        return map;
    }

    private static Map<tw.exilecore.monster.MonsterRarity, Integer> rarityInts(final YamlNode node) {
        final Map<tw.exilecore.monster.MonsterRarity, Integer> map = new java.util.EnumMap<>(tw.exilecore.monster.MonsterRarity.class);
        for (final String key : node.keys()) {
            final tw.exilecore.monster.MonsterRarity rarity = tw.exilecore.monster.MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(node.path() + "：不認得的怪物稀有度 " + key);
            }
            map.put(rarity, Math.max(0, node.integer(key, 0)));
        }
        return map;
    }

    private static Reward reward(final String where, final String text) {
        try {
            return Reward.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "：不認得的獎勵 " + text + "（omen、currency、rare、essence、jewel、map、catalyst、unique、emotion）");
        }
    }

    private static Map<Reward, Integer> rewardInts(final YamlNode node) {
        final Map<Reward, Integer> map = new java.util.EnumMap<>(Reward.class);
        for (final String key : node.keys()) {
            map.put(reward(node.path() + "." + key, key), Math.max(0, node.integer(key, 0)));
        }
        return map;
    }

    private static Map<Reward, int[]> rewardRanges(final YamlNode node) {
        final Map<Reward, int[]> map = new java.util.EnumMap<>(Reward.class);
        for (final String key : node.keys()) {
            final int[] range = node.range(key);
            map.put(reward(node.path() + "." + key, key), new int[] {Math.max(1, Math.min(range[0], range[1])), Math.max(1, Math.max(range[0], range[1]))});
        }
        return map;
    }

    private static Kind kind(final String where, final String text) {
        try {
            return Kind.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "：不認得的事件 " + text + "（shrine、strongbox、breach、exile）");
        }
    }

    /** 一條增益：{ stat, type, value }；INCREASED／MORE 的 value 是百分比。 */
    static Modifier modifier(final YamlNode node) {
        final String stat = node.requireString("stat").trim();
        final ModType type;
        try {
            type = ModType.valueOf(node.string("type", "FLAT").trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(node.path() + ".type：要寫 FLAT、INCREASED 或 MORE");
        }
        final double value = node.decimal("value", 0);
        return new Modifier(stat, type, type == ModType.FLAT ? value : value / 100.0, Set.of(), "神龕");
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- 讀取 ----

    public int minPerMap() {
        return minPerMap;
    }

    public int maxPerMap() {
        return maxPerMap;
    }

    public Map<Kind, Integer> weights() {
        return weights;
    }

    /** 有權重的事件加起來；0 表示沒有事件。 */
    public int totalWeight() {
        int total = 0;
        for (final int weight : weights.values()) {
            total += Math.max(0, weight);
        }
        return total;
    }

    public double approach() {
        return approach;
    }

    public int shrineDuration() {
        return shrineDuration;
    }

    public int minGuards() {
        return minGuards;
    }

    public int maxGuards() {
        return maxGuards;
    }

    public double shrineReach() {
        return shrineReach;
    }

    public List<Shrine> shrines() {
        return shrines;
    }

    public int minAmbush() {
        return minAmbush;
    }

    public int maxAmbush() {
        return maxAmbush;
    }

    public double boxRadius() {
        return boxRadius;
    }

    public List<Box> boxes() {
        return boxes;
    }

    public double breachDuration() {
        return breachDuration;
    }

    public double waveSeconds() {
        return waveSeconds;
    }

    public int minWave() {
        return minWave;
    }

    public int maxWave() {
        return maxWave;
    }

    public double breachStart() {
        return breachStart;
    }

    public double breachEnd() {
        return breachEnd;
    }

    /** 裂隙怪的物品數量加成（小數）。 */
    public double breachQuantity() {
        return breachQuantity / 100.0;
    }

    /** 裂隙怪死掉掉催化劑的機率。 */
    /** 這種事件掉它專屬傳奇的機率（每個有份的人各擲一次）。 */
    public double uniqueChance(final Kind kind) {
        return uniqueChance.getOrDefault(kind, MapConfig.DEFAULT_UNIQUE_CHANCE);
    }

    /** 這種事件的專屬傳奇在 uniques.yml 的來源代號（shrine、strongbox、breach、exile）。 */
    public static String uniqueSource(final Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** 祭壇、幻霧的專屬傳奇來源代號。 */
    public static final String RITUAL_SOURCE = "ritual";
    public static final String DELIRIUM_SOURCE = "delirium";

    public double catalystChance() {
        return catalystChance;
    }

    public int minOmens() {
        return minOmens;
    }

    public int maxOmens() {
        return maxOmens;
    }

    public double exileLife() {
        return exileLife;
    }

    public double exileDamage() {
        return exileDamage;
    }

    public List<String> exileMods() {
        return exileMods;
    }

    public int exileDrops() {
        return exileDrops;
    }

    public List<String> exileNames() {
        return exileNames;
    }

    public Ritual ritual() {
        return ritual;
    }

    public Delirium delirium() {
        return delirium;
    }

    public Twists twists() {
        return twists;
    }
}
