package tw.exilecore.skill;

import java.util.random.RandomGenerator;

/**
 * 2026-10-30 照 PoE2 Ancestral Spirits（先祖之魂）：每放一個圖騰叫出的先祖之靈是四種型態之一（poe2db：先祖玉法師、先祖玉獸、先祖戰角、先祖靈龜）。
 * 倍率照 poe2db 各型態的基礎數值（生命、護甲 +x%、閃避 +x%、傷害；攻擊時間都是 1 秒；護甲、閃避的寫法跟 skills.yml 的 ally 召喚物一樣：+70% 寫 0.7）。純資料，不依賴 Bukkit。
 * <p>
 * 原作沒寫怎麼決定型態：照玩家的描述是隨機、靈龜比較少見，這裡用權重 3：3：3：1。
 */
public enum AncestralForms {

    /** 先祖玉法師（Ancestral Jadecaster）：生命 53%（另有生命 20% 的能量護盾）、傷害 100%；冰冷投射物法術（施法 1.5 秒），每 10 秒定身目標周圍的敵人。 */
    JADECASTER("先祖玉法師", 3, "STRAY", 0.53, 1.0, 0, 0, 0.2, 8.0, "cold", "EMERALD", 1.0, 2.0),
    /** 先祖玉獸（Ancestral Jade Hulk）：生命 100%、護甲 +70%、傷害 180%；圓盾衝鋒（7 秒，重暈）、戰吼（30 秒：友軍傷害增加 50%，20 秒）。 */
    JADE_HULK("先祖玉獸", 3, "IRON_GOLEM", 1.0, 1.8, 0.7, 0, 0, 0, "arrow", null, 1.0, 2.4),
    /** 先祖戰角（Ancestral Warhorn）：生命 75%、護甲 +35%、閃避 +15%、傷害 120%；遠程攻擊，每 12 秒吹號角（友軍 20 秒：攻擊擊中獲得 10 怒氣、技能速度增加 20%）。 */
    WARHORN("先祖戰角", 3, "WITHER_SKELETON", 0.75, 1.2, 0.35, 0.15, 0, 9.0, "arrow", "GOAT_HORN", 1.0, 2.0),
    /** 先祖靈龜（Ancestral Spirit Turtle）：生命 130%、護甲 +100%、傷害 120%；不會被暈眩、走得很慢；光環給範圍內友軍防禦增加 80%、格擋率增加 40%。 */
    TURTLE("先祖靈龜", 1, "TURTLE", 1.3, 1.2, 1.0, 0, 0, 0, "arrow", null, 0.6, 1.8);

    /** 玉法師的冰冷投射物：施法時間 1.5 秒、暴擊率 6%（poe2db MPSAncestralTotemSpiritSoulCasterProjectile）。 */
    public static final double CASTER_BOLT_CAST = 1.5;
    public static final double CASTER_BOLT_CRIT = 6;
    /** 玉法師的封玉（EASAncestralEncaseEnemy／GSAncestralJadePrison）：冷卻 10 秒，定身目標周圍的敵人。 */
    public static final double ENCASE_COOLDOWN = 10;
    public static final double ENCASE_RADIUS = 2.0;
    public static final int ENCASE_TICKS = 40;
    /** 玉獸的圓盾衝鋒（TCAncestralLeagueKaruiHulk）：冷卻 7 秒，擊退並暈眩（這裡：暈眩累積更多 400%）。 */
    public static final double CHARGE_COOLDOWN = 7;
    public static final double CHARGE_STUN_MORE = 4.0;
    /** 玉獸的幻步（DTTAncestralJadeHulkLeapSlam）：冷卻 5 秒，跳 2.5～7 公尺（leap_slam_minimum_distance 25、skill_maximum_travel_distance 70）；
     *  落地衝擊（GAAncestralJadeHulkLeapImpact）150% 攻擊傷害、更多 50%。 */
    public static final double DASH_COOLDOWN = 5;
    public static final double DASH_MIN = 2.5;
    public static final double DASH_MAX = 7.0;
    public static final double DASH_IMPACT = 1.5;
    public static final double DASH_IMPACT_MORE = 0.5;
    /** 玉獸的戰吼：冷卻 30 秒，友軍傷害增加 50%，20 秒。 */
    public static final double HULK_CRY_COOLDOWN = 30;
    public static final double HULK_CRY_DAMAGE = 50;
    /** 戰角的號角（EGTotemSpiritJadeHornBlow）：冷卻 12 秒，友軍攻擊擊中獲得 10 怒氣、技能速度增加 20%，20 秒。 */
    public static final double HORN_COOLDOWN = 12;
    public static final double HORN_RAGE = 10;
    public static final double HORN_SPEED = 20;
    /** 玉獸戰吼、戰角號角的持續時間與範圍。 */
    public static final double ALLY_BUFF_SECONDS = 20;
    public static final double ALLY_RADIUS = 6.0;
    /** 靈龜的光環：範圍內友軍防禦（護甲、閃避、能量護盾）增加 80%、格擋率增加 40%。 */
    public static final double TURTLE_DEFENCES = 80;
    public static final double TURTLE_BLOCK = 40;
    public static final double TURTLE_RADIUS = 6.0;

    private final String label;
    private final int weight;
    private final String entity;
    private final double life;
    private final double damage;
    private final double armour;
    private final double evasion;
    private final double es;
    private final double ranged;
    private final String bolt;
    private final String weapon;
    private final double speed;
    private final double reach;

    AncestralForms(final String label, final int weight, final String entity, final double life, final double damage, final double armour,
                   final double evasion, final double es, final double ranged, final String bolt, final String weapon, final double speed,
                   final double reach) {
        this.label = label;
        this.weight = weight;
        this.entity = entity;
        this.life = life;
        this.damage = damage;
        this.armour = armour;
        this.evasion = evasion;
        this.es = es;
        this.ranged = ranged;
        this.bolt = bolt;
        this.weapon = weapon;
        this.speed = speed;
        this.reach = reach;
    }

    public String label() {
        return label;
    }

    public String entity() {
        return entity;
    }

    /** 抽一種型態（權重 3：3：3：1）。 */
    public static AncestralForms pick(final RandomGenerator random) {
        int total = 0;
        for (final AncestralForms form : values()) {
            total += form.weight;
        }
        int roll = random.nextInt(total);
        for (final AncestralForms form : values()) {
            roll -= form.weight;
            if (roll < 0) {
                return form;
            }
        }
        return JADE_HULK;
    }

    /** 這種型態的召喚物設定（照 PoE2 召喚物表：ally）。玉法師的能量護盾是生命的 20%：生命 53% × 1.2 拆成 5/6 生命、1/6 護盾。 */
    public SummonSpec spec(final SummonSpec base) {
        final double total = es > 0 ? life * (1 + es) : life;
        final double esShare = es > 0 ? es / (1 + es) : 0;
        final SummonSpec spec = base.withForm(entity, total, damage, armour, evasion, esShare, ranged, bolt, weapon, base.speed() * speed, reach, label);
        // 靈龜：poe2db 的怪物詞綴「堅韌的」（base cannot be stunned）
        return this == TURTLE ? spec.withExtraStats(java.util.List.of(tw.exilecore.stats.Modifier.flat("flag.immune.stun", 1, label))) : spec;
    }

    /** 機率（給說明用）：百分比。 */
    public double chancePercent() {
        int total = 0;
        for (final AncestralForms form : values()) {
            total += form.weight;
        }
        return weight * 100.0 / total;
    }
}
