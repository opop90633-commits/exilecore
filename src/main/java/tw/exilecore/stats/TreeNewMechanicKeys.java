package tw.exilecore.stats;

/**
 * 2026-10-26：PoE2 0.5 天賦樹「新機制」類天賦用的屬性鍵（統治者增益、易爆、切割、傀儡大師、秘術增強、先祖強化、衝刺、
 * 圖騰、招架、餘震、護符、殘影、戰旗、重暈、釘住、凋零、詛咒的阻礙、存在範圍內的敵人、身體護甲的本地效果…）。
 * 機制本身寫在 {@code tw.exilecore.skill.TreeMechanics}；不放進 {@link StatKeys}，方便合併。
 */
public final class TreeNewMechanicKeys {

    private TreeNewMechanicKeys() {
    }

    // ---- 統治者（PoE2 Archon）：被觸發的強化狀態，預設 10 秒，失去後 20 秒恢復期內不能再獲得 ----
    /** INCREASED：你身上的統治者增益效果。 */
    public static final String ARCHON_EFFECT = "archon.effect";
    /** INCREASED：統治者增益持續時間。 */
    public static final String ARCHON_DURATION = "archon.duration";
    /** INCREASED：統治者恢復期結束速度（正的 = 更快；恢復期 ÷ (1 + x)）。 */
    public static final String ARCHON_RECOVERY = "archon.recovery_speed";
    /** FLAT %：使用指令技能時獲得不死統治者的機率。 */
    public static final String ARCHON_UNDEATH_ON_COMMAND = "archon.undeath_on_command";
    /** FLAT（旗標）：累計消耗 100% 最大魔力後獲得元素統治者。 */
    public static final String ARCHON_ELEMENTAL_ON_MANA = "archon.elemental_on_mana";
    /** FLAT（旗標）：能量護盾開始回充時獲得元素統治者。 */
    public static final String ARCHON_ELEMENTAL_ON_RECHARGE = "archon.elemental_on_recharge";
    /** 統治者增益另外給的：全元素抗性（FLAT）、移動速度、暴擊率、暴擊加成（INCREASED）。 */
    public static final String ARCHON_GRANT_RESIST = "archon.grant.resist";
    public static final String ARCHON_GRANT_MOVEMENT = "archon.grant.movement";
    public static final String ARCHON_GRANT_CRIT_CHANCE = "archon.grant.crit_chance";
    public static final String ARCHON_GRANT_CRIT_BONUS = "archon.grant.crit_bonus";
    /** 旗標：失去統治者增益後沒有恢復期。 */
    public static final String FLAG_ARCHON_NO_RECOVERY = "flag.archon_no_recovery";

    // ---- 易爆（PoE2 Volatility）：層數，4 秒沒有再獲得就爆炸，每層對你造成 100 物理傷害，之後 10 秒易爆之力 ----
    /** FLAT %：擊殺時獲得 1 層易爆的機率。 */
    public static final String VOLATILITY_ON_KILL = "volatility.on_kill";
    /** INCREASED：易爆爆炸的延遲。 */
    public static final String VOLATILITY_DELAY = "volatility.delay";
    /** FLAT：最大易爆改成這個數（預設 200）。 */
    public static final String VOLATILITY_MAX = "volatility.max";
    /** FLAT：易爆之力每層另外給增加 x% 暴擊率。 */
    public static final String VOLATILITY_POWER_CRIT = "volatility.power_crit";
    /** FLAT %：你身上的易爆爆炸時重新獲得等量易爆的機率。 */
    public static final String VOLATILITY_REFRESH = "volatility.refresh";
    /** FLAT：友方會復活的常駐召喚物被擊殺時獲得幾層易爆。 */
    public static final String VOLATILITY_ON_MINION_DEATH = "volatility.on_minion_death";
    /** FLAT：造成元素異常時獲得幾層易爆。 */
    public static final String VOLATILITY_ON_ELEMENTAL_AILMENT = "volatility.on_elemental_ailment";
    /** 旗標：易爆不會對你造成傷害。 */
    public static final String FLAG_VOLATILITY_IMMUNE = "flag.volatility_immune";

    // ---- 切割（PoE2 Incision）與加劇流血 ----
    /** FLAT %：擊中時給目標 1 層切割的機率（[attack]）。 */
    public static final String INCISION_CHANCE = "cut_wound.chance";
    /** FLAT %：擊中時給目標 10 層切割的機率（[attack]）。 */
    public static final String INCISION_CHANCE_TEN = "cut_wound.chance_ten";
    /** INCREASED：你造成的切割效果（每層 10% 被流血的機率）。 */
    public static final String INCISION_EFFECT = "cut_wound.effect";
    /** FLAT：被你切割的敵人每層受到的物理傷害增加 x%。 */
    public static final String INCISION_PHYSICAL_TAKEN = "cut_wound.physical_taken";
    /** FLAT %：暴擊時加劇目標身上你的流血的機率（[attack]）。 */
    public static final String AGGRAVATE_ON_CRIT = "bleed.aggravate_crit";
    /** FLAT（毫秒）：擊中時加劇目標身上存在超過這麼久的你的流血（[attack]）。 */
    public static final String AGGRAVATE_OLDER_THAN = "bleed.aggravate_older";

    // ---- 傀儡大師（PoE2 Puppet Master）：使用指令技能時獲得的層數增益，每層各自 8 秒，最多 5 層 ----
    /** FLAT %（可超過 100%）：使用指令技能時獲得 1 層傀儡大師的機率。 */
    public static final String PUPPET_CHANCE = "puppet.chance";
    public static final String PUPPET_EFFECT = "puppet.effect";
    public static final String PUPPET_MAX = "puppet.max";
    public static final String PUPPET_DURATION = "puppet.duration";

    // ---- 秘術增強（PoE2 Arcane Surge）：增加 15% 施法速度、魔力回復更多 20% ----
    /** INCREASED：你身上的秘術增強效果。 */
    public static final String ARCANE_SURGE_EFFECT = "surging_chant.effect";
    /** 旗標：秘術增強的「魔力回復更多」改成生命回復。 */
    public static final String FLAG_ARCANE_SURGE_LIFE = "flag.arcane_surge_life";
    /** 旗標：一直有秘術增強。 */
    public static final String FLAG_ARCANE_SURGE_ALWAYS = "flag.arcane_surge_always";
    /** FLAT %：被輔助的技能累計消耗最大魔力的 x% 後獲得 10 秒秘術增強（湧詠輔助）。 */
    public static final String ARCANE_SURGE_ON_SPEND = "surging_chant.on_spend";

    // ---- 先祖強化（PoE2 Ancestral Boost）：猛擊更多 30% 傷害、範圍增加 25%；打擊多打 2 個目標；算強化攻擊 ----
    /** INCREASED：先祖強化攻擊的範圍。 */
    public static final String ANCESTRAL_AREA = "ancestral.area";
    /** FLAT（秒）：重暈稀有或傳奇敵人後這麼多秒內，下一次攻擊先祖強化。 */
    public static final String ANCESTRAL_AFTER_HEAVY_STUN = "ancestral.after_heavy_stun";
    /** 旗標：你親自使用的猛擊每第二次先祖強化。 */
    public static final String FLAG_ANCESTRAL_EVERY_OTHER_SLAM = "flag.ancestral_every_other_slam";
    /** 標籤：先祖強化的那一下。 */
    public static final String TAG_ANCESTRAL = TreeEffectKeys.ANCESTRAL_BOOST; // 2026-10-26 合併：跟英靈的先祖強化同一個標籤

    // ---- 衝刺（翻滾後按住翻滾鍵）----
    /** INCREASED：衝刺時的移動速度。 */
    public static final String SPRINT_MOVEMENT = "sprint.movement";

    // ---- 圖騰 ----
    /** FLAT %：最近（4 秒內）放過圖騰時，增加的攻擊與施法速度。 */
    public static final String TOTEM_RECENT_SPEED = "totem.recent_speed";
    /** FLAT %：你的圖騰受到的傷害的 x% 補償為你的生命。 */
    public static final String TOTEM_RECOUP = "totem.recoup";
    /** FLAT %：每根圖騰讓存在範圍內的敵人受到的傷害增加 x%。 */
    public static final String TOTEM_PRESENCE_TAKEN = "totem.presence_taken";

    // ---- 招架 ----
    /** FLAT %：招架近戰擊中後，下一次遠程攻擊增加的傷害。 */
    public static final String PARRY_NEXT_RANGED = "parry.next_ranged";
    /** FLAT %：招架投射物擊中後，下一次近戰攻擊增加的傷害。 */
    public static final String PARRY_NEXT_MELEE = "parry.next_melee";

    // ---- 餘震 ----
    /** FLAT %：你親自使用的猛擊多造成 1 次餘震的機率（[mace]、[shapeshift] 限定的帶標籤）。 */
    public static final String AFTERSHOCK_CHANCE = "aftershock.chance";
    /** INCREASED：你親自使用的猛擊的餘震範圍。 */
    public static final String AFTERSHOCK_AREA = "aftershock.area";

    // ---- 護符 ----
    /** FLAT %：護符觸發時再觸發另一個護符（不消耗充能）的機率。 */
    public static final String CHARM_CHAIN = "charm.chain_chance";
    /** FLAT %：護符觸發時回復最大魔力的 x%。 */
    public static final String CHARM_MANA_ON_USE = "charm.mana_on_use";

    // ---- 殘影、碎地 ----
    /** INCREASED：你造的殘影的效果。 */
    public static final String REMNANT_EFFECT = "remnant.effect";
    /** FLAT %：在你造的碎地裡的敵人受到的傷害增加 x%。 */
    public static final String JAGGED_TAKEN = "jagged.taken";

    // ---- 戰旗、榮耀 ----
    /** FLAT：戰旗也給受影響者 +x% 全元素抗性。 */
    public static final String BANNER_RESIST = "banner.resist";
    /** FLAT %：技能不消耗榮耀的機率。 */
    public static final String GLORY_KEEP = "glory.keep_chance";
    /** INCREASED：榮耀獲得。 */
    public static final String GLORY_GAIN = "glory.gain";
    /** FLAT %：用掉榮耀放戰旗後，回復那支戰旗需要的榮耀的 x%。 */
    public static final String BANNER_GLORY_REFUND = "banner.glory_refund";

    // ---- 重暈、釘住、眩暈 ----
    /** FLAT %：重暈稀有或傳奇敵人時回復最大生命的 x%。 */
    public static final String HEAVY_STUN_LIFE = "stun.heavy_rare_life";
    /** FLAT：重暈稀有或傳奇敵人時獲得幾顆磐星。 */
    public static final String HEAVY_STUN_ENDURANCE = "stun.heavy_rare_endurance";
    /** FLAT %：重暈敵人時破壞牠 x% 的護甲。 */
    public static final String HEAVY_STUN_ARMOUR_BREAK = "stun.heavy_armour_break";
    /** 旗標：擊中被重暈的敵人有撲殺。 */
    public static final String FLAG_HEAVY_STUN_CULL = "flag.heavy_stun_cull";
    /** FLAT %：舉盾格擋擊中時讓攻擊者眩暈的機率。 */
    public static final String DAZE_ON_SHIELD_BLOCK = "daze.on_shield_block";
    /** 旗標：物理法術的暴擊也累積釘住。 */
    public static final String FLAG_SPELL_CRIT_PIN = "flag.spell_crit_pin";
    /** FLAT %：釘住敵人時破壞牠 x% 的護甲。 */
    public static final String PIN_ARMOUR_BREAK = "pin.armour_break";
    /** FLAT（秒）：敵人解除釘住後癱瘓這麼多秒。 */
    public static final String PIN_MAIM_AFTER = "pin.maim_after";

    // ---- 凋零、阻礙、致盲、曝露 ----
    /** FLAT %：召喚物擊中時給 1 層凋零的機率。 */
    public static final String MINION_WITHER_CHANCE = "withered.minion_chance";
    /** FLAT：被凋零的敵人每層造成的傷害減少 x%。 */
    public static final String WITHERED_WEAKEN = "withered.weaken";
    /** FLAT %：被你詛咒的敵人被阻礙，移動速度減少 x%。 */
    public static final String CURSE_HINDER = "curse.hinder";
    /** 旗標：被你標記的敵人附近的敵人被致盲。 */
    public static final String FLAG_MARK_BLIND_NEARBY = "flag.mark_blind_nearby";

    // ---- 夥伴 ----
    /** FLAT %：夥伴擊殺時獲得疾速的機率。 */
    public static final String COMPANION_ONSLAUGHT = "companion.onslaught_on_kill";
    /** 旗標：夥伴獲得你的力量。 */
    public static final String FLAG_COMPANION_STRENGTH = "flag.companion_strength";
    /** 旗標：夥伴傷害的增加與減少也套用在你身上。 */
    public static final String FLAG_COMPANION_DAMAGE_TO_YOU = "flag.companion_damage_to_you";
    /** 旗標：你偷取生命時夥伴也偷取同樣多。 */
    public static final String FLAG_COMPANION_LEECH = "flag.companion_leech";
    /** 旗標：生命藥劑的回復也給夥伴。 */
    public static final String FLAG_COMPANION_FLASK = "flag.companion_flask";
    /** FLAT %：擊中傷害的 x% 先由你的夥伴的生命承受。 */
    public static final String COMPANION_TAKES = "companion.takes_damage";

    // ---- 存在範圍內的敵人 ----
    /** INCREASED（負的 = 降低）：存在範圍內敵人的生命回復。 */
    public static final String PRESENCE_ENEMY_REGEN = "presence.enemy_regen";
    /** INCREASED（負的 = 降低）：存在範圍內敵人的冷卻回復速度。 */
    public static final String PRESENCE_ENEMY_COOLDOWN = "presence.enemy_cooldown";

    // ---- 擊殺、怒氣、其他 ----
    /** FLAT %：用帶這個標籤的技能擊殺時獲得疾速的機率（[axe]）。 */
    public static final String ONSLAUGHT_ON_KILL_TAGGED = "onslaught.on_kill_tagged";
    /** FLAT %：強化攻擊擊殺的敵人爆炸（牠最大生命 10% 的火焰傷害）的機率。 */
    public static final String EMPOWERED_KILL_EXPLODE = "kill.explode_empowered";
    /** FLAT：榴彈技能多幾次冷卻次數。 */
    public static final String GRENADE_COOLDOWN_USES = "grenade.cooldown_uses";
    /** FLAT %：從擊中獲得怒氣時，直接補到最大怒氣的機率。 */
    public static final String RAGE_FILL_CHANCE = "rage.fill_chance";
    /** FLAT：使用生命藥劑時獲得幾點怒氣。 */
    public static final String RAGE_ON_LIFE_FLASK = "rage.on_life_flask";
    /** FLAT：基礎暴擊率 +x%（帶標籤的技能，例如 [herald]）。 */
    public static final String CRIT_BASE_EXTRA = "crit.base_extra";
    /** FLAT %：每用掉 1 點連擊，這次增加 x% 傷害（最多 40%）。 */
    public static final String COMBO_CONSUMED_DAMAGE = "combo.consumed_damage";
    /** FLAT %：弩裝填時直接裝好的機率。 */
    public static final String CROSSBOW_INSTANT_RELOAD = "crossbow.instant_reload";
    /** FLAT %：榴彈再爆一次的機率。 */
    public static final String GRENADE_TWICE = "grenade.twice_chance";

    // ---- 裝備欄位 ----
    /** FLAT %（萬分比換成 %）：獲得等同身體護甲上護甲 x% 的物理荊棘傷害。 */
    public static final String BODY_ARMOUR_THORNS = "body.armour_thorns";
    /** INCREASED：裝備中的身體護甲的能量護盾、閃避值。 */
    public static final String BODY_ES = "body.es";
    public static final String BODY_EVASION = "body.evasion";
    /** FLAT %：獲得等同鞋子、手套、頭盔力量需求總和 x% 的護甲。 */
    public static final String ARMOUR_FROM_STR_REQUIREMENT = "armour.from_str_requirement";

    // ---- 怪物不會對玩家施加的減益（凋零、曝露、癱瘓）：天賦照樣生效（等於已經是這樣），旗標只給說明用 ----
    public static final String FLAG_EXPOSURE_IMMUNE = "flag.exposure_immune";
    public static final String FLAG_DEFLECT_NO_MAIM = "flag.deflect_no_maim";
    public static final String WITHERED_SELF_EFFECT = "withered.self_effect";
}
