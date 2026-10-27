package tw.exilecore.map;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.skill.BossHazard;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * data/maps.yml：地圖的設定、掉落、首殺獎勵、每張地圖（主題、星圖位置、相連、怪物、Boss）與終局。
 * 純資料，不依賴 Bukkit；{@link #validate} 對照物品資料檢查基底存在而且是地圖。
 */
public final class MapConfig {

    /** 版面主題。 */
    public static final Set<String> THEMES = Set.of("beach", "forest", "ruins", "swamp", "snow", "cave", "crypt", "star",
            "desert", "volcano", "fungal", "void");
    /** 終局的圈數（只用來排序與顯示；一般地圖照基底的 map_tN）。 */
    public static final int FINAL_RING = 6;

    public record PackSettings(int weight, int minSize, int maxSize) {
    }

    /**
     * @param tiers             地圖的階級表：每一階的怪物等級、每高一階多的物品數量與稀有度（2026-10-27 照 PoE2 都是 0）
     * @param instanceCap       同時最多幾個實例
     * @param portals           每張圖可以進入幾次
     * @param idleMinutes       裡面沒人多久就關閉
     * @param emptyCloseSeconds 次數用完、裡面沒人多久就關閉
     * @param packRadius        玩家走到怪物群多近時刷出來
     * @param bossRadius        玩家走到 Boss 房中心多近時 Boss 出現
     */
    public record Settings(MapStats.Tiers tiers, int instanceCap, int portals, int idleMinutes, int emptyCloseSeconds,
                           double packRadius, double bossRadius, PackSettings normal, PackSettings magic, PackSettings rare) {
    }

    /**
     * @param tierCapBase    掉落的地圖最高幾階：一開始這麼多，
     * @param tierCapPerMap  星圖每完成一張不同的地圖再高這麼多階
     * @param rarity         T1 地圖掉落時的稀有度權重
     * @param rarityTop      最高階地圖掉落時的稀有度權重（中間照階級線性內插）
     * @param frontierWeight 掉哪一張：解鎖了還沒完成的地圖（星圖的前線）權重是完成過的幾倍
     * @param bossUniqueChance   地圖 Boss（不含終局）掉共通傳奇（map_boss 池）的機率
     * @param namedUniqueChance  具名 Boss 掉它自己那件傳奇的機率
     * @param chaseChance       所有地圖 Boss（一般、具名、終局、極境）掉追逐級傳奇（chase 池）的機率：固定，不吃任何加成
     * @param finalUniqueChance 終局 Boss 掉它自己專屬那件傳奇的機率（不是必掉：每個有份的人各擲一次）
     * @param finalCommonChance  終局 Boss（與極境）另外掉一件共通傳奇（map_boss 池）的機率
     * @param finalSupportChance 終局 Boss 掉它自己專屬輔助寶石（supports.yml 的 source 寫這個終局）的機率
     */
    public record Drops(Map<MonsterRarity, Double> mapChance, int bossMaps, double tierUpChance, double tierUpPerCompleted,
                        int tierCapBase, int tierCapPerMap, Map<Rarity, Integer> rarity, Map<Rarity, Integer> rarityTop,
                        int frontierWeight, double bossUniqueChance, double namedUniqueChance, String fragment,
                        Set<String> fragmentMaps, int fragmentCost, String campaignBossZone, int campaignBossMaps,
                        int campaignFirstKillMaps, int campaignBossDailyMaps, double chaseChance, double finalUniqueChance,
                        double finalCommonChance, double finalSupportChance) {
        public Drops {
            mapChance = Map.copyOf(mapChance);
            rarity = Map.copyOf(rarity);
            rarityTop = Map.copyOf(rarityTop);
            fragmentMaps = Set.copyOf(fragmentMaps);
        }
    }

    public record FirstKill(int mapPoints, int namedPoints, int finalPoints) {
    }

    /**
     * Boss 的一個階段：血量比例掉到 {@code at} 以下時觸發一次。
     *
     * @param summonType  召喚的原版生物（null = 不召喚）
     * @param summonCount 召喚幾隻
     * @param stats       加在 Boss 身上的修飾器
     * @param speed       原版移動速度加成（0.25 = 快 25%）
     * @param shield      進入這個階段時的護盾（null = 沒有）
     */
    public record Phase(double at, String name, String message, String summonType, int summonCount,
                        List<AffixMod> stats, double speed, Shield shield) {
        public Phase {
            stats = List.copyOf(stats);
        }

        /** 沒有護盾的階段。 */
        public Phase(final double at, final String name, final String message, final String summonType, final int summonCount,
                     final List<AffixMod> stats, final double speed) {
            this(at, name, message, summonType, summonCount, stats, speed, null);
        }
    }

    /**
     * 階段護盾：Boss 無敵，場上出現 count 顆結晶（不會動、不會打人）；duration 秒內全部打掉護盾就破，
     * 沒打完 Boss 回 heal% 最大生命、剩下的結晶消失。
     *
     * @param type     結晶用的原版生物（沒有 AI、不會掉下去）
     * @param name     結晶的名字
     * @param life     結晶的生命：同等級普通怪物的幾倍
     * @param duration 幾秒內要打完
     * @param heal     沒打完時 Boss 回幾 % 最大生命
     */
    public record Shield(int count, String type, String name, double life, double duration, double heal) {
    }

    /**
     * 狂暴：開打 {@code after} 秒後還沒打倒，Boss 更多 damage% 傷害、移動快 speed（一場只會一次）。
     *
     * @param message 狂暴時 Boss 喊的話
     */
    public record Enrage(double after, double damage, double speed, String message) {
    }

    /**
     * @param skills  招式組（data/boss_skills.yml 的代號；空的 = 用這種生物的預設招式組）
     * @param enrage  狂暴（null = 沒有）
     * @param hazards 場地危害（照時間自己落下的招式）
     */
    public record Boss(String name, String type, String mythic, boolean named, double life, double damage, double scale,
                       List<String> mods, String intro, String defeat, List<Phase> phases, List<BossSkillUse> skills,
                       Enrage enrage, List<BossHazard> hazards) {
        public Boss {
            mods = List.copyOf(mods);
            phases = List.copyOf(phases);
            skills = skills == null ? List.of() : List.copyOf(skills);
            hazards = hazards == null ? List.of() : List.copyOf(hazards);
        }

        /** 沒有狂暴與場地危害。 */
        public Boss(final String name, final String type, final String mythic, final boolean named, final double life, final double damage,
                    final double scale, final List<String> mods, final String intro, final String defeat, final List<Phase> phases,
                    final List<BossSkillUse> skills) {
            this(name, type, mythic, named, life, damage, scale, mods, intro, defeat, phases, skills, null, List.of());
        }

        /** 沒寫招式組（用預設）。 */
        public Boss(final String name, final String type, final String mythic, final boolean named, final double life, final double damage,
                    final double scale, final List<String> mods, final String intro, final String defeat, final List<Phase> phases) {
            this(name, type, mythic, named, life, damage, scale, mods, intro, defeat, phases, List.of());
        }
    }

    /**
     * 一張地圖（或終局）。地圖本身沒有階級：階級寫在地圖物品上（T1–T16），終局開的時候選。
     *
     * @param base    物品基底代號（終局為 null）
     * @param ring    星圖上的第幾圈（基底的 map_t1 … map_t5；終局是 6）：決定在星圖的哪裡、
     *                最外圈全部完成後解鎖終局、版面大小、舊的地圖物品（沒寫階級的）當作幾階
     * @param level   固定的怪物等級：只有特別的實例（回響、試煉）用；一般地圖與終局是 0（照階級）
     * @param col     星圖上的欄
     * @param row     星圖上的列
     * @param isFinal 是不是終局
     */
    public record MapDef(String id, String name, String base, int ring, int level, String theme, int col, int row,
                         List<String> links, boolean start, List<ZoneConfig.SpawnEntry> monsters, Boss boss, String lore,
                         boolean isFinal, MapLayout.Style layout) {
        public MapDef {
            links = List.copyOf(links);
            monsters = List.copyOf(monsters);
            layout = layout == null ? MapLayout.Style.HALLS : layout;
        }

        /** 廳堂樣式的版面（試煉、回響）。 */
        public MapDef(final String id, final String name, final String base, final int ring, final int level, final String theme, final int col,
                      final int row, final List<String> links, final boolean start, final List<ZoneConfig.SpawnEntry> monsters, final Boss boss,
                      final String lore, final boolean isFinal) {
            this(id, name, base, ring, level, theme, col, row, links, start, monsters, boss, lore, isFinal, MapLayout.Style.HALLS);
        }

        /** 2026-10-18：在星圖上有格子（事件巔峰沒有 atlas，col／row 是 -1，星圖與連線都略過）。 */
        public boolean onAtlas() {
            return col >= 0 && row >= 0;
        }
    }

    /** 最多幾階。 */
    public static final int MAX_TIERS = 30;
    /** 地圖事件（神龕、寶箱、裂隙、流寇）掉專屬傳奇的預設機率（2026-10 從 2% 調成 1%）。 */
    public static final double DEFAULT_UNIQUE_CHANCE = 0.01;
    /** 地圖 Boss 掉共通傳奇（擲一次、中了抽一件；跟賭博一起校準）的預設機率；終局另外算。 */
    public static final double DEFAULT_BOSS_UNIQUE_CHANCE = 0.05;
    /** 具名 Boss 掉自己那件的預設機率（2026-10-16：1%）。 */
    public static final double DEFAULT_NAMED_UNIQUE_CHANCE = 0.01;
    /** 追逐級傳奇：所有地圖 Boss（含終局、極境）每次擊殺的固定機率（0.034%，約 3000 隻一件）。 */
    public static final double DEFAULT_CHASE_CHANCE = 0.00034;
    /** 追逐級傳奇：一般怪物（不是 Boss）每隻、以及僥倖石每次使用的機率（2026-10：0.0001%，約 100 萬隻一件）。 */
    public static final double DEFAULT_CHASE_MOB_CHANCE = 0.000001;
    private double chaseMobChance = DEFAULT_CHASE_MOB_CHANCE;
    /** 天穹碎片地圖（fragment_maps）的 Boss 掉碎片的機率（2026-10-24，maps.yml 的 drops.fragment_chance；以前是必掉）。 */
    private double fragmentChance = 1;
    /** 映星鏡（2026-10）：地圖 Boss 每次擊殺、一般怪物每隻的機率（以前跟追逐級傳奇一樣；改成追逐級的十分之一）。 */
    public static final double DEFAULT_MIRROR_CHANCE = 0.000034;
    public static final double DEFAULT_MIRROR_MOB_CHANCE = 0.0000001;
    private double mirrorChance = DEFAULT_MIRROR_CHANCE;
    /**
     * 共鳴石（2026-10-18）：比追逐級傳奇難——地圖 Boss 每次擊殺 0.00034/1.5、一般怪物（等級 75 以上）每隻 1/15,000,000（擁有者定的「約 1500 萬隻一顆」）。
     * 每個有份的人各擲一次，跟追逐級一樣不吃任何加成（maps.yml 的 drops.resonance_chance、drops.resonance_mob_chance）。
     */
    public static final double DEFAULT_RESONANCE_CHANCE = 0.000068; // 2026-10-18：追逐級 ÷ 5
    public static final double DEFAULT_RESONANCE_MOB_CHANCE = 0.0000002; // 2026-10-18：約 500 萬隻一顆（追逐級 ÷ 5）
    /** 共鳴石：怪物等級不到這個不掉（跟追逐級傳奇的池子一樣 75 起）。 */
    public static final int RESONANCE_MIN_LEVEL = 75;
    private double resonanceChance = DEFAULT_RESONANCE_CHANCE;
    private double resonanceMobChance = DEFAULT_RESONANCE_MOB_CHANCE;

    /** 2026-10-18：共鳴石：地圖 Boss（一般、具名、終局、極境）每次擊殺、每個有份的人的機率（maps.yml 的 drops.resonance_chance）。 */
    public double resonanceChance() {
        return resonanceChance;
    }

    /** 2026-10-18：共鳴石：一般怪物（等級 75 以上）每隻、每個有份的人的機率（maps.yml 的 drops.resonance_mob_chance）。 */
    public double resonanceMobChance() {
        return resonanceMobChance;
    }

    /** 映星鏡：地圖 Boss（一般、具名、終局、極境）每次擊殺、每個有份的人的機率（maps.yml 的 drops.mirror_chance）。 */
    public double mirrorChance() {
        return mirrorChance;
    }

    /** 追逐級傳奇：一般怪物每隻的掉落機率＝僥倖石每次賭到的機率（maps.yml 的 drops.chase_mob_chance）。 */
    public double chaseMobChance() {
        return chaseMobChance;
    }

    public double fragmentChance() {
        return fragmentChance;
    }
    /** 終局的怪物等級比選的那一階高幾級（maps.yml 的 final.level_bonus）。 */
    public static final int DEFAULT_FINAL_LEVEL_BONUS = 3;
    /** 終局 Boss 的生命照階級再乘：這一階（含）以下 ×1（maps.yml 的 final.tier_life.from）。 */
    public static final int DEFAULT_FINAL_TIER_LIFE_FROM = 5;
    /** 終局 Boss 的生命照階級再乘：最高階的倍率（maps.yml 的 final.tier_life.max；中間每階等量增加）。 */
    public static final double DEFAULT_FINAL_TIER_LIFE_MAX = 2.5;

    private Settings settings = defaultSettings();
    private Drops drops = defaultDrops();
    /** 讀設定時發現的舊寫法（{@link #validate} 一起回報）。 */
    private final List<String> notes = new ArrayList<>();
    private FirstKill firstKill = new FirstKill(0, 1, 2);
    /** 一般終局 Boss 的每秒傷害上限（2026-10，maps.yml 的 final_dps；極境、巔峰寫在自己的區塊）。 */
    private double finalDpsCap;
    private List<DpsTier> finalDpsOver = List.of();
    /** 金庫之主、淵鯨的每秒傷害上限（2026-10-21，maps.yml 的 heist_dps、whale_dps；沒寫照 final_dps）。開天者、南瓜王、潮汐統帥不遞減。 */
    private double heistDpsCap;
    private List<DpsTier> heistDpsOver = List.of();
    private double whaleDpsCap;
    private List<DpsTier> whaleDpsOver = List.of();
    /** 一般終局每一階的生命倍率（2026-10-21，final.tier_life_table；null = 照 tier_life）。 */
    private double[] finalTierLifeTable;
    /**
     * 2026-10-27 照站長：極境、金庫之主、各巔峰的生命再乘（maps.yml 的 boss_life；照「單人 3 分半打完要技能面板每秒多少」對準
     * 極境 50 萬、巔峰 100 萬）；這次之前沒有（只照 final.tier_life 的最高階，現在 ×1）。
     */
    private double uberLifeScale = 1.0;
    private double heistLifeScale = 1.0;
    private final Map<String, Double> pinnacleLifeScale = new LinkedHashMap<>();
    private final Map<String, MapDef> maps = new LinkedHashMap<>();
    private MapDef finalMap;
    private int finalLevelBonus = DEFAULT_FINAL_LEVEL_BONUS;
    private int finalTierLifeFrom = DEFAULT_FINAL_TIER_LIFE_FROM;
    private double finalTierLifeMax = DEFAULT_FINAL_TIER_LIFE_MAX;
    /** 一般地圖 Boss 的生命照階級再乘（maps.yml 的 settings.boss_tier_life；2026-10）：這一階（含）以下 ×1、最高階 ×max。 */
    private int bossTierLifeFrom = 16;
    private double bossTierLifeMax = 1.0;
    /** 地圖裡一般怪物（不是 Boss）的生命、傷害照階級再乘（maps.yml 的 settings.monster_tier；2026-10-21）：這一階（含）以下 ×1、最高階 ×life／×damage。 */
    private int monsterTierFrom = 16;
    private double monsterTierLifeMax = 1.0;
    private double monsterTierDamageMax = 1.0;
    /** 護盾結晶吃幾成的階級倍率（maps.yml 的 settings.shield_tier_share；2026-10）。 */
    public static final double DEFAULT_SHIELD_TIER_SHARE = 0.5;
    private double shieldTierShare = DEFAULT_SHIELD_TIER_SHARE;
    /** 其他終局 Boss（maps.yml 的 finals），依設定檔順序。 */
    private final Map<String, MapDef> extraFinals = new LinkedHashMap<>();
    /** 每個終局（含主要的那個）的額外設定。 */
    private final Map<String, FinalInfo> finalInfos = new LinkedHashMap<>();

    /**
     * 一個終局的額外設定。
     *
     * @param requires   要先打贏哪些終局才能開（代號；空 = 完成最外圈就能開）
     * @param points     首殺的天賦點
     * @param levelBonus 怪物等級比選的那一階高幾級
     */
    public record FinalInfo(String id, List<String> requires, int points, int levelBonus, Uber uber, Pinnacle pinnacle) {
        public FinalInfo {
            requires = List.copyOf(requires);
        }

        public FinalInfo(final String id, final List<String> requires, final int points, final int levelBonus) {
            this(id, requires, points, levelBonus, null, null);
        }

        public FinalInfo(final String id, final List<String> requires, final int points, final int levelBonus, final Uber uber) {
            this(id, requires, points, levelBonus, uber, null);
        }

        /**
         * 2026-10-18：事件巔峰——用一張事件入場券（pinnacle.ticket）開的巔峰，不在星圖上（沒有 atlas）、不算星圖天賦的終局點數、
         * 在地圖裝置的「事件巔峰」按鈕裡開。
         */
        public boolean isEventPinnacle() {
            return pinnacle != null && pinnacle.ticket() != null;
        }
    }

    /**
     * 巔峰（2026-10；maps.yml 的 finals 底下某個終局寫 {@code pinnacle:}）：終局之上的最終 Boss。不用天穹碎片、不選階級——
     * 要集齊 {@code fragments} 列的每一種碎片各一個（各由某個終局 Boss 以機率掉）才能在地圖裝置打開，固定開在最高階
     * ＋終局的 level_bonus＋這裡的 {@code levelBonus} 級；跟極境一樣只有 Boss 房、沒有路上的怪與地圖事件，每個人只能走進去一次。
     * Boss 的生命、傷害照這個終局自己的 boss 寫（跟其他終局一樣吃 tier_life 的最高階倍率），
     * 這張圖所有怪物的招式冷卻乘 {@code cooldown}。
     *
     * @param fragments  要集齊的碎片（每一種各 1 個）
     * @param levelBonus 比終局的最高階再高幾級
     * @param cooldown   這張圖怪物的招式冷卻倍率（0.65 = 縮短 35%）
     * @param monster    這張圖所有怪物額外的屬性
     * @param player     玩家在這張圖裡額外的屬性
     * @param drops      Boss 的掉落（寫法同極境的 drops：maps、unique_chance、exclusive_chance、extra）
     * @param ticket     2026-10-18：事件巔峰——用這一種通貨（入場券，currencies.yml 的代號）1 張開，不用碎片（fragments 是空的）；
     *                   一般的巔峰（集碎片的）是 null
     */
    public record Pinnacle(List<FragmentSource> fragments, int levelBonus, double cooldown, List<AffixMod> monster, List<AffixMod> player,
                           UberDrops drops, int attempts, double dpsCap, List<DpsTier> dpsOver, String ticket) {
        public Pinnacle {
            dpsOver = List.copyOf(dpsOver);
            fragments = List.copyOf(fragments);
            monster = List.copyOf(monster);
            player = List.copyOf(player);
        }

        /** 2026-10-18：舊的建構式（集碎片的巔峰）。 */
        public Pinnacle(final List<FragmentSource> fragments, final int levelBonus, final double cooldown, final List<AffixMod> monster,
                        final List<AffixMod> player, final UberDrops drops, final int attempts, final double dpsCap, final List<DpsTier> dpsOver) {
            this(fragments, levelBonus, cooldown, monster, player, drops, attempts, dpsCap, dpsOver, null);
        }

        /** 2026-10-18：開門要用掉的通貨代號（各 1 個）：事件巔峰是那一張入場券，一般巔峰是每一種碎片。 */
        public List<String> costs() {
            if (ticket != null) {
                return List.of(ticket);
            }
            final List<String> list = new ArrayList<>();
            for (final FragmentSource fragment : fragments) {
                list.add(fragment.currency());
            }
            return list;
        }
    }

    /**
     * 巔峰的一種碎片：{@code from} 這個終局的 Boss（一般版本，開到 {@code minTier} 階以上）每次倒下、每個有份的人各擲一次
     * {@code chance} 的機率掉一個 {@code currency}（currencies.yml 的代號）。
     * 2026-10：那個終局的極境版本也掉，機率 {@code uberChance}（maps.yml 的 uber_chance；0 = 不掉）。
     */
    public record FragmentSource(String currency, String from, double chance, int minTier, double uberChance) {
    }

    /**
     * 極境（Uber）版本（maps.yml 的終局底下的 {@code uber:}）：固定開在最高階＋{@code levelBonus} 級，Boss 的生命、傷害再乘，
     * 這張圖所有怪物的招式冷卻乘 {@code cooldown}，多極境專屬的招式、階段（護盾）、狂暴、場地危害；每個人只能進一次（死了就出局）。
     * 極境的地圖是最小的版面，沒有路上的怪、沒有地圖事件，只有 Boss。
     * 開門要 {@code keyCost} 個 {@code key}（通貨，一般終局 Boss 會掉），而且要先打贏過這個終局。
     *
     * @param name       極境 Boss 的名字（null = 原本的名字加「・極境」）
     * @param life       生命再乘幾倍
     * @param damage     傷害再乘幾倍
     * @param cooldown   這張圖怪物的招式冷卻倍率（0.75 = 縮短 25%）
     * @param levelBonus 比終局的最高階再高幾級
     * @param skills     額外的招式（加在原本的後面，一樣可以寫 phase）
     * @param phases     階段（寫了就整組換掉；沒寫照原本的）
     * @param enrage     狂暴（寫了就換掉）
     * @param hazards    額外的場地危害
     * @param mods       額外的怪物詞綴
     * @param intro      出場台詞（null = 原本的）
     * @param monster    這張圖所有怪物額外的屬性（不用寫 monster:）
     * @param player     玩家在這張圖裡額外的屬性（不用寫 player:）
     * @param key        開門要的通貨代號
     * @param keyCost    要幾個
     * @param keyChance  一般版本的這個終局 Boss 掉一個 key 的機率（每個有份的人各擲一次）
     * @param keyMinTier 一般版本要開到第幾階以上才會掉 key
     * @param drops      極境的掉落
     */
    public record Uber(String name, double life, double damage, double cooldown, int levelBonus, List<BossSkillUse> skills,
                       List<Phase> phases, Enrage enrage, List<BossHazard> hazards, List<String> mods, String intro,
                       List<AffixMod> monster, List<AffixMod> player, String key, int keyCost, double keyChance, int keyMinTier,
                       UberDrops drops, double dpsCap, List<DpsTier> dpsOver) {
        public Uber {
            dpsOver = List.copyOf(dpsOver);
            skills = List.copyOf(skills);
            phases = List.copyOf(phases);
            hazards = List.copyOf(hazards);
            mods = List.copyOf(mods);
            monster = List.copyOf(monster);
            player = List.copyOf(player);
        }

        /** 極境 Boss 的定義：原本的定義套上極境的倍率、多的招式與危害、換掉的階段與狂暴。 */
        public Boss merge(final Boss base) {
            final List<BossSkillUse> mergedSkills = new ArrayList<>(base.skills());
            mergedSkills.addAll(skills);
            final List<BossHazard> mergedHazards = new ArrayList<>(base.hazards());
            mergedHazards.addAll(hazards);
            final List<String> mergedMods = new ArrayList<>(base.mods());
            for (final String mod : mods) {
                if (!mergedMods.contains(mod)) {
                    mergedMods.add(mod);
                }
            }
            return new Boss(name != null ? name : base.name() + "・極境", base.type(), base.mythic(), true, base.life() * life,
                    base.damage() * damage, base.scale() * 1.1, mergedMods, intro != null ? intro : base.intro(), base.defeat(),
                    phases.isEmpty() ? base.phases() : phases, mergedSkills, enrage != null ? enrage : base.enrage(), mergedHazards);
        }
    }

    /**
     * 極境的掉落（每個有份的人各擲一次）。
     *
     * @param maps            掉幾張地圖（最高階）
     * @param uniqueChance    掉這個終局自己那件傳奇的機率
     * @param exclusiveChance 掉極境專屬傳奇（uniques.yml 的 sources 寫 uber_終局代號；沒有的話用 uber）的機率
     * @param extra           額外的掉落（極境專屬的通貨、輔助寶石…）
     * @param commonChance    掉共通傳奇（map_boss 池）的機率（2026-10）；負的 = 沒寫，照 drops.final_common_chance
     */
    public record UberDrops(int maps, double uniqueChance, double exclusiveChance, List<ExtraDrop> extra, double commonChance) {
        public UberDrops {
            extra = List.copyOf(extra);
        }

        public UberDrops(final int maps, final double uniqueChance, final double exclusiveChance, final List<ExtraDrop> extra) {
            this(maps, uniqueChance, exclusiveChance, extra, -1);
        }

        /** 共通傳奇的機率：有寫 common_chance 用它，沒寫用 fallback（drops.final_common_chance）。 */
        public double commonChance(final double fallback) {
            return commonChance < 0 ? fallback : commonChance;
        }
    }

    /** 舊的設定檔在極境的掉落寫了 chase_chance：不再使用（追逐級跟一般 Boss 一樣照 drops.chase_chance）。 */
    private void noteUberChase(final YamlNode finalNode) {
        if (finalNode.has("uber") && finalNode.node("uber").node("drops").has("chase_chance")) {
            notes.add("maps.yml：" + finalNode.path() + ".uber.drops.chase_chance 不再使用——追逐級傳奇照 drops.chase_chance，跟一般 Boss 一樣");
        }
    }

    /** 巔峰（2026-10）：見 {@link Pinnacle}。 */
    static Pinnacle parsePinnacle(final YamlNode node) {
        // 2026-10-18：事件巔峰寫 ticket:（一張入場券開），就不寫 fragments
        final String ticket = node.has("ticket") ? node.requireString("ticket").trim() : null;
        if (ticket != null && ticket.isEmpty()) {
            throw new IllegalArgumentException(node.path() + ".ticket：要寫入場券的通貨代號（currencies.yml）");
        }
        if (ticket != null && node.has("fragments")) {
            throw new IllegalArgumentException(node.path() + "：寫了 ticket 就不能再寫 fragments（事件巔峰只用一張入場券開）");
        }
        final List<FragmentSource> fragments = new ArrayList<>();
        for (final YamlNode item : node.list("fragments")) {
            fragments.add(new FragmentSource(item.requireString("currency").trim(), item.requireString("from").trim(),
                    clamp01(item.decimal("chance", 0.1)), Math.max(1, item.integer("min_tier", 1)), clamp01(item.decimal("uber_chance", 0))));
        }
        if (fragments.isEmpty() && ticket == null) {
            throw new IllegalArgumentException(node.path() + ".fragments：至少要寫一種碎片（{ currency, from, chance, min_tier }），或 ticket: 入場券代號");
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (final FragmentSource fragment : fragments) {
            if (!seen.add(fragment.currency())) {
                throw new IllegalArgumentException(node.path() + ".fragments：碎片 " + fragment.currency() + " 寫了兩次");
            }
        }
        final List<AffixMod> monster = new ArrayList<>();
        for (final YamlNode stat : node.list("monster")) {
            monster.add(AffixMod.parse(stat));
        }
        final List<AffixMod> player = new ArrayList<>();
        for (final YamlNode stat : node.list("player")) {
            player.add(AffixMod.parse(stat));
        }
        final YamlNode drops = node.node("drops");
        final List<ExtraDrop> extra = new ArrayList<>();
        for (final YamlNode item : drops.list("extra")) {
            final String kind = item.has("support") ? "support" : item.has("skill") ? "skill" : "currency";
            final String id = item.requireString(kind).trim();
            extra.add(new ExtraDrop(kind, id, Math.max(1, item.integer("amount", 1)), clamp01(item.decimal("chance", 1))));
        }
        final double cooldown = node.decimal("cooldown", 0.65);
        if (cooldown < 0.25 || cooldown > 1.5) {
            throw new IllegalArgumentException(node.path() + ".cooldown：要在 0.25 到 1.5 之間（招式冷卻的倍率）");
        }
        final double dpsCap = Math.max(0, node.decimal("dps_cap", 0));
        final List<DpsTier> dpsOver = parseDpsOver(node, dpsCap);
        return new Pinnacle(fragments, Math.max(0, Math.min(30, node.integer("level_bonus", 5))), cooldown, monster, player,
                new UberDrops(Math.max(0, drops.integer("maps", 6)), clamp01(drops.decimal("unique_chance", 0.2)),
                        clamp01(drops.decimal("exclusive_chance", 0.1)), extra, commonChance(drops)),
                Math.max(1, Math.min(5, node.integer("attempts", 2))), dpsCap, dpsOver, ticket);
    }

    /** 巔峰、極境超過每秒上限的那一段（2026-10）：每秒傷害 from 以上的部分只算 rate（0.3 = 30%）。 */
    public record DpsTier(double from, double rate) {
    }

    /** dps_over：[{ from, rate }]，由小排到大、第一段從 dps_cap 開始。 */
    static List<DpsTier> parseDpsOver(final YamlNode node, final double dpsCap) {
        final List<DpsTier> dpsOver = new ArrayList<>();
        double previousFrom = dpsCap;
        for (final YamlNode tier : node.list("dps_over")) {
            final double from = tier.decimal("from", 0);
            final double rate = tier.decimal("rate", 0);
            if (from < previousFrom || (!dpsOver.isEmpty() && from == previousFrom)) {
                throw new IllegalArgumentException(tier.path() + ".from：要從 dps_cap 開始、由小排到大");
            }
            if (rate < 0 || rate > 1) {
                throw new IllegalArgumentException(tier.path() + ".rate：要在 0 到 1 之間（超過的部分算幾成）");
            }
            dpsOver.add(new DpsTier(from, rate));
            previousFrom = from;
        }
        return dpsOver;
    }

    /** 極境專屬傳奇的來源代號（uniques.yml 的 sources）：uber_終局代號。 */
    public static String uberSource(final String finalId) {
        return "uber_" + finalId;
    }

    /**
     * 一項額外掉落：currency: 通貨代號（currencies.yml）、support: 輔助寶石代號（supports.yml；掉一顆切好的，通常是專屬的），
     * 或 skill: 技能寶石代號（skills.yml；掉一顆 20 級的，通常是專屬的）。
     *
     * @param kind   currency、support 或 skill
     * @param id     代號
     * @param amount 幾個
     * @param chance 機率（0–1）
     */
    public record ExtraDrop(String kind, String id, int amount, double chance) {
    }

    public void load(final Path dataFolder) throws IOException {
        // 2026-10-18：先讀到新的一份，成功才換上（以前讀到一半出錯，地圖全部消失、設定回預設）；讀的途中改到的全域值失敗時還原
        final double oldChase = tw.exilecore.item.ItemGenerator.chaseChance();
        final double oldMirror = tw.exilecore.item.ItemGenerator.mirrorChance();
        final MapStats.Tiers oldTiers = MapStats.tiers();
        final MapConfig fresh = new MapConfig();
        try {
            fresh.parse(dataFolder);
        } catch (final IOException | RuntimeException e) {
            tw.exilecore.item.ItemGenerator.setChaseChance(oldChase);
            tw.exilecore.item.ItemGenerator.setMirrorChance(oldMirror);
            MapStats.setTiers(oldTiers);
            throw e;
        }
        chaseMobChance = fresh.chaseMobChance;
        fragmentChance = fresh.fragmentChance;
        mirrorChance = fresh.mirrorChance;
        resonanceChance = fresh.resonanceChance; // 2026-10-18
        resonanceMobChance = fresh.resonanceMobChance;
        settings = fresh.settings;
        drops = fresh.drops;
        notes.clear();
        notes.addAll(fresh.notes);
        firstKill = fresh.firstKill;
        finalDpsCap = fresh.finalDpsCap;
        finalDpsOver = fresh.finalDpsOver;
        heistDpsCap = fresh.heistDpsCap;
        heistDpsOver = fresh.heistDpsOver;
        whaleDpsCap = fresh.whaleDpsCap;
        whaleDpsOver = fresh.whaleDpsOver;
        finalTierLifeTable = fresh.finalTierLifeTable;
        uberLifeScale = fresh.uberLifeScale; // 2026-10-27
        heistLifeScale = fresh.heistLifeScale;
        pinnacleLifeScale.clear();
        pinnacleLifeScale.putAll(fresh.pinnacleLifeScale);
        maps.clear();
        maps.putAll(fresh.maps);
        finalMap = fresh.finalMap;
        finalLevelBonus = fresh.finalLevelBonus;
        finalTierLifeFrom = fresh.finalTierLifeFrom;
        finalTierLifeMax = fresh.finalTierLifeMax;
        bossTierLifeFrom = fresh.bossTierLifeFrom;
        bossTierLifeMax = fresh.bossTierLifeMax;
        monsterTierFrom = fresh.monsterTierFrom;
        monsterTierLifeMax = fresh.monsterTierLifeMax;
        monsterTierDamageMax = fresh.monsterTierDamageMax;
        shieldTierShare = fresh.shieldTierShare;
        extraFinals.clear();
        extraFinals.putAll(fresh.extraFinals);
        finalInfos.clear();
        finalInfos.putAll(fresh.finalInfos);
    }

    private void parse(final Path dataFolder) throws IOException {
        maps.clear();
        finalMap = null;
        finalLevelBonus = DEFAULT_FINAL_LEVEL_BONUS;
        finalTierLifeFrom = DEFAULT_FINAL_TIER_LIFE_FROM;
        finalTierLifeMax = DEFAULT_FINAL_TIER_LIFE_MAX;
        shieldTierShare = DEFAULT_SHIELD_TIER_SHARE;
        extraFinals.clear();
        finalInfos.clear();
        notes.clear();
        settings = defaultSettings();
        drops = defaultDrops();
        firstKill = new FirstKill(0, 1, 2);
        finalDpsCap = 0;
        finalDpsOver = List.of();
        heistDpsCap = 0;
        heistDpsOver = List.of();
        whaleDpsCap = 0;
        whaleDpsOver = List.of();
        finalTierLifeTable = null;
        uberLifeScale = 1.0;
        heistLifeScale = 1.0;
        pinnacleLifeScale.clear();
        final Path file = dataFolder.resolve("maps.yml");
        if (!Files.exists(file)) {
            MapStats.setTiers(settings.tiers());
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode s = root.node("settings");
        final YamlNode bossTierLife = s.node("boss_tier_life");
        bossTierLifeFrom = Math.max(1, bossTierLife.integer("from", 16));
        bossTierLifeMax = Math.max(1, Math.min(40, bossTierLife.decimal("max", 1.0))); // 2026-10-25：上限 10 → 40（現在設 20）
        final YamlNode monsterTier = s.node("monster_tier");
        monsterTierFrom = Math.max(1, monsterTier.integer("from", 16));
        monsterTierLifeMax = Math.max(1, Math.min(10, monsterTier.decimal("life", 1.0)));
        monsterTierDamageMax = Math.max(1, Math.min(5, monsterTier.decimal("damage", 1.0)));
        shieldTierShare = Math.max(0, Math.min(1, s.decimal("shield_tier_share", DEFAULT_SHIELD_TIER_SHARE)));
        final YamlNode packs = s.node("packs");
        settings = new Settings(parseTiers(s), Math.max(1, s.integer("instance_cap", 20)),
                Math.max(1, s.integer("portals", 6)), Math.max(1, s.integer("idle_minutes", 15)),
                Math.max(5, s.integer("empty_close_seconds", 60)), Math.max(8, s.decimal("pack_radius", 36)),
                Math.max(6, s.decimal("boss_radius", 22)),
                pack(packs.node("normal"), 64, 4, 6), pack(packs.node("magic"), 28, 3, 5), pack(packs.node("rare"), 8, 2, 3));

        final YamlNode d = root.node("drops");
        if (d.has("final_chase_chance")) {
            notes.add("maps.yml：drops.final_chase_chance 不再使用——追逐級傳奇改成 drops.chase_chance，所有地圖 Boss（含終局、極境）一樣");
        }
        final Map<MonsterRarity, Double> chance = new EnumMap<>(MonsterRarity.class);
        final YamlNode chanceNode = d.node("map_chance");
        for (final MonsterRarity rarity : MonsterRarity.values()) {
            chance.put(rarity, Math.max(0, chanceNode.decimal(rarity.name().toLowerCase(Locale.ROOT), defaultDrops().mapChance().getOrDefault(rarity, 0.0))));
        }
        final Map<Rarity, Integer> rarityWeights = rarityWeights(d.node("rarity"), defaultDrops().rarity());
        // 沒寫 rarity_top（舊的設定檔）：每一階都照 rarity
        final Map<Rarity, Integer> rarityTop = d.has("rarity_top") ? rarityWeights(d.node("rarity_top"), defaultDrops().rarityTop()) : rarityWeights;
        drops = new Drops(chance, Math.max(0, d.integer("boss_maps", 1)), clamp01(d.decimal("tier_up_chance", 0.15)),
                Math.max(0, d.decimal("tier_up_per_completed", 0.01)), Math.max(1, d.integer("tier_cap_base", 4)),
                Math.max(0, d.integer("tier_cap_per_map", 1)), rarityWeights, rarityTop, Math.max(1, d.integer("frontier_weight", 2)),
                clamp01(d.decimal("boss_unique_chance", DEFAULT_BOSS_UNIQUE_CHANCE)),
                clamp01(d.decimal("named_unique_chance", DEFAULT_NAMED_UNIQUE_CHANCE)), d.string("fragment", "sky_fragment"),
                new LinkedHashSet<>(d.strings("fragment_maps")), Math.max(1, d.integer("fragment_cost", 2)),
                d.string("campaign_boss_zone", "mountain"), Math.max(0, d.integer("campaign_boss_maps", 1)),
                Math.max(0, d.integer("campaign_first_kill_maps", 3)), Math.max(0, d.integer("campaign_boss_daily_maps", 3)),
                clamp01(d.decimal("chase_chance", DEFAULT_CHASE_CHANCE)), clamp01(d.decimal("final_unique_chance", 0.01)),
                clamp01(d.decimal("final_common_chance", 0.01)), clamp01(d.decimal("final_support_chance", 0.01)));

        chaseMobChance = clamp01(d.decimal("chase_mob_chance", DEFAULT_CHASE_MOB_CHANCE));
        fragmentChance = clamp01(d.decimal("fragment_chance", 1));
        tw.exilecore.item.ItemGenerator.setChaseChance(chaseMobChance); // 僥倖石賭追逐級也照這個機率
        mirrorChance = clamp01(d.decimal("mirror_chance", DEFAULT_MIRROR_CHANCE));
        tw.exilecore.item.ItemGenerator.setMirrorChance(clamp01(d.decimal("mirror_mob_chance", DEFAULT_MIRROR_MOB_CHANCE)));
        resonanceChance = clamp01(d.decimal("resonance_chance", DEFAULT_RESONANCE_CHANCE)); // 2026-10-18：共鳴石
        resonanceMobChance = clamp01(d.decimal("resonance_mob_chance", DEFAULT_RESONANCE_MOB_CHANCE));

        final YamlNode finalDps = root.node("final_dps");
        finalDpsCap = Math.max(0, finalDps.decimal("dps_cap", 0));
        finalDpsOver = List.copyOf(parseDpsOver(finalDps, finalDpsCap));
        if (root.has("heist_dps")) {
            final YamlNode heistDps = root.node("heist_dps");
            heistDpsCap = Math.max(0, heistDps.decimal("dps_cap", 0));
            heistDpsOver = List.copyOf(parseDpsOver(heistDps, heistDpsCap));
        } else {
            heistDpsCap = finalDpsCap;
            heistDpsOver = finalDpsOver;
        }
        if (root.has("whale_dps")) {
            final YamlNode whaleDps = root.node("whale_dps");
            whaleDpsCap = Math.max(0, whaleDps.decimal("dps_cap", 0));
            whaleDpsOver = List.copyOf(parseDpsOver(whaleDps, whaleDpsCap));
        } else {
            whaleDpsCap = finalDpsCap;
            whaleDpsOver = finalDpsOver;
        }
        if (root.has("boss_life")) {
            // 2026-10-27 照站長：極境、金庫之主、各巔峰的生命再乘（0.1–20）
            final YamlNode bossLife = root.node("boss_life");
            uberLifeScale = lifeScale(bossLife, "uber");
            heistLifeScale = lifeScale(bossLife, "heist_finale");
            final YamlNode pinnacles = bossLife.node("pinnacle");
            for (final String id : pinnacles.keys()) {
                pinnacleLifeScale.put(id, lifeScale(pinnacles, id));
            }
        }
        final YamlNode f = root.node("first_kill");
        firstKill = new FirstKill(Math.max(0, f.integer("map_points", 0)), Math.max(0, f.integer("named_points", 1)),
                Math.max(0, f.integer("final_points", 2)));

        final YamlNode mapsNode = root.node("maps");
        for (final String id : mapsNode.keys()) {
            maps.put(id, parseMap(id, mapsNode.node(id), false));
        }
        if (root.has("final")) {
            final YamlNode node = root.node("final");
            finalMap = parseMap(node.string("id", "final"), node, true);
            finalLevelBonus = Math.max(0, Math.min(20, node.integer("level_bonus", DEFAULT_FINAL_LEVEL_BONUS)));
            final YamlNode tierLife = node.node("tier_life");
            finalTierLifeFrom = Math.max(1, tierLife.integer("from", DEFAULT_FINAL_TIER_LIFE_FROM));
            finalTierLifeMax = Math.max(1, Math.min(20, tierLife.decimal("max", DEFAULT_FINAL_TIER_LIFE_MAX)));
            finalTierLifeTable = parseTierTable(node, "tier_life_table");
            finalInfos.put(finalMap.id(), new FinalInfo(finalMap.id(), List.of(), firstKill.finalPoints(), finalLevelBonus,
                    node.has("uber") ? parseUber(node.node("uber")) : null));
            noteUberChase(node);
        }
        if (root.has("finals")) {
            final YamlNode finals = root.node("finals");
            for (final String id : finals.keys()) {
                final YamlNode node = finals.node(id);
                if (maps.containsKey(id) || finalMap != null && finalMap.id().equals(id)) {
                    throw new IllegalArgumentException(node.path() + "：代號跟別的地圖或終局重複");
                }
                // 2026-10-18：事件巔峰（pinnacle.ticket）不在星圖上，atlas 可以不寫
                final boolean eventPinnacle = node.has("pinnacle") && node.node("pinnacle").has("ticket");
                final MapDef map = parseMap(id, node, true, eventPinnacle);
                extraFinals.put(id, map);
                finalInfos.put(id, new FinalInfo(id, node.strings("requires"), Math.max(0, node.integer("points", firstKill.finalPoints())),
                        Math.max(0, Math.min(20, node.integer("level_bonus", finalLevelBonus))),
                        node.has("uber") ? parseUber(node.node("uber")) : null,
                        node.has("pinnacle") ? parsePinnacle(node.node("pinnacle")) : null));
                noteUberChase(node);
                if (node.has("pinnacle") && node.has("uber")) {
                    throw new IllegalArgumentException(node.path() + "：巔峰（pinnacle）不能再有極境版本（uber）");
                }
            }
            for (final FinalInfo info : finalInfos.values()) {
                for (final String required : info.requires()) {
                    if (!finalInfos.containsKey(required)) {
                        throw new IllegalArgumentException("maps.yml：終局 " + info.id() + " 的 requires 寫了不存在的終局 " + required);
                    }
                    if (required.equals(info.id())) {
                        throw new IllegalArgumentException("maps.yml：終局 " + info.id() + " 不能 requires 自己");
                    }
                }
            }
            // 巔峰（2026-10）：碎片要由存在的、不是巔峰自己的終局掉
            for (final FinalInfo info : finalInfos.values()) {
                if (info.pinnacle() == null) {
                    continue;
                }
                for (final FragmentSource fragment : info.pinnacle().fragments()) {
                    final FinalInfo source = finalInfos.get(fragment.from());
                    if (source == null) {
                        throw new IllegalArgumentException("maps.yml：巔峰 " + info.id() + " 的碎片 " + fragment.currency()
                                + " 寫了不存在的終局 " + fragment.from());
                    }
                    if (source.pinnacle() != null) {
                        throw new IllegalArgumentException("maps.yml：巔峰 " + info.id() + " 的碎片 " + fragment.currency()
                                + " 不能由另一個巔峰（" + fragment.from() + "）掉");
                    }
                }
            }
        }
        symmetrizeLinks();
        MapStats.setTiers(settings.tiers());
    }

    /**
     * settings.tiers：每一階的怪物等級（由低到高，不能變低）。舊的設定檔只有 level_base：T1–T16 = level_base + 1 … + 16。
     */
    private static MapStats.Tiers parseTiers(final YamlNode s) {
        final List<Integer> levels = new ArrayList<>();
        final Object raw = s.raw("tiers");
        if (raw instanceof List<?> list) {
            for (final Object value : list) {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException(s.path() + ".tiers：要寫成怪物等級的清單，例如 [61, 62, 63]");
                }
                levels.add(number.intValue());
            }
            if (levels.isEmpty() || levels.size() > MAX_TIERS) {
                throw new IllegalArgumentException(s.path() + ".tiers：要有 1 到 " + MAX_TIERS + " 階");
            }
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i) < 1 || levels.get(i) > 200 || i > 0 && levels.get(i) < levels.get(i - 1)) {
                    throw new IllegalArgumentException(s.path() + ".tiers：T" + (i + 1) + " 的怪物等級 " + levels.get(i)
                            + " 不對（1–200，而且不能比前一階低）");
                }
            }
        } else if (raw != null) {
            throw new IllegalArgumentException(s.path() + ".tiers：要寫成怪物等級的清單，例如 [61, 62, 63]");
        } else {
            final int base = Math.max(0, s.integer("level_base", 60));
            for (int tier = 1; tier <= MapStats.DEFAULT_TIERS.max(); tier++) {
                levels.add(base + tier);
            }
        }
        return new MapStats.Tiers(levels, Math.max(0, s.decimal("tier_quantity", MapStats.DEFAULT_TIERS.quantityPerTier())),
                Math.max(0, s.decimal("tier_rarity", MapStats.DEFAULT_TIERS.rarityPerTier())));
    }

    private static Map<Rarity, Integer> rarityWeights(final YamlNode node, final Map<Rarity, Integer> defaults) {
        final Map<Rarity, Integer> weights = new EnumMap<>(Rarity.class);
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
            weights.put(rarity, Math.max(0, node.integer(rarity.name().toLowerCase(Locale.ROOT), defaults.get(rarity))));
        }
        return weights;
    }

    private MapDef parseMap(final String id, final YamlNode node, final boolean isFinal) {
        return parseMap(id, node, isFinal, false);
    }

    /** @param atlasOptional 2026-10-18：事件巔峰可以不寫 atlas（不在星圖上；col／row 是 -1） */
    private MapDef parseMap(final String id, final YamlNode node, final boolean isFinal, final boolean atlasOptional) {
        final String theme = node.string("theme", "ruins").trim().toLowerCase(Locale.ROOT);
        if (!THEMES.contains(theme)) {
            throw new IllegalArgumentException(node.path() + ".theme：不認得的主題 " + theme + "（可以用 " + THEMES + "）");
        }
        final List<Integer> atlas = new ArrayList<>();
        final Object raw = node.raw("atlas");
        if (raw instanceof List<?> list) {
            for (final Object value : list) {
                if (value instanceof Number number) {
                    atlas.add(number.intValue());
                }
            }
        }
        if (atlasOptional && raw == null) {
            atlas.clear();
            atlas.add(-1);
            atlas.add(-1);
        } else if (atlas.size() != 2 || atlas.get(0) < 0 || atlas.get(0) > 8 || atlas.get(1) < 0 || atlas.get(1) > 4) {
            throw new IllegalArgumentException(node.path() + ".atlas：星圖位置要寫成 [欄 0–8, 列 0–4]");
        }
        final List<ZoneConfig.SpawnEntry> monsters = parseMonsters(node.node("monsters"));
        final Boss boss = parseBoss(node.node("boss"));
        final String base = isFinal ? null : node.requireString("base");
        final String layoutText = node.string("layout", "halls");
        final MapLayout.Style layout = MapLayout.Style.parse(layoutText);
        if (layout == null) {
            final List<String> styles = new ArrayList<>();
            for (final MapLayout.Style style : MapLayout.Style.values()) {
                styles.add(style.id());
            }
            throw new IllegalArgumentException(node.path() + ".layout：不認得的版面樣式 " + layoutText + "（可以用 " + String.join("、", styles) + "）");
        }
        return new MapDef(id, isFinal ? node.requireString("name") : node.string("name", null), base, isFinal ? FINAL_RING : 0, 0,
                theme, atlas.get(0), atlas.get(1), node.strings("links"), node.bool("start", false), monsters, boss,
                node.string("lore", ""), isFinal, layout);
    }

    /**
     * 怪物清單：原版生物代號（或 mythic:代號）→ 權重；權重 0 以下的不算。至少要有一種（試煉也用這個寫法）。
     */
    public static List<ZoneConfig.SpawnEntry> parseMonsters(final YamlNode node) {
        final List<ZoneConfig.SpawnEntry> monsters = new ArrayList<>();
        for (final String key : node.keys()) {
            final int weight = node.integer(key, 0);
            if (weight > 0) {
                monsters.add(new ZoneConfig.SpawnEntry(key.startsWith("mythic:") ? key : key.toUpperCase(Locale.ROOT), weight));
            }
        }
        if (monsters.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一種怪物");
        }
        return monsters;
    }

    /**
     * 地圖 Boss 的寫法（name、type、mythic、named、life、damage、scale、mods、intro、defeat、phases、skills、enrage、hazards；
     * 試煉也用這個寫法）。phases 的每一個可以多寫 shield（護盾）。
     */
    public static Boss parseBoss(final YamlNode node) {
        final List<Phase> phases = new ArrayList<>();
        for (final YamlNode phase : node.list("phases")) {
            final List<AffixMod> stats = new ArrayList<>();
            for (final YamlNode stat : phase.list("stats")) {
                stats.add(AffixMod.parse(stat));
            }
            final YamlNode summon = phase.node("summon");
            phases.add(new Phase(clamp01(phase.decimal("at", 0.5)), phase.string("name", "第二階段"), phase.string("message", null),
                    summon.has("type") ? summon.requireString("type").trim().toUpperCase(Locale.ROOT) : null,
                    Math.max(0, summon.integer("count", 0)), stats, Math.max(-0.9, phase.decimal("speed", 0)),
                    phase.has("shield") ? parseShield(phase.node("shield")) : null));
        }
        phases.sort((a, b) -> Double.compare(b.at(), a.at()));
        return new Boss(node.requireString("name"), node.string("type", "ZOMBIE").trim().toUpperCase(Locale.ROOT),
                node.string("mythic", null), node.bool("named", false), Math.max(0.1, node.decimal("life", 6)),
                Math.max(0.1, node.decimal("damage", 1.4)), Math.max(0.2, node.decimal("scale", 1.5)), node.strings("mods"),
                node.string("intro", null), node.string("defeat", null), phases, BossSkillUse.parseList(node, "skills"),
                node.has("enrage") ? parseEnrage(node.node("enrage")) : null, BossHazard.parseList(node, "hazards"));
    }

    /** 極境（Uber）：見 {@link Uber}。 */
    static Uber parseUber(final YamlNode node) {
        final List<Phase> phases = new ArrayList<>();
        if (node.has("phases")) {
            // 借用 Boss 的寫法讀階段（一樣可以寫 shield）
            final YamlNode wrapper = YamlNode.of(node.path(), java.util.Map.of("name", "x", "phases", node.raw("phases")));
            phases.addAll(parseBoss(wrapper).phases());
        }
        final List<AffixMod> monster = new ArrayList<>();
        for (final YamlNode stat : node.list("monster")) {
            monster.add(AffixMod.parse(stat));
        }
        final List<AffixMod> player = new ArrayList<>();
        for (final YamlNode stat : node.list("player")) {
            player.add(AffixMod.parse(stat));
        }
        final YamlNode drops = node.node("drops");
        final List<ExtraDrop> extra = new ArrayList<>();
        for (final YamlNode item : drops.list("extra")) {
            final String kind = item.has("support") ? "support" : item.has("skill") ? "skill" : "currency";
            final String id = item.requireString(kind).trim();
            extra.add(new ExtraDrop(kind, id, Math.max(1, item.integer("amount", 1)), clamp01(item.decimal("chance", 1))));
        }
        final double cooldown = node.decimal("cooldown", 0.75);
        if (cooldown < 0.25 || cooldown > 1.5) {
            throw new IllegalArgumentException(node.path() + ".cooldown：要在 0.25 到 1.5 之間（招式冷卻的倍率）");
        }
        final double dpsCap = Math.max(0, node.decimal("dps_cap", 0));
        return new Uber(node.string("name", null), Math.max(1, Math.min(500, node.decimal("life", 4))) /* 2026-10-21：50 → 500（極境照每秒 300 萬調） */,
                Math.max(1, Math.min(10, node.decimal("damage", 1.5))), cooldown, Math.max(0, Math.min(30, node.integer("level_bonus", 4))),
                BossSkillUse.parseList(node, "skills"), phases, node.has("enrage") ? parseEnrage(node.node("enrage")) : null,
                BossHazard.parseList(node, "hazards"), node.strings("mods"), node.string("intro", null), monster, player,
                node.string("key", "abyss_core").trim(), Math.max(1, node.integer("key_cost", 1)),
                clamp01(node.decimal("key_chance", 0.1)), Math.max(1, node.integer("key_min_tier", 12)),
                new UberDrops(Math.max(0, drops.integer("maps", 4)), clamp01(drops.decimal("unique_chance", 0.2)),
                        clamp01(drops.decimal("exclusive_chance", 0.05)), extra, commonChance(drops)),
                dpsCap, parseDpsOver(node, dpsCap));
    }

    /** 護盾：{ count, type, name, life, duration, heal }。 */
    static Shield parseShield(final YamlNode node) {
        final int count = node.integer("count", 3);
        if (count < 1 || count > 8) {
            throw new IllegalArgumentException(node.path() + ".count：結晶要 1 到 8 顆");
        }
        final double duration = node.decimal("duration", 25);
        if (duration < 5 || duration > 120) {
            throw new IllegalArgumentException(node.path() + ".duration：要在 5 到 120 秒之間");
        }
        return new Shield(count, node.string("type", "BLAZE").trim().toUpperCase(Locale.ROOT), node.string("name", "星骸結晶"),
                Math.max(0.2, Math.min(50, node.decimal("life", 4))), duration, Math.max(0, Math.min(100, node.decimal("heal", 25))));
    }

    /** 狂暴：{ after, damage, speed, message }。 */
    static Enrage parseEnrage(final YamlNode node) {
        final double after = node.decimal("after", 300);
        if (after < 30 || after > 3600) {
            throw new IllegalArgumentException(node.path() + ".after：要在 30 到 3600 秒之間");
        }
        return new Enrage(after, Math.max(0, Math.min(500, node.decimal("damage", 100))), Math.max(0, Math.min(2, node.decimal("speed", 0.3))),
                node.string("message", null));
    }

    /** 相連是雙向的：A 寫了 B，B 沒寫 A 也算。 */
    private void symmetrizeLinks() {
        final Map<String, Set<String>> links = new LinkedHashMap<>();
        for (final MapDef map : all()) {
            links.computeIfAbsent(map.id(), key -> new LinkedHashSet<>()).addAll(map.links());
            for (final String other : map.links()) {
                links.computeIfAbsent(other, key -> new LinkedHashSet<>()).add(map.id());
            }
        }
        for (final Map.Entry<String, MapDef> entry : new ArrayList<>(maps.entrySet())) {
            final MapDef map = entry.getValue();
            maps.put(entry.getKey(), withLinks(map, links.getOrDefault(map.id(), Set.of())));
        }
        if (finalMap != null) {
            finalMap = withLinks(finalMap, links.getOrDefault(finalMap.id(), Set.of()));
        }
        for (final Map.Entry<String, MapDef> entry : new ArrayList<>(extraFinals.entrySet())) {
            extraFinals.put(entry.getKey(), withLinks(entry.getValue(), links.getOrDefault(entry.getKey(), Set.of())));
        }
    }

    private static MapDef withLinks(final MapDef map, final Set<String> links) {
        return new MapDef(map.id(), map.name(), map.base(), map.ring(), map.level(), map.theme(), map.col(), map.row(),
                new ArrayList<>(links), map.start(), map.monsters(), map.boss(), map.lore(), map.isFinal(), map.layout());
    }

    /**
     * 對照物品資料：基底存在而且是地圖，補上名稱、星圖的圈數；相連的地圖要存在；星圖位置不重複。
     * 回傳問題清單（空的 = 沒問題）。有問題的地圖會被拿掉。
     */
    public List<String> validate(final ItemRepository items) {
        final List<String> problems = new ArrayList<>(notes);
        final Set<String> positions = new LinkedHashSet<>();
        for (final Map.Entry<String, MapDef> entry : new ArrayList<>(maps.entrySet())) {
            final MapDef map = entry.getValue();
            final ItemBase base = items.base(map.base());
            if (base == null || !base.isMap()) {
                problems.add("maps.yml：" + map.id() + " 的基底 " + map.base() + (base == null ? " 不存在" : " 不是地圖（slot 要是 MAP）"));
                maps.remove(entry.getKey());
                continue;
            }
            final int ring = Math.max(1, base.mapTier());
            maps.put(entry.getKey(), new MapDef(map.id(), map.name() == null ? base.name() : map.name(), map.base(), ring,
                    0, map.theme(), map.col(), map.row(), map.links(), map.start(), map.monsters(),
                    map.boss(), map.lore(), false, map.layout()));
        }
        for (final MapDef map : all()) {
            if (map.onAtlas() && !positions.add(map.col() + "," + map.row())) { // 2026-10-18：事件巔峰不在星圖上
                problems.add("maps.yml：" + map.id() + " 的星圖位置 [" + map.col() + ", " + map.row() + "] 跟別張重複");
            }
            for (final String link : map.links()) {
                if (find(link) == null) {
                    problems.add("maps.yml：" + map.id() + " 連到不存在的地圖 " + link);
                }
            }
        }
        if (!maps.isEmpty() && maps.values().stream().noneMatch(MapDef::start)) {
            problems.add("maps.yml：沒有任何 start: true 的地圖，星圖永遠打不開");
        }
        return problems;
    }

    /**
     * 極境的地圖數值：終局的最高階、怪物等級再加 {@code levelBonus}（跟一般終局一樣先加終局本身的等級加成）；
     * 這張圖所有怪物的招式冷卻乘 {@code cooldown}（怪物的 skill.cooldown：0.75 = 增加 -25%）、加上 monster 的屬性；玩家加上 player 的屬性。
     */
    public MapStats uberStats(final String finalId, final Uber uber) {
        final MapStats base = MapStats.forTier(tiers(), tiers().max(), finalLevelBonus(finalId) + uber.levelBonus());
        final List<tw.exilecore.stats.Modifier> monster = new ArrayList<>(base.monsterModifiers());
        if (uber.cooldown() != 1.0) {
            monster.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.SKILL_COOLDOWN, tw.exilecore.stats.ModType.INCREASED,
                    uber.cooldown() - 1.0, Set.of(), "極境"));
        }
        for (final AffixMod mod : uber.monster()) {
            monster.add(mod.toModifier(Map.of(), "極境"));
        }
        final List<tw.exilecore.stats.Modifier> players = new ArrayList<>(base.playerModifiers());
        for (final AffixMod mod : uber.player()) {
            players.add(mod.toModifier(Map.of(), "極境"));
        }
        return new MapStats(base.tier(), base.monsterLevel(), base.quantity(), base.rarity(), base.packSize(), base.magicPacks(),
                base.rarePacks(), monster, base.bossModifiers(), players);
    }

    /**
     * 每個地圖 Boss（含極境）的招式問題：不存在的招式（打的時候會略過）、場地危害用了不能用的種類、
     * 招式或危害要等的階段比 Boss 的階段還多（永遠用不到）；極境：鑰匙要掉的階級比最高階還高。
     * 回傳說明（空的 = 沒問題），給載入時警告。
     */
    public List<String> bossProblems(final tw.exilecore.skill.BossSkillConfig skills) {
        final List<String> problems = new ArrayList<>();
        for (final MapDef map : all()) {
            bossProblems(problems, "maps.yml：" + map.id() + " 的 Boss", map.boss(), skills);
            final Uber uber = uber(map.id());
            if (uber == null) {
                continue;
            }
            final String where = "maps.yml：" + map.id() + " 的極境";
            // 極境多的招式與危害；階段看合併之後的（極境寫了 phases 會整組換掉，原本的招式也要等得到）
            unknownProblems(problems, where, uber.skills(), uber.hazards(), skills);
            phaseProblems(problems, where, uber.merge(map.boss()));
            if (uber.keyMinTier() > tiers().max()) {
                problems.add(where + "：key_min_tier " + uber.keyMinTier() + " 比最高階 T" + tiers().max() + " 還高（鑰匙永遠不會掉）");
            }
        }
        return problems;
    }

    /** 一隻 Boss 的招式組與場地危害有什麼問題（地圖、試煉共用）。 */
    public static void bossProblems(final List<String> problems, final String where, final Boss boss,
                                    final tw.exilecore.skill.BossSkillConfig skills) {
        unknownProblems(problems, where, boss.skills(), boss.hazards(), skills);
        phaseProblems(problems, where, boss);
    }

    private static void unknownProblems(final List<String> problems, final String where, final List<BossSkillUse> uses,
                                        final List<BossHazard> hazards, final tw.exilecore.skill.BossSkillConfig skills) {
        final List<String> unknown = skills.unknown(uses);
        if (!unknown.isEmpty()) {
            problems.add(where + "：boss_skills.yml 沒有這些招式 " + String.join("、", unknown) + "（會略過）");
        }
        for (final String problem : BossHazard.problems(hazards, skills)) {
            problems.add(where + " 的場地危害：" + problem + "（會略過）");
        }
        for (final String problem : skills.comboProblems(uses)) {
            problems.add(where + " 的招式：" + problem + "（會略過）");
        }
    }

    /** 招式、場地危害寫的 phase 比 Boss 的階段數還多：永遠用不到。 */
    private static void phaseProblems(final List<String> problems, final String where, final Boss boss) {
        final int phases = boss.phases().size();
        for (final BossSkillUse use : boss.skills()) {
            if (use.fromPhase() > phases) {
                problems.add(where + "：招式 " + use.id() + " 要等第 " + use.fromPhase() + " 個階段，但只有 " + phases + " 個階段（永遠用不到）");
            }
        }
        for (final BossHazard hazard : boss.hazards()) {
            if (hazard.fromPhase() > phases) {
                problems.add(where + "：場地危害 " + hazard.skill() + " 要等第 " + hazard.fromPhase() + " 個階段，但只有 " + phases
                        + " 個階段（永遠不會落下）");
            }
        }
    }

    public Settings settings() {
        return settings;
    }

    public Drops drops() {
        return drops;
    }

    /** 一般終局 Boss 的每秒傷害上限（0 = 不限）。 */
    public double finalDpsCap() {
        return finalDpsCap;
    }

    public List<DpsTier> finalDpsOver() {
        return finalDpsOver;
    }

    public FirstKill firstKill() {
        return firstKill;
    }

    /** 一般地圖（不含終局），依設定檔順序。 */
    public Collection<MapDef> maps() {
        return maps.values();
    }

    /** 終局；沒設定為 null。 */
    public MapDef finalMap() {
        return finalMap;
    }

    /** 一般地圖＋所有終局。 */
    public List<MapDef> all() {
        final List<MapDef> list = new ArrayList<>(maps.values());
        list.addAll(finals());
        return list;
    }

    /** 所有終局（主要的那個在前面，再來是 finals 的）；沒設定是空的。 */
    public List<MapDef> finals() {
        final List<MapDef> list = new ArrayList<>();
        if (finalMap != null) {
            list.add(finalMap);
        }
        list.addAll(extraFinals.values());
        return list;
    }

    /** 這個終局的額外設定（requires、首殺點數、等級加成）；不是終局回傳 null。 */
    /** 這個終局的極境版本（沒有是 null）。 */
    public Uber uber(final String id) {
        final FinalInfo info = finalInfos.get(id);
        return info == null ? null : info.uber();
    }

    /** 這個終局是不是巔峰（2026-10：集齊碎片才能開的最終 Boss）；是的話回傳設定，不是回傳 null。 */
    public Pinnacle pinnacle(final String id) {
        final FinalInfo info = finalInfos.get(id);
        return info == null ? null : info.pinnacle();
    }

    /** 所有的巔峰（依設定檔順序）。 */
    public List<MapDef> pinnacles() {
        final List<MapDef> list = new ArrayList<>();
        for (final MapDef map : finals()) {
            if (pinnacle(map.id()) != null) {
                list.add(map);
            }
        }
        return list;
    }

    /** 2026-10-18：這個終局是不是事件巔峰（用一張事件入場券開的巔峰；不在星圖上、不算終局點數）。 */
    public boolean isEventPinnacle(final String id) {
        final FinalInfo info = finalInfos.get(id);
        return info != null && info.isEventPinnacle();
    }

    /** 2026-10-18：所有的事件巔峰（依設定檔順序）。 */
    public List<MapDef> eventPinnacles() {
        final List<MapDef> list = new ArrayList<>();
        for (final MapDef map : pinnacles()) {
            if (isEventPinnacle(map.id())) {
                list.add(map);
            }
        }
        return list;
    }

    /** 2026-10-18：一般的巔峰（集碎片開的，像萬界之巔）：不含事件巔峰。 */
    public List<MapDef> fragmentPinnacles() {
        final List<MapDef> list = new ArrayList<>();
        for (final MapDef map : pinnacles()) {
            if (!isEventPinnacle(map.id())) {
                list.add(map);
            }
        }
        return list;
    }

    /** 一般的終局（用天穹碎片開、可以選階級的）：不含巔峰。 */
    public List<MapDef> regularFinals() {
        final List<MapDef> list = new ArrayList<>();
        for (final MapDef map : finals()) {
            if (pinnacle(map.id()) == null) {
                list.add(map);
            }
        }
        return list;
    }

    /**
     * 巔峰的地圖數值：終局的最高階、怪物等級再加終局本身與巔峰的等級加成；招式冷卻乘 cooldown；monster、player 的屬性。
     */
    public MapStats pinnacleStats(final String finalId, final Pinnacle pinnacle) {
        final MapStats base = MapStats.forTier(tiers(), tiers().max(), finalLevelBonus(finalId) + pinnacle.levelBonus());
        final List<tw.exilecore.stats.Modifier> monster = new ArrayList<>(base.monsterModifiers());
        if (pinnacle.cooldown() != 1.0) {
            monster.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.SKILL_COOLDOWN, tw.exilecore.stats.ModType.INCREASED,
                    pinnacle.cooldown() - 1.0, Set.of(), "巔峰"));
        }
        for (final AffixMod mod : pinnacle.monster()) {
            monster.add(mod.toModifier(Map.of(), "巔峰"));
        }
        final List<tw.exilecore.stats.Modifier> players = new ArrayList<>(base.playerModifiers());
        for (final AffixMod mod : pinnacle.player()) {
            players.add(mod.toModifier(Map.of(), "巔峰"));
        }
        return new MapStats(base.tier(), base.monsterLevel(), base.quantity(), base.rarity(), base.packSize(), base.magicPacks(),
                base.rarePacks(), monster, base.bossModifiers(), players);
    }

    public FinalInfo finalInfo(final String id) {
        return finalInfos.get(id);
    }

    /** 這個終局首殺的天賦點（不是終局回傳 first_kill.final_points）。 */
    public int finalPoints(final String id) {
        final FinalInfo info = finalInfos.get(id);
        return info == null ? firstKill.finalPoints() : info.points();
    }

    /**
     * 終局 Boss（含極境，極境開在最高階）的生命照開的階級再乘：{@code from} 階（含）以下 ×1，往上每階等量增加，
     * 到最高階是 {@code max} 倍（maps.yml 的 final.tier_life）。低階給剛進終局的人照原本的時間打，高階給練好的人。
     */
    public double finalLifeMultiplier(final int tier) {
        return finalLifeMultiplier(tier, finalTierLifeFrom, finalTierLifeMax, tiers().max());
    }

    /** final.tier_life_table：每一階一個倍率（1–1000），數量要跟 settings.tiers 一樣；沒寫回傳 null。 */
    private double[] parseTierTable(final YamlNode node, final String key) {
        if (!node.has(key)) {
            return null;
        }
        if (!(node.raw(key) instanceof List<?> list) || list.size() != tiers().max()) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要寫 " + tiers().max() + " 個數字（T1 … T" + tiers().max() + "）");
        }
        final double[] table = new double[list.size()];
        for (int i = 0; i < table.length; i++) {
            if (!(list.get(i) instanceof Number number) || number.doubleValue() < 1 || number.doubleValue() > 1000) {
                throw new IllegalArgumentException(node.path() + "." + key + " 第 " + (i + 1) + " 個：要是 1 到 1000 的數字");
            }
            table[i] = number.doubleValue();
        }
        return table;
    }

    /**
     * 一般終局 Boss（不是極境、巔峰、金庫之主）的生命照階級再乘幾倍（2026-10-21）：有 final.tier_life_table 照表，
     * 沒有照 tier_life（{@link #finalLifeMultiplier}）。
     */
    public double finalBossLifeMultiplier(final int tier) {
        final double[] table = finalTierLifeTable;
        if (table == null || table.length == 0) {
            return finalLifeMultiplier(tier);
        }
        return table[Math.max(1, Math.min(table.length, tier)) - 1];
    }

    /** boss_life 底下一個倍率（0.1–20；沒寫 = 1）。 */
    private static double lifeScale(final YamlNode node, final String key) {
        final double value = node.decimal(key, 1.0);
        if (value < 0.1 || value > 20) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要在 0.1 到 20 之間（Boss 生命再乘幾倍）");
        }
        return value;
    }

    /**
     * 極境 Boss 的生命再乘幾倍（開在最高階）：final.tier_life 的最高階倍率 × boss_life.uber。
     * 2026-10-27 照站長：極境建議每秒 50 萬（技能面板、單人 3 分半）；這次之前只有 final.tier_life（×1，52.7 萬）。
     */
    public double uberLifeMultiplier() {
        return finalLifeMultiplier(tiers().max()) * uberLifeScale;
    }

    /**
     * 這隻巔峰（含事件巔峰）的生命再乘幾倍：final.tier_life 的最高階倍率 × boss_life.pinnacle.代號（沒寫 = 1）。
     * 2026-10-27 照站長：巔峰建議每秒 100 萬；這次之前只有 final.tier_life（×1，106～119 萬）。
     */
    public double pinnacleLifeMultiplier(final String id) {
        return finalLifeMultiplier(tiers().max()) * pinnacleLifeScale.getOrDefault(id, 1.0);
    }

    /**
     * 金庫之主（劫盜終局）的生命再乘幾倍：照開的人打過的最高階的 final.tier_life × boss_life.heist_finale。
     * 2026-10-27 照站長：比照極境（極境 52.7 萬 → 50 萬，金庫之主跟著乘一樣的倍率）；這次之前只有 final.tier_life。
     */
    public double heistFinaleLifeMultiplier(final int tier) {
        return finalLifeMultiplier(tier) * heistLifeScale;
    }

    /** 金庫之主的每秒傷害上限（2026-10-21，heist_dps）。 */
    public double heistDpsCap() {
        return heistDpsCap;
    }

    public List<DpsTier> heistDpsOver() {
        return heistDpsOver;
    }

    /** 淵鯨的每秒傷害上限（2026-10-21，whale_dps）。 */
    public double whaleDpsCap() {
        return whaleDpsCap;
    }

    public List<DpsTier> whaleDpsOver() {
        return whaleDpsOver;
    }

    /** 一般地圖 Boss（不是終局）的生命照階級再乘幾倍（maps.yml 的 settings.boss_tier_life）。 */
    public double bossLifeMultiplier(final int tier) {
        return finalLifeMultiplier(tier, bossTierLifeFrom, bossTierLifeMax, tiers().max());
    }

    /** 地圖裡一般怪物（不是 Boss）的生命照階級再乘幾倍（maps.yml 的 settings.monster_tier.life；2026-10-21）。 */
    public double monsterLifeMultiplier(final int tier) {
        return finalLifeMultiplier(tier, monsterTierFrom, monsterTierLifeMax, tiers().max());
    }

    /** 地圖裡一般怪物（不是 Boss）的傷害照階級再乘幾倍（maps.yml 的 settings.monster_tier.damage；2026-10-21）。 */
    public double monsterDamageMultiplier(final int tier) {
        return finalLifeMultiplier(tier, monsterTierFrom, monsterTierDamageMax, tiers().max());
    }

    /**
     * 護盾結晶的生命照階級再乘幾倍（2026-10）：Boss 的階級倍率 {@code bossMultiplier} 多出來的部分，結晶只吃
     * settings.shield_tier_share 成（0 = 不跟著變硬、1 = 跟 Boss 一樣；預設 0.5：Boss ×7.8 時結晶 ×4.4）。
     * 完全不吃的話高階的護盾兩三秒就破、限時與回血沒有作用；全吃又會變成輸出不夠的人一定打不破。
     */
    public double shieldLifeMultiplier(final double bossMultiplier) {
        return shieldLifeMultiplier(bossMultiplier, shieldTierShare);
    }

    /** 同上的純計算（測試用）。 */
    public static double shieldLifeMultiplier(final double bossMultiplier, final double share) {
        return 1.0 + Math.max(0, bossMultiplier - 1.0) * Math.max(0, Math.min(1, share));
    }

    /** 同上的純計算（測試用）。 */
    public static double finalLifeMultiplier(final int tier, final int from, final double max, final int maxTier) {
        if (tier <= from || maxTier <= from || max <= 1) {
            return 1.0;
        }
        final double progress = Math.min(1.0, (tier - from) / (double) (maxTier - from));
        return 1.0 + (max - 1.0) * progress;
    }

    /** 這個終局的怪物等級比選的那一階高幾級（不是終局用主要終局的）。 */
    public int finalLevelBonus(final String id) {
        final FinalInfo info = finalInfos.get(id);
        return info == null ? finalLevelBonus : info.levelBonus();
    }

    /** 地圖或終局的代號 → 定義；沒有回傳 null。 */
    public MapDef find(final String id) {
        if (id == null) {
            return null;
        }
        if (finalMap != null && (finalMap.id().equals(id) || id.equals("final"))) {
            return finalMap; // 「final」也可以（指令用）
        }
        final MapDef extra = extraFinals.get(id);
        return extra != null ? extra : maps.get(id);
    }

    /** 物品基底 → 地圖；不是任何地圖的基底回傳 null。 */
    public MapDef byBase(final String baseId) {
        for (final MapDef map : maps.values()) {
            if (map.base().equals(baseId)) {
                return map;
            }
        }
        return null;
    }

    /** 星圖上第幾圈的地圖。 */
    public List<MapDef> ofRing(final int ring) {
        final List<MapDef> list = new ArrayList<>();
        for (final MapDef map : maps.values()) {
            if (map.ring() == ring) {
                list.add(map);
            }
        }
        return list;
    }

    /** 星圖最外圈（沒有地圖是 0）：這一圈全部完成後解鎖終局。 */
    public int maxRing() {
        int max = 0;
        for (final MapDef map : maps.values()) {
            max = Math.max(max, map.ring());
        }
        return max;
    }

    /** 地圖的階級表（T1–T16 的怪物等級）。 */
    public MapStats.Tiers tiers() {
        return settings.tiers();
    }

    /** 終局的怪物等級比選的那一階高幾級。 */
    public int finalLevelBonus() {
        return finalLevelBonus;
    }

    private static PackSettings pack(final YamlNode node, final int weight, final int min, final int max) {
        int lo = min;
        int hi = max;
        if (node.has("size")) {
            final int[] range = node.range("size");
            lo = Math.max(1, Math.min(range[0], range[1]));
            hi = Math.max(lo, Math.max(range[0], range[1]));
        }
        return new PackSettings(Math.max(0, node.integer("weight", weight)), lo, hi);
    }

    /** drops.common_chance（2026-10）：沒寫回傳 -1（照 drops.final_common_chance）。 */
    private static double commonChance(final YamlNode drops) {
        return drops.has("common_chance") ? clamp01(drops.decimal("common_chance", 0)) : -1;
    }

    private static double clamp01(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static Settings defaultSettings() {
        return new Settings(MapStats.DEFAULT_TIERS, 20, 6, 15, 60, 36, 22, new PackSettings(64, 4, 6), new PackSettings(28, 3, 5),
                new PackSettings(8, 2, 3));
    }

    private static Drops defaultDrops() {
        final Map<MonsterRarity, Double> chance = new EnumMap<>(MonsterRarity.class);
        chance.put(MonsterRarity.NORMAL, 0.0025); // 2026-10：以前 1.2%／3%／10%、Boss 2 張，一張 T16 掉 5～6 張地圖
        chance.put(MonsterRarity.MAGIC, 0.006);
        chance.put(MonsterRarity.RARE, 0.02);
        chance.put(MonsterRarity.UNIQUE, 0.0);
        final Map<Rarity, Integer> rarity = new EnumMap<>(Rarity.class);
        rarity.put(Rarity.NORMAL, 55);
        rarity.put(Rarity.MAGIC, 35);
        rarity.put(Rarity.RARE, 10);
        final Map<Rarity, Integer> rarityTop = new EnumMap<>(Rarity.class);
        rarityTop.put(Rarity.NORMAL, 40); // 2026-10：高階少掉現成的稀有圖（以前 15／35／50），低階通貨拿來洗地圖
        rarityTop.put(Rarity.MAGIC, 45);
        rarityTop.put(Rarity.RARE, 15);
        return new Drops(chance, 1, 0.15, 0.01, 4, 1, rarity, rarityTop, 2, DEFAULT_BOSS_UNIQUE_CHANCE, DEFAULT_NAMED_UNIQUE_CHANCE, "sky_fragment", Set.of(), 2,
                "mountain", 1, 3, 3, DEFAULT_CHASE_CHANCE, 0.01, 0.01, 0.01);
    }
}
