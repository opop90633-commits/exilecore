package tw.exilecore.stats;

/**
 * 2026-10-26 照 PoE2 0.5 重做輔助寶石與戰吼新增的屬性鍵（不放進 {@link StatKeys}，方便合併）。
 * 寫法跟 StatKeys 一樣：supports.yml、skills.yml 直接用這些字串。
 */
public final class SupportKeys {

    private SupportKeys() {
    }

    // ---- 擊中 ----

    /** MORE：只有擊中的傷害（燎原、深切這種「擊中傷害更少」）：異常（點燃、流血、中毒）照沒乘之前的擊中算。 */
    public static final String DAMAGE_HIT = "damage.hit";
    /** MORE：最大傷害（沉重：物理擊中的最大傷害更多 30%；最小傷害不變）。tags 寫傷害類型。 */
    public static final String DAMAGE_MAX = "damage.max";
    /** FLAG：會獲得任何非冰冷類型的額外傷害時，改成獲得額外冰冷傷害（凝霜刃）。 */
    public static final String FLAG_GAIN_AS_COLD = "flag.gain_as_cold";
    /** FLAT：打點燃中的敵人時，獲得 x% 傷害為額外火焰傷害（炙獵）。 */
    public static final String EXTRA_FIRE_VS_IGNITED = "damage.extra_fire_vs_ignited";
    /** FLAT：離你 7 公尺以上的敵人更多 x% 攻擊傷害，3.5 公尺以內沒有加成，中間照距離（遠穿）。 */
    public static final String DAMAGE_FAR = "damage.far";
    /** FLAT：最近用過的技能每有一種不同的元素（火、冰、電），元素傷害更多 x%（元湧）。 */
    public static final String ELEMENTAL_PER_RECENT_TYPE = "damage.elemental_per_recent_type";
    /** FLAT：每移動 1 公尺累積 2 點電荷（最多 30），放的時候用掉，每點獲得 1% 傷害為額外閃電傷害（引雷）。 */
    public static final String VOLT = "volt.max";
    /** INCREASED：對被定住（凍結、重暈、定身、電擊）的敵人的暴擊率（釘地 II）。 */
    public static final String CRIT_VS_IMMOBILISED = "crit.vs_immobilised";

    // ---- 斷命 ----

    /** FLAG：只對稀有與傳奇（含 Boss）的敵人斷命（斷命 I／II）。 */
    public static final String FLAG_CULLING_RARE = "flag.culling_rare";
    /** FLAT：斷命打死敵人後 20 秒內，斷命門檻增加 x%（斷命 II）。 */
    public static final String CULL_THRESHOLD_ON_CULL = "culling.threshold_on_cull";
    /** INCREASED：斷命的門檻（給你自己的增益）。 */
    public static final String CULL_THRESHOLD = "culling.threshold";

    // ---- 定身累積（釘地）----

    /** FLAG：物理傷害累積定身（照暈眩累積：「暈眩累積」的加成改成加在定身累積上），而且不能造成重暈累積。 */
    public static final String FLAG_PIN_BUILDUP = "flag.pin_buildup";
    /** MORE：定身累積（釘地 III：更少 60%）。 */
    public static final String PIN_BUILDUP = "pin.buildup";
    /** MORE：定身持續時間（釘地 III：更多 30%）。 */
    public static final String PIN_DURATION = "pin.duration";

    // ---- 持續傷害 ----

    /** MORE：非異常的持續傷害（技能留下的地面、混沌減益…；急蝕）。 */
    public static final String DAMAGE_SKILL_DOT = "damage.skill_dot";
    /** MORE：非異常的持續傷害，持續時間不到 1 秒時（急蝕 III）。 */
    public static final String DAMAGE_SKILL_DOT_SHORT = "damage.skill_dot_short";
    /** MORE：技能與它造成的傷害型異常（點燃、流血、中毒）的持續時間（急蝕）。 */
    public static final String SKILL_AND_AILMENT_DURATION = "duration.skill_and_ailment";
    /** MORE：施加時目標是滿血，非異常的持續傷害更多（速蝕）。 */
    public static final String DOT_VS_FULL_LIFE = "damage.skill_dot_vs_full_life";

    // ---- 異常 ----

    /** FLAT：敵人身上你的中毒可以多幾層同時生效（層瘴；跟異常模組的 poison.stacks 是同一個鍵）。 */
    public static final String POISON_STACKS = "poison.stacks";
    /** MORE：對流血中的敵人造成的中毒傷害（淬毒 III）。 */
    public static final String POISON_VS_BLEEDING = "poison.magnitude_vs_bleeding";
    /** MORE：對凍結中的敵人造成的點燃傷害（寒焚）；FLAT 寫在 IGNITE_VS_FROZEN_UNIQUE：對凍結的傳奇再更多。 */
    public static final String IGNITE_VS_FROZEN = "ignite.magnitude_vs_frozen";
    public static final String IGNITE_VS_FROZEN_UNIQUE = "ignite.magnitude_vs_frozen_unique";
    /** FLAG：點燃敵人時用掉牠身上的凍結（寒焚）。 */
    public static final String FLAG_CONSUME_FREEZE_ON_IGNITE = "flag.consume_freeze_on_ignite";
    /** FLAT：中毒也破甲，等於那層中毒總傷害的 x%（鏽甲）。 */
    public static final String POISON_BREAKS_ARMOUR = "armour.break_from_poison";
    /** FLAT：擊中被你凍結的敵人時破甲，等於物理傷害的 x%（霜裂甲）。 */
    public static final String BREAK_VS_FROZEN = "armour.break_vs_frozen";
    /** FLAT：被這個印記標記的敵人被你擊中時破甲，等於物理傷害的 x%（烙印深）。 */
    public static final String MARK_BREAK = "mark.armour_break";
    /** FLAT：每 100% 敵人異常門檻的混沌傷害有 x% 機率凋零，可以超過 100%（枯觸）。 */
    public static final String WITHER_PER_THRESHOLD = "withered.per_threshold";
    /** FLAT：凋零持續幾秒（枯觸）。 */
    public static final String WITHER_SECONDS = "withered.seconds";

    // ---- 投射物 ----

    /** FLAG：投射物穿透點燃中的敵人（穿燼）。 */
    public static final String PIERCE_IGNITED = "projectile.pierce_ignited";
    /** FLAG：投射物打中凍結的敵人時分裂（霜岔）。 */
    public static final String FORK_FROZEN = "projectile.fork_frozen";
    /** FLAG：投射物第一次打中感電的敵人一定連鎖（躍霆）。 */
    public static final String CHAIN_SHOCKED = "projectile.chain_shocked";

    // ---- 詛咒、印記 ----

    /** MORE：詛咒落地之後要等多久才生效（捷咒：更少 50%）。 */
    public static final String CURSE_DELAY = "curse.delay";
    /** FLAG：印記第一次觸發時不會用掉（久印）。 */
    public static final String FLAG_MARK_ETERNAL = "flag.mark_eternal";

    // ---- 召喚物、圖騰 ----

    /** FLAT：你每有一種不同的復活型召喚物，被輔助的召喚物更多 x% 傷害（群鋒）。 */
    public static final String MUSTER = "minion.muster";
    /** FLAT：召喚物每秒受到最大生命 x% 的火焰傷害，並點燃身邊的敵人，像造成最大生命 x% 的基礎火焰傷害（燃僕）。 */
    public static final String INFERNAL_LEGION = "minion.infernal_legion";
    /** FLAT：燃僕點燃的半徑（公尺）。 */
    public static final String INFERNAL_LEGION_RADIUS = "minion.infernal_legion_radius";
    /** INCREASED：放圖騰的速度（疾樁）。 */
    public static final String TOTEM_PLACEMENT_SPEED = "totem.placement_speed";

    // ---- 觸發、祭品 ----

    /** FLAT：被輔助的技能觸發出來的技能回復 0～x% 的消耗（骰運）。 */
    public static final String FLUKE_REFUND = "trigger_cost.refund";
    /** FLAG：祭品多用掉一隻骷髏召喚物；有用到才有 30% 增益效果與 30% 更多傷害（骸舞）。 */
    public static final String FLAG_EXTRA_SKELETON = "flag.offering_extra_skeleton";
    /** FLAT：放的時候獻祭 x% 最大生命（捐命）。 */
    public static final String SACRIFICE_LIFE = "cost.sacrifice_life";

    /** FLAG：擊中時用掉敵人身上的點燃、凍結、感電，觸發元素崩解（元崩）。 */
    public static final String FLAG_ELEMENTAL_DISCHARGE = "flag.elemental_discharge";

    // ---- 戰吼 ----

    /** INCREASED／MORE：戰吼的速度（使用時間 = 0.8 秒 ÷ 戰吼速度；不吃施法速度）。 */
    public static final String WARCRY_SPEED = "warcry.speed";
    /** MORE：戰吼算到的怪物強度（震嘯：更少 50%）。 */
    public static final String WARCRY_POWER = "warcry.power";
    /** FLAT：冷卻中可以花 x 點怒氣跳過冷卻，而不是一顆磐星（怒嘯）。 */
    public static final String WARCRY_RAGE_BYPASS = "warcry.rage_bypass";
    /** FLAT：被強化的攻擊有 x% 機率不算進強化次數（不竭嘯）。 */
    public static final String WARCRY_TIRELESS = "warcry.tireless";
    /** FLAT：戰吼回聲幾次（震嘯：2 次，每次 1.3 秒，範圍每次更多 100%；要先移動 10 公尺才能用）。 */
    public static final String WARCRY_ECHOES = "warcry.echoes";
    /** FLAT：每 5 點怪物強度給 x 點怒氣（聚怒嘯）。 */
    public static final String WARCRY_RAGE_PER_5_POWER = "warcry.rage_per_5_power";
    /** FLAG：用掉範圍內敵人的完全破甲，觸發物理爆炸（等於破掉的護甲 40%）；這個戰吼不能破甲（碎甲吼）。 */
    public static final String FLAG_BATTERSHOUT = "flag.battershout";
    /** FLAG：範圍內每隻敵人觸發扭曲契約（腐化之血）；每最近用過一次，消耗多 10% 最大生命（最多 30%）（契血嘯）。 */
    public static final String FLAG_PAQUATE = "flag.paquate";
    /** FLAT：戰吼炸掉範圍內的冰晶；炸掉至少 5 個時多強化 x 次攻擊（碎晶嘯）。 */
    public static final String WARCRY_CRYSTAL_EMPOWERS = "warcry.crystal_empowers";
    /** FLAT：要 x 點榮耀才能用；你花在技能上的生命有 5% 變成榮耀（澤嘯）。 */
    public static final String GLORY_COST = "glory.cost";
    /** FLAT：凍結敵人時，每 2 點怒氣讓你和隊友 15 秒內獲得 1% 傷害為額外冰冷傷害（霜仇嘯）。 */
    public static final String SHATTER_FANG = "freeze.shatter_fang";
    /** FLAT：凍結敵人時，x 秒內你獲得 y% 傷害為額外冰冷傷害（冰嚙；value 是 y，tags 第一個是秒數）。 */
    public static final String ICE_BITE = "freeze.ice_bite";
    /** FLAT：冰嚙的秒數。 */
    public static final String ICE_BITE_SECONDS = "freeze.ice_bite_seconds";
    /** FLAG：被強化的技能有斷命（決嘯）。 */
    public static final String FLAG_EMPOWERED_CULL = "flag.empowered_cull";
    /** FLAG：被強化的攻擊消耗更少 30%、命中更多 30%（嘯準）。 */
    public static final String FLAG_UNERRING = "flag.unerring_power";
}
