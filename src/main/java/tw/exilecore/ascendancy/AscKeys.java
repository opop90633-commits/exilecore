package tw.exilecore.ascendancy;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-26 照 PoE2 0.5：昇華天賦裡原本寫著「（尚未生效）」的機制，這次做出來用到的屬性鍵（效果在 {@link AscendancyEffects}）。
 * 這次之前這些天賦沒有 stats（說明行後面寫（尚未生效））。
 * 寫在昇華檔的 stats 裡（{@code asc.} 開頭都是 FLAT、不加標籤，除非下面註明），{@link StatLines#problem} 交給 {@link #problem} 檢查。
 */
public final class AscKeys {

    // ---- 鐵衛（Titan）----
    /** 親自使用的猛擊有 x% 機率多一次餘震（天崩地裂 25）。 */
    public static final String SLAM_AFTERSHOCK = "asc.slam_aftershock";
    /** 每第三次不產生裂隙的猛擊在前方與兩側各多 3 次餘震（劈山碎石）。 */
    public static final String MOUNTAIN_SPLITTER = "asc.mountain_splitter";
    /** 攜帶一個箱子：多 x 格背包（巨量承載 20）。 */
    public static final String CHEST_SLOTS = "asc.chest_slots";

    // ---- 狂嘯者（Warbringer）----
    /** 可以把敵人的護甲破壞到 0 以下（碎猛之擊）。 */
    public static final String NEGATIVE_ARMOUR = "asc.negative_armour";
    /** 每秒獲得一層玉石（最多 10 層，每層 1% 額外物理減免；玉石之護）。 */
    public static final String JADE = "asc.jade";
    /** 召喚圖騰時觸發先祖之魂（先祖之引）。 */
    public static final String ANCESTRAL_SPIRITS = "asc.ancestral_spirits";
    /** 擊中傷害有 x% 先從最近的圖騰生命扣（堅木之壘 20）。 */
    public static final String WOODEN_WALL = "asc.wooden_wall";
    /** 戰吼引爆屍體，造成屍體最大生命 x% 的物理傷害（戰爭之哮 25）。 */
    public static final String WARCRY_CORPSES = "asc.warcry_corpses";

    // ---- 鑄星（Smith of Kitava）----
    /** 火焰抗性的修飾器也給冰冷、閃電抗性，數值的 x%（添煤工 50）。 */
    public static final String COAL_STOKER = "asc.coal_stoker";
    /** 最大火焰抗性的修飾器也給最大冰冷、閃電抗性（烈焰鑄造）。 */
    public static final String FORGED_FLAME = "asc.forged_flame";
    /** 只能使用普通（白色）胸甲（鐵匠傑作）。 */
    public static final String NORMAL_CHEST = "asc.normal_chest";
    /** 護甲的 x% 也套用在混沌傷害（奇塔弗奉獻 100）。 */
    public static final String CHAOS_ARMOUR = "asc.chaos_armour";
    /** 榮耀產生（INCREASED；奇塔弗銘刻、堤防光環）。 */
    /** 2026-10-26 照 PoE2 天賦樹：有 x% 機率避免被擊中致死（AscendancyEffects#absorb）。 */
    public static final String AVOID_DEATH = "death.avoid_chance";
    public static final String GLORY_GENERATION = tw.exilecore.stats.TreeNewMechanicKeys.GLORY_GAIN; // 2026-10-26：跟天賦樹的榮耀獲得同一個（glory.gain）

    // ---- 斷咒獵人（Witchhunter）----
    /** 敵人的最大專注 = 最大生命的 x%，擊中破壞專注（女巫殺手 30）。 */
    public static final String WITCHBANE = "asc.witchbane";
    /** 依敵人損失的專注最多更多 x% 傷害（毫不留情 40）。 */
    public static final String NO_MERCY = "asc.no_mercy";
    /** 巫術護盾的屏障也吃物理與混沌（儀式淨身）。 */
    public static final String WARD_ALL = "asc.ward_all";
    /** 毀滅打擊：擊中滿血的敵人先扣掉 5～30% 生命（法官、陪審團和劊子手）。 */
    public static final String DECIMATING = "asc.decimating";

    // ---- 弩衛（Tactician）----
    /** 獲得等同護甲 x% 的偏斜值（為齒輪拋光 20）。 */
    public static final String DEFLECT_FROM_ARMOUR = "asc.deflect_from_armour";
    /** 閃避值的 x% 當作額外異常門檻（為齒輪拋光 100）。 */
    public static final String THRESHOLD_FROM_EVASION = "asc.threshold_from_evasion";
    /** 敵人依距離對你有命中懲罰（保持警覺，善用掩護）。 */
    public static final String ENEMY_ACCURACY_FALLOFF = "asc.enemy_accuracy_falloff";
    /** 圖騰只在你發射攻擊投射物時使用技能（砲彈準備好了！）。 */
    public static final String CANNONS_READY = "asc.cannons_ready";
    /** 圖騰提供堤防光環（戰術陣地）。 */
    public static final String EMBANKMENT = "asc.embankment";
    /** 存在範圍內的友軍獲得附加攻擊傷害 = 你主手武器傷害的 x%（看看我怎麼辦事的 25）。 */
    public static final String ALLY_WEAPON_DAMAGE = "asc.ally_weapon_damage";
    /** 鬥旗技能每秒獲得 x 榮耀（待價而沽 5）。 */
    public static final String BANNER_GLORY = "asc.banner_glory";
    /** 投射物傷害會累積釘身（位置正好）。 */
    public static final String PROJECTILE_PIN = "asc.projectile_pin";
    /** 被釘身的敵人無法進行動作（位置正好）。 */
    public static final String PIN_STOPS = "asc.pin_stops";

    // ---- 嵌晶（Gemling Legionnaire）----
    /** 裝備的屬性需求減少 x%（INCREASED，負數；減少能力值需求）。 */
    public static final String ATTRIBUTE_REQUIREMENT = "asc.attribute_requirement";
    /** 依鑲嵌數量最多的輔助寶石顏色獲得效果（鑲嵌的寶石）。 */
    public static final String GEM_STUDDED = "asc.gem_studded";
    /** 每個相連的紅／綠／藍輔助寶石：技能傷害／技能速度／暴擊率（整合效率）。 */
    public static final String INTEGRATED = "asc.integrated";
    /** 每鑲嵌 3 個紅／藍／綠輔助寶石 +1% 最大火焰／冰冷／閃電抗性（奇術注入）。 */
    public static final String THAUMATURGICAL = "asc.thaumaturgical";
    /** 屬性需求可以用你最高的屬性滿足（應變能力；我們的寶石沒有屬性需求，套用在裝備上）。 */
    public static final String ADAPTIVE = "asc.adaptive";
    /** 屬性提供的固有加成加倍（強化效果）。 */
    public static final String INHERENT_DOUBLE = "asc.inherent_double";
    /** 所有技能品質 +x%（技能寶石品質 2）。 */
    public static final String SKILL_QUALITY = "asc.skill_quality";
    /** 需求力量／敏捷／智慧的技能等級 +x（鑲嵌物 2）。 */
    public static final String LEVEL_STR = "asc.level_str";
    public static final String LEVEL_DEX = "asc.level_dex";
    public static final String LEVEL_INT = "asc.level_int";
    /** 寶石品質為技能提供額外效果（進階奇術）。 */
    public static final String ADVANCED_QUALITY = "asc.advanced_quality";

    // ---- 獵姬（Amazon）----
    /** 生命藥劑也回復魔力、魔力藥劑也回復生命（阿茲莫里之酒）。 */
    public static final String AZMERI = "asc.azmeri";
    /** 武器攻擊附加物理傷害 = 武器命中值的 x%（穿透 25）。 */
    public static final String ACCURACY_DAMAGE = "asc.accuracy_damage";
    /** 對稀有、傳奇揭露弱點；對有開放弱點的敵人更多 x% 傷害（獵食直覺 50）。 */
    public static final String WEAKNESS = "asc.weakness";
    /** 存在範圍內有開放弱點的敵人時：增加 20% 移動速度、40% 技能速度（專注擊殺）。 */
    public static final String FOR_THE_KILL = "asc.for_the_kill";

    // ---- 儀祭（Ritualist）----
    /** 對受血液沸騰影響的敵人更多 x% 傷害（腐化生命力 15）。 */
    public static final String BLOOD_BOIL_MORE = "asc.blood_boil_more";
    /** 護符充能不足時改消耗 x% 最大魔力（心靈護符 25）。 */
    public static final String CHARM_MANA = "asc.charm_mana";

    // ---- 星矢（Deadeye）----
    /** 消耗印記時施加 x 層暴擊弱點（靶心 10）。 */
    public static final String BULLSEYE = "asc.bullseye";
    /** 使用技能時獲得順風、被擊中時失去全部順風（集風）。 */
    public static final String TAILWIND = "asc.tailwind";
    /** 消耗怒星的效益有 x% 機率加倍（驚險追逐 50）。 */
    public static final String FRENZY_DOUBLE = "asc.frenzy_double";

    // ---- 御風（Pathfinder）----
    /** 衝刺時無法被重度暈眩（衝刺突擊）。 */
    public static final String SPRINT_STUN = "asc.sprint_stun";
    /** 原作「移動中使用技能的移動速度懲罰更少 x%」（衝刺突擊 30）。2026-10-27 照站長「放技能減速全部移除」：AscBuild 換成增加 x × 0.27% 移動速度（30 → 8%）；這次之前換成 movement.action_penalty 更少。 */
    public static final String MOVE_PENALTY = "asc.move_penalty";
    /** 生命全滿時不移除生命藥劑效果（耐久靈藥）。 */
    public static final String ENDURING_ELIXIRS = "asc.enduring_elixirs";
    /** 可以從女術者／戰士的起點配置天賦（之道）。 */
    public static final String PATH_SORCERESS = "asc.path_sorceress";
    public static final String PATH_WARRIOR = "asc.path_warrior";
    /** 屬性天賦可以改給傷害、防禦或消耗效率（旅者的智慧）。 */
    public static final String TRAVELLERS_WISDOM = "asc.travellers_wisdom";

    // ---- 喚雷（Invoker）----
    /** 近戰暴擊時觸發元素展現（……並驅散於長風之中）。 */
    public static final String EXPRESSION = "asc.expression";
    /** 感電敵人時 x% 機率產生感電地面（我即為雷鳴 25）。 */
    public static final String SHOCKED_GROUND = "asc.shocked_ground";
    /** 凍結敵人時產生冰緩地面（我即為暴雪）。 */
    public static final String CHILLED_GROUND = "asc.chilled_ground";
    /** 精魂改從胸甲的能量護盾、閃避算，不能從裝備獲得精魂（引我行恩典之途）。 */
    public static final String GRACE = "asc.grace";

    // ---- 虛步（Acolyte of Chayula）----
    /** 黑暗（INCREASED：最大黑暗；FLAT：基礎最大黑暗、每等級）。 */
    public static final String DARKNESS = "asc.darkness";
    public static final String DARKNESS_PER_LEVEL = "asc.darkness_per_level";
    /** 黑暗保留幾秒（靈魂昇華 5）。 */
    public static final String DARKNESS_SECONDS = "asc.darkness_seconds";
    /** 每 1% 混沌抗性增加 x% 最大黑暗（加深陰影 1）。 */
    public static final String DARKNESS_PER_CHAOS = "asc.darkness_per_chaos";
    /** 夏烏拉之炎的顏色（力量之選 1 紫、魔力之選 2 藍、生命之選 3 紅）。 */
    public static final String FLAME_COLOUR = "asc.flame_colour";
    /** 你產生的殘骸效果（INCREASED）。 */
    public static final String REMNANT_EFFECT = "asc.remnant_effect";
    /** 紛亂（收心）。 */
    public static final String UNRAVELLING = "asc.unravelling";

    // ---- 星焰（Stormweaver）----
    /** 法術暴擊時觸發元素風暴（暴風召喚者）。 */
    public static final String TEMPEST = "asc.tempest";
    /** 元素技能上限 +x（頻繁暴雨 2）。 */
    public static final String ELEMENTAL_LIMIT = "asc.elemental_limit";
    /** 目標可以同時受到你兩個冰緩／感電影響（狂風暴雪、二重打擊）。 */
    public static final String DOUBLE_CHILL = "asc.double_chill";
    public static final String DOUBLE_SHOCK = "asc.double_shock";
    /** 你的冰緩最多緩速 x%（狂風暴雪 35）。 */
    public static final String CHILL_CAP = "asc.chill_cap";
    /** 你造成的冰緩幅度更少 x%（狂風暴雪 25）。 */
    public static final String CHILL_LESS = "asc.chill_less";
    /** 你造成的感電幅度更少 x%（二重打擊 25）。 */
    public static final String SHOCK_LESS = "asc.shock_less";
    /** 每損失 10% 魔力，身上的秘術增強效果增加 x%（意志之力 20）。 */
    public static final String ARCANE_PER_MANA = "asc.arcane_per_mana";

    // ---- 法袍（Chronomancer）----
    /** 可層疊法術 x% 機率回響、可重複法術 x% 機率重複（循環往復 20）。 */
    public static final String ECHO = "asc.echo";
    public static final String REPEAT = "asc.repeat";
    /** 時間之沙（流沙沙漏）。 */
    public static final String SANDS = "asc.sands";
    /** 身上的增益效果消退速度減慢 x%（增益效果失效速度 10）。 */
    public static final String BUFF_EXPIRY = "asc.buff_expiry";

    // ---- 沙靈（Disciple of Varashta）----
    /** 非傳奇遺時珠寶的半徑增加 x%（巴雅地脈 40）。 */
    public static final String LEYLINES = "asc.leylines";
    /** 持用長杖時可以裝備法器；法器的加成減少 x%（力量之器 50）。 */
    public static final String STAFF_FOCUS = "asc.staff_focus";
    /** x% 的元素傷害先從魔力扣（瓦拉什塔的直覺 100）。 */
    public static final String ELEMENTAL_FROM_MANA = "asc.elemental_from_mana";
    /** 低能量護盾時更多 x% 能量護盾回充速度（第四教誨 40）。 */
    public static final String LOW_ES_RECHARGE = "asc.low_es_recharge";
    /** 計算物理減免時，當前能量護盾的 x% 加到護甲（神聖儀式 60）。 */
    public static final String ES_ARMOUR = "asc.es_armour";

    // ---- 疫喚（Infernalist）----
    /** 火祭協議：最大魔力換成兩倍的煉獄之炎、技能消耗改成獲得煉獄之炎。 */
    public static final String INFERNAL = "asc.infernal";
    /** 沸騰軀體：高煉獄之炎時施放法術獲得元素執政官。 */
    public static final String SEETHING = "asc.seething";
    /** 惡魔烈焰沒有上限（黑暗精通）。 */
    public static final String DEMONFLAME_UNCAPPED = "asc.demonflame_uncapped";
    /** 承受的傷害視為另一種（變形肉體、演化化身、熔化標誌）：asc.taken.來源.變成 = x%。 */
    public static final String TAKEN_AS = "asc.taken.";

    // ---- 血術（Blood Mage）----
    /** 被你詛咒的目標至少保留 x% 生命（血肉低語 15）。 */
    public static final String CURSE_RESERVE = "asc.curse_reserve";
    /** 被你詛咒的目標沒有生命再生（血肉低語）。 */
    public static final String CURSE_NO_REGEN = "asc.curse_no_regen";
    /** 血紅之潮。 */
    public static final String SANGUINE = "asc.sanguine";
    /** 你對被詛咒目標造成的流血加劇（鮮血荊棘）。 */
    public static final String CURSE_AGGRAVATE = "asc.curse_aggravate";
    /** 元素傷害也計入流血幅度（鮮血荊棘）。 */
    public static final String ELEMENTAL_BLEED = "asc.elemental_bleed";

    // ---- 骸主（Lich）----
    /** 承受的傷害有 x% 無視能量護盾（無魂型態 10）。 */
    public static final String ES_BYPASS = "asc.es_bypass";
    /** 沒有固有魔力再生（無魂型態）。 */
    public static final String NO_MANA_REGEN = "asc.no_mana_regen";
    /** 每秒再生等同 x% 最大生命的魔力（無魂型態 6）。 */
    public static final String MANA_REGEN_LIFE = "asc.mana_regen_life";
    /** 擁有能量護盾時生命無法改變（永生）。 */
    public static final String ETERNAL_LIFE = "asc.eternal_life";
    /** 最近消耗過銳星時增加 x% 最大能量護盾（30）。 */
    public static final String ES_POWER = "asc.es_power";
    /** 法術消耗 1 顆銳星，更多 x% 傷害（力量的代價 40）。 */
    public static final String PRICE_OF_POWER = "asc.price_of_power";
    /** 沒有能量護盾時更多 x% 技能魔力消耗（晶化護符 50）。 */
    public static final String NO_ES_COST = "asc.no_es_cost";
    /** 命匣（晶化護符）：可以鑲一顆非傳奇基礎珠寶，效果增加 100%。 */
    public static final String PHYLACTERY = "asc.phylactery";
    /** 施放法術時犧牲 x% 最大能量護盾（異能賦予 5）；完全支付的法術更多 30% 傷害。 */
    public static final String ELDRITCH = "asc.eldritch";
    /** 詛咒的敵人被擊殺時 x% 機率爆炸（割裂靈魂 33）。 */
    public static final String RUPTURE = "asc.rupture";
    /** 你施加的詛咒持續時間無限、可以額外施加 1 個詛咒（不斷爭吵）。 */
    public static final String CURSE_INFINITE = "asc.curse_infinite";
    public static final String CURSE_EXTRA = "asc.curse_extra";
    /** 非低魔力時你與存在範圍內的友軍擁有不潔之力；每秒失去 x% 最大魔力（靈能導體 5）。 */
    public static final String CONDUIT = "asc.conduit";
    /** 每 100 點最大魔力，你給予的不潔之力幅度增加 x%（黑化之心 4）。 */
    public static final String UNHOLY_PER_MANA = "asc.unholy_per_mana";

    // ---- 觀星（Oracle）----
    /** 圖騰生命降到 0 之後 x 秒才死（無名心木 6）。 */
    public static final String TOTEM_LINGER = "asc.totem_linger";
    /** 敵人對你的暴擊率、擊中傷害為不幸（微小損害）。 */
    public static final String LESSER_HARM = "asc.lesser_harm";
    /** 必然暴擊（強制結果）。 */
    public static final String INEVITABLE_CRIT = "asc.inevitable_crit";
    /** 當前魔力高於當前生命時，擊中傷害先從魔力扣（心宇和諧）。 */
    public static final String HARMONY = "asc.harmony";
    /** 踏上未曾走過的道路：可以配置觀星專屬的天賦（未見之道）。 */
    public static final String UNSEEN_PATH = "asc.unseen_path";
    /** 已配置基石天賦中半徑內的非基石天賦不用相連就能配（虛實交織）。 */
    public static final String ENTWINED = "asc.entwined";

    // ---- 獸巫（Shaman）----
    /** 獲得 x% 傷害為隨機一種元素的額外傷害（季節循環 10）。 */
    public static final String RANDOM_ELEMENT = "asc.random_element";
    /** 適應（反應性生長）：每個相符的適應更少 x% 該元素傷害。 */
    public static final String ADAPT = "asc.adapt";
    /** 更少 x% 承受的元素傷害（反應性生長 10）。 */
    public static final String ELEMENTAL_TAKEN_LESS = "asc.elemental_taken_less";
    /** 適應持續 x 秒、效果加倍（演化化身）。 */
    public static final String ADAPT_DURATION = "asc.adapt_duration";
    public static final String ADAPT_DOUBLE = "asc.adapt_double";
    /** 狂怒源泉：每秒再生 x% 最大怒氣、怒氣不自然流失、魔力再生的增減也套用、技能 +5 怒氣消耗。 */
    public static final String RAGE_REGEN = "asc.rage_regen";
    public static final String RAGE_COST = "asc.rage_cost";
    /** 每個空的護符欄位 +x 精魂（神聖流向 40）。 */
    public static final String SPIRIT_PER_EMPTY_CHARM = "asc.spirit_per_empty_charm";
    /** 符文的效果增加 x%（聖賢智慧：原作是羈絆詞綴，我們的符文沒有，平衡等效 50）。 */
    public static final String RUNE_EFFECT = "asc.rune_effect";
    /** 2026-10-27 照 PoE2（聖賢智慧 Wisdom of the Maji）：FLAT 1 = 嵌著的符文的羈絆詞綴生效（Runes.bondedModifiers）。這次之前用 {@link #RUNE_EFFECT}（符文效果增加 50%）等效。 */
    public static final String BONDED = "asc.bonded";

    // ---- 2026-10-27 照 PoE2 0.5 補上的三個昇華（效果在 AscPaths）----
    // 靈狩者（Spirit Walker）
    /** 裝備上每個空的符文孔（還沒有神像，當作神像）夥伴增加 x% 傷害（偶像崇敬 10）。 */
    public static final String IDOL_COMPANION_DAMAGE = "asc.idol_companion_damage";
    /** 裝備上每個空的符文孔增加 x% 技能的保留效率（偶像崇敬 2）。 */
    public static final String IDOL_RESERVATION = "asc.idol_reservation";
    /** 裝備上每顆嵌著的符文（不是神像的嵌入物）x% 全部元素抗性（偶像崇敬 -4）。 */
    public static final String AUGMENT_RESIST = "asc.augment_resist";
    /** 靈現之靈的上限（每移動 10 格 1 個；攻擊時用掉全部觸發靈現奔竄；靈現奔竄 3）。 */
    public static final String VIVID_WISPS = "asc.vivid_wisps";
    /** 靈鹿每躍起一次傷害、感電幅度更多 x%；奔竄結束時獲得 1 個靈現之靈（莫里根的指引 20）。 */
    public static final String STAG_GUIDANCE = "asc.stag_guidance";
    /** 夥伴在存在範圍內時增加 x% 傷害（共享盟友傷害 10）。 */
    public static final String COMPANION_NEAR_DAMAGE = "asc.companion_near_damage";
    /** 夥伴獲得附加攻擊傷害 = 主手武器傷害的 x%（凱莎的平衡 60）。 */
    public static final String COMPANION_WEAPON = "asc.companion_weapon";
    /** 夥伴增加 30% 移動速度、被隨機林靈附身，每 x 秒換一種（自然秩序 20）。 */
    public static final String NATURAL_ORDER = "asc.natural_order";
    /** 原始梟羽的上限（每 4 秒 1 根；翻滾時用掉觸發原始賞金；原始賞金 3）。 */
    public static final String OWL_FEATHERS = "asc.owl_feathers";
    /** 翻滾時最多用掉幾根梟羽（每多 1 根強化效果更多 100%；姆哈查之贈 2）。 */
    public static final String OWL_SPEND = "asc.owl_spend";
    /** 梟羽的獲得速度加快 x%（姆哈查之贈 50）。 */
    public static final String OWL_RATE = "asc.owl_rate";
    /** 聖潔一統：熊靈的荒野擁抱、靈鹿追敵、梟羽強化的投射物留下 x 秒的翱翔之地（6）。 */
    public static final String SACRED_UNITY = "asc.sacred_unity";
    // 鳴鐘武師（Martial Artist）
    /** 符文子午線：身上有 x 個只能嵌符文的孔（頭 1、胸 2、腿 1、腳 1）。 */
    public static final String MERIDIANS = "asc.meridians";
    /** 山巒之道：山巒之教的上限（30）。 */
    public static final String MOUNTAIN = "asc.mountain";
    /**
     * 石拳之道（FLAT 1）：穿上的腿甲（原作的手套）變成磐石之拳（tw.exilecore.item.Stonefist）、裝備腿甲不需要能力值。
     * 2026-10-28 照 PoE2；這次之前是 50（詞綴數值增加 50%、防禦換成每等級 +3 閃避 +1 能量護盾）。
     */
    public static final String STONEFIST = "asc.stonefist";
    /** 使用技能時每用掉 1 層連擊，能量護盾回充延遲 -x 秒（武術精通 0.2）。 */
    public static final String COMBO_ES_DELAY = "asc.combo_es_delay";
    /** 所有攻擊擊中都會累積連擊（武術宗師）。 */
    public static final String COMBO_ANY_ATTACK = "asc.combo_any_attack";
    // 淵井骸主（Abyssal Lich）
    /** 詛咒的敵人被擊殺時 x% 機率爆炸，造成牠最大生命四分之一的物理傷害（割裂血肉 33）。 */
    public static final String RUPTURE_PHYSICAL = "asc.rupture_physical";
    /** 骷髏召喚物改成幽影之魂（幽影之井）。 */
    public static final String UMBRAL_WELL = "asc.umbral_well";
    /** 祭品可以撲殺敵人當目標、改成影響你、每點強度效果增加 1%（不情願的獻祭）。 */
    public static final String UNWILLING_OFFERING = "asc.unwilling_offering";

    /** 更少 x% 屬性（強化效果 20；屬性表那邊是 MORE，昇華檔的屬性不能寫 MORE，所以另外一個鍵）。 */
    public static final String ATTRIBUTE_LESS = "asc.attribute_less";
    /** 你沒有精魂（靈魂昇華）。 */
    public static final String NO_SPIRIT = "asc.no_spirit";

    /** 昇華天賦的修飾器展開成引擎看得懂的（AscendancyService.modifiers 呼叫）：更少 x% 屬性、沒有精魂。 */
    static List<Modifier> expand(final List<Modifier> mods) {
        List<Modifier> out = null;
        for (final Modifier mod : mods) {
            if (mod.stat().equals(ATTRIBUTE_LESS) || mod.stat().equals(NO_SPIRIT)) {
                if (out == null) {
                    out = new java.util.ArrayList<>(mods);
                }
                if (mod.stat().equals(ATTRIBUTE_LESS)) {
                    for (final String attr : new String[] {tw.exilecore.stats.StatKeys.STR, tw.exilecore.stats.StatKeys.DEX, tw.exilecore.stats.StatKeys.INT}) {
                        out.add(Modifier.more(attr, -mod.value() / 100.0, mod.source()));
                    }
                } else {
                    out.add(Modifier.more(tw.exilecore.stats.StatKeys.SPIRIT_MAX, -1.0, mod.source()));
                }
            }
        }
        return out == null ? mods : out;
    }

    /** 昇華賦予的技能自己的增益標記（效果在 AscGranted；增益列上顯示「效果見技能說明」）。 */
    public static final String SKILL_MARK = "asc.skill_mark";

    /** 昇華賦予的技能：grant_skill.技能代號（跟傳奇裝備同一種寫法；等級照角色等級）。 */
    public static final String GRANT_SKILL = "grant_skill.";

    /** 這次用到、原本昇華檔不認得的別的屬性（引擎早就有，名稱給說明用）。 */
    private static final Map<String, String> OTHER = new LinkedHashMap<>();
    /** 這次用到、昇華檔原本不認得的標籤（傷害、範圍的標籤）。 */
    static final Map<String, String> TAGS = new LinkedHashMap<>();

    static {
        OTHER.put("warcry.speed", "戰吼速度");
        OTHER.put("pin.buildup", "釘身累積");
        OTHER.put("remnant.pickup_range", "殘骸拾取距離");
        OTHER.put("presence.slow", "存在範圍內的敵人緩速");
        OTHER.put("presence.exposure", "存在範圍內的敵人曝露");
        OTHER.put("taken.physical_as_fire", "承受的物理傷害視為火焰");
        OTHER.put("charm.slots", "護符欄位");
        // 2026-10-26 照 PoE2（展露之指）：第三個戒指欄；這次之前是 asc.ring_effect（戒指的詞綴效果增加 50%）
        OTHER.put(tw.exilecore.character.PlayerState.RING_EXTRA_STAT, "戒指欄位");
        OTHER.put("flag.immune.ignite", "不受點燃影響");
        OTHER.put("flag.immune.bleed", "不受流血影響");
        OTHER.put("flag.immune.poison", "不受中毒影響");
        OTHER.put("meta.energy_gain", "引星獲得的能量");
        OTHER.put(GLORY_GENERATION, "榮耀產生");
        OTHER.put(AVOID_DEATH, "避免被擊中致死的機率");
        // 2026-10-26：跟天賦樹同一套的機制（TreeMechanics）
        OTHER.put(tw.exilecore.stats.TreeNewMechanicKeys.FLAG_ANCESTRAL_EVERY_OTHER_SLAM, "每兩次猛擊先祖強化一次");
        OTHER.put(tw.exilecore.stats.TreeNewMechanicKeys.VOLATILITY_ON_KILL, "擊殺時獲得易爆的機率");
        OTHER.put(tw.exilecore.stats.TreeNewMechanicKeys.VOLATILITY_ON_ELEMENTAL_AILMENT, "施加元素異常時獲得易爆");
        OTHER.put(tw.exilecore.stats.TreeNewMechanicKeys.FLAG_VOLATILITY_IMMUNE, "易爆不會傷害你");
        OTHER.put(tw.exilecore.stats.TreeNewMechanicKeys.FLAG_ARCANE_SURGE_ALWAYS, "你擁有秘術增強");
        // 2026-10-26：移動中使用技能的移動速度懲罰（SkillMovePenalty；2026-10-27 照站長「放技能減速全部移除」：已經沒有來源，不會出現）、生命回復速度（再生、偷取、回復都算）
        OTHER.put(tw.exilecore.stats.TreeEffectKeys.ACTION_PENALTY, "移動中使用技能的移動速度懲罰");
        OTHER.put(tw.exilecore.stats.TreeEffectKeys.LIFE_RECOVERY_RATE, "生命回復速度");
        // 2026-10-27 照 PoE2：魔力回復速度（再生、偷取、回復、藥劑都算；跟只加再生的「魔力再生速度」不同）、魔力藥劑回復速度
        OTHER.put(tw.exilecore.stats.TreeEffectKeys.MANA_RECOVERY_RATE, "魔力回復速度");
        OTHER.put(tw.exilecore.stats.TreeEffectKeys.FLASK_MANA_RATE, "魔力藥劑回復速度");
        OTHER.put(tw.exilecore.stats.KeystoneKeys.FLASK_LIFE_RATE, "生命藥劑回復速度");
        OTHER.put(tw.exilecore.stats.StatKeys.COMBO_EXTRA_CHANCE, "累積連擊時多累積 1 層的機率"); // 2026-10-27 照 PoE2（武術精通）
        TAGS.put("triggered", "觸發的");
        TAGS.put(tw.exilecore.stats.KeystoneKeys.CRIT_HIT, "暴擊時"); // 2026-10-26：暴擊引起流血
        TAGS.put("banner", "鬥旗");
    }

    private AscKeys() {
    }

    /** 是不是這次的鍵（StatLines 交給這裡檢查）。 */
    static boolean handles(final String stat) {
        return stat.startsWith("asc.") || stat.startsWith(GRANT_SKILL) || OTHER.containsKey(stat);
    }

    /** 檢查寫法（null = 沒問題）。 */
    static String problem(final Modifier modifier) {
        final String stat = modifier.stat();
        if (modifier.value() == 0) {
            return stat + " 的數值是 0";
        }
        if (OTHER.containsKey(stat)) {
            return modifier.tags().isEmpty() || stat.equals("pin.buildup") ? null : stat + " 不能加標籤";
        }
        if (!modifier.tags().isEmpty()) {
            return stat + " 不能加標籤";
        }
        if (stat.equals(DARKNESS) || stat.equals(REMNANT_EFFECT) || stat.equals(ATTRIBUTE_REQUIREMENT)) {
            return modifier.type() == ModType.INCREASED || modifier.type() == ModType.FLAT ? null : stat + " 只能用 FLAT／INCREASED";
        }
        return modifier.type() == ModType.FLAT ? null : stat + " 要用 FLAT";
    }

    /** 說明（昇華檔寫了 lines，這裡只給沒寫說明時的備用）。 */
    static String describe(final Modifier modifier) {
        final String name = OTHER.get(modifier.stat());
        if (name != null) {
            return name + " " + StatLines.number(modifier.type() == ModType.FLAT ? modifier.value() : modifier.value() * 100)
                    + (modifier.type() == ModType.FLAT ? "" : "%");
        }
        if (modifier.stat().equals(SKILL_MARK)) {
            return "效果見技能說明";
        }
        if (modifier.stat().startsWith(GRANT_SKILL)) {
            return "提供技能：" + modifier.stat().substring(GRANT_SKILL.length());
        }
        return modifier.stat();
    }

    /** 屬性值的固定集合（測試用）。 */
    static Set<String> otherStats() {
        return OTHER.keySet();
    }
}
