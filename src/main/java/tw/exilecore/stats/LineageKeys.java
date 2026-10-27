package tw.exilecore.stats;

/**
 * 2026-10-27 照 PoE2 0.5 補上的血脈輔助寶石（data/supports_lineage.yml）用的屬性鍵（不放進 {@link StatKeys}，方便合併）。
 * <p>
 * 兩種前綴：{@code lineage.u.} 是不用擊中敵人也有作用的（{@link tw.exilecore.skill.SupportDefinition} 的「不會造成傷害的技能不行」不擋），
 * {@code lineage.h.} 是要擊中才有作用的。讀的地方：{@link tw.exilecore.skill.LineageRules}（建技能、能不能掛、說明文字）、
 * {@link tw.exilecore.skill.LineageMechanics}（放技能、擊中、擊殺、每 5 tick）與幾個共用的入口（異常、偷取、暴擊、圖騰、召喚物）。
 */
public final class LineageKeys {

    private LineageKeys() {
    }

    public static final String UTILITY = "lineage.u.";
    public static final String HIT = "lineage.h.";

    // ---- 建技能時（LineageRules / SkillBuild）----

    /** FLAT：被輔助的技能沒有其他輔助時 +x 等級（孤星）。 */
    public static final String LEVELS_IF_ALONE = "lineage.u.levels_if_alone";
    /** FLAT：剛好還有 1 顆其他輔助時 +x 等級（雙兆）。 */
    public static final String LEVELS_IF_ONE = "lineage.u.levels_if_one";
    /** FLAT：剛好還有 2 顆其他輔助時 +x 等級（三兆）。 */
    public static final String LEVELS_IF_TWO = "lineage.u.levels_if_two";
    /** FLAT：被輔助的技能品質 +x%（願晶）。 */
    public static final String QUALITY = "lineage.u.quality";
    /** MORE：連鎖次數（倍鏈）。 */
    public static final String CHAINS_MORE = "lineage.u.chains_more";
    /** FLAT：施法速度的增加／減少有 x% 也套用在投射物速度（疾詠）。 */
    public static final String CAST_TO_PROJECTILE_SPEED = "lineage.u.cast_to_projectile_speed";
    /** FLAT：冷卻可以多存幾次（裂潮、星律）。 */
    public static final String COOLDOWN_USES = "lineage.u.cooldown_uses";
    /** FLAT：牆改成沿著一道往前的裂縫立起來（裂垣）；INCREASED 寫在 {@link #WALL_LENGTH}。 */
    public static final String WALL_FISSURE = "lineage.u.wall_fissure";
    /** INCREASED：牆的長度（裂垣）。 */
    public static final String WALL_LENGTH = "lineage.u.wall_length";
    /** FLAT：裂地技能多幾道裂縫（狂裂）。 */
    public static final String EXTRA_FISSURES = "lineage.u.extra_fissures";
    /** FLAT：光環改成保留生命：每 1 點基礎精魂保留 x/100 % 最大生命（血契）。 */
    public static final String LIFE_RESERVE = "lineage.u.life_reserve";
    /** MORE：要的榮耀（旗熾）。 */
    public static final String GLORY_REQUIRED = "lineage.u.glory_required";
    /** FLAT：封印每過施法時間的 x% 蓄一個（萬封）。 */
    public static final String SEAL_CAST_PERCENT = "lineage.u.seal_cast_percent";
    /** FLAT：解封的重複傷害更少 x%（萬封；沒有的話照原本的少一半）。 */
    public static final String REPEAT_LESS = "lineage.u.repeat_less";

    // ---- 放技能時（LineageMechanics.prepare／used／afterCast）----

    /** FLAT：每 20 點生命消耗獲得 x% 傷害為額外物理傷害（最多 40%；血滲）。 */
    public static final String PHYS_PER_LIFE_COST = "lineage.u.phys_per_life_cost";
    /** FLAT：放的時候失去 x% 最大生命、魔力、能量護盾（焦血）。 */
    public static final String LOSE_ON_USE = "lineage.u.lose_on_use";
    /** FLAT：放的時候每 5 點怒氣失去 x% 最大生命（狂伐）。 */
    public static final String LIFE_PER_5_RAGE = "lineage.u.life_per_5_rage";
    /** FLAT：每 5 點怒氣增加 x% 攻擊速度（狂伐）。 */
    public static final String SPEED_PER_5_RAGE = "lineage.u.speed_per_5_rage";
    /** FLAT：每放一次獲得一層碎星，每層 x% 機率多射 8 發環射（碎星）。 */
    public static final String WILDSHARD = "lineage.u.wildshard";
    /** FLAT：連擊（用其他近戰攻擊打中時累積，最多 10），放的時候用掉，每點增加 x% 範圍（積勢）。 */
    public static final String COMBO_AREA = "lineage.u.combo_area";
    /** FLAT：格擋 2 次蓄 1 個封印（最多 x 個），放的時候每個封印回響一次（雪封）。 */
    public static final String BLOCK_SEALS = "lineage.u.block_seals";
    /** FLAT：法術回響 x 次（三迴）。 */
    public static final String ECHOES = "lineage.u.echoes";
    /** INCREASED：回響的範圍（三迴）。 */
    public static final String ECHO_AREA = "lineage.u.echo_area";
    /** FLAT：每放一次留下一個幻身（最多 x 個，5 秒）；到上限時全部一起放這個法術（傷害更少 50%；幻分）。 */
    public static final String MIMIC = "lineage.u.mimic";
    /** MORE：法術在祭柱的位置也放一次，那一次的傷害（祭脈）。 */
    public static final String OFFERING_CASCADE = "lineage.u.offering_cascade";
    /** FLAT：觸發出來的技能範圍隨機在更少 40% 到更多 50% 之間（骰禍）。 */
    public static final String RANDOM_AREA = "lineage.u.random_area";
    /** FLAT：放的時候獲得滿溢聖杯，每 1 秒冷卻 x 秒（聖杯）。 */
    public static final String CHALICE = "lineage.u.chalice";
    /** FLAT：魔力藥劑生效時魔力消耗效率增加 x%（聖杯）。 */
    public static final String MANA_FLASK_EFFICIENCY = "lineage.u.mana_flask_efficiency";
    /** FLAT：再額外消耗 x% 最大魔力，每次附加這筆消耗 15% 的物理與冰冷傷害（傲寒）。 */
    public static final String HUBRIS = "lineage.u.hubris";
    /** FLAT：指令技能用過時，你與聽命的召喚物獲得不潔之力 x 秒（縛魔）。 */
    public static final String UNHOLY_ON_COMMAND = "lineage.u.unholy_on_command";
    /** FLAT：完美時機時獲得一層律勁（最多 5 層），每層更多 x% 傷害、完美時機的區間更短 30%（律勁）。 */
    public static final String RHYTHM = "lineage.u.rhythm";

    // ---- 詛咒、地面（SkillExecutor.applyCurseAt）----

    /** FLAG：詛咒不受詛咒上限限制，但也會反射到你身上（魅咒）。 */
    public static final String CURSE_REFLECT = "lineage.u.curse_reflect";
    /** FLAT：詛咒最後 x% 的時間讓敵人不能造成傷害（寂咒）。 */
    public static final String PACIFY = "lineage.u.pacify";
    /** FLAT：詛咒改成留下詛咒地面，裡面每 0.25 秒生出一隻巫蟾（每塊最多 x 隻）撲向敵人炸開（蟾咒）。 */
    public static final String TOAD_GROUND = "lineage.u.toad_ground";
    /** FLAT：詛咒改成留下詛咒地面，撐 x 秒後落下湮滅光柱（咒光）。 */
    public static final String ANNIHILATION_GROUND = "lineage.u.annihilation_ground";

    // ---- 持續中的（光環、召喚物、圖騰、每 5 tick）----

    /** FLAT：光環開著時，每 6 秒讓身邊的隊友獲得你最大生命 x% 的守護（守頌）。 */
    public static final String ALLY_GUARD = "lineage.u.ally_guard";
    /** FLAT：光環開著時，走進你身邊的敵人被恫嚇 x 秒（威宣）。 */
    public static final String INTIMIDATE_PRESENCE = "lineage.u.intimidate_presence";
    /** FLAT：光環開著時，敵人擊中你有 x% 機率把你的抗性當成 90%（熾心）。 */
    public static final String SERAPH = "lineage.u.seraph";
    /** FLAT：召喚物死掉時 x% 機率給你一個怪物詞綴 20 秒（最多 3 個；淵賜）。 */
    public static final String ABYSSAL_ON_MINION_DEATH = "lineage.u.abyssal_on_minion_death";
    /** FLAG（tags [minion]）：召喚物不會受到傷害（憨魄）。 */
    public static final String MINION_UNDAMAGEABLE = "lineage.u.minion_undamageable";
    /** FLAT：夥伴致盲並點燃 2 格內的敵人（像造成牠最大生命 x% 的基礎火焰傷害；焰伴）。 */
    public static final String COMPANION_BLAZE = "lineage.u.companion_blaze";
    /** FLAT：你受到的擊中有 x% 先由夥伴承受，你補償其中 200% 為生命（伴償）。 */
    public static final String COMPANION_SHIELD = "lineage.u.companion_shield";
    /** FLAT：夥伴變巨大（生命、傷害更多 20%，體型增加 20%），每 10 點你的閃避、偏斜給牠 x 點（巨伴）。 */
    public static final String GIGANTIC = "lineage.u.gigantic";
    /** FLAT：召喚物生命降到 0 時不會馬上死，再撐 x 秒（殘戰）。 */
    public static final String UNDYING = "lineage.u.undying";
    /** FLAT：圖騰附近每有一根你的圖騰（4 格內，最多 5 根），更多 x% 傷害、增加 x/2% 範圍、每秒回復 x/10% 生命（林樁）。 */
    public static final String TOTEM_GROVE = "lineage.u.totem_grove";
    /** FLAT：圖騰倒下時炸開，造成它最大生命 x% 的物理傷害（樁爆）。 */
    public static final String TOTEM_EXPLODE = "lineage.u.totem_explode";
    /** FLAT：撿到殘骸時獲得一層卡塔之息（冷卻回復速度增加 x%，最多 8 層，各 10 秒；拾息）。 */
    public static final String REMNANT_CDR = "lineage.u.remnant_cdr";
    /** FLAT：舉盾時格擋過的話，其他近戰技能更多 x% 傷害（岳盾）。 */
    public static final String SHIELD_BLOCK_MORE = "lineage.u.shield_block_more";
    /** FLAT：舉盾時暈眩門檻更多 x%（岳盾）。 */
    public static final String SHIELD_STUN_THRESHOLD = "lineage.u.shield_stun_threshold";
    /** FLAT：翻滾時替被輔助的弩技能裝 x 發（連膛）。 */
    public static final String DODGE_LOAD = "lineage.u.dodge_load";
    /** FLAG：被輔助的弩技能不會自己裝填（連膛）。 */
    public static final String NO_RELOAD = "lineage.u.no_reload";
    /** FLAT：落地時引爆 3.5 格內的屍體（每具造成牠最大生命 x% 的火焰傷害；燼躍）。 */
    public static final String LANDING_DETONATE = "lineage.u.landing_detonate";

    // ---- 擊中（LineageMechanics.preHit／onHit／擊殺）----

    /** FLAT：敵人身上每層中毒，擊中更多 x%（最多 40%；毒慾）。 */
    public static final String PER_POISON = "lineage.h.per_poison";
    /** FLAG：點燃敵人時獲得元素執政（燼裁）。 */
    public static final String ARCHON_ON_IGNITE = "lineage.h.archon_on_ignite";
    /** FLAT：擊殺時 x% 機率替所有弩技能裝一發（彈勳）。 */
    public static final String LOAD_ON_KILL = "lineage.h.load_on_kill";
    /** FLAT：凍結或電擊敵人時破掉牠 x% 的護甲；完全破甲也讓牠受到的冰冷、閃電擊中傷害增加（凝破）。 */
    public static final String BREAK_ON_FREEZE = "lineage.h.break_on_freeze";
    /** FLAG：只能傷害低血（35% 以下）的敵人（掠獸）。 */
    public static final String LOW_LIFE_ONLY = "lineage.h.low_life_only";
    /** FLAT：打死稀有怪物時獲得牠兩個詞綴 x 秒（掠獸）。 */
    public static final String STEAL_TWO = "lineage.h.steal_two";
    /** FLAG：閃電傷害也累積電擊；不能感電（霆爆）。 */
    public static final String LIGHTNING_ELECTROCUTES = "lineage.h.lightning_electrocutes";
    /** FLAT：打死電擊中的敵人時炸開（牠最大生命 x% 的閃電傷害；霆爆，照技能等級變大）。 */
    public static final String VOLTAIC = "lineage.h.voltaic";
    /** FLAG：閃電傷害只擲最小值或最大值（極雷）。 */
    public static final String LIGHTNING_EXTREMES = "lineage.h.lightning_extremes";
    /** FLAG：混沌傷害也算進感電（霆蝕）、凍結（寂寒）、點燃（燼蝕）；物理傷害也算進凍結（傲寒）。 */
    public static final String CHAOS_SHOCKS = "lineage.h.chaos_shocks";
    public static final String CHAOS_FREEZES = "lineage.h.chaos_freezes";
    public static final String CHAOS_IGNITES = "lineage.h.chaos_ignites";
    public static final String PHYSICAL_FREEZES = "lineage.h.physical_freezes";
    /** FLAG：暴擊擲兩次（任一成功就暴擊，兩次都成功暴擊加成算兩次）；FLAT {@link #CRIT_CAP}：暴擊率最多 x%（雙鋒）。 */
    public static final String BIFURCATE = "lineage.h.bifurcate";
    public static final String CRIT_CAP = "lineage.h.crit_cap";
    /** FLAT：近戰擊中時獲得最大怒氣 x% 的怒氣（狂伐）。 */
    public static final String RAGE_ON_MELEE_HIT = "lineage.h.rage_on_melee_hit";
    /** FLAT：用掉敵人的凍結，更多 x% 傷害，觸發自然交換（霜換）。 */
    public static final String CONSUME_FREEZE = "lineage.h.consume_freeze";
    /** FLAG：魔力偷取照元素傷害算，不照物理（元汲）。 */
    public static final String MANA_LEECH_ELEMENTAL = "lineage.h.mana_leech_elemental";
    /** FLAT：每 5 點怒氣增加 x% 暴擊率；暴擊時失去全部怒氣（怒準）。 */
    public static final String CRIT_PER_5_RAGE = "lineage.h.crit_per_5_rage";
    /** FLAT：擊中時壓碎敵人 x 秒；FLAT {@link #CRUSHED_BREAK}：打壓碎中的敵人時破甲，等於物理傷害的 x%（碾盾）。 */
    public static final String CRUSH_ON_HIT = "lineage.h.crush_on_hit";
    public static final String CRUSHED_BREAK = "lineage.h.crushed_break";
    /** FLAT：每點怒氣讓中毒的傷害快 x%（怒毒）。 */
    public static final String POISON_FASTER_PER_RAGE = "lineage.h.poison_faster_per_rage";
    /** FLAT：最近 8 秒每用過一種有冷卻的技能，更多 x% 傷害（星律）。 */
    public static final String PER_COOLDOWN_SKILL = "lineage.h.per_cooldown_skill";
    /** FLAT：生命藥劑生效時更多 x% 傷害（聖杯）。 */
    public static final String LIFE_FLASK_MORE = "lineage.h.life_flask_more";
    /** FLAT：完全破甲時讓牠永久受到的物理傷害增加 x%（最多 20%；熔甲）。 */
    public static final String PHYS_TAKEN_ON_BREAK = "lineage.h.phys_taken_on_break";
    /** FLAT：混沌傷害也破甲，等於混沌傷害的 x%（蝕甲）。 */
    public static final String CHAOS_BREAKS = "lineage.h.chaos_breaks";
    /** FLAT：最近 15 秒每用過一種不同的指令技能，更多 x% 傷害（令福）。 */
    public static final String PER_COMMAND = "lineage.h.per_command";
    /** FLAT：對孤立的敵人（6 格內沒有其他敵人）擊中更多 x%（孤圍）。 */
    public static final String VS_ISOLATED = "lineage.h.vs_isolated";
}
