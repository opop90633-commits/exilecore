package tw.exilecore.ailment;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * data/ailments.yml：異常狀態（點燃、冰緩、凍結、感電，以及 PoE2 的流血、中毒）的數值與公式。純函式，不依賴 Bukkit。
 * <p>
 * 機制照 PoE2 0.5（2026-10-26）：看這一擊<b>目標減免前</b>的傷害佔目標「異常門檻」或「暈眩門檻（poise）」的比例。
 * <ul>
 *   <li>點燃：每一擊給目標一層「易燃」= 比例 × 20（每 5% 門檻 1%）＋ 點燃機率，易燃疊加（怪物身上 8 秒、玩家 4 秒），
 *   這一擊點燃的機率 = 身上所有易燃加起來；每秒造成那一擊火焰傷害的 20%，4 秒；同時只有最強的在燒。</li>
 *   <li>冰緩：冰冷傷害一定冰緩；效果 = 100 × 比例，最多 50%，小於 30% 不算。</li>
 *   <li>凍結、電擊、重暈、釘地：累積條，對暈眩門檻的係數 2.1／1.7／0.58／4.2；滿 100% 觸發，觸發後門檻暫時提高 500%（慢慢降回來）。</li>
 *   <li>感電：機率 = 比例 × 25（每 4% 門檻 1%）；目標受到的傷害增加 20%。</li>
 *   <li>流血（物理，只有攻擊）：機率只看加成；每秒造成那一擊物理傷害的 15%，5 秒，目標移動時 2 倍；只有最強的在流。</li>
 *   <li>中毒（物理＋混沌）：每秒造成那一擊傷害的 20%，2 秒；預設同一個人只有 1 層生效（輔助、天賦加層數）。</li>
 * </ul>
 */
public final class AilmentConfig {

    public static final String FILE = "ailments.yml";

    private final Map<MonsterRarity, Double> monsterThreshold = new EnumMap<>(MonsterRarity.class);
    /** 2026-10-26 照 PoE2：玩家的異常門檻 = 最大生命的幾 %（不含能量護盾）。 */
    private double playerThresholdLife;
    private double igniteChancePerThreshold;
    private double flammabilityMonster;
    private double flammabilityPlayer;
    private double chillPerThreshold;
    private double igniteDamagePerSecond;
    private double igniteDuration;
    private double chillMax;
    private double chillMin;
    private double chillDurationMonster;
    private double chillDurationPlayer;
    private MeterSettings freeze;
    private MeterSettings pin;
    private double shockChancePerThreshold;
    private double shockEffect;
    private double shockMaxEffect;
    private double shockDurationMonster;
    private double shockDurationPlayer;
    // 流血、中毒（PoE2）
    private double bleedChancePerThreshold;
    private double bleedDamagePerSecond;
    private double bleedMovingMultiplier;
    private double bleedDuration;
    private double poisonChancePerThreshold;
    private double poisonDamagePerSecond;
    private double poisonDuration;
    private int poisonMaxStacks;
    // 暈眩、電擊（PoE2）：累積條
    private MeterSettings stun;
    private double stunElementalMultiplier;
    private MeterSettings electrocute;
    // 2026-10 PoE2：輕暈、蓄勢待暈、玩家不會重暈、破甲
    private double lightStunMin = 15;
    private double lightStunMonster = 0.3;
    private double lightStunPlayer = 0.2;
    private boolean playerHeavyStun = false;
    private final java.util.EnumMap<MonsterRarity, Double> primed = new java.util.EnumMap<>(MonsterRarity.class);
    private final java.util.EnumMap<MonsterRarity, Double> armourBreakMultiplier = new java.util.EnumMap<>(MonsterRarity.class);
    private double armourBreakDuration = 12;
    private double fullyBrokenTaken = 20;
    /** 2026-10-26 照 PoE2（PoB 的 Boss 設定）：Boss 的暈眩門檻再乘（地圖 Boss ×3.13、巔峰與極境 Boss ×9.38）；這次之前沒有。 */
    private double bossPoiseMap = 3.13;
    private double bossPoisePinnacle = 9.38;

    /**
     * 一條累積條（凍結、重暈、電擊、釘地）的設定（2026-10-26 照 PoE2 0.5：門檻是暈眩門檻 poise；觸發後門檻暫時提高，取代以前的傳奇怪打折與免疫時間）。
     *
     * @param buildup              每造成門檻 100% 的傷害累積幾 %（PoE2 的 DamageScale × 100：凍結 210、電擊 170、重暈 58、釘地 420）
     * @param buildupPlayer        打玩家時的累積（凍結 200、重暈 65）
     * @param durationMonster      怪物觸發幾秒
     * @param durationPlayer       玩家觸發幾秒
     * @param decayDelay           怪物最後一次被累積後幾秒開始消退
     * @param decayPerSecond       怪物每秒消退幾 %
     * @param playerDecayDelay     玩家幾秒開始消退
     * @param playerDecayPerSecond 玩家每秒消退幾 %
     * @param afterBonus           觸發後門檻提高幾 %（之後 afterSeconds 秒內直線降回 0）
     * @param afterSeconds         提高的門檻幾秒降回來
     * @param playerAfterBonus     玩家觸發後門檻提高幾 %
     * @param playerAfterSeconds   玩家幾秒降回來
     */
    public record MeterSettings(double buildup, double buildupPlayer, double durationMonster, double durationPlayer,
                                double decayDelay, double decayPerSecond, double playerDecayDelay, double playerDecayPerSecond,
                                double afterBonus, double afterSeconds, double playerAfterBonus, double playerAfterSeconds) {

        /** 這一擊的累積（1 = 100%）：傷害 ÷ 門檻 × buildup% × 加成（打怪物）。 */
        public double buildupFor(final double damage, final double threshold, final double multiplier) {
            return buildupFor(damage, threshold, multiplier, false);
        }

        /** 這一擊的累積（1 = 100%）；player：目標是玩家（用 buildupPlayer）。 */
        public double buildupFor(final double damage, final double threshold, final double multiplier, final boolean player) {
            if (damage <= 0 || threshold <= 0) {
                return 0;
            }
            return damage / threshold * (player ? buildupPlayer : buildup) / 100.0 * Math.max(0, multiplier);
        }

        public double duration(final boolean player) {
            return player ? durationPlayer : durationMonster;
        }

        /** 怪物每 tick 消退多少（小數）。 */
        public double decayPerTick() {
            return decayPerTick(false);
        }

        public double decayPerTick(final boolean player) {
            return (player ? playerDecayPerSecond : decayPerSecond) / 100.0 / 20.0;
        }

        /** 怪物幾 tick 後開始消退。 */
        public long decayDelayTicks() {
            return decayDelayTicks(false);
        }

        public long decayDelayTicks(final boolean player) {
            return Math.max(0, Math.round((player ? playerDecayDelay : decayDelay) * 20));
        }

        /** 觸發後門檻提高多少（小數，5 = +500%）。 */
        public double after(final boolean player) {
            return (player ? playerAfterBonus : afterBonus) / 100.0;
        }

        /** 提高的門檻幾 tick 降回來。 */
        public long afterTicks(final boolean player) {
            return Math.max(1, Math.round((player ? playerAfterSeconds : afterSeconds) * 20));
        }

        static MeterSettings parse(final YamlNode node, final MeterSettings def) {
            final double buildup = nonNegative(node, "buildup", def.buildup());
            final YamlNode after = node.node("after");
            final YamlNode playerAfter = node.node("player_after");
            final YamlNode playerDecay = node.node("player_decay");
            final double afterBonus = nonNegative(after, "bonus", def.afterBonus());
            final double afterSeconds = positive(after, "seconds", def.afterSeconds());
            return new MeterSettings(buildup, nonNegative(node, "player_buildup", def.buildupPlayer()),
                    positive(node.node("duration"), "monster", def.durationMonster()),
                    positive(node.node("duration"), "player", def.durationPlayer()),
                    nonNegative(node, "decay_delay", def.decayDelay()),
                    nonNegative(node, "decay_per_second", def.decayPerSecond()),
                    nonNegative(playerDecay, "delay", def.playerDecayDelay()),
                    nonNegative(playerDecay, "per_second", def.playerDecayPerSecond()),
                    afterBonus, afterSeconds,
                    nonNegative(playerAfter, "bonus", def.playerAfterBonus()),
                    positive(playerAfter, "seconds", def.playerAfterSeconds()));
        }
    }

    public AilmentConfig() {
        reset();
    }

    private void reset() {
        monsterThreshold.clear();
        for (final MonsterRarity rarity : MonsterRarity.values()) {
            monsterThreshold.put(rarity, rarity == MonsterRarity.UNIQUE ? 0.5 : 1.0);
        }
        playerThresholdLife = 50;
        igniteChancePerThreshold = 20;
        flammabilityMonster = 8;
        flammabilityPlayer = 4;
        igniteDamagePerSecond = 20;
        igniteDuration = 4;
        chillPerThreshold = 100;
        chillMax = 50;
        chillMin = 30;
        chillDurationMonster = 8;
        chillDurationPlayer = 2;
        freeze = new MeterSettings(210, 200, 4, 2, 8, 5, 2, 50, 500, 14, 500, 14);
        pin = new MeterSettings(420, 420, 3, 2, 8, 5, 2, 50, 500, 13, 500, 13);
        shockChancePerThreshold = 25;
        shockEffect = 20;
        shockMaxEffect = 50;
        shockDurationMonster = 8;
        shockDurationPlayer = 4;
        bleedChancePerThreshold = 0;
        bleedDamagePerSecond = 15;
        bleedMovingMultiplier = 2.0;
        bleedDuration = 5;
        poisonChancePerThreshold = 0;
        poisonDamagePerSecond = 20;
        poisonDuration = 2;
        poisonMaxStacks = 1;
        stun = new MeterSettings(58, 65, 2, 3, 8, 5, 2, 50, 500, 16.5, 100, 10);
        stunElementalMultiplier = 1.0;
        electrocute = new MeterSettings(170, 170, 5, 2, 8, 5, 2, 50, 500, 15, 500, 15);
    }

    /** 讀 ailments.yml。先讀進一份新的，全部檢查過才換上去（寫錯時 /ec reload 失敗，遊戲裡還是原本的設定）。 */
    public void load(final Path dataFolder) throws IOException {
        final AilmentConfig fresh = new AilmentConfig();
        final Path file = dataFolder.resolve(FILE);
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    private void parse(final YamlNode root) {
        final YamlNode threshold = root.node("threshold").node("monster");
        for (final String key : threshold.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(threshold.path() + "：不認得的怪物稀有度「" + key + "」（normal、magic、rare、unique）");
            }
            monsterThreshold.put(rarity, positive(threshold, key, 1.0));
        }
        playerThresholdLife = positive(root.node("threshold"), "player_life", playerThresholdLife);
        final YamlNode ignite = root.node("ignite");
        igniteChancePerThreshold = nonNegative(ignite, "chance_per_threshold", igniteChancePerThreshold);
        flammabilityMonster = positive(ignite.node("flammability_duration"), "monster", flammabilityMonster);
        flammabilityPlayer = positive(ignite.node("flammability_duration"), "player", flammabilityPlayer);
        igniteDamagePerSecond = nonNegative(ignite, "damage_per_second", igniteDamagePerSecond);
        igniteDuration = positive(ignite, "duration", igniteDuration);
        final YamlNode chill = root.node("chill");
        chillPerThreshold = nonNegative(chill, "effect_per_threshold", chillPerThreshold);
        chillMax = nonNegative(chill, "max", chillMax);
        chillMin = nonNegative(chill, "min", chillMin);
        if (chillMin > chillMax) {
            throw new IllegalArgumentException(chill.path() + "：min 不能比 max 大");
        }
        chillDurationMonster = positive(chill.node("duration"), "monster", chillDurationMonster);
        chillDurationPlayer = positive(chill.node("duration"), "player", chillDurationPlayer);
        freeze = MeterSettings.parse(root.node("freeze"), freeze);
        pin = MeterSettings.parse(root.node("pin"), pin);
        final YamlNode shock = root.node("shock");
        shockChancePerThreshold = nonNegative(shock, "chance_per_threshold", shockChancePerThreshold);
        shockEffect = nonNegative(shock, "effect", shockEffect);
        shockMaxEffect = nonNegative(shock, "max_effect", shockMaxEffect);
        shockDurationMonster = positive(shock.node("duration"), "monster", shockDurationMonster);
        shockDurationPlayer = positive(shock.node("duration"), "player", shockDurationPlayer);
        final YamlNode bleed = root.node("bleed");
        bleedChancePerThreshold = nonNegative(bleed, "chance_per_threshold", bleedChancePerThreshold);
        bleedDamagePerSecond = nonNegative(bleed, "damage_per_second", bleedDamagePerSecond);
        bleedMovingMultiplier = positive(bleed, "moving_multiplier", bleedMovingMultiplier);
        bleedDuration = positive(bleed, "duration", bleedDuration);
        final YamlNode poison = root.node("poison");
        poisonChancePerThreshold = nonNegative(poison, "chance_per_threshold", poisonChancePerThreshold);
        poisonDamagePerSecond = nonNegative(poison, "damage_per_second", poisonDamagePerSecond);
        poisonDuration = positive(poison, "duration", poisonDuration);
        poisonMaxStacks = (int) positive(poison, "max_stacks", poisonMaxStacks);
        final YamlNode stunNode = root.node("stun");
        stun = MeterSettings.parse(stunNode, stun);
        stunElementalMultiplier = nonNegative(stunNode, "elemental_multiplier", stunElementalMultiplier);
        electrocute = MeterSettings.parse(root.node("electrocute"), electrocute);
        final YamlNode bossPoise = root.node("boss_poise");
        bossPoiseMap = positive(bossPoise, "map", bossPoiseMap);
        bossPoisePinnacle = positive(bossPoise, "pinnacle", bossPoisePinnacle);
        final YamlNode light = stunNode.node("light");
        lightStunMin = nonNegative(light, "min_chance", lightStunMin);
        lightStunMonster = nonNegative(light.node("duration"), "monster", lightStunMonster);
        lightStunPlayer = nonNegative(light.node("duration"), "player", lightStunPlayer);
        playerHeavyStun = stunNode.bool("player_heavy", playerHeavyStun);
        final YamlNode primedNode = stunNode.node("primed");
        for (final String key : primedNode.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(primedNode.path() + "：不認得的怪物稀有度「" + key + "」");
            }
            primed.put(rarity, nonNegative(primedNode, key, 0) / 100.0);
        }
        final YamlNode breakNode = root.node("armour_break");
        final YamlNode multipliers = breakNode.node("multiplier");
        for (final String key : multipliers.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(multipliers.path() + "：不認得的怪物稀有度「" + key + "」");
            }
            armourBreakMultiplier.put(rarity, nonNegative(multipliers, key, 1));
        }
        armourBreakDuration = positive(breakNode, "duration", armourBreakDuration);
        fullyBrokenTaken = nonNegative(breakNode, "fully_broken_taken", fullyBrokenTaken);
    }

    private void copyFrom(final AilmentConfig other) {
        monsterThreshold.clear();
        monsterThreshold.putAll(other.monsterThreshold);
        playerThresholdLife = other.playerThresholdLife;
        igniteChancePerThreshold = other.igniteChancePerThreshold;
        flammabilityMonster = other.flammabilityMonster;
        flammabilityPlayer = other.flammabilityPlayer;
        chillPerThreshold = other.chillPerThreshold;
        igniteDamagePerSecond = other.igniteDamagePerSecond;
        igniteDuration = other.igniteDuration;
        chillMax = other.chillMax;
        chillMin = other.chillMin;
        chillDurationMonster = other.chillDurationMonster;
        chillDurationPlayer = other.chillDurationPlayer;
        freeze = other.freeze;
        pin = other.pin;
        shockChancePerThreshold = other.shockChancePerThreshold;
        shockEffect = other.shockEffect;
        shockMaxEffect = other.shockMaxEffect;
        shockDurationMonster = other.shockDurationMonster;
        shockDurationPlayer = other.shockDurationPlayer;
        bleedChancePerThreshold = other.bleedChancePerThreshold;
        bleedDamagePerSecond = other.bleedDamagePerSecond;
        bleedMovingMultiplier = other.bleedMovingMultiplier;
        bleedDuration = other.bleedDuration;
        poisonChancePerThreshold = other.poisonChancePerThreshold;
        poisonDamagePerSecond = other.poisonDamagePerSecond;
        poisonDuration = other.poisonDuration;
        poisonMaxStacks = other.poisonMaxStacks;
        stun = other.stun;
        stunElementalMultiplier = other.stunElementalMultiplier;
        electrocute = other.electrocute;
        bossPoiseMap = other.bossPoiseMap;
        bossPoisePinnacle = other.bossPoisePinnacle;
        lightStunMin = other.lightStunMin;
        lightStunMonster = other.lightStunMonster;
        lightStunPlayer = other.lightStunPlayer;
        playerHeavyStun = other.playerHeavyStun;
        primed.clear();
        primed.putAll(other.primed);
        armourBreakMultiplier.clear();
        armourBreakMultiplier.putAll(other.armourBreakMultiplier);
        armourBreakDuration = other.armourBreakDuration;
        fullyBrokenTaken = other.fullyBrokenTaken;
    }

    // ---- 輕暈、蓄勢待暈、破甲（2026-10 PoE2）----

    /** 輕暈機率低於這個（百分比）算 0。 */
    /** 2026-10-26 照 PoE2：Boss 的暈眩門檻倍率（pinnacle = 巔峰、極境 Boss；不然是一般 Boss）。 */
    public double bossPoise(final boolean pinnacle) {
        return pinnacle ? bossPoisePinnacle : bossPoiseMap;
    }

    public double lightStunMin() {
        return lightStunMin;
    }

    public double lightStunDuration(final boolean player) {
        return player ? lightStunPlayer : lightStunMonster;
    }

    /** 玩家會不會被重暈（PoE2：不會，只會輕暈）。 */
    public boolean playerHeavyStun() {
        return playerHeavyStun;
    }

    /** 暈眩條到這個比例就是「蓄勢待暈」（小數；沒設定 = 1，不會蓄勢）。 */
    public double primed(final MonsterRarity rarity) {
        return rarity == null ? 1.0 : primed.getOrDefault(rarity, 1.0);
    }

    /** 對這種稀有度的怪物，破甲量的倍率（普通 3、魔法 2）。 */
    public double armourBreakMultiplier(final MonsterRarity rarity) {
        return rarity == null ? 1.0 : armourBreakMultiplier.getOrDefault(rarity, 1.0);
    }

    public double armourBreakDuration() {
        return armourBreakDuration;
    }

    /** 完全破甲的敵人受到的物理擊中傷害增加（百分比點數）。 */
    public double fullyBrokenTaken() {
        return fullyBrokenTaken;
    }

    // ---- 暈眩、電擊 ----

    public MeterSettings stun() {
        return stun;
    }

    /** 暈眩累積時，元素與混沌傷害算幾成（物理算 100%）。 */
    public double stunElementalMultiplier() {
        return stunElementalMultiplier;
    }

    public MeterSettings electrocute() {
        return electrocute;
    }

    /** 2026-10-26 照 PoE2 0.5：凍結的累積條。 */
    public MeterSettings freeze() {
        return freeze;
    }

    /** 2026-10-26 照 PoE2 0.5：釘地的累積條。 */
    public MeterSettings pin() {
        return pin;
    }

    private static double nonNegative(final YamlNode node, final String key, final double def) {
        final double value = node.decimal(key, def);
        if (value < 0) {
            throw new IllegalArgumentException(node.path() + "." + key + "：不能是負的");
        }
        return value;
    }

    private static double positive(final YamlNode node, final String key, final double def) {
        final double value = node.decimal(key, def);
        if (value <= 0) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要大於 0");
        }
        return value;
    }

    // ---- 門檻 ----

    /**
     * 怪物的異常門檻再乘的倍率（2026-10-26 照 PoE2：門檻改用等級表、不看稀有度，預設全部 1；這次之前是 × 最大生命，傳奇 0.5）。
     */
    public double monsterThreshold(final MonsterRarity rarity) {
        return monsterThreshold.getOrDefault(rarity, 1.0);
    }

    /** 2026-10-26 照 PoE2：玩家的異常門檻 = 最大生命 × 這個比例（0.5；不含能量護盾）。 */
    public double playerThresholdLife() {
        return playerThresholdLife / 100.0;
    }

    // ---- 公式（damage 都是目標減免前的那一種傷害；2026-10-26 這次之前是抗性減免後）----

    /**
     * 點燃或感電的機率（0–1）：(傷害 ÷ 門檻 × 每門檻機率 ＋ flat) ×（1 ＋ increased）。沒有這一元素的傷害就是 0。
     *
     * @param flat      機率 +x%（百分比點數）
     * @param increased 增加 x% 機率（小數）
     */
    private static double chance(final double damage, final double threshold, final double perThreshold,
                                 final double flat, final double increased) {
        if (damage <= 0 || threshold <= 0) {
            return 0;
        }
        final double percent = (damage / threshold * perThreshold + Math.max(0, flat)) * Math.max(0, 1 + increased);
        return Math.max(0, Math.min(100, percent)) / 100.0;
    }

    /** 只看這一擊的點燃機率（0–1；面板估計用。實際打中時看身上疊的易燃 {@link #flammability}）。 */
    public double igniteChance(final double fire, final double threshold, final double flat, final double increased) {
        return chance(fire, threshold, igniteChancePerThreshold, flat, increased);
    }

    /**
     * 2026-10-26 照 PoE2 0.5：這一擊給的易燃（百分比點數，可以超過 100）：(火焰 ÷ 門檻 × 20 ＋ 點燃機率) ×（1 ＋ 增加）。
     * 身上的易燃疊加，加起來就是這一擊點燃的機率。
     */
    public double flammability(final double fire, final double threshold, final double flat, final double increased) {
        if (fire <= 0 || threshold <= 0) {
            return 0;
        }
        return Math.max(0, (fire / threshold * igniteChancePerThreshold + Math.max(0, flat)) * Math.max(0, 1 + increased));
    }

    /** 易燃在身上幾秒（怪物 8、玩家 4）。 */
    public double flammabilityDuration(final boolean player) {
        return player ? flammabilityPlayer : flammabilityMonster;
    }

    public double shockChance(final double lightning, final double threshold, final double flat, final double increased) {
        return chance(lightning, threshold, shockChancePerThreshold, flat, increased);
    }

    /** 流血的機率：跟點燃一樣算（預設每門檻機率是 0，只看流血機率的加成）。 */
    public double bleedChance(final double physical, final double threshold, final double flat, final double increased) {
        return chance(physical, threshold, bleedChancePerThreshold, flat, increased);
    }

    /** 中毒的機率（看物理＋混沌傷害）。 */
    public double poisonChance(final double physicalAndChaos, final double threshold, final double flat, final double increased) {
        return chance(physicalAndChaos, threshold, poisonChancePerThreshold, flat, increased);
    }

    /** 流血每秒的傷害：那一擊物理傷害 × 15% ×（1 ＋ 增加）× 更多（目標移動時再乘 {@link #bleedMovingMultiplier()}）。 */
    public double bleedDps(final double physical, final double increased, final double more) {
        return Math.max(0, physical) * bleedDamagePerSecond / 100.0 * Math.max(0, 1 + increased) * Math.max(0, more);
    }

    /** 中毒每秒的傷害：那一擊（物理＋混沌）傷害 × 20% ×（1 ＋ 增加）× 更多。 */
    public double poisonDps(final double physicalAndChaos, final double increased, final double more) {
        return Math.max(0, physicalAndChaos) * poisonDamagePerSecond / 100.0 * Math.max(0, 1 + increased) * Math.max(0, more);
    }

    /** 點燃每秒的傷害：那一擊火焰傷害 × 20% ×（1 ＋ 增加）× 更多。 */
    public double igniteDps(final double fire, final double increased, final double more) {
        return Math.max(0, fire) * igniteDamagePerSecond / 100.0 * Math.max(0, 1 + increased) * Math.max(0, more);
    }

    /**
     * 冰緩效果（0–最大值的小數）：100 ×（傷害 ÷ 門檻）×（1 ＋ 增加效果），最多 50%，小於 30% 回傳 0
     * （2026-10-26 照 PoE2 0.5 / Path of Building 的直線公式；這次之前是 50% × 比例^0.4，最低 5%）。
     */
    public double chillMagnitude(final double cold, final double threshold, final double increasedEffect) {
        if (cold <= 0 || threshold <= 0) {
            return 0;
        }
        final double percent = chillPerThreshold * cold / threshold * Math.max(0, 1 + increasedEffect);
        return percent < chillMin ? 0 : Math.min(chillMax, percent) / 100.0;
    }

    /**
     * 這一擊的凍結累積（1 = 100%，滿了就凍結）：冰冷傷害 ÷ 暈眩門檻 × 210% × 詞綴與輔助的倍率（打玩家 200%）。
     *
     * @param multiplier 攻擊方的凍結累積倍率（（1 ＋ 增加）× 更多）
     */
    public double freezeBuildup(final double cold, final double threshold, final double multiplier, final boolean player) {
        return freeze.buildupFor(cold, threshold, multiplier, player);
    }

    /** 感電效果（小數，受到的傷害增加多少）：基礎 ×（1 ＋ 增加效果），夾在上限內。 */
    public double shockEffect(final double increasedEffect) {
        return Math.max(0, Math.min(shockMaxEffect, shockEffect * Math.max(0, 1 + increasedEffect))) / 100.0;
    }

    /** 避免的機率（0–1）：屬性是百分比點數，最多 {@link tw.exilecore.stats.StatKeys#AVOID_CAP}%（堆再多也一樣）。 */
    public static double avoidChance(final double value) {
        return Math.max(0, Math.min(tw.exilecore.stats.StatKeys.AVOID_CAP, value)) / 100.0;
    }

    /**
     * 門檻 = 基礎 ×（1 ＋ 增加），最少基礎的 10%。元素異常用「增加元素異常門檻」、暈眩用「增加暈眩門檻」：兩個互不影響。
     */
    public static double threshold(final double base, final double increased) {
        return base * Math.max(0.1, 1 + increased);
    }

    /**
     * 持續時間（秒）：基礎 ×（1 ＋ 攻擊方的增加）×（1 ＋ 防禦方「自己身上的異常持續時間」），最少基礎的 10%。
     */
    public static double duration(final double base, final double attackerIncreased, final double defenderIncreased) {
        return base * Math.max(0.1, Math.max(0, 1 + attackerIncreased) * Math.max(0, 1 + defenderIncreased));
    }

    // ---- 基礎數值 ----

    public double igniteDuration() {
        return igniteDuration;
    }

    public double chillDuration(final boolean player) {
        return player ? chillDurationPlayer : chillDurationMonster;
    }

    public double freezeDuration(final boolean player) {
        return freeze.duration(player);
    }

    public double shockDuration(final boolean player) {
        return player ? shockDurationPlayer : shockDurationMonster;
    }

    public double igniteDamagePerSecond() {
        return igniteDamagePerSecond;
    }

    public double bleedDuration() {
        return bleedDuration;
    }

    /** 目標移動時流血的傷害乘多少（PoE2：2 倍）。 */
    public double bleedMovingMultiplier() {
        return bleedMovingMultiplier;
    }

    public double poisonDuration() {
        return poisonDuration;
    }

    /** 中毒同時有幾層生效（2026-10-26 照 PoE2：預設 1；這次之前 12）。 */
    public int poisonMaxStacks() {
        return poisonMaxStacks;
    }

    public double bleedDamagePerSecond() {
        return bleedDamagePerSecond;
    }

    public double poisonDamagePerSecond() {
        return poisonDamagePerSecond;
    }

    public double shockBaseEffect() {
        return shockEffect;
    }
}
