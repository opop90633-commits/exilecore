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
 * data/map_events.yml：地圖事件（神龕、寶箱、裂隙、流寇、深淵裂縫、保險箱）與 PoE2 的兩個機制（祭壇、幻霧）的設定。純資料，不依賴 Bukkit。
 */
public final class MapEventConfig {

    /** 2026-10-18：＋深淵裂縫（abyss，weights 的第五種）、地圖保險箱（safe，不在 weights 裡，照 settings.safe_chance 另外算，一張圖最多一個）。 */
    public enum Kind { SHRINE, STRONGBOX, BREACH, EXILE, ABYSS, SAFE }

    /** 祭壇與幻霧的獎勵種類（reward_weights 的鍵）。 */
    public enum Reward {
        OMEN("星籤"), CURRENCY("通貨"), RARE("稀有裝備"), ESSENCE("星魄"), JEWEL("珠寶"), MAP("地圖"), CATALYST("催化劑"), UNIQUE("傳奇"),
        EMOTION("蒸餾情緒"),
        /** 2026-11-04 照站長「地圖絕境塞核心」：魂核符文（runes.yml 的 core_＊；PoE2 混沌試煉的 Soul Core）。 */
        CORE("魂核符文"),
        /** 2026-11-04 照站長「也塞一些通貨（Wager）」：照 PoE2 混沌試煉的賭注，一次一疊高階通貨（{@link MapRewards#WAGERS}）。 */
        WAGER("賭注通貨"),
        /** 2026-11-04 照站長「這些放地圖內軍團掉落」：魔偶符文（runes.yml 的 idol_＊；PoE2 的 Idol）。 */
        IDOL("魔偶符文");

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

    /**
     * 深淵裂縫（2026-10-18）：走近時地上裂開一條縫，往地圖裡延伸，每段湧怪；盡頭一隻小 Boss＋一個深淵寶箱。
     *
     * @param advanceSeconds 每幾秒延伸一段
     * @param advance        一段幾格
     * @param minLength      總長幾格（隨機；碰到牆提早結束）
     * @param minWave        每段湧幾隻
     * @param quantityBonus  縫裡的怪的物品數量加成（小數，0.4 = +40%）
     * @param follow         有人在縫尾端幾格內才繼續延伸
     * @param stallSeconds   沒人跟著超過幾秒就合上（沒獎勵）
     * @param bossLife       小 Boss 的生命是同等稀有怪的幾倍
     * @param bossMods       小 Boss 的詞綴
     * @param rolls          寶箱擲幾次掉落表
     * @param chestQuantity  寶箱的物品數量加成（小數）
     * @param chestRarity    寶箱的物品稀有度加成（小數）
     * @param shardCurrency  另外掉的通貨代號（currencies.yml；沒有這個代號就照掉落表抽）
     * @param minShards      掉幾顆
     * @param radius         寶箱打開時幾格內的人有份
     * @param linger         事件結束幾秒後把縫的方塊還原
     */
    public record Abyss(double advanceSeconds, int advance, int minLength, int maxLength, int minWave, int maxWave, double quantityBonus,
                        double follow, double stallSeconds, double bossLife, List<String> bossMods, int rolls, double chestQuantity,
                        double chestRarity, String shardCurrency, int minShards, int maxShards, double radius, double linger) {
        public Abyss {
            bossMods = List.copyOf(bossMods);
        }

        static Abyss defaults() {
            return new Abyss(0.75, 3, 30, 45, 2, 3, 0.4, 8, 6, 3.0, List.of("slammer", "swift"), 3, 0.6, 0.8, "turmoil_orb", 1, 1, 20, 20); // 2026-10-18：先給 1 顆；2026-11-04 照站長「深淵速度加倍」：每段 1.5 → 0.75 秒
        }
    }

    /**
     * 地圖保險箱（2026-10-18）：鐵塊、鐵門、箱子的小結構；手上拿著劫盜合約右鍵，吃掉一張開始開鎖，開鎖時一波一波湧怪。
     *
     * @param chance          每張地圖有保險箱的機率（settings.safe_chance；跟 per_map 分開算，最多一個）
     * @param unlockSeconds   開鎖幾秒
     * @param waveSeconds     開鎖時每幾秒一波怪
     * @param minWave         一波幾隻
     * @param radius          幾格內有人進度才走
     * @param grace           沒人最多暫停幾秒，超過就失敗（合約沒了）
     * @param minRolls        打開擲幾次掉落表
     * @param quantityBonus   物品數量加成（小數）
     * @param rarityBonus     物品稀有度加成（小數）
     * @param minCurrency     另外掉幾顆通貨（照掉落表的通貨池抽）
     * @param blueprintChance 掉一張劫盜藍圖的機率
     * @param rewardRadius    打開時幾格內的人有份
     */
    public record Safe(double chance, double unlockSeconds, double waveSeconds, int minWave, int maxWave, double radius, double grace,
                       int minRolls, int maxRolls, double quantityBonus, double rarityBonus, int minCurrency, int maxCurrency,
                       double blueprintChance, double rewardRadius) {
        static Safe defaults() {
            return new Safe(0.35, 20, 4, 3, 5, 6, 15, 2, 3, 0.5, 1.0, 2, 4, 0.2, 20);
        }
    }

    private int minPerMap = 2;
    private int maxPerMap = 3;
    private final Map<Kind, Integer> weights = new LinkedHashMap<>();
    /** 每種事件掉它專屬傳奇的機率（每個有份的人各擲一次；uniques.yml 的 sources 寫 shrine、strongbox、breach、exile）。 */
    private final Map<Kind, Double> uniqueChance = new java.util.EnumMap<>(Kind.class);
    /**
     * 2026-10-18：每種事件完成時「有份的人」各擲一次的事件巔峰入場券（map_events.yml 各事件的 ticket: { currency, chance }）；
     * 鍵是事件的來源代號（shrine、strongbox、breach、exile、ritual、delirium、abyss；保險箱沒有）。固定機率，不吃任何加成。
     */
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
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
    /**
     * 2026-11-04 照站長「裂隙指環放進裂痕掉落」「淵印指環放進深淵」：事件完成時有份的人各擲一次，掉一個這種基底
     * （breach.ring、abyss.ring：{ base, chance }；沒寫不掉）。
     */
    public record EventBase(String base, double chance) {
    }

    private final Map<Kind, EventBase> eventBases = new java.util.EnumMap<>(Kind.class);
    /** 寶箱的每一顆星魄留下來的機率（2026-10-24，map_events.yml 的 strongbox.essence_chance）。 */
    private double essenceChance = 1;
    // 流寇
    private double exileLife = 4.0;
    private double exileDamage = 1.3;
    private List<String> exileMods = List.of("slammer", "charger", "swift");
    private int exileDrops = 2;
    private int minOmens = 1;
    private int maxOmens = 1;
    private List<String> exileNames = List.of("伊薇特", "柯林", "瑞瓦", "托爾加", "安雅", "莫瑟");
    // 2026-10-18：深淵裂縫、地圖保險箱
    private Abyss abyss = Abyss.defaults();
    private Safe safe = Safe.defaults();
    // PoE2 的機制
    private Ritual ritual;
    /** 2026-10-27：祭壇的「傳奇」獎勵有多少機率是祭壇專屬傳奇（其他是共通傳奇 map_boss 池）。 */
    private double ritualExclusiveChance = DEFAULT_RITUAL_EXCLUSIVE_CHANCE;
    /** 2026-10-27：祭壇專屬傳奇的貢品價格範圍（共通傳奇照 prices.unique）。 */
    private int[] ritualExclusivePrice = DEFAULT_RITUAL_EXCLUSIVE_PRICE.clone();
    /** 2026-10-27：剩下的貢品存進帳號，滿這麼多換一張祭壇的入場券（0 = 不存）。 */
    private int ritualBankThreshold = DEFAULT_RITUAL_BANK_THRESHOLD;
    private Delirium delirium;
    /** 2026-11-04 照站長：迷霧回廊（照 PoE 的 Simulacrum：15 波的幻霧場地；map_events.yml 的 fog_trial）。 */
    private FogTrialSettings fogTrial = FogTrialSettings.DEFAULTS;
    /** 2026-11-04 照站長「先做絕境」：絕境（照 PoE 的 Ultimatum；map_events.yml 的 ultimatum）。 */
    private UltimatumSettings ultimatum = UltimatumSettings.DEFAULTS;
    /** 2026-11-04 照站長「軍團」：軍團（照 PoE 的 Legion；map_events.yml 的 legion）。 */
    private LegionSettings legion = LegionSettings.DEFAULTS;

    /**
     * 2026-11-04 照站長「軍團」：軍團的設定（map_events.yml 的 legion）。
     *
     * @param chance         每張圖出現的機率
     * @param freezeSeconds  凍住幾秒（這段時間打到的才會醒）
     * @param soldiers       兩軍合計幾隻（每多一個人 × (1 + perPlayer)）
     * @param rewardCarriers 其中幾隻帶著獎勵（1 份）；sergeants 軍曹（稀有、sergeantShares 份）；generals 將領（傳奇、generalShares 份、生命 ×generalLife；最多 2，一邊一隻）
     * @param rewardWeights  獎勵種類的權重
     * @param domainName     時沉領域（地圖裝置用 domainCost 個 emblem 開；將領掉徽記的機率 generalEmblem、軍曹 sergeantEmblem；怪物等級 +domainLevelBonus）
     */
    public record LegionSettings(double chance, double freezeSeconds, int soldiers, double perPlayer, int rewardCarriers, int sergeants, int generals,
                                 int sergeantShares, int generalShares, double generalLife, Map<Reward, Integer> rewardWeights,
                                 String domainName, String emblem, int domainCost, double generalEmblem, double sergeantEmblem, int domainLevelBonus) {
        public static final LegionSettings DEFAULTS = new LegionSettings(0.2, 25, 24, 0.5, 6, 2, 2, 2, 3, 3.0,
                Map.of(Reward.CURRENCY, 35, Reward.RARE, 15, Reward.ESSENCE, 15, Reward.JEWEL, 10, Reward.MAP, 10, Reward.CATALYST, 10, Reward.OMEN, 5),
                "時沉領域", "legion_emblem", 5, 1.0, 0.3, 2);

        public LegionSettings {
            rewardWeights = Map.copyOf(rewardWeights);
        }
    }

    /**
     * 2026-11-04 照站長「先做絕境」：絕境的設定（map_events.yml 的 ultimatum）。地圖裡一座審判之壇，右鍵開始：一回合一回合打，
     * 每一回合先從三個懲罰挑一個（之後的回合全部疊著），打完可以收手拿累積的獎勵，或續押下一回合（失敗全部沒收）。
     *
     * @param chance          每張圖出現的機率
     * @param rounds          最多幾回合
     * @param prepareSeconds  挑完懲罰到開打的倒數（秒）
     * @param arenaRadius     場地半徑（格；離開場地超過 leaveSeconds 秒算失敗）
     * @param leaveSeconds    可以離開場地幾秒
     * @param killBase        殲滅：第一回合要殺幾隻；每回合多 killPerRound；時限 killSeconds 秒
     * @param surviveBase     生存：第一回合撐幾秒；每回合多 survivePerRound
     * @param circleBase      守圈：第一回合要在圈裡累積幾秒；每回合多 circlePerRound；圈半徑 circleRadius 格、每 circleMoveSeconds 秒換位置；時限是需要秒數的兩倍
     * @param spawnSeconds    每幾秒在每個人附近刷一群（groupMin–groupMax 隻）；每回合多 spawnPerRound（倍率）
     * @param killSpawnSeconds 殲滅回合每幾秒刷一群（2026-11-04 照站長「殲滅 0.5 秒一群」；生存、守圈照 spawnSeconds）
     * @param magicStart      魔法怪的機率（第一回合 → 最後一回合）；稀有怪 rareStart → rareEnd
     * @param rewardsMin      每回合累積的獎勵份數（第一回合 → 最後一回合）；打完全部回合乘 finalMultiplier，再打倒審判者再乘 bossMultiplier
     * @param rewardWeights   獎勵種類的權重
     * @param typeWeights     回合種類的權重（kill、survive、circle）
     * @param modifierWeights 懲罰的權重（代號見 Ultimatum.Modifier；0 = 不出）
     */
    public record UltimatumSettings(double chance, int rounds, double prepareSeconds, double arenaRadius, double leaveSeconds,
                                    int killBase, int killPerRound, double killSeconds, double surviveBase, double survivePerRound,
                                    double circleBase, double circlePerRound, double circleRadius, double circleMoveSeconds,
                                    double spawnSeconds, double killSpawnSeconds, int groupMin, int groupMax, double spawnPerRound,
                                    double magicStart, double magicEnd, double rareStart, double rareEnd,
                                    int rewardsMin, int rewardsMax, double finalMultiplier, double bossMultiplier, Map<Reward, Integer> rewardWeights,
                                    Map<String, Integer> typeWeights, Map<String, Integer> modifierWeights) {
        public static final Set<String> TYPES = Set.of("kill", "survive", "circle");
        // 2026-11-04 照站長：準備 1 秒、生存與守圈減半、殲滅每 0.5 秒一群；這次之前準備 5、生存 30＋10、守圈 15＋5、殲滅跟其他一樣每 2 秒
        public static final UltimatumSettings DEFAULTS = new UltimatumSettings(0.2, 5, 1, 14, 4, 12, 6, 60, 15, 5, 7.5, 2.5, 3.5, 8,
                2.0, 0.5, 2, 4, 0.25, 0.2, 0.5, 0.05, 0.15, 1, 2, 2.0, 2.0,
                Map.of(Reward.CURRENCY, 35, Reward.EMOTION, 15, Reward.ESSENCE, 15, Reward.JEWEL, 10, Reward.MAP, 10, Reward.CATALYST, 10, Reward.OMEN, 5),
                Map.of("kill", 40, "survive", 30, "circle", 30), Map.of());

        public UltimatumSettings {
            rewardWeights = Map.copyOf(rewardWeights);
            typeWeights = Map.copyOf(typeWeights);
            modifierWeights = Map.copyOf(modifierWeights);
        }

        /** 第 round 回合（1 起算）在 0～1 之間的進度。 */
        public double progress(final int round) {
            return rounds <= 1 ? 1 : Math.max(0, Math.min(1, (round - 1) / (double) (rounds - 1)));
        }

        public int killCount(final int round, final int players) {
            return Math.max(1, (int) Math.round((killBase + killPerRound * (round - 1)) * (1 + 0.5 * Math.max(0, players - 1))));
        }

        public double surviveSeconds(final int round) {
            return surviveBase + survivePerRound * (round - 1);
        }

        public double circleSeconds(final int round) {
            return circleBase + circlePerRound * (round - 1);
        }

        /** 第 round 回合刷怪的倍率（1 起算）。 */
        public double spawnScale(final int round) {
            return 1 + spawnPerRound * (round - 1);
        }

        public double magicChance(final int round) {
            return magicStart + (magicEnd - magicStart) * progress(round);
        }

        public double rareChance(final int round) {
            return rareStart + (rareEnd - rareStart) * progress(round);
        }

        /** 第 round 回合過關累積幾份獎勵。 */
        public int rewards(final int round) {
            return (int) Math.round(rewardsMin + (rewardsMax - rewardsMin) * progress(round));
        }

        /** 懲罰的權重（沒寫的照 def）。 */
        public int modifierWeight(final String id, final int def) {
            return modifierWeights.getOrDefault(id, def);
        }
    }

    /**
     * 2026-11-04 照站長「波次引擎＋15 層迷霧」：迷霧回廊的設定（map_events.yml 的 fog_trial）。
     *
     * @param name          玩家看到的名字
     * @param splinter      入場用的碎片（currencies.yml 的代號）
     * @param cost          開一次用幾個碎片
     * @param splinterDrop  幻霧結束時拿到獎勵的人各拿幾個碎片（吃物品數量）
     * @param levelBonus    怪物等級＝開的人打過的最高階的地圖等級＋這麼多
     * @param waves         總共幾波（最後一波是 Boss）
     * @param breakSeconds  每波之間休息幾秒
     * @param waveSeconds   一波最多幾秒（到了剩下的怪消失、照樣算過這一波；卡住不會打不完）
     * @param monsters      第一波幾隻
     * @param perWave       每過一波多幾隻
     * @param perPlayer     每多一個人多幾成（0.5 = 兩個人 1.5 倍）
     * @param spawnSeconds  每隔幾秒在每個人附近刷一群
     * @param groupMin      一群最少幾隻
     * @param groupMax      一群最多幾隻
     * @param difficultyEnd 最後一波怪的生命、傷害增加多少（2.0 = +200%；第一波 0，照波數平均往上）
     * @param magicStart    第一波魔法怪的機率
     * @param magicEnd      最後一波魔法怪的機率
     * @param rareFrom      第幾波起有稀有怪
     * @param rareStart     稀有怪的機率（rareFrom 那一波）
     * @param rareEnd       稀有怪的機率（最後一波）
     * @param eliteWaves    這幾波另外出一隻傳奇怪（菁英）
     * @param eliteLife     菁英的生命倍率
     * @param rewardsMin    第一波過關每人幾份獎勵
     * @param rewardsMax    最後一波過關每人幾份獎勵
     * @param rewardWeights 獎勵種類的權重（跟幻霧一樣的種類）
     */
    public record FogTrialSettings(String name, String splinter, int cost, int[] splinterDrop, int levelBonus, int waves, double breakSeconds,
                                   double waveSeconds, int monsters, int perWave, double perPlayer, double spawnSeconds, int groupMin,
                                   int groupMax, double difficultyEnd, double magicStart, double magicEnd, int rareFrom, double rareStart,
                                   double rareEnd, Set<Integer> eliteWaves, double eliteLife, int rewardsMin, int rewardsMax,
                                   Map<Reward, Integer> rewardWeights) {
        // 2026-11-04 照站長「殺完怪物馬上進下一階段、怪物每 0.5 秒刷一輪」；這次之前休息 8 秒、每 1.5 秒
        public static final FogTrialSettings DEFAULTS = new FogTrialSettings("迷霧回廊", "fog_splinter", 50, new int[] {4, 8}, 2, 15, 0, 90,
                18, 4, 0.5, 0.5, 3, 5, 2.0, 0.15, 0.6, 4, 0.03, 0.15, Set.of(5, 10), 4.0, 1, 3,
                Map.of(Reward.EMOTION, 40, Reward.CURRENCY, 25, Reward.ESSENCE, 10, Reward.JEWEL, 10, Reward.MAP, 10, Reward.CATALYST, 5));

        public FogTrialSettings {
            splinterDrop = splinterDrop.clone();
            eliteWaves = Set.copyOf(eliteWaves);
            rewardWeights = Map.copyOf(rewardWeights);
        }

        /** 第 wave 波（1 起算）在 0～1 之間的進度（第一波 0、最後一波 1）。 */
        public double progress(final int wave) {
            return waves <= 1 ? 1 : Math.max(0, Math.min(1, (wave - 1) / (double) (waves - 1)));
        }

        /** 第 wave 波的怪物生命、傷害增加（給 MapInstance.setDelirium）。 */
        public double difficulty(final int wave) {
            return difficultyEnd * progress(wave);
        }

        /** 第 wave 波總共幾隻（players 個人）。 */
        public int count(final int wave, final int players) {
            final double scale = 1 + perPlayer * Math.max(0, players - 1);
            return Math.max(1, (int) Math.round((monsters + perWave * (wave - 1)) * scale));
        }

        public double magicChance(final int wave) {
            return magicStart + (magicEnd - magicStart) * progress(wave);
        }

        public double rareChance(final int wave) {
            if (wave < rareFrom) {
                return 0;
            }
            final double t = waves <= rareFrom ? 1 : (wave - rareFrom) / (double) (waves - rareFrom);
            return rareStart + (rareEnd - rareStart) * Math.max(0, Math.min(1, t));
        }

        /** 第 wave 波過關每人幾份獎勵。 */
        public int rewards(final int wave) {
            return (int) Math.round(rewardsMin + (rewardsMax - rewardsMin) * progress(wave));
        }

        public boolean bossWave(final int wave) {
            return wave == waves;
        }
    }
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

    /**
     * 2026-10-18：事件巔峰的入場券：完成這種事件時有份的人各擲一次 {@code chance} 掉一張 {@code currency}（currencies.yml 的代號）。
     */
    public record Ticket(String currency, double chance) {
    }

    /** 2026-10-18：有入場券的事件來源代號（map_events.yml 的區塊名；保險箱沒有）。 */
    public static final List<String> TICKET_SOURCES = List.of("shrine", "strongbox", "breach", "exile", "ritual", "delirium", "abyss");
    /** 2026-10-18：入場券的預設機率（2%）。 */
    public static final double DEFAULT_TICKET_CHANCE = 0.02;

    private void defaults() {
        minPerMap = 2;
        maxPerMap = 3;
        weights.clear();
        weights.put(Kind.SHRINE, 30); // 2026-10-18：35、35、15、15 → 30、30、15、10，多一種深淵裂縫 15（保險箱不在這裡，照 safe_chance）
        weights.put(Kind.STRONGBOX, 30);
        weights.put(Kind.BREACH, 15);
        weights.put(Kind.EXILE, 10);
        weights.put(Kind.ABYSS, 15);
        uniqueChance.clear();
        for (final Kind kind : Kind.values()) {
            uniqueChance.put(kind, kind == Kind.SAFE ? 0 : MapConfig.DEFAULT_UNIQUE_CHANCE); // 2026-10-18：保險箱沒有專屬傳奇
        }
        tickets.clear(); // 2026-10-18：入場券預設 ticket_<事件> 2%
        for (final String source : TICKET_SOURCES) {
            tickets.put(source, new Ticket("ticket_" + source, DEFAULT_TICKET_CHANCE));
        }
        abyss = Abyss.defaults();
        safe = Safe.defaults();
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
        eventBases.clear();
        essenceChance = 1;
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
                // 2026-10-27 照 PoE2 0.5：祭壇只給傳奇與星籤（這次之前星籤 30、通貨 25、稀有 15、星魄 10、珠寶 8、地圖 6、催化劑 4、傳奇 1）
                Map.of(Reward.OMEN, 65, Reward.UNIQUE, 35),
                Map.of(Reward.OMEN, new int[] {260, 420}, Reward.UNIQUE, new int[] {280, 520}));
        ritualExclusiveChance = DEFAULT_RITUAL_EXCLUSIVE_CHANCE;
        ritualExclusivePrice = DEFAULT_RITUAL_EXCLUSIVE_PRICE.clone();
        ritualBankThreshold = DEFAULT_RITUAL_BANK_THRESHOLD;
        delirium = new Delirium(0.3, 75, 60, 0.6, 4, 2, 3,
                Map.of(tw.exilecore.monster.MonsterRarity.NORMAL, 1, tw.exilecore.monster.MonsterRarity.MAGIC, 3,
                        tw.exilecore.monster.MonsterRarity.RARE, 10, tw.exilecore.monster.MonsterRarity.UNIQUE, 25),
                2, List.of(20, 50, 100, 170),
                Map.of(Reward.EMOTION, 40, Reward.CURRENCY, 25, Reward.ESSENCE, 10, Reward.JEWEL, 10, Reward.MAP, 10, Reward.CATALYST, 5,
                        Reward.UNIQUE, 1),
                // 2026-10-27 照 PoE2：十種蒸餾情緒；這次之前四種、權重 50／30／15／5
                List.of("distilled_ire", "distilled_guilt", "distilled_greed", "distilled_paranoia", "distilled_envy", "distilled_disgust",
                        "distilled_despair", "distilled_fear", "distilled_suffering", "distilled_isolation",
                        "distilled_melancholy", "distilled_ferocity", "distilled_contempt"),
                List.of(500, 300, 180, 108, 65, 39, 23, 14, 8, 5, 3, 3, 3));
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
        tickets.clear(); // 2026-10-18
        tickets.putAll(other.tickets);
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
        eventBases.clear();
        eventBases.putAll(other.eventBases);
        essenceChance = other.essenceChance;
        exileLife = other.exileLife;
        exileDamage = other.exileDamage;
        exileMods = other.exileMods;
        exileDrops = other.exileDrops;
        minOmens = other.minOmens;
        maxOmens = other.maxOmens;
        exileNames = other.exileNames;
        abyss = other.abyss; // 2026-10-18
        safe = other.safe;
        ritual = other.ritual;
        ritualExclusiveChance = other.ritualExclusiveChance;
        ritualExclusivePrice = other.ritualExclusivePrice.clone();
        ritualBankThreshold = other.ritualBankThreshold;
        delirium = other.delirium;
        fogTrial = other.fogTrial;
        ultimatum = other.ultimatum;
        legion = other.legion;
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
        weights.remove(Kind.SAFE); // 2026-10-18：保險箱不照權重抽（寫了也不算），照 settings.safe_chance

        final YamlNode shrine = root.node("shrine");
        shrineDuration = (int) clamp(shrine.integer("duration", shrineDuration), 5, 600);
        if (shrine.has("guards")) {
            final int[] range = shrine.range("guards");
            minGuards = Math.max(0, Math.min(range[0], range[1]));
            maxGuards = Math.min(20, Math.max(range[0], range[1]));
        }
        shrineReach = clamp(shrine.decimal("reach", shrineReach), 2, 48);
        uniqueChance.put(Kind.SHRINE, clamp(shrine.decimal("unique_chance", uniqueChance.get(Kind.SHRINE)), 0, 1));
        // 2026-10-18：各事件的入場券（ticket: { currency, chance }；沒寫照預設 ticket_<事件> 2%；chance 0 = 不掉）
        for (final String source : TICKET_SOURCES) {
            final YamlNode section = root.node(source);
            if (section.has("ticket")) {
                final YamlNode node = section.node("ticket");
                final Ticket base = tickets.get(source);
                tickets.put(source, new Ticket(node.string("currency", base.currency()).trim(), clamp(node.decimal("chance", base.chance()), 0, 1)));
            }
        }
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
        essenceChance = clamp(box.decimal("essence_chance", 1), 0, 1);
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
        readEventBase(Kind.BREACH, breach); // 2026-11-04：裂隙指環
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

        // 2026-10-18：深淵裂縫
        final YamlNode abyssNode = root.node("abyss");
        readEventBase(Kind.ABYSS, abyssNode); // 2026-11-04：淵印指環
        {
            final Abyss base = abyss;
            final int[] length = abyssNode.has("length") ? abyssNode.range("length") : new int[] {base.minLength(), base.maxLength()};
            final int[] wave = abyssNode.has("wave") ? abyssNode.range("wave") : new int[] {base.minWave(), base.maxWave()};
            final int[] shards = abyssNode.has("shards") ? abyssNode.range("shards") : new int[] {base.minShards(), base.maxShards()};
            final YamlNode boss = abyssNode.node("boss");
            final YamlNode chest = abyssNode.node("chest");
            abyss = new Abyss(clamp(abyssNode.decimal("advance_seconds", base.advanceSeconds()), 0.5, 30),
                    (int) clamp(abyssNode.integer("advance", base.advance()), 1, 10),
                    Math.max(6, Math.min(length[0], length[1])), Math.min(120, Math.max(length[0], length[1])),
                    Math.max(0, Math.min(wave[0], wave[1])), Math.min(20, Math.max(wave[0], wave[1])),
                    clamp(abyssNode.decimal("quantity_bonus", base.quantityBonus() * 100), 0, 1000) / 100.0,
                    clamp(abyssNode.decimal("follow", base.follow()), 2, 48), clamp(abyssNode.decimal("stall_seconds", base.stallSeconds()), 1, 120),
                    clamp(boss.decimal("life", base.bossLife()), 0.5, 50), boss.has("mods") ? List.copyOf(boss.strings("mods")) : base.bossMods(),
                    (int) clamp(chest.integer("rolls", base.rolls()), 0, 20),
                    clamp(chest.decimal("quantity_bonus", base.chestQuantity() * 100), -100, 1000) / 100.0,
                    clamp(chest.decimal("rarity_bonus", base.chestRarity() * 100), -100, 1000) / 100.0,
                    abyssNode.string("shard_currency", base.shardCurrency()).trim(),
                    Math.max(0, Math.min(shards[0], shards[1])), Math.min(50, Math.max(shards[0], shards[1])),
                    clamp(abyssNode.decimal("radius", base.radius()), 4, 64), clamp(abyssNode.decimal("linger", base.linger()), 0, 600));
            uniqueChance.put(Kind.ABYSS, clamp(abyssNode.decimal("unique_chance", uniqueChance.get(Kind.ABYSS)), 0, 1));
        }
        // 2026-10-18：地圖保險箱（出現機率在 settings.safe_chance）
        final YamlNode safeNode = root.node("safe");
        {
            final Safe base = safe;
            final int[] wave = safeNode.has("wave") ? safeNode.range("wave") : new int[] {base.minWave(), base.maxWave()};
            final int[] rolls = safeNode.has("rolls") ? safeNode.range("rolls") : new int[] {base.minRolls(), base.maxRolls()};
            final int[] currency = safeNode.has("currency") ? safeNode.range("currency") : new int[] {base.minCurrency(), base.maxCurrency()};
            safe = new Safe(clamp(settings.decimal("safe_chance", base.chance()), 0, 1),
                    clamp(safeNode.decimal("unlock_seconds", base.unlockSeconds()), 3, 300), clamp(safeNode.decimal("wave_seconds", base.waveSeconds()), 1, 60),
                    Math.max(0, Math.min(wave[0], wave[1])), Math.min(20, Math.max(wave[0], wave[1])),
                    clamp(safeNode.decimal("radius", base.radius()), 2, 32), clamp(safeNode.decimal("grace", base.grace()), 0, 300),
                    Math.max(0, Math.min(rolls[0], rolls[1])), Math.min(20, Math.max(rolls[0], rolls[1])),
                    clamp(safeNode.decimal("quantity_bonus", base.quantityBonus() * 100), -100, 1000) / 100.0,
                    clamp(safeNode.decimal("rarity_bonus", base.rarityBonus() * 100), -100, 1000) / 100.0,
                    Math.max(0, Math.min(currency[0], currency[1])), Math.min(50, Math.max(currency[0], currency[1])),
                    clamp(safeNode.decimal("blueprint_chance", base.blueprintChance()), 0, 1), clamp(safeNode.decimal("reward_radius", base.rewardRadius()), 4, 64));
            uniqueChance.put(Kind.SAFE, clamp(safeNode.decimal("unique_chance", uniqueChance.get(Kind.SAFE)), 0, 1));
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
            ritualExclusiveChance = clamp(node.decimal("exclusive_chance", ritualExclusiveChance), 0, 1);
            if (node.has("exclusive_price")) {
                final int[] range = node.range("exclusive_price");
                ritualExclusivePrice = new int[] {Math.max(1, Math.min(range[0], range[1])), Math.max(1, Math.max(range[0], range[1]))};
            }
            if (node.has("tribute_bank")) {
                ritualBankThreshold = Math.max(0, node.node("tribute_bank").integer("threshold", ritualBankThreshold));
            }
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
        if (root.has("fog_trial")) {
            fogTrial = parseFogTrial(root.node("fog_trial"), fogTrial); // 2026-11-04：迷霧回廊
        }
        if (root.has("ultimatum")) {
            ultimatum = parseUltimatum(root.node("ultimatum"), ultimatum); // 2026-11-04：絕境
        }
        if (root.has("legion")) {
            legion = parseLegion(root.node("legion"), legion); // 2026-11-04：軍團
        }
    }

    /** 2026-11-04：map_events.yml 的 fog_trial（迷霧回廊；沒寫的照預設）。 */
    static FogTrialSettings parseFogTrial(final YamlNode node, final FogTrialSettings base) {
        final int[] drop = node.has("splinter_drop") ? node.range("splinter_drop") : base.splinterDrop();
        final YamlNode monsters = node.node("monsters");
        final YamlNode spawn = node.node("spawn");
        final int[] group = spawn.has("group") ? spawn.range("group") : new int[] {base.groupMin(), base.groupMax()};
        final YamlNode magic = node.node("magic");
        final YamlNode rare = node.node("rare");
        final YamlNode elite = node.node("elite");
        final YamlNode rewards = node.node("rewards");
        final int[] perWave = rewards.has("per_wave") ? rewards.range("per_wave") : new int[] {base.rewardsMin(), base.rewardsMax()};
        final Set<Integer> eliteWaves = new java.util.TreeSet<>();
        if (elite.has("waves")) {
            for (final String text : elite.strings("waves")) {
                eliteWaves.add(Integer.parseInt(text.trim()));
            }
        } else {
            eliteWaves.addAll(base.eliteWaves());
        }
        final int waves = (int) clamp(node.integer("waves", base.waves()), 1, 50);
        return new FogTrialSettings(node.string("name", base.name()), node.string("splinter", base.splinter()),
                (int) clamp(node.integer("cost", base.cost()), 1, 10000),
                new int[] {Math.max(0, Math.min(drop[0], drop[1])), Math.max(0, Math.max(drop[0], drop[1]))},
                (int) clamp(node.integer("level_bonus", base.levelBonus()), 0, 30), waves,
                clamp(node.decimal("break_seconds", base.breakSeconds()), 0, 60) /* 2026-11-04 照站長「殺完馬上進下一波」可以寫 0；這次之前最少 2 */, clamp(node.decimal("wave_seconds", base.waveSeconds()), 20, 600),
                (int) clamp(monsters.integer("base", base.monsters()), 1, 200), (int) clamp(monsters.integer("per_wave", base.perWave()), 0, 50),
                clamp(monsters.decimal("per_player", base.perPlayer()), 0, 2),
                clamp(spawn.decimal("every", base.spawnSeconds()), 0.5, 10),
                Math.max(1, Math.min(group[0], group[1])), Math.min(12, Math.max(group[0], group[1])),
                clamp(node.decimal("difficulty", base.difficultyEnd() * 100), 0, 1000) / 100.0,
                clamp(magic.decimal("start", base.magicStart()), 0, 1), clamp(magic.decimal("end", base.magicEnd()), 0, 1),
                (int) clamp(rare.integer("from_wave", base.rareFrom()), 1, waves),
                clamp(rare.decimal("start", base.rareStart()), 0, 1), clamp(rare.decimal("end", base.rareEnd()), 0, 1),
                eliteWaves, clamp(elite.decimal("life", base.eliteLife()), 1, 50),
                (int) clamp(Math.min(perWave[0], perWave[1]), 0, 10), (int) clamp(Math.max(perWave[0], perWave[1]), 0, 10),
                rewards.has("weights") ? rewardInts(rewards.node("weights")) : base.rewardWeights());
    }

    /** 2026-11-04：map_events.yml 的 ultimatum（絕境；沒寫的照預設）。 */
    static UltimatumSettings parseUltimatum(final YamlNode node, final UltimatumSettings base) {
        final YamlNode kill = node.node("kill");
        final YamlNode survive = node.node("survive");
        final YamlNode circle = node.node("circle");
        final YamlNode spawn = node.node("spawn");
        final YamlNode magic = node.node("magic");
        final YamlNode rare = node.node("rare");
        final YamlNode rewards = node.node("rewards");
        final int[] group = spawn.has("group") ? spawn.range("group") : new int[] {base.groupMin(), base.groupMax()};
        final int[] perRound = rewards.has("per_round") ? rewards.range("per_round") : new int[] {base.rewardsMin(), base.rewardsMax()};
        final Map<String, Integer> modifiers = new LinkedHashMap<>(base.modifierWeights());
        if (node.has("modifiers")) {
            final YamlNode list = node.node("modifiers");
            for (final String key : list.keys()) {
                modifiers.put(key.trim().toLowerCase(Locale.ROOT), Math.max(0, list.integer(key, 0)));
            }
        }
        return new UltimatumSettings(clamp(node.decimal("chance", base.chance()), 0, 1), (int) clamp(node.integer("rounds", base.rounds()), 1, 20),
                clamp(node.decimal("prepare_seconds", base.prepareSeconds()), 1, 60), clamp(node.decimal("arena_radius", base.arenaRadius()), 6, 40),
                clamp(node.decimal("leave_seconds", base.leaveSeconds()), 1, 60),
                (int) clamp(kill.integer("base", base.killBase()), 1, 500), (int) clamp(kill.integer("per_round", base.killPerRound()), 0, 200),
                clamp(kill.decimal("seconds", base.killSeconds()), 10, 600),
                clamp(survive.decimal("base", base.surviveBase()), 5, 600), clamp(survive.decimal("per_round", base.survivePerRound()), 0, 300),
                clamp(circle.decimal("base", base.circleBase()), 3, 600), clamp(circle.decimal("per_round", base.circlePerRound()), 0, 300),
                clamp(circle.decimal("radius", base.circleRadius()), 1.5, 10), clamp(circle.decimal("move_seconds", base.circleMoveSeconds()), 2, 60),
                clamp(spawn.decimal("every", base.spawnSeconds()), 0.5, 10), clamp(spawn.decimal("kill_every", base.killSpawnSeconds()), 0.5, 10),
                Math.max(1, Math.min(group[0], group[1])), Math.min(12, Math.max(group[0], group[1])),
                clamp(spawn.decimal("per_round", base.spawnPerRound()), 0, 3),
                clamp(magic.decimal("start", base.magicStart()), 0, 1), clamp(magic.decimal("end", base.magicEnd()), 0, 1),
                clamp(rare.decimal("start", base.rareStart()), 0, 1), clamp(rare.decimal("end", base.rareEnd()), 0, 1),
                (int) clamp(Math.min(perRound[0], perRound[1]), 0, 10), (int) clamp(Math.max(perRound[0], perRound[1]), 0, 10),
                clamp(rewards.decimal("final_multiplier", base.finalMultiplier()), 1, 10),
                clamp(rewards.decimal("boss_multiplier", base.bossMultiplier()), 1, 10),
                rewards.has("weights") ? rewardInts(rewards.node("weights")) : base.rewardWeights(),
                node.has("types") ? weights(node.node("types"), UltimatumSettings.TYPES) : base.typeWeights(), modifiers);
    }

    /** 2026-11-04：map_events.yml 的 legion（軍團；沒寫的照預設）。 */
    static LegionSettings parseLegion(final YamlNode node, final LegionSettings base) {
        final YamlNode rewards = node.node("rewards");
        final YamlNode domain = node.node("domain"); // 2026-11-04：時沉領域
        return new LegionSettings(clamp(node.decimal("chance", base.chance()), 0, 1), clamp(node.decimal("freeze_seconds", base.freezeSeconds()), 5, 120),
                (int) clamp(node.integer("soldiers", base.soldiers()), 4, 120), clamp(node.decimal("per_player", base.perPlayer()), 0, 2),
                (int) clamp(node.integer("reward_carriers", base.rewardCarriers()), 0, 40), (int) clamp(node.integer("sergeants", base.sergeants()), 0, 10),
                (int) clamp(node.integer("generals", base.generals()), 0, 2),
                (int) clamp(rewards.integer("sergeant_shares", base.sergeantShares()), 1, 10), (int) clamp(rewards.integer("general_shares", base.generalShares()), 1, 10),
                clamp(node.decimal("general_life", base.generalLife()), 1, 20),
                rewards.has("weights") ? rewardInts(rewards.node("weights")) : base.rewardWeights(),
                domain.string("name", base.domainName()), domain.string("emblem", base.emblem()), (int) clamp(domain.integer("cost", base.domainCost()), 1, 1000),
                clamp(domain.decimal("general_drop", base.generalEmblem()), 0, 1), clamp(domain.decimal("sergeant_drop", base.sergeantEmblem()), 0, 1),
                (int) clamp(domain.integer("level_bonus", base.domainLevelBonus()), 0, 30));
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
            throw new IllegalArgumentException(where + "：不認得的事件 " + text + "（shrine、strongbox、breach、exile、abyss、safe）");
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

    /** 這種事件的專屬傳奇在 uniques.yml 的來源代號（shrine、strongbox、breach、exile、abyss；safe 沒有專屬）。 */
    public static String uniqueSource(final Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** 祭壇、幻霧的專屬傳奇來源代號。 */
    public static final String RITUAL_SOURCE = "ritual";
    public static final String DELIRIUM_SOURCE = "delirium";

    /** 2026-10-18：這種事件的入場券（來源代號 shrine、ritual…）；沒有（保險箱、不認得的）回傳 null。 */
    public Ticket ticket(final String source) {
        return tickets.get(source);
    }

    /** 2026-10-18：這種事件的入場券；保險箱沒有（null）。 */
    public Ticket ticket(final Kind kind) {
        return ticket(uniqueSource(kind));
    }

    /** 2026-10-18：所有的入場券（來源代號 → 券），依事件順序。 */
    public Map<String, Ticket> tickets() {
        return java.util.Collections.unmodifiableMap(tickets);
    }

    /** 2026-10-18：哪一種事件掉這張入場券（通貨代號 → 來源代號）；沒有事件掉它回傳 null。 */
    public String ticketSource(final String currency) {
        for (final Map.Entry<String, Ticket> entry : tickets.entrySet()) {
            if (entry.getValue().currency().equals(currency) && entry.getValue().chance() > 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 2026-10-18：事件來源代號的中文名（神龕、寶箱、裂隙、流寇、祭壇、幻霧、深淵裂縫、保險箱）。 */
    public static String sourceName(final String source) {
        return switch (source) {
            case "shrine" -> "神龕";
            case "strongbox" -> "寶箱";
            case "breach" -> "裂隙";
            case "exile" -> "流寇";
            case RITUAL_SOURCE -> "祭壇";
            case DELIRIUM_SOURCE -> "幻霧";
            case "abyss" -> "深淵裂縫";
            case "safe" -> "保險箱";
            default -> source;
        };
    }

    public double catalystChance() {
        return catalystChance;
    }

    /** 2026-11-04：這種事件完成時掉的基底（裂隙指環、淵印指環；沒有 null）。 */
    public EventBase eventBase(final Kind kind) {
        return eventBases.get(kind);
    }

    private void readEventBase(final Kind kind, final YamlNode node) {
        if (node == null || !node.has("ring")) {
            return;
        }
        final YamlNode ring = node.node("ring");
        final String base = ring.string("base", null);
        if (base != null && !base.isBlank()) {
            eventBases.put(kind, new EventBase(base.trim(), clamp(ring.decimal("chance", 0), 0, 1)));
        }
    }

    public double essenceChance() {
        return essenceChance;
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

    /** 深淵裂縫（2026-10-18）。 */
    public Abyss abyss() {
        return abyss;
    }

    /** 地圖保險箱（2026-10-18）。 */
    public Safe safe() {
        return safe;
    }

    public Ritual ritual() {
        return ritual;
    }

    /** 2026-10-27 預設：祭壇的傳奇獎勵 3% 是專屬傳奇（7 件獎勵、傳奇權重 35%：一組平均 0.07 件專屬，跟以前權重 1/99 一樣）。 */
    public static final double DEFAULT_RITUAL_EXCLUSIVE_CHANCE = 0.03;
    /** 2026-10-27 預設：專屬傳奇的價格（跟以前的傳奇一樣）。 */
    static final int[] DEFAULT_RITUAL_EXCLUSIVE_PRICE = {520, 880};
    /** 2026-10-27 預設：存下的貢品滿 10000 換一張入場券。 */
    public static final int DEFAULT_RITUAL_BANK_THRESHOLD = 10000;

    /** 祭壇的傳奇獎勵是專屬傳奇的機率（ritual.exclusive_chance）。 */
    public double ritualExclusiveChance() {
        return ritualExclusiveChance;
    }

    /** 祭壇專屬傳奇的貢品價格範圍（ritual.exclusive_price）。 */
    public int[] ritualExclusivePrice() {
        return ritualExclusivePrice.clone();
    }

    /** 剩下的貢品存進帳號，滿這麼多換一張入場券（ritual.tribute_bank.threshold；0 = 不存）。 */
    public int ritualBankThreshold() {
        return ritualBankThreshold;
    }

    public Delirium delirium() {
        return delirium;
    }

    /** 2026-11-04：迷霧回廊的設定。 */
    public FogTrialSettings fogTrial() {
        return fogTrial;
    }

    public UltimatumSettings ultimatum() {
        return ultimatum;
    }

    public LegionSettings legion() {
        return legion;
    }

    public Twists twists() {
        return twists;
    }
}
