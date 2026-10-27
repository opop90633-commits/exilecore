package tw.exilecore.stats;

import java.util.Set;

/**
 * 2026-10-27 照 PoE2 0.5：天賦樹最後一批「尚未生效」的 stat 用的屬性鍵（站長：「天賦點好像還有蠻多沒有生效的東西」）。
 * 機制寫在 {@code tw.exilecore.skill.TreeLeftovers}（事件、每 10 tick 的掃描）與幾個共用的地方（每個鍵寫消費的地方）；
 * 不放進 {@link StatKeys}，方便合併。純邏輯的換算也放這裡（不依賴 Bukkit，可以直接測）。
 */
public final class TreeLastKeys {

    private TreeLastKeys() {
    }

    // ---- 防禦 ----

    /**
     * FLAT %：防禦時護甲多算 x%（PoE2「Defend with 200% of Armour」＝ +100）。標籤是這一下的情境：
     * 擊中技能的標籤（[projectile, attack]＝投射物攻擊）、{@link #CRIT_TAKEN}（被暴擊）、{@link #FROM_FAR}（6 格外打來的）；
     * 條件（nolowes＝不在低能量護盾）照屬性表。DamageCalculator 算護甲減傷前乘上去。
     */
    public static final String ARMOUR_DEFEND = "armour.defend";
    /** 情境標籤：這一下是暴擊（防禦方的「對暴擊用 x% 護甲防禦」「被偏斜的暴擊多擋 x%」）。DamageCalculator 補上。 */
    public static final String CRIT_TAKEN = "crit_taken";
    /** 情境標籤：攻擊者在 6 格外（CombatListener 照距離補在防禦方那一下的屬性表）。 */
    public static final String FROM_FAR = "hit_from_far";
    /** 旗標：不會被暴擊（條件 parrying＝招架中）。DamageCalculator 用。 */
    public static final String FLAG_NO_CRIT_TAKEN = "flag.cannot_be_crit";
    /** 旗標：不會被輕暈（條件 stun＝最近被重暈過、nohit＝最近沒被擊中）。AilmentService 的輕暈用。 */
    public static final String FLAG_NO_LIGHT_STUN = "flag.no_light_stun";
    /** FLAT %：被暈眩（輕暈、重暈）時獲得 1 層易爆的機率。{@code TreeLeftovers#stunnedBy}。 */
    public static final String VOLATILITY_ON_STUNNED = "volatility.on_stunned";
    /** 旗標：敵人暈眩你時讓牠致盲。同上。 */
    public static final String FLAG_BLIND_STUNNER = "flag.blind_stunner";
    /** INCREASED：判定「被包圍」的範圍（PoE2 Surrounded Area of Effect）。ConditionTracker 用（半徑 × √(1＋x)）。 */
    public static final String SURROUNDED_AREA = "surrounded.area";
    /** 旗標：舉盾時正前方 x 格內的敵人衰弱（FLAT，值＝格）。{@code TreeLeftovers} 每 10 tick。 */
    public static final String SHIELD_DEBILITATE = "shield.debilitate_range";

    // ---- 攻擊 ----

    /** 旗標：擊中無視敵人非負的元素抗性（條件寫在標籤：vs_frozen）。DamageCalculator 用。 */
    public static final String FLAG_IGNORE_ELEMENTAL_RESIST = "flag.ignore_elemental_resist";
    /** FLAT %：投射物穿透 3 格內敵人的機率（不算穿透次數）。ProjectileEngine 用。 */
    public static final String PIERCE_CLOSE = "projectile.pierce_close";
    /** 近距離穿透的範圍（PoE2：3 格）。 */
    public static final double PIERCE_CLOSE_RANGE = 3;
    /** FLAT %：投射物分裂一次的機率（條件 meleehit8＝過去 8 秒近戰擊中過）。ProjectileEngine 射出時擲。 */
    public static final String FORK_CHANCE = "projectile.fork_chance";
    /** FLAT %：擊中時擊退的機率（條件寫在標籤：vs_close、vs_bleeding）。{@code TreeLeftovers#onHit}。 */
    public static final String KNOCKBACK_CHANCE = "knockback.chance";
    /** 旗標：完全破甲的敵人被癱瘓。CombatListener 完全破甲時呼叫 {@code TreeLeftovers#onFullBreak}。 */
    public static final String FLAG_MAIM_ON_FULL_BREAK = "flag.maim_on_full_break";
    /** 旗標：對被釘住的敵人造成的流血是加劇的。AilmentService 上流血時呼叫 {@code TreeLeftovers#bleedInflicted}。 */
    public static final String FLAG_AGGRAVATE_PINNED = "flag.aggravate_bleed_pinned";
    /** FLAT %：對站在碎地上的敵人造成的流血加劇的機率。同上。 */
    public static final String AGGRAVATE_JAGGED = "bleed.aggravate_jagged";
    /** FLAT（秒）：禁錮（釘住、重暈、凍結、電擊）敵人時恫嚇牠幾秒。{@code TreeLeftovers#immobilised}。 */
    public static final String INTIMIDATE_ON_IMMOBILISE = "intimidate.on_immobilise";
    /** FLAT（秒）：變身時重暈敵人，恫嚇牠幾秒。{@code TreeLeftovers#heavyStunned}。 */
    public static final String INTIMIDATE_SHAPESHIFT_STUN = "intimidate.shapeshift_stun";
    /** 恫嚇（PoE2 Intimidate）：受到的傷害增加 10%。 */
    public static final double INTIMIDATE_TAKEN = 0.10;
    /** FLAT %：被你點燃的敵人每被點燃 1 秒，永久受到的火焰傷害增加 x%（最多 10 倍）。{@code TreeLeftovers} 每 10 tick。 */
    public static final String MELTING_FLAMES = "ignite.permanent_fire_taken";
    /** 「最多 10%」：每秒數值的倍數。 */
    public static final double MELTING_CAP_FACTOR = 10;
    /** FLAT（秒）：凋零沒有凋零的敵人，在牠進入你的存在範圍時。{@code TreeLeftovers} 每 10 tick。 */
    public static final String WITHER_ON_ENTER = "presence.wither_on_enter";
    /** 旗標：被你詛咒的敵人（PvP 的玩家；怪物沒有能量護盾）不能回充能量護盾。PlayerStateService 回充時看。 */
    public static final String FLAG_CURSE_NO_ES_RECHARGE = "flag.curse_no_es_recharge";
    /** 危害（hazard）擊中過的敵人：護甲、閃避減少（INCREASED，負的）。{@code TreeLeftovers#onHit} 給 4 秒的減益。 */
    public static final String HAZARD_ENEMY_ARMOUR = "hazard.enemy_armour";
    public static final String HAZARD_ENEMY_EVASION = "hazard.enemy_evasion";

    // ---- 資源、召喚物、其他 ----

    /** FLAT（秒）：變身至少這麼久之後變回人形時獲得秘術增強。ConditionTracker 變回人形時呼叫 {@code TreeLeftovers#reverted}。 */
    public static final String REVERT_ARCANE_SURGE = "shapeshift.revert_arcane_surge";
    /** FLAT %：用掉榮耀時，每點回復最大生命的 x%。SupportMechanics 用掉榮耀時呼叫 {@code TreeLeftovers#gloryUsed}。 */
    public static final String LIFE_PER_GLORY = "glory.life_per_consumed";
    /** FLAT %：用掉榮耀時，每點增加 x% 攻擊傷害 6 秒（最多 20 倍）。同上。 */
    public static final String ATTACK_PER_GLORY = "glory.attack_per_consumed";
    /** 「最多 60%」：每點數值的倍數；持續 6 秒。 */
    public static final double ATTACK_PER_GLORY_CAP_FACTOR = 20;
    public static final int ATTACK_PER_GLORY_TICKS = 120;
    /** FLAT（秒）：存在範圍內每有一隻稀有或傳奇怪物，技能每 x 秒獲得 1 點榮耀。{@code TreeLeftovers} 每 10 tick。 */
    public static final String GLORY_PER_RARE = "glory.per_rare_interval";
    /** FLAT（每秒）：最近獲得過怒氣時，存在範圍內的隊友每秒回復幾點怒氣。同上。 */
    public static final String ALLY_RAGE_REGEN = "rage.ally_regen";
    /** 旗標：變身時存在範圍內的夥伴有疾速。同上。 */
    public static final String FLAG_COMPANION_ONSLAUGHT_SHAPESHIFTED = "flag.companion_onslaught_shapeshifted";
    /** FLAT %：放下祭品（祭柱）時回復最大生命的 x%。Warcries 立祭柱時呼叫 {@code TreeLeftovers#offeringCreated}。 */
    public static final String LIFE_ON_OFFERING = "offering.life_on_create";
    /** FLAT %：召喚物復活時回復最大生命的 x%。AuraService 補回死掉的召喚物時呼叫 {@code TreeLeftovers#minionRevived}。 */
    public static final String LIFE_ON_MINION_REVIVE = "minion.revive_life";
    /**
     * 旗標：最近立的祭品不會受到傷害。我們的祭柱是不能被打的展示方塊（一直不會受到傷害），等於一直成立；
     * 這個鍵登記在 {@link StatConsumers#INHERENT}（說明照原作，效果本來就有）。
     */
    public static final String FLAG_OFFERING_UNDAMAGEABLE = "flag.offering_undamageable";
    // 2026-10-27：flag.blocked_knockback_less（被格擋的擊中擊退更少 80%）拿掉了——格擋本來就不會擊退你，
    // 蹲守的這一行換成「最近格擋過時增加 20% 暈眩門檻」（tools/poe2_tree/overrides.json）

    // ---- 條件用的事件（PlayerState.markRecent） ----

    /** 被暈眩過（輕暈或重暈；PoE2 的 Stunned 兩種都算。Recently.STUN 只記重暈，玩家預設不會被重暈）。 */
    public static final String EVENT_STUNNED = "stunnedany";
    /** 用雙手近戰武器重暈過敵人。 */
    public static final String EVENT_STUNNED_TWO_HAND = "stunned2h";
    /** 用了需要榮耀的技能（20 秒內的條件看這個 tick）。 */
    public static final String EVENT_GLORY_USED = "gloryused";
    /** 強化攻擊（戰吼強化、先祖強化）出手的那一下。 */
    public static final String EVENT_EMPOWERED = "empoweredhit";
    /** 能量護盾開始回充。 */
    public static final String EVENT_ES_RECHARGE = "esrechargestart";
    /** 站在感電、冰緩、點燃地面上（地面每一波記一次）。 */
    public static final String EVENT_GROUND_SHOCK = "groundshock";
    public static final String EVENT_GROUND_CHILL = "groundchill";
    public static final String EVENT_GROUND_FIRE = "groundfire";
    /** 被某種元素的擊中打到過（最近 4 秒）。 */
    public static final String EVENT_HIT_FIRE = "hitbyfire";
    public static final String EVENT_HIT_COLD = "hitbycold";
    public static final String EVENT_HIT_LIGHTNING = "hitbylightning";

    /** 站在地面上的事件多久內還算（地面的一波最多 20 tick，多留一點）。 */
    public static final long GROUND_WINDOW = 30;
    /** 「過去 8 秒」「20 秒」「2 秒」「強化攻擊中」的 tick。 */
    public static final long EIGHT_SECONDS = 160;
    public static final long TWENTY_SECONDS = 400;
    public static final long TWO_SECONDS = 40;
    public static final long EMPOWERED_WINDOW = 20;

    /** 護甲防禦的倍率：1 ＋ 多算的 %（負的不算）。 */
    public static double armourDefend(final StatSheet defender, final Set<String> context) {
        return 1 + Math.max(0, defender.flat(ARMOUR_DEFEND, context)) / 100.0;
    }

    /** 被你點燃了這麼多秒的敵人，受到的火焰傷害增加多少（小數）：每秒 per%，最多 10 倍。 */
    public static double meltingTaken(final double perSecond, final double seconds) {
        if (perSecond <= 0 || seconds <= 0) {
            return 0;
        }
        return Math.min(perSecond * MELTING_CAP_FACTOR, perSecond * Math.floor(seconds)) / 100.0;
    }

    /** 用掉 glory 點榮耀的攻擊傷害增加（小數）：每點 per%，最多 20 倍。 */
    public static double gloryAttack(final double per, final double glory) {
        if (per <= 0 || glory <= 0) {
            return 0;
        }
        return Math.min(per * ATTACK_PER_GLORY_CAP_FACTOR, per * glory) / 100.0;
    }
}
