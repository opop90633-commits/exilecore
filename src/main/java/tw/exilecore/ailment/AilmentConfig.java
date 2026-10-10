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
 * 機制照 PoE2：看這一擊造成的元素傷害（抗性減免後）佔目標「異常門檻」的比例。
 * <ul>
 *   <li>點燃：機率 = 比例 × 每門檻機率（PoE2：每 4% 門檻 1%）＋ 點燃機率；每秒造成那一擊火焰傷害的 20%，4 秒；同時只有最強的在燒。</li>
 *   <li>冰緩：冰冷傷害一定冰緩；效果 = 50% × 比例^0.4，最多 50%，太小不算。</li>
 *   <li>凍結：冰冷傷害累積凍結值，滿 100% 凍結；一陣子沒被打到會消退。</li>
 *   <li>感電：機率跟點燃一樣算；目標受到的傷害增加 20%。</li>
 *   <li>流血（物理，只有攻擊）：機率只看加成（每門檻機率預設 0）；每秒造成那一擊物理傷害的 15%，5 秒，目標移動時 2 倍；只有最強的在流。</li>
 *   <li>中毒（物理＋混沌）：每秒造成那一擊傷害的 20%，2 秒，可以疊加（上限 8 個）。</li>
 * </ul>
 */
public final class AilmentConfig {

    public static final String FILE = "ailments.yml";

    private final Map<MonsterRarity, Double> monsterThreshold = new EnumMap<>(MonsterRarity.class);
    private double igniteChancePerThreshold;
    private double igniteDamagePerSecond;
    private double igniteDuration;
    private double chillMax;
    private double chillMin;
    private double chillDurationMonster;
    private double chillDurationPlayer;
    private double freezeBuildup;
    private double freezeDurationMonster;
    private double freezeDurationPlayer;
    private double freezeDecayDelay;
    private double freezeDecayPerSecond;
    private double freezeUniqueBuildup;
    private double freezeUniqueImmunity;
    private double freezePlayerImmunity;
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

    /**
     * 一條累積條（暈眩、電擊）的設定。
     *
     * @param buildup         每造成門檻 100% 的傷害累積幾 %
     * @param durationMonster 怪物觸發幾秒
     * @param durationPlayer  玩家觸發幾秒
     * @param decayDelay      最後一次被打到後幾秒開始消退
     * @param decayPerSecond  每秒消退幾 %
     * @param playerImmunity  玩家結束後幾秒不能再累積
     * @param uniqueBuildup   傳奇怪的累積只有幾 %
     * @param uniqueImmunity  傳奇怪結束後幾秒不能再累積
     */
    public record MeterSettings(double buildup, double durationMonster, double durationPlayer, double decayDelay, double decayPerSecond,
                                double playerImmunity, double uniqueBuildup, double uniqueImmunity) {

        /** 這一擊的累積（1 = 100%）：傷害佔門檻的比例 × buildup% × 加成；傳奇怪再打折。 */
        public double buildupFor(final double damage, final double threshold, final double multiplier, final boolean unique) {
            if (damage <= 0 || threshold <= 0) {
                return 0;
            }
            final double amount = damage / threshold * buildup / 100.0 * Math.max(0, multiplier);
            return unique ? amount * uniqueBuildup / 100.0 : amount;
        }

        public double duration(final boolean player) {
            return player ? durationPlayer : durationMonster;
        }

        /** 每 tick 消退多少（小數）。 */
        public double decayPerTick() {
            return decayPerSecond / 100.0 / 20.0;
        }

        /** 幾 tick 後開始消退。 */
        public long decayDelayTicks() {
            return Math.max(0, Math.round(decayDelay * 20));
        }

        static MeterSettings parse(final YamlNode node, final MeterSettings def) {
            return new MeterSettings(nonNegative(node, "buildup", def.buildup()),
                    positive(node.node("duration"), "monster", def.durationMonster()),
                    positive(node.node("duration"), "player", def.durationPlayer()),
                    nonNegative(node, "decay_delay", def.decayDelay()),
                    nonNegative(node, "decay_per_second", def.decayPerSecond()),
                    nonNegative(node, "player_immunity", def.playerImmunity()),
                    nonNegative(node.node("unique"), "buildup", def.uniqueBuildup()),
                    nonNegative(node.node("unique"), "immunity", def.uniqueImmunity()));
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
        igniteChancePerThreshold = 25;
        igniteDamagePerSecond = 20;
        igniteDuration = 4;
        chillMax = 50;
        chillMin = 5;
        chillDurationMonster = 8;
        chillDurationPlayer = 2;
        freezeBuildup = 200;
        freezeDurationMonster = 4;
        freezeDurationPlayer = 1.5;
        freezeDecayDelay = 2;
        freezeDecayPerSecond = 50;
        freezeUniqueBuildup = 50;
        freezeUniqueImmunity = 6;
        freezePlayerImmunity = 3;
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
        poisonMaxStacks = 8;
        stun = new MeterSettings(120, 1.5, 0.8, 1.5, 40, 3, 40, 8);
        stunElementalMultiplier = 0.5;
        electrocute = new MeterSettings(150, 3, 1.5, 2, 50, 3, 50, 8);
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
        final YamlNode ignite = root.node("ignite");
        igniteChancePerThreshold = nonNegative(ignite, "chance_per_threshold", igniteChancePerThreshold);
        igniteDamagePerSecond = nonNegative(ignite, "damage_per_second", igniteDamagePerSecond);
        igniteDuration = positive(ignite, "duration", igniteDuration);
        final YamlNode chill = root.node("chill");
        chillMax = nonNegative(chill, "max", chillMax);
        chillMin = nonNegative(chill, "min", chillMin);
        if (chillMin > chillMax) {
            throw new IllegalArgumentException(chill.path() + "：min 不能比 max 大");
        }
        chillDurationMonster = positive(chill.node("duration"), "monster", chillDurationMonster);
        chillDurationPlayer = positive(chill.node("duration"), "player", chillDurationPlayer);
        final YamlNode freeze = root.node("freeze");
        freezeBuildup = nonNegative(freeze, "buildup", freezeBuildup);
        freezeDurationMonster = positive(freeze.node("duration"), "monster", freezeDurationMonster);
        freezeDurationPlayer = positive(freeze.node("duration"), "player", freezeDurationPlayer);
        freezeDecayDelay = nonNegative(freeze, "decay_delay", freezeDecayDelay);
        freezeDecayPerSecond = nonNegative(freeze, "decay_per_second", freezeDecayPerSecond);
        freezeUniqueBuildup = nonNegative(freeze.node("unique"), "buildup", freezeUniqueBuildup);
        freezeUniqueImmunity = nonNegative(freeze.node("unique"), "immunity", freezeUniqueImmunity);
        freezePlayerImmunity = nonNegative(freeze, "player_immunity", freezePlayerImmunity);
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
        igniteChancePerThreshold = other.igniteChancePerThreshold;
        igniteDamagePerSecond = other.igniteDamagePerSecond;
        igniteDuration = other.igniteDuration;
        chillMax = other.chillMax;
        chillMin = other.chillMin;
        chillDurationMonster = other.chillDurationMonster;
        chillDurationPlayer = other.chillDurationPlayer;
        freezeBuildup = other.freezeBuildup;
        freezeDurationMonster = other.freezeDurationMonster;
        freezeDurationPlayer = other.freezeDurationPlayer;
        freezeDecayDelay = other.freezeDecayDelay;
        freezeDecayPerSecond = other.freezeDecayPerSecond;
        freezeUniqueBuildup = other.freezeUniqueBuildup;
        freezeUniqueImmunity = other.freezeUniqueImmunity;
        freezePlayerImmunity = other.freezePlayerImmunity;
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

    /** 怪物的異常門檻倍率（× 最大生命）。 */
    public double monsterThreshold(final MonsterRarity rarity) {
        return monsterThreshold.getOrDefault(rarity, 1.0);
    }

    // ---- 公式（damage 都是抗性減免後、感電之前的那一元素的傷害）----

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

    public double igniteChance(final double fire, final double threshold, final double flat, final double increased) {
        return chance(fire, threshold, igniteChancePerThreshold, flat, increased);
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

    /** 冰緩效果（0–最大值的小數）：50% ×（傷害 ÷ 門檻）^0.4 ×（1 ＋ 增加效果），小於下限回傳 0。 */
    public double chillMagnitude(final double cold, final double threshold, final double increasedEffect) {
        if (cold <= 0 || threshold <= 0) {
            return 0;
        }
        final double percent = Math.min(chillMax, 50 * Math.pow(cold / threshold, 0.4) * Math.max(0, 1 + increasedEffect));
        return percent < chillMin ? 0 : percent / 100.0;
    }

    /**
     * 這一擊的凍結累積（1 = 100%，滿了就凍結）：傷害 ÷ 門檻 × 累積倍率 × 詞綴與輔助的倍率，傳奇怪再乘 unique.buildup。
     *
     * @param multiplier 攻擊方的凍結累積倍率（（1 ＋ 增加）× 更多）
     */
    public double freezeBuildup(final double cold, final double threshold, final double multiplier, final boolean unique) {
        if (cold <= 0 || threshold <= 0) {
            return 0;
        }
        final double buildup = cold / threshold * freezeBuildup / 100.0 * Math.max(0, multiplier);
        return unique ? buildup * freezeUniqueBuildup / 100.0 : buildup;
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
        return player ? freezeDurationPlayer : freezeDurationMonster;
    }

    public double shockDuration(final boolean player) {
        return player ? shockDurationPlayer : shockDurationMonster;
    }

    /** 最後一次被冰冷傷害打到後，幾秒開始消退。 */
    public double freezeDecayDelay() {
        return freezeDecayDelay;
    }

    /** 每秒消退多少（1 = 100%）。 */
    public double freezeDecayPerSecond() {
        return freezeDecayPerSecond / 100.0;
    }

    /** 傳奇怪凍結結束後幾秒不能再累積。 */
    public double freezeUniqueImmunity() {
        return freezeUniqueImmunity;
    }

    /** 玩家凍結結束後幾秒不能再累積（不會被一群怪一直凍住）。 */
    public double freezePlayerImmunity() {
        return freezePlayerImmunity;
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

    /** 中毒最多疊幾個。 */
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
