package tw.exilecore.item.unique;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * 2026-10-26：照 PoE2 0.5 的傳奇特殊效果用到的屬性鍵（uniques.yml 的 stats）。效果寫在這個套件的
 * {@link UniqueMechanics}（事件、每秒檢查）、{@link UniqueDefence}（傷害減免）、{@link UniqueDerived}（屬性表算完之後才知道的數值）、
 * {@link UniqueCosts}（技能消耗）。數值都是 FLAT 的點數或百分比點數，旗標寫 1。
 */
public final class UniqueStats {

    private UniqueStats() {
    }

    // ---- 受到的傷害 ----
    /** 受到的攻擊擊中物理傷害 -x（天樞墜；寫 projectile 標籤的只算投射物攻擊）。 */
    public static final String TAKEN_ATTACK_PHYSICAL = "taken.attack_physical";
    /** 受到的物理傷害有 x% 視為火焰傷害（焚衣）。 */
    public static final String TAKEN_PHYSICAL_AS_FIRE = "taken.physical_as_fire";
    /** 防禦時護甲多 x%（鐵律腰帶：防禦時護甲視為 200% = 100）。 */
    public static final String ARMOUR_DEFEND = "armour.defend";
    /** 物理傷害減免上限 x%（鐵律腰帶）。 */
    public static final String PHYSICAL_REDUCTION_CAP = "physical.reduction_cap";
    /** 被壓碎（怪物身上的減益）：物理傷害減免降低 x%，可以降到負的（PoE2 Crushed：15%）。 */
    public static final String CRUSHED = "physical.crushed";
    /** 你被冰緩時，擊中你的暴擊率增加 x%（負的 = 減少；暗潮指環）。 */
    public static final String CRIT_CHANCE_TAKEN_CHILLED = "crit.chance_taken_chilled";

    // ---- 打出去的 ----
    /** 擊中時壓碎敵人（守墓巨鎚、屠王巨鎚）。 */
    public static final String CRUSH_ON_HIT = "crush.on_hit";
    /** 對重暈中的敵人一定暴擊（屠王巨鎚）。 */
    public static final String CRIT_VS_HEAVY_STUNNED = "flag.crit_vs_heavy_stunned";
    /** 對重暈中的敵人增加 x% 傷害（INCREASED，可寫標籤；夢魘長棍）。 */
    public static final String DAMAGE_VS_HEAVY_STUNNED = "damage.vs_heavy_stunned";
    /** 敵人身上每 1% 冰緩效果，獲得冰冷傷害的 x% 為額外火焰傷害（霜環）。 */
    public static final String COLD_AS_FIRE_PER_CHILL = "damage.cold_as_fire_per_chill";
    /** 冰緩中的敵人受到的傷害增加冰緩效果的數值（寒息箭袋）。 */
    public static final String CHILL_TAKEN_FROM_MAGNITUDE = "chill.taken_from_magnitude";
    /** 對流血中的敵人，擊中的所有傷害都算進冰緩（赤紋之戒）。 */
    public static final String BLEEDING_TARGET_CHILL = "flag.bleeding_target_chill";
    /** 你流血時，受到的擊中傷害全部算進你身上的冰緩（赤紋之戒）。 */
    public static final String BLEEDING_SELF_CHILL = "flag.bleeding_self_chill";
    /** 每一種傷害只擲出最小值或最大值（貫天矛）。 */
    public static final String EXTREME_ROLLS = "flag.extreme_rolls";
    /** 暴擊時 x% 機率讓敵人中毒（夢鏡面具：100 = 暴擊一定中毒）。 */
    public static final String POISON_ON_CRIT = "poison.on_crit";
    /** 你造成的流血傷害快 x%（INCREASED：每秒傷害乘上去、持續時間除掉；叛徒之矛）。 */
    public static final String BLEED_FASTER = "bleed.faster";
    /** 擊中時 x% 機率加劇目標身上你造成的流血（叛徒之矛）。 */
    public static final String BLEED_AGGRAVATE_CHANCE = "bleed.aggravate_chance";
    /** 擊中時施加深淵消耗（6 秒內生命回復速度減少 50%；淵握戒）。 */
    public static final String ABYSSAL_WASTING_ON_HIT = "abyssal_wasting.on_hit";
    /** 投射物分裂之後穿透所有目標（裂穹）。 */
    public static final String PIERCE_AFTER_FORK = "flag.pierce_after_fork";
    /** 攻擊的投射物穿透至少 x 次之後折返（穿雲箭袋）。 */
    public static final String PIERCE_RETURN_AFTER = "pierce.return_after";
    /** 投射物每穿透過一次，擊中傷害增加 x%（穿雲箭袋）。 */
    public static final String PIERCE_DAMAGE_PER = "pierce.damage_per";
    /** 投射物每穿透過一次，暴擊率增加 x%（穿雲箭袋）。 */
    public static final String PIERCE_CRIT_PER = "pierce.crit_per";
    /** 火焰、冰冷荊棘傷害（被近戰擊中時反彈；焚衣、浮冰圓盾）。 */
    public static final String THORNS_FIRE_MIN = "thorns.fire.min";
    public static final String THORNS_FIRE_MAX = "thorns.fire.max";
    public static final String THORNS_COLD_MIN = "thorns.cold.min";
    public static final String THORNS_COLD_MAX = "thorns.cold.max";
    /** 你造成的點燃擴散給待在 x/10 格（格）內 1 秒的其他敵人（火葬長杖、野焰之戒）。 */
    public static final String IGNITE_SPREAD = "ignite.spread";
    /** 擊退的方向反過來（堞衛脛甲）。 */
    public static final String KNOCKBACK_REVERSED = "flag.knockback_reversed";
    /** 用擊中殺死完全破甲的敵人時，牠碎裂、不留屍體（撬桿）。 */
    public static final String SHATTER_BROKEN = "kill.shatter_broken";
    /** 被你冰緩的敵人可以像凍結一樣被擊碎：殺死時碎裂、不留屍體（長冬之戒）。 */
    public static final String SHATTER_CHILLED = "kill.shatter_chilled";
    /** 你施加的詛咒沒有持續時間限制（司祭法衣）。 */
    public static final String CURSE_INFINITE = "flag.curse_infinite";
    /** 你施加的凋零沒有持續時間限制（司祭法衣）。 */
    public static final String WITHER_INFINITE = "flag.wither_infinite";
    /** 2026-10-28 照 PoE2 Blackflame：你施加的點燃造成混沌傷害而非火焰傷害（吃混沌抗性、受到的混沌傷害增加；黯炎）。這次之前點燃一律是火焰。 */
    public static final String IGNITE_AS_CHAOS = "flag.ignite_as_chaos";
    /** 2026-10-28 照 PoE2 Blackflame：你施加的凋零同時增加受到的火焰傷害（每層 5%，跟混沌的一樣多；黯炎）。 */
    public static final String WITHERED_FIRE_TAKEN = "flag.withered_fire_taken";
    /** 2026-10-28 照 PoE2 Blackflame：被點燃的敵人身上你施加的凋零不會失效（點燃期間暫停計時；黯炎）。 */
    public static final String WITHERED_NO_EXPIRE_IGNITED = "flag.withered_no_expire_ignited";
    /** 你身邊的敵人曝露（元素抗性 -20%；焚王獸符）。 */
    public static final String PRESENCE_EXPOSURE = "presence.exposure";

    // ---- 你自己 ----
    /** 每 x 秒獲得一個隨機的非傷害神龕增益，20 秒（隕砧）。 */
    public static final String SHRINE_PERIODIC = "shrine.periodic";
    /** 神龕增益的秒數（隕砧：20）。 */
    public static final long SHRINE_PERIODIC_SECONDS = 20;
    /** 每顆銳星 x% 全部元素抗性（星軌魔杖：-10）。 */
    public static final String RESIST_PER_POWER = "resist.elemental_per_power";
    /** 每 100 最大生命，精魂的保留效率增加 x%（稜壁）。 */
    public static final String SPIRIT_EFFICIENCY_PER_LIFE = "spirit.efficiency_per_life";
    /** 拿著法器時 +x% 格擋率（香爐墜）。 */
    public static final String BLOCK_CHANCE_FOCUS = "block.chance_focus";
    /** 你有邪力：獲得 30% 傷害為額外的混沌傷害（未啟骸杖）。 */
    public static final String UNHOLY_MIGHT = "flag.unholy_might";
    /** 邪力的效果增加 x%（INCREASED；未啟骸杖）。 */
    public static final String UNHOLY_MIGHT_EFFECT = "unholy_might.effect";
    /** 邪力：獲得多少 % 傷害為額外的混沌傷害（PoE2：30%）。 */
    public static final double UNHOLY_MIGHT_PERCENT = 30;
    /** 生命回復改成回復能量護盾（空殼護符）。 */
    public static final String REGEN_TO_ES = "flag.regen_to_es";
    /** 不能使用其他戒指（計數之戒）。 */
    public static final String ONLY_RING = "flag.only_ring";
    /** 你身上的詛咒持續時間增加 x%（負的 = 減少；計數之戒）。 */
    public static final String CURSE_SELF_DURATION = "curse.self_duration";
    /** 暈眩回復速度增加 x%（INCREASED：你被暈眩的時間 ÷（1 + x）；冰行者之靴）。 */
    public static final String STUN_RECOVERY = "stun.recovery";
    /** 翻滾距離 +x 格（格；可以寫 @dodge／@nododge；鷹師之弓）。 */
    public static final String DODGE_METRES = "dodge.metres";
    /** 翻滾時穿過敵人（相位之履）。 */
    public static final String DODGE_PHASE = "flag.dodge_phase";
    /** 生命藥劑的回復立即生效（亡命者背心）。 */
    public static final String FLASK_LIFE_INSTANT = "flag.flask_life_instant";
    /** 你的生命藥劑也回復你的召喚物（骸骨束帶）。 */
    public static final String LIFE_FLASK_MINIONS = "flag.life_flask_minions";
    /** 召喚物在生命藥劑效果中不會死（骸骨束帶）。 */
    public static final String MINION_UNDYING_FLASK = "flag.minion_undying_flask";
    /** 召喚物的抗性等於你的抗性（冥河之衛）。 */
    public static final String MINION_RESIST_MIRROR = "flag.minion_resist_mirror";
    /** 移動時留下燃燒地面，點燃上面的敵人，視同造成你最大生命 x% 的火焰傷害（行灰之履）。 */
    public static final String GROUND_IGNITE_MOVING = "ground.ignite_moving";
    /** 每移動 x/10 格（格）觸發一次潰骸（踏骸之履）。 */
    public static final String TRIGGER_DECOMPOSE_METRES = "trigger.decompose_metres";
    /** 武器以外的裝備賦予技能（grant_skill.技能代號，FLAT 1；等級照物品等級，跟法杖賦予的一樣）。 */
    public static final String GRANT_SKILL = "grant_skill.";

    // ---- 技能 ----
    /** 技能魔力消耗的 x% 改扣生命（恍惚之矛）。 */
    public static final String COST_MANA_TO_LIFE = "cost.mana_to_life";
    /** 支付技能魔力消耗時，受到消耗量 x% 的物理傷害（寂心）。 */
    public static final String MANA_COST_AS_PHYSICAL = "mana_cost.as_physical";
    /** 施放法術時偷取 x% 最大生命（念珠魔杖）。 */
    public static final String LEECH_LIFE_ON_CAST = "leech.life_on_cast";
    /** 不能使用戰吼（血偶巨鎚）。 */
    public static final String NO_WARCRY = "flag.no_warcry";
    /** 你自己使用的猛擊技能多造成 x 次餘震（鑄父之錘）。 */
    public static final String SLAM_AFTERSHOCK = "slam.aftershock";
    /** 技能的完美時機區間長 x%（INCREASED；虛望之盔）。 */
    public static final String PERFECT_WINDOW = "perfect_timing.window";
    /** 最近（4 秒內）裝填過時，弩的攻擊 x% 機率不消耗彈藥（破城機弩）。 */
    public static final String CROSSBOW_AMMO_SAVE_RELOADED = "crossbow.ammo_save_reloaded";
    /** 「最近裝填過」的事件代號（PlayerState.markRecent）。 */
    public static final String RECENT_RELOAD = "reload";
    /** 擊殺時 x% 機率替所有弩技能裝填一發（歃盟弩）。 */
    public static final String CROSSBOW_LOAD_ON_KILL = "crossbow.load_on_kill";
    /** 發射最後一發時犧牲 x 生命，不消耗那一發（歃盟弩）。 */
    public static final String CROSSBOW_SACRIFICE_LAST = "crossbow.sacrifice_last";
    /** 技能代號當成「技能等級」的標籤（+4 遲滯詛咒技能等級：skill.level 寫 tags: [skill:temporal_curse]）。 */
    public static final String SKILL_TAG_PREFIX = "skill:";

    // ---- 這件物品自己 ----
    /** 嵌在這件物品上的符文效果增加 x%（INCREASED；淵座束帶）。 */
    public static final String LOCAL_RUNE_EFFECT = "local.rune_effect";
    /** 嵌在這件物品上的符文照盾牌（防具）的效果算（淵座束帶；非武器的符文本來就照防具那一欄）。 */
    public static final String LOCAL_RUNE_AS_SHIELD = "local.rune_as_shield";
    /** 這件物品的力量需求 +x（守墓巨鎚）。 */
    public static final String LOCAL_REQUIREMENT_STR = "local.requirement.str";
    public static final String LOCAL_REQUIREMENT_DEX = "local.requirement.dex";
    public static final String LOCAL_REQUIREMENT_INT = "local.requirement.int";

    // ---- 2026-10-27 照 PoE2 0.5 第二批（原作有、我們以前沒有的傳奇） ----
    /** 某種傷害的 x% 轉換為另一種（damage.convert_from.來源.目標，例如 fire.cold；濁源指環、藍焰護腕、雷鉗鐵手、兄弟戒）。 */
    public static final String CONVERT_FROM = "damage.convert_from.";
    /** 技能的基礎冷卻時間 +x 秒（負的 = 縮短，最少 0.1 秒；逆漏法袍）。 */
    public static final String COOLDOWN_FLAT = "skill.cooldown_flat";
    /** 冷卻縮短之後最少剩幾秒。 */
    public static final double COOLDOWN_FLAT_MIN = 0.1;
    /** 映照另一枚戒指：另一隻手的戒指的詞綴在這裡再算一次（映鏡指環）。 */
    public static final String RING_REFLECT = "ring.reflect";
    /** 左手、右手戒指的加成增加 x%（INCREASED；巧匠束帶）。 */
    public static final String RING_EFFECT_LEFT = "ring.effect.left";
    public static final String RING_EFFECT_RIGHT = "ring.effect.right";
    /** 暴擊無視怪物正的抗性（標籤寫元素；雷頌墜）。 */
    public static final String CRIT_IGNORE_RESIST = "crit.ignore_resist";
    /** 暴擊時觸發落雷（雷頌墜；每 {@link #TRIGGER_COOLDOWN_TICKS} tick 最多一次）。 */
    public static final String TRIGGER_CRIT_LIGHTNING_BOLT = "trigger.crit_lightning_bolt";
    /** 殺死感電的敵人時觸發游雷（縛地長杖）。 */
    public static final String TRIGGER_KILL_SHOCKED_SPARK = "trigger.kill_shocked_spark";
    /** 傳奇觸發的技能：同一種最快多久一次（照 PoE2 觸發技能的冷卻）。 */
    public static final long TRIGGER_COOLDOWN_TICKS = 5;
    /** 獲得最大生命／最大魔力 x% 的額外最大能量護盾（冷蔑金冠、恆望墜）。 */
    public static final String ES_FROM_LIFE = "es.from_life";
    public static final String ES_FROM_MANA = "es.from_mana";
    /** 被偏斜的擊中不會讓你輕暈（冷蔑步靴）。 */
    public static final String NO_LIGHT_STUN_DEFLECTED = "stun.no_light_deflected";
    /** 每顆怒星增加 x% 移動速度（暗芒戰靴）；每顆銳星增加 x% 暴擊傷害加成（銳踏便鞋）。 */
    public static final String MOVEMENT_PER_FRENZY = "movement.per_frenzy";
    public static final String CRIT_BONUS_PER_POWER = "crit.bonus_per_power";
    /** 照屬性點數給的：每 10 點敏捷 x% 攻擊速度（知行捲布寫 0.5 = 每 20 點 1%）、每 10 點智慧攻擊 x% 範圍、每 10 點力量攻擊 x% 傷害（囚神長棍）、
     * 每 10 點精魂 x% 法術傷害（織光法器）、每 20 點智慧攻擊附加 x/10 到 x 閃電傷害（知行捲布）。 */
    public static final String PER_DEX_ATTACK_SPEED = "per_dex.attack_speed";
    public static final String PER_INT_ATTACK_AREA = "per_int.attack_area";
    public static final String PER_STR_ATTACK_DAMAGE = "per_str.attack_damage";
    public static final String PER_SPIRIT_SPELL_DAMAGE = "per_spirit.spell_damage";
    public static final String PER_INT_ATTACK_LIGHTNING = "per_int.attack_lightning";
    /** 獲得等於最大魔力 x% 的冰冷荊棘傷害（冰塚法鎧）。 */
    public static final String THORNS_COLD_PER_MANA = "thorns.cold_per_mana";
    /** 武器攻擊的基礎暴擊率是 x%（北爪護腕）。 */
    public static final String CRIT_WEAPON_BASE = "crit.weapon_base";
    /** 每 1% 格擋率，獲得 x% 傷害為額外火焰傷害（黑神柱盾）。 */
    public static final String FIRE_PER_BLOCK = "damage.fire_per_block";
    /** 閃避率最多 x%（警望指環；不像戰術家那樣連物理減免也壓）。 */
    public static final String EVADE_MAX = "evade.max";
    /** 每秒失去 x% 最大生命（骸節鐵手；最少留 1 點）。 */
    public static final String LIFE_LOSS_PERCENT = "life.loss_percent";
    /** 使用技能時失去 x 生命（荊冠細環；最少留 1 點）。 */
    public static final String LIFE_LOSE_ON_SKILL = "life.lose_on_skill";
    /** 每 3 秒消耗附近一具屍體，回復 x% 最大生命（噬屍頭環）。 */
    public static final String CORPSE_CONSUME_HEAL = "corpse.consume_heal";
    public static final long CORPSE_CONSUME_TICKS = 60;
    /** 擊殺時 x% 機率讓能量護盾立刻開始回充（喪鈴法器）。 */
    public static final String ES_RECHARGE_ON_KILL = "es.recharge_on_kill";
    /** 格擋時回復 x% 最大生命（降伏塔盾）。 */
    public static final String BLOCK_RECOVER_LIFE = "block.recover_life";
    /** 格擋時，生命低於撲殺門檻的攻擊者直接倒下（厄門塔盾）。 */
    public static final String BLOCK_CULL = "block.cull";

    // ---- 2026-10-28 照 PoE2 0.5：石拳之道的手套（磐石之拳版本）與補上的手套傳奇 ----
    /** 每秒失去 x% 最大魔力（磐石之拳的骸節鐵手；最少留 0）。 */
    public static final String MANA_LOSS_PERCENT = "mana.loss_percent";
    /** 每 20 點敏捷，攻擊附加 min 到 max 冰冷傷害（磐石之拳的智行之手）。 */
    public static final String PER_DEX_ATTACK_COLD_MIN = "per_dex.attack_cold_min";
    public static final String PER_DEX_ATTACK_COLD_MAX = "per_dex.attack_cold_max";
    /** 每 20 點智慧增加 x% 範圍效果（全部技能；磐石之拳的智行之手）。 */
    public static final String PER_INT_AREA = "per_int.area";
    /** 每 1 點力量 +x 護甲（毀面者）。 */
    public static final String PER_STR_ARMOUR = "per_str.armour";
    /** 空手時，每 5 點力量空手攻擊傷害更多 x%（毀面者；PlayerStateService 照有沒有拿武器加）。 */
    public static final String PER_STR_UNARMED_MORE = "per_str.unarmed_more";
    /** 空手攻擊的基礎物理傷害改成這個（毀面者：8 到 12；PlayerStateService 的徒手傷害）。 */
    public static final String UNARMED_MIN = "unarmed.base_min";
    public static final String UNARMED_MAX = "unarmed.base_max";
    /** 雙手都空著時當作拿單手錘（錘的技能能用、吃錘的修飾器；毀面者）。 */
    public static final String UNARMED_MACE = "flag.unarmed_mace";
    /** 獲得某種傷害的 x% 為額外的另一種傷害（damage.gain_from.來源.目標；畫家的侍從、藍焰護腕、雷鉗鐵手的磐石之拳版本）。 */
    public static final String GAIN_FROM = "damage.gain_from.";
    /** 施放法術時犧牲 x% 最大生命，獲得等量的能量護盾（縫魔）。 */
    public static final String SACRIFICE_ES_ON_SPELL = "sacrifice.es_on_spell";
    /** 攻擊時犧牲 x% 最大生命，獲得一半的能量護盾（磐石之拳的縫魔；原作是符文保護）。 */
    public static final String SACRIFICE_ES_ON_ATTACK = "sacrifice.es_on_attack";
    /** 怒氣滿時回復 x% 最大能量護盾（磐石之拳的憎鑄；原作是符文保護）。 */
    public static final String ES_ON_MAX_RAGE = "es.on_max_rage";
    /** 你擊中的元素傷害照敵人最低的元素抗性算（磐石之拳的里歐波德的掌聲）。 */
    public static final String LOWEST_RESIST = "flag.lowest_elemental_resist";
    /** 攻擊的暴擊率固定是 x%（磐石之拳的馬雷葛蘿）。 */
    public static final String CRIT_ATTACK_OVERRIDE = "crit.attack_override";
    /** 你的暴擊傷害加成固定是 x%（馬雷葛蘿的血染手套）。 */
    public static final String CRIT_BONUS_OVERRIDE = "crit.bonus_override";
    /** 你的暴擊率不會被重擲（馬雷葛蘿；引擎的暴擊沒有幸運重擲，這條本來就成立）。 */
    public static final String CRIT_NO_REROLL = "flag.crit_no_reroll";
    /** 魔力在最大魔力的 x% 以下就算貧魔（磐石之拳的遊手好閒：50）／以上就算滿魔（遊手好閒：90）。ConditionTracker 讀。 */
    public static final String LOW_MANA_THRESHOLD = "mana.low_threshold";
    public static final String FULL_MANA_THRESHOLD = "mana.full_threshold";
    /** 你擊中造成的物理傷害有 x% 算進敵人的失血（阿茲里的捷思）。 */
    public static final String BLOOD_LOSS_ON_HIT = "blood_loss.on_hit";
    /** 生命偷取改成偷取能量護盾（精華收割器）。 */
    public static final String LIFE_LEECH_TO_ES = "flag.life_leech_to_es";
    /** 魔力偷取也回復能量護盾（磐石之拳的精華收割器）。 */
    public static final String MANA_LEECH_ALSO_ES = "flag.mana_leech_also_es";
    /** 擊殺效果觸發兩次（掃興之人）。 */
    public static final String ON_KILL_TWICE = "flag.on_kill_twice";
    /** 你臨在範圍內被任何人殺死的敵人算是你殺的（擊殺效果；幽墓束縛）。 */
    public static final String PRESENCE_KILLS = "flag.presence_kills";
    /** 你其他的物品稀有度加成無效（只剩這件的；幽墓束縛）。 */
    public static final String RARITY_ONLY_THIS = "flag.rarity_only_this";
    /** 力量可以滿足近戰武器的其他能力值需求（雅恩格利佩爾）；敏捷可以（磐石之拳版本）。 */
    public static final String STR_MELEE_REQUIREMENTS = "flag.str_melee_requirements";
    public static final String DEX_MELEE_REQUIREMENTS = "flag.dex_melee_requirements";
    /** 被某種元素異常之後 x 秒內不會再中（悲運之縛：ailment.immune_after.chill／freeze／ignite／shock）。 */
    public static final String IMMUNE_AFTER = "ailment.immune_after.";
    /** 你施加的詛咒也會落在你自己身上（悲運之縛；跟魅咒一樣，但不會無視詛咒上限）。 */
    public static final String CURSE_REFLECT_SELF = "flag.curse_reflect_self";
    /** 暴擊時 x% 機率穿刺（驚懼之拳：100）。 */
    public static final String IMPALE_ON_CRIT = "impale.on_crit";
    /** 用近戰攻擊暴擊敵人時，對牠造成你的荊棘傷害（磐石之拳的驚懼之拳）。 */
    public static final String THORNS_ON_MELEE_CRIT = "flag.thorns_on_melee_crit";
    /** 擊中的所有傷害都算進中毒幅度（瘟疫之指）。 */
    public static final String ALL_DAMAGE_POISONS = "flag.all_damage_poisons";
    /** 你以暴擊施加的中毒幅度增加 x%（INCREASED；磐石之拳的瘟疫之指）。 */
    public static final String POISON_MAGNITUDE_CRIT = "poison.magnitude_crit";
    /** 擊中的物理傷害也算進易燃、點燃幅度、凍結累積與感電機率（磐石之拳的畫家的侍從）。 */
    public static final String PHYSICAL_ELEMENTAL_AILMENTS = "flag.physical_elemental_ailments";
    /** 攻擊的所有傷害都累積電擊（磐石之拳的奇多可之流）。 */
    public static final String ATTACK_ALL_ELECTROCUTE = "flag.attack_all_electrocute";
    /** 擊中時 x% 機率讓臨在範圍內的隊友獲得一顆怒星／磐星／銳星（磐石之拳的意識之緣）。 */
    public static final String ALLY_CHARGE_ON_HIT = "charge.ally_on_hit.";
    /**
     * 受到稀有、傳奇敵人的傷害有 x% 之後慢慢回成生命（主宰之觸；原作是「暴露破綻的敵人」——它對稀有、傳奇敵人暴露弱點）。
     * CombatListener 照打你的是不是稀有、傳奇怪物加在生命補償上。
     */
    public static final String LIFE_RECOUP_VS_RARE = "life.recoup_vs_rare";
    /** 點了山巒之道時，禁錮敵人獲得山巒之教的機率每點敵人強度再多 x% 超越機率（磐石之拳的無與倫比；AscPaths 讀）。 */
    public static final String MOUNTAIN_EXTRA_CHANCE = "asc.mountain_extra_chance";
    /** 擊中的所有傷害都算進凍結累積（磐石之拳的凜冬之握）。 */
    public static final String ALL_DAMAGE_FREEZES = "flag.all_damage_freezes";
    /** 空手攻擊的基礎傷害（unarmed.base_min／max）是火焰而不是物理（磐石之拳的毀面者）。 */
    public static final String UNARMED_FIRE = "flag.unarmed_base_fire";
    /** 空手時，每 5 點智慧獲得等同於空手傷害 x% 的額外火焰傷害（磐石之拳的毀面者）。 */
    public static final String PER_INT_UNARMED_FIRE = "per_int.unarmed_fire";
    /** 空手時，每 10 點智慧增加 x% 空手攻擊的範圍效果（磐石之拳的毀面者）。 */
    public static final String PER_INT_UNARMED_AREA = "per_int.unarmed_area";
    /** 無法偷取魔力（磐石之拳的意識之緣）。 */
    public static final String CANNOT_LEECH_MANA = "flag.cannot_leech_mana";
    /** 你獲得戰星時，臨在範圍內的隊友也獲得一顆（意識之緣：與存在範圍內的友方共享能量球）。 */
    public static final String SHARE_CHARGES = "flag.share_charges";

    /** 獲得某種傷害為額外另一種的屬性鍵（{@link #GAIN_FROM}）。 */
    public static String gainFrom(final tw.exilecore.stats.DamageType from, final tw.exilecore.stats.DamageType to) {
        return GAIN_FROM + from.tag() + "." + to.tag();
    }
    // ---- 2026-10-28 照 PoE2 補的傳奇（綺夢、謎容、神聖烈焰、元素之章、貝雷克的兩枚、薛朗的背包、吉爾菲的聖殿）----

    /** FLAT 1：無法使用生命藥劑；生命藥劑（非傳奇）的效果一直套用（不立即、只給你；綺夢，FlaskService）。 */
    public static final String FLASK_LIFE_CONSTANT = "flag.flask_life_constant";
    /** FLAT 1：藥劑的生命回復同時套用在能量護盾（薛朗的背包，FlaskService）。 */
    public static final String FLASK_LIFE_TO_ES = "flag.flask_life_to_es";
    /** FLAT %：最大能量護盾等於力量的 x%，而且不能被轉換（吉爾菲的聖殿，PlayerState.maxEnergyShield）。 */
    public static final String ES_EQUAL_TO_STR = "es.equal_to_str";
    /** FLAT：每 10 點智慧每秒回復 x 生命（吉爾菲的聖殿，UniqueDerived）。 */
    public static final String PER_INT_LIFE_REGEN = "per_int.life_regen";
    /** FLAT %：存在範圍內的盟友每秒回復 x% 最大生命（神聖烈焰，AuraService 給隊友）。 */
    public static final String PRESENCE_ALLY_REGEN = "presence.ally_regen";
    /** FLAT %：存在範圍內的盟友獲得 x% 傷害為額外火焰傷害（神聖烈焰，AuraService 給隊友）。 */
    public static final String PRESENCE_ALLY_EXTRA_FIRE = "presence.ally_extra_fire";
    /** FLAT 1：存在範圍內的敵人照牠最低的元素抗性抵抗元素傷害（神聖烈焰，combat.Presence）。 */
    public static final String PRESENCE_LOWEST_RESIST = "flag.presence_lowest_resist";
    /** FLAT：1 = 裝備沒有屬性需求、2 = 技能寶石沒有屬性需求（謎容的兩種，掉落時決定；TreeEffectKeys、GemAttributes）。 */
    public static final String NO_ATTR_REQ = "attr_req.none";
    /** FLAT 1：會被元素地面強化的疾風技能（旋矛擊的旋風、旋風矛）視為受到點燃／冰緩／感電地面強化（貝雷克的戒指、元素之章；skill.WindGround）。 */
    public static final String WIND_GROUND_FIRE = "wind.ground.fire";
    public static final String WIND_GROUND_COLD = "wind.ground.cold";
    public static final String WIND_GROUND_LIGHTNING = "wind.ground.lightning";
    /** FLAT 1：疾風技能可以同時被好幾種元素地面強化（元素之章）。 */
    public static final String WIND_GROUND_MULTIPLE = "flag.wind_ground_multiple";

    /**
     * 2026-10-27：傳奇直接沿用的天賦樹、昇華、輔助的屬性鍵（那邊已經有程式在讀；裝備的詞綴一樣進角色屬性表）。
     * 資料檢查（UniqueDataTest）把這些也算進傳奇可以用的屬性。
     */
    public static final Set<String> SHARED = Set.of(
            tw.exilecore.stats.TreeEffectKeys.ACCURACY_RANGE_PENALTY, tw.exilecore.stats.TreeEffectKeys.CORPSE_SAVE,
            tw.exilecore.stats.TreeEffectKeys.DEFLECT_PREVENT, tw.exilecore.stats.TreeEffectKeys.ES_BYPASS,
            tw.exilecore.stats.TreeEffectKeys.LIFE_CONVERT_ES, tw.exilecore.stats.TreeEffectKeys.MINION_REVIVE_SPEED,
            tw.exilecore.stats.TreeEffectKeys.QUIVER_EFFECT, tw.exilecore.stats.TreeEffectKeys.RAGE_DECAY,
            tw.exilecore.stats.TreeKeys.CHILL_EFFECT_ON_SELF, tw.exilecore.stats.TreeKeys.IGNITE_EFFECT_ON_SELF,
            tw.exilecore.stats.TreeKeys.SHOCK_EFFECT_ON_SELF, tw.exilecore.stats.TreeKeys.LIFE_COST_PERCENT,
            tw.exilecore.stats.TreeNewMechanicKeys.AGGRAVATE_ON_CRIT, tw.exilecore.stats.TreeNewMechanicKeys.COMPANION_TAKES,
            tw.exilecore.stats.TreeLastKeys.FLAG_NO_LIGHT_STUN, tw.exilecore.stats.TreeMechanicKeys.PARRIED_EFFECT,
            tw.exilecore.stats.KeystoneKeys.FLAG_GIANTS_BLOOD, tw.exilecore.stats.KeystoneKeys.CURSE_LIMIT,
            tw.exilecore.stats.KeystoneKeys.UNLUCKY_TAKEN, tw.exilecore.stats.KeystoneKeys.RECOUP_PREVENTED,
            tw.exilecore.stats.KeystoneKeys.MINION_LIFE_AS_ES, tw.exilecore.stats.KeystoneKeys.SKILL_LIMIT,
            tw.exilecore.stats.KeystoneKeys.THORNS_CRIT, tw.exilecore.stats.SupportKeys.CULL_THRESHOLD,
            tw.exilecore.stats.SupportKeys.POISON_STACKS, tw.exilecore.stats.ConditionKeys.GUARD_SLOW,
            tw.exilecore.stats.ConditionKeys.LIFE_PER_ENDURANCE_SPENT, tw.exilecore.skill.SkillResources.RAGE_REGEN,
            tw.exilecore.ascendancy.AscKeys.DECIMATING, tw.exilecore.ascendancy.AscKeys.ENEMY_ACCURACY_FALLOFF,
            tw.exilecore.ascendancy.AscKeys.AVOID_DEATH,
            // 2026-10-28 照 PoE2：補上的手套傳奇與它們的磐石之拳版本
            tw.exilecore.stats.KeystoneKeys.FLAG_HOLLOW_PALM, tw.exilecore.stats.KeystoneKeys.ES_RECOUP,
            tw.exilecore.stats.SupportExtraKeys.FLAG_LUCKY_SURROUNDED, tw.exilecore.stats.SupportExtraKeys.FLAG_NO_EXTRACT,
            tw.exilecore.stats.SupportKeys.CULL_THRESHOLD_ON_CULL, tw.exilecore.stats.TreeNewMechanicKeys.CRIT_BASE_EXTRA,
            tw.exilecore.stats.TreeNewMechanicKeys.VOLATILITY_ON_KILL, tw.exilecore.stats.TreeEffectKeys.SKILL_QUALITY,
            tw.exilecore.ascendancy.AscKeys.DOUBLE_CHILL);

    /** 這件物品自己的（local.）屬性：不進角色屬性表，由 Runes、ItemStats 讀物品的詞綴。 */
    public static final Set<String> LOCAL_ITEM = Set.of(LOCAL_RUNE_EFFECT, LOCAL_RUNE_AS_SHIELD, LOCAL_REQUIREMENT_STR,
            LOCAL_REQUIREMENT_DEX, LOCAL_REQUIREMENT_INT);

    /** 某種傷害轉換為另一種的屬性鍵（{@link #CONVERT_FROM}）。 */
    public static String convertFrom(final tw.exilecore.stats.DamageType from, final tw.exilecore.stats.DamageType to) {
        return CONVERT_FROM + from.tag() + "." + to.tag();
    }

    /** 這裡所有的屬性鍵（資料檢查用；GRANT_SKILL、SKILL_TAG_PREFIX、CONVERT_FROM 是前綴，不算；轉換的每一種組合、{@link #SHARED} 另外加）。 */
    public static Set<String> all() {
        final Set<String> keys = new HashSet<>(SHARED);
        for (final tw.exilecore.stats.DamageType from : tw.exilecore.stats.DamageType.values()) {
            // 2026-10-27（舞影外套）：受到的某種傷害增加／減少（減益用的 debuff.taken.類型，傷害計算照類型讀）
            keys.add(tw.exilecore.stats.StatKeys.DEBUFF_TAKEN + from.tag());
            for (final tw.exilecore.stats.DamageType to : tw.exilecore.stats.DamageType.values()) {
                keys.add(gainFrom(from, to)); // 2026-10-28（畫家的侍從：元素傷害也獲得為同一種）
                if (from != to) {
                    keys.add(convertFrom(from, to));
                    // 2026-10-27（夜落塔盾）：受到的某種傷害視為另一種（昇華的 asc.taken.來源.目標，AscendancyEffects.reroute 讀）
                    keys.add(tw.exilecore.ascendancy.AscKeys.TAKEN_AS + from.tag() + "." + to.tag());
                }
            }
        }
        for (final Field field : UniqueStats.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && !field.getName().equals("GRANT_SKILL") && !field.getName().equals("SKILL_TAG_PREFIX")
                    && !field.getName().equals("RECENT_RELOAD") && !field.getName().equals("CONVERT_FROM")
                    && !field.getName().equals("GAIN_FROM") && !field.getName().equals("IMMUNE_AFTER")
                    && !field.getName().equals("ALLY_CHARGE_ON_HIT")) {
                try {
                    keys.add((String) field.get(null));
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        for (final String ailment : java.util.List.of("chill", "freeze", "ignite", "shock")) {
            keys.add(IMMUNE_AFTER + ailment); // 2026-10-28（悲運之縛）
        }
        for (final String charge : java.util.List.of("frenzy", "endurance", "power")) {
            keys.add(ALLY_CHARGE_ON_HIT + charge); // 2026-10-28（磐石之拳的意識之緣）
        }
        return keys;
    }
}
