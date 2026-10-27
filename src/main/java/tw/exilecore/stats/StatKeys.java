package tw.exilecore.stats;

/**
 * 屬性鍵。設定檔可以直接引用這些字串；新增屬性只要加常數（或直接在設定檔用新字串）。
 */
public final class StatKeys {

    private StatKeys() {
    }

    // 資源
    public static final String LIFE_MAX = "life.max";
    public static final String LIFE_REGEN = "life.regen";          // 每秒回復量
    public static final String MANA_MAX = "mana.max";
    public static final String MANA_REGEN = "mana.regen";          // 每秒回復量
    public static final String ES_MAX = "es.max";
    /** INCREASED／MORE：能量護盾回充的速度（基礎：延遲 4 秒過後每秒回最大值的 12.5%）。 */
    public static final String ES_RECHARGE = "es.recharge";
    /** INCREASED（負的 = 縮短）：被打之後，能量護盾要等多久才開始回充（基礎 2 秒，最短剩 20%）。 */
    public static final String ES_DELAY = "es.delay";

    // 防禦
    public static final String ARMOUR = "armour";
    public static final String EVASION = "evasion";
    public static final String BLOCK_CHANCE = "block.chance";      // 百分比點數（25 = 25%）
    public static final String RESIST_FIRE = "resist.fire";        // 百分比點數
    public static final String RESIST_COLD = "resist.cold";
    public static final String RESIST_LIGHTNING = "resist.lightning";
    public static final String RESIST_CHAOS = "resist.chaos";
    public static final String RESIST_MAX = "resist.max";          // 抗性上限，預設 75

    // 傷害：增加／更多用 DAMAGE 配標籤；附加固定值用 DAMAGE_ADDED_*
    public static final String DAMAGE = "damage";
    public static final String DAMAGE_ADDED_MIN = "damage.added.min";  // 配 damageType 標籤
    public static final String DAMAGE_ADDED_MAX = "damage.added.max";

    // 武器基礎（由裝備的武器提供，FLAT）
    public static final String WEAPON_MIN = "weapon.min";          // 配 damageType 標籤
    public static final String WEAPON_MAX = "weapon.max";
    public static final String WEAPON_APS = "weapon.aps";          // 每秒攻擊次數
    public static final String WEAPON_CRIT = "weapon.crit";        // 百分比點數
    public static final String WEAPON_RELOAD = "weapon.reload";    // 2026-10-26：弩的裝填時間（秒，照 PoE2 每把基底不同）

    // 速度與暴擊
    public static final String ATTACK_SPEED = "attack.speed";      // 只用 INCREASED／MORE
    public static final String CAST_SPEED = "cast.speed";
    public static final String CRIT_CHANCE = "crit.chance";        // INCREASED，基礎來自武器
    public static final String CRIT_MULTI = "crit.multi";          // 百分比點數，基礎 200（PoE2：暴擊傷害加成 +100%）
    public static final String ACCURACY = "accuracy";
    public static final String MOVEMENT_SPEED = "movement.speed";  // 只用 INCREASED

    // 屬性值
    public static final String STR = "attr.str";
    public static final String DEX = "attr.dex";
    public static final String INT = "attr.int";

    // 掉落
    public static final String DROP_QUANTITY = "drop.quantity";    // INCREASED
    public static final String DROP_RARITY = "drop.rarity";
    /** 2026-10-18：打怪的經驗多幾 %（INCREASED；稱號這類加成用，跟累積贊助的經驗加成加在一起算）。 */
    public static final String EXPERIENCE_GAIN = "experience.gain";
    /** 2026-10-18：怪物掉給你的金幣多幾 %（INCREASED；稱號這類加成用，跟累積贊助的金幣加成加在一起算）。 */
    public static final String GOLD_FIND = "gold.find";

    // 藥劑
    /** INCREASED：喝生命藥劑回復的量（任務獎勵）。 */
    public static final String FLASK_LIFE_RECOVERY = "flask.life_recovery";
    /** INCREASED：喝魔力藥劑回復的量。 */
    public static final String FLASK_MANA_RECOVERY = "flask.mana_recovery";

    // 技能參數（輔助寶石；之後天賦與詞綴也能用）：用技能自己的標籤當情境查詢
    public static final String SKILL_AREA = "skill.area";                         // INCREASED／MORE：範圍效果，半徑 = √倍率
    public static final String SKILL_PROJECTILES = "skill.projectiles";           // FLAT：額外的投射物
    public static final String SKILL_PROJECTILE_SPEED = "skill.projectile_speed"; // INCREASED／MORE
    public static final String SKILL_PIERCE = "skill.pierce";                     // FLAT：投射物多穿透幾個敵人
    public static final String SKILL_CHAINS = "skill.chains";                     // FLAT：多連鎖幾次
    public static final String SKILL_COST = "skill.cost";                         // INCREASED／MORE：魔力消耗倍率
    /** INCREASED／MORE：技能的持續時間（地面效果、印記、增益、圖騰、陷阱、召喚物）。 */
    public static final String SKILL_DURATION = "skill.duration";
    /** INCREASED／MORE：冷卻時間（負的 = 更短）。 */
    public static final String SKILL_COOLDOWN = "skill.cooldown";
    /** 2026-10-26（PoE2）：冷卻回復速度（INCREASED／MORE；冷卻時間 ÷ 這個倍率）。換界石「玩家冷卻回復速度更少」用它。 */
    public static final String COOLDOWN_RECOVERY = "cooldown.recovery";
    /** 2026-10-26（PoE2）：身上受到的詛咒效果（INCREASED／MORE，看被詛咒的那一方；換界石「怪物受到的詛咒效果更少」、稀有度）。 */
    public static final String CURSE_EFFECT_ON_SELF = "curse.effect_on_self";
    /** 2026-10：沒有冷卻的技能多出 x 秒冷卻（FLAT；蓄域、蓄鋒這類輔助）。 */
    public static final String SKILL_ADDED_COOLDOWN = "skill.added_cooldown";
    /** FLAT：投射物第一次打中時分裂成幾發（歧射輔助）。 */
    public static final String SKILL_FORK = "skill.fork";
    /** 開關：投射物停下來之後飛回施放者身邊，路上再打一次（回歸輔助）。 */
    public static final String FLAG_RETURNING = "flag.returning";
    /** 開關：法術施放後再自動重複一次（雙詠）。 */
    public static final String FLAG_ECHO = "flag.echo";
    /** FLAT：近戰攻擊按一次之後自動再打幾下（複擊；每一下照攻擊間隔，魔力只扣一次，打完才能做別的）。 */
    public static final String SKILL_REPEATS = "skill.repeats";
    /** FLAT：打擊技能（strike 標籤、只打一個的）多打幾個附近的敵人（2026-10，照 PoE 的「打擊技能額外目標」）。 */
    public static final String STRIKE_TARGETS = "skill.strike_targets";
    /** FLAT：近戰打擊打中時，目標周圍 2.5 格（吃範圍效果）的其他敵人也受到這一下 x% 的傷害（近戰擴散，2026-10）。 */
    public static final String MELEE_SPLASH = "skill.melee_splash";
    /** 開關：法術改由圖騰施放（圖騰施法輔助）。 */
    public static final String FLAG_SPELL_TOTEM = "flag.spell_totem";
    /** 開關：法術改成丟出去的陷阱（陷阱施法輔助）。 */
    public static final String FLAG_TRAP = "flag.trap";
    /** FLAT：同時最多多幾個圖騰。 */
    public static final String SKILL_TOTEMS = "skill.totems";
    /** 2026-10-25：主要寶石的能量（輔助掛在引星上）：INCREASED 能量獲得、INCREASED 能量上限、FLAT 觸發時退還一半能量的機率 %。 */
    public static final String META_ENERGY_GAIN = "meta.energy_gain";
    public static final String META_ENERGY_MAX = "meta.energy_max";
    public static final String META_ENERGY_REFUND = "meta.energy_refund";
    /** FLAT：召喚技能同時最多多幾隻召喚物（跟技能的標籤；魔像這種固定一隻的不吃）。 */
    public static final String SKILL_MINIONS = "skill.minions";
    /** FLAT：每秒回復最大生命的 x%（生機光環；帶 minion 標籤的給召喚物）。 */
    public static final String LIFE_REGEN_PERCENT = "life.regen_percent";
    /** INCREASED／MORE：你放的光環的效果（光環的每一條數值都乘）。 */
    public static final String AURA_EFFECT = "aura.effect";
    /** 2026-10：詛咒效果（INCREASED／MORE）。2026-10-22 照 PoE2：只給詛咒，印記改吃 {@link #MARK_EFFECT}。 */
    public static final String CURSE_EFFECT = "curse.effect";
    /** 2026-10-22：印記效果（INCREASED／MORE；以前跟詛咒共用 curse.effect）。 */
    public static final String MARK_EFFECT = "mark.effect";
    /** 2026-10：戰吼效果（增益、強化、敵人受到的傷害；INCREASED／MORE）。 */
    public static final String WARCRY_EFFECT = "warcry.effect";
    /** 2026-10：掛在精魂技能上的輔助多保留的精魂（FLAT）。 */
    public static final String SUPPORT_SPIRIT = "support.spirit";
    /** 2026-10：荊棘傷害（INCREASED）。 */
    public static final String THORNS_DAMAGE = "thorns.damage";
    /** 2026-10：「grant.」開頭的屬性 = 被輔助的精魂技能開著時，你自己得到後面那條（精魂輔助：清明、活力…）。 */
    public static final String GRANT_PREFIX = "grant.";
    /** 2026-10：擊退距離（FLAT 加在技能本來的擊退上，INCREASED／MORE 乘上去）。 */
    public static final String SKILL_KNOCKBACK = "skill.knockback";
    /** INCREASED：光環的魔力保留（負的 = 保留比較少）。 */
    public static final String AURA_RESERVATION = "aura.reservation";
    /** FLAT：被光環保留的最大魔力百分比（光環系統算好之後放進屬性表；{@code PlayerState#maxMana} 用它縮小魔力池）。 */
    public static final String MANA_RESERVED = "mana.reserved";
    /** 2026-10：精魂（PoE2）：光環、捷光環等常駐技能保留的點數池。FLAT／INCREASED；基礎來自劇情 Boss 與權杖。 */
    public static final String SPIRIT_MAX = "spirit.max";
    /** 2026-10：已經被光環保留的精魂點數（AuraService 寫進屬性表）。 */
    public static final String SPIRIT_RESERVED = "spirit.reserved";
    /** 開關（2026-10，元素的庇護）：光環保留生命而不是魔力（魔力池不縮、最大生命照保留的比例縮小，最多保留 99%、至少留 1 點）。 */
    public static final String FLAG_AURA_LIFE = "flag.aura_life";
    /** FLAT：戰吼之後多強化幾次攻擊。 */
    public static final String WARCRY_EXERTS = "warcry.exerts";
    /** FLAT：怒嘯（2026-10）：戰吼吼出時用掉全部怒氣，每點怒氣讓強化攻擊更多 x% 傷害（最多 {@link #WARCRY_RAGE_CAP} 點）。 */
    public static final String WARCRY_RAGE_EXERT = "warcry.rage_exert";
    public static final int WARCRY_RAGE_CAP = 30;
    /** 開關：光環不影響自己，只給隊友與召喚物（效果更強）。 */
    public static final String FLAG_GENEROSITY = "flag.generosity";
    // ---- 昇華的招牌天賦（2026-10，每個昇華一個，FLAT 1；效果在 combat/Signatures） ----
    public static final String SIG_LAST_STAND = "sig.last_stand";
    public static final String SIG_BLOOD_RAGE = "sig.blood_rage";
    public static final String SIG_FAR_SHOT = "sig.far_shot";
    public static final String SIG_WINDWALK = "sig.windwalk";
    public static final String SIG_WILDFIRE = "sig.wildfire";
    public static final String SIG_WINTER_TOMB = "sig.winter_tomb";
    public static final String SIG_PLAGUE_LORD = "sig.plague_lord";
    public static final String SIG_BLOOD_FEAST = "sig.blood_feast";
    public static final String SIG_VERDICT = "sig.verdict";
    public static final String SIG_LEGION = "sig.legion";
    public static final String SIG_THUNDER_SOUL = "sig.thunder_soul";
    public static final String SIG_VOID_STEP = "sig.void_step";
    public static final String SIG_GODSLAYER = "sig.godslayer";
    public static final String SIG_SACRIFICE = "sig.sacrifice";
    /** 造成的物理傷害有 x% 偷取為生命（百分比點數，FLAT）。 */
    public static final String LEECH_LIFE = "leech.life";
    /** 造成的物理傷害有 x% 偷取為魔力（百分比點數，FLAT；跟生命偷取一樣先存起來再慢慢回）。 */
    public static final String LEECH_MANA = "leech.mana";
    // ---- 屍體（2026-10-17，傳奇「踏骸之履」）----
    /** 移動時在腳下留下屍體（FLAG：FLAT 1；每 1.5 秒最多一具）。 */
    public static final String CORPSE_WALK = "corpse.walk";
    /** 附近（6 格）每具屍體，每秒回復 x 生命（FLAT；最多算 15 具）。 */
    public static final String CORPSE_LIFE_REGEN = "corpse.life_regen";
    /** 附近（6 格）每具屍體，增加 x% 移動速度（FLAT 百分比點數；最多算 15 具）。 */
    public static final String CORPSE_MOVEMENT = "corpse.movement";
    // ---- 偷取（2026-10，照 PoE）----
    /** 偷取有 x% 立即生效（FLAT 百分比點數；其餘照常慢慢回）。 */
    public static final String LEECH_INSTANT = "leech.instant";
    /** 最大偷取速度（INCREASED：每秒最多回 20% 最大生命／魔力的那個上限；2026-10-27 照 PoE2，這次之前 10%）。 */
    public static final String LEECH_RATE = "leech.rate";
    /** 偷取的量（INCREASED；地圖詞綴可以是負的）。 */
    public static final String LEECH_EFFECT = "leech.effect";
    /** 造成的元素傷害（火焰、冰冷、閃電）有 x% 偷取為生命（FLAT 百分比點數）。 */
    public static final String LEECH_LIFE_ELEMENTAL = "leech.life_elemental";
    /** 開關：不能偷取（地圖詞綴「玩家無法從怪物身上偷取」）。 */
    public static final String FLAG_NO_LEECH = "flag.no_leech";
    /** 開關：偷取全部立即生效（基石「血之契約」）。 */
    public static final String FLAG_INSTANT_LEECH = "flag.instant_leech";
    /** 被近戰攻擊擊中時，對攻擊者反彈的物理傷害（荊棘）：下限與上限，FLAT。 */
    public static final String THORNS_MIN = "thorns.min";
    public static final String THORNS_MAX = "thorns.max";
    /** 2026-10-17：荊棘也會被遠程攻擊、法術擊中觸發（FLAG：FLAT 1；以前只有近戰貼身打你才會）。 */
    public static final String FLAG_THORNS_ANY_HIT = "flag.thorns_any_hit";
    /** 球（銳星、怒星、磐星）：最大顆數，後面接球的代號（charges.yml），FLAT；基礎值在 charges.yml。 */
    public static final String CHARGE_MAX = "charge.max.";
    /** 球：每次觸發（暴擊、擊殺、被擊中，照 charges.yml）獲得一顆的機率，百分比點數，FLAT；後面接球的代號。 */
    public static final String CHARGE_CHANCE = "charge.chance.";
    /**
     * 球：你擊中敵人時獲得一顆的機率（任何種類都可以；每打中一個敵人擲一次，召喚物打的不算），百分比點數，FLAT；後面接球的代號。
     * 照這一下的技能算：輔助寶石給的只對被輔助的技能有效，可以加技能種類的標籤（[melee] = 近戰擊中時）。
     */
    public static final String CHARGE_ON_HIT = "charge.on_hit.";
    /** INCREASED：球的持續時間（所有種類）。 */
    public static final String CHARGE_DURATION = "charge.duration";

    public static String chargeMax(final String chargeId) {
        return CHARGE_MAX + chargeId;
    }

    public static String chargeChance(final String chargeId) {
        return CHARGE_CHANCE + chargeId;
    }

    public static String chargeOnHit(final String chargeId) {
        return CHARGE_ON_HIT + chargeId;
    }

    /** 2026-10：物理傷害的 x% 轉換為某個類型（百分比點數，FLAT；跟技能自己的轉換加總，最多 100%）；例如 damage.convert.lightning。 */
    public static final String DAMAGE_CONVERT = "damage.convert.";
    /** 2026-10：FLAT 1 = 這個技能只造成這個類型的傷害，其他類型都不算（隨機元素的稜光裂）；例如 damage.only.fire。 */
    public static final String DAMAGE_ONLY = "damage.only.";
    /** 2026-10：FLAT：穿透 x% 抗性（tags 限定類型，例如 [elemental]、[fire]）；在抗性上限之後扣。 */
    public static final String PENETRATION = "penetration";
    /** 2026-10：身上現在有幾顆某種球（ChargeService 自動放進屬性表；後面接球的代號）。 */
    public static final String CHARGE_HELD = "charge.held.";
    /** 2026-10-24：獲得戰星時多一顆同種的機率（FLAT 百分比點數；蛇卵墜 100）。同 ChargeService.EXTRA。 */
    public static final String CHARGE_EXTRA = "charge.extra";
    /** 2026-10-24（憎鑄）：怒氣滿時給一顆隨機戰星，至少隔 x 秒。同 ChargeService.ON_MAX_RAGE。 */
    public static final String CHARGE_ON_MAX_RAGE = "charge.on_max_rage";
    /** 2026-10-24（憎鑄）：怒氣滿時失去全部怒氣。 */
    public static final String FLAG_LOSE_RAGE_AT_MAX = "flag.lose_rage_at_max";
    /** 2026-10-24（共鳴基石）：戰星輪換。同 ChargeService.RESONANCE。 */
    public static final String FLAG_RESONANCE = "flag.resonance";
    /** 2026-10-24（招架，PoE2 Parry）：舉盾中，下一次被攻擊一定格擋、攻擊者「被招架」。 */
    public static final String FLAG_PARRY = "flag.parry";
    /** 2026-10-24（馭星訣的品質）：身上每顆戰星（不分種類）增加 x% 傷害，FLAT 百分比點數。 */
    public static final String DAMAGE_PER_CHARGE = "damage.per_charge";
    /** 2026-10-24（熔殼，PoE2 Magma Barrier）：盾牌灌熔岩，灌滿後格擋噴熔岩、給磐星。 */
    public static final String FLAG_MAGMA_BARRIER = "flag.magma_barrier";
    /** 2026-10：FLAT：每顆怒星讓這個技能更多 x% 攻擊傷害與 x% 攻擊速度（怒星箭）。 */
    public static final String PER_FRENZY = "skill.per_frenzy";
    /** 2026-10：FLAT 1：每次施放隨機選一種元素（火焰、冰冷、閃電），物理全部轉成它、只造成那種傷害（稜光裂）。 */
    public static final String FLAG_RANDOM_ELEMENT = "flag.random_element";
    /** 2026-10：FLAT 1：三相輔助（元素共鳴）。 */
    public static final String FLAG_TRINITY = "flag.trinity";

    /** 2026-10：INCREASED／MORE：圖騰的生命（圖騰的生命 = 施放者最大生命的一半，被替身圖騰引過來的怪會打它）。 */
    public static final String TOTEM_LIFE = "totem.life";
    /** 2026-10-26：FLAT：圖騰的元素抗性（PoE2 的英靈圖騰 0→75%）。 */
    public static final String TOTEM_RESIST_ELEMENTAL = "totem.resist_elemental";
    /** 2026-10-26：FLAT：圖騰的混沌抗性（PoE2 的英靈圖騰 0→35%）。 */
    public static final String TOTEM_RESIST_CHAOS = "totem.resist_chaos";
    /** 2026-10：FLAT 1：圖騰不施法，改成嘲諷附近的怪物來打它（替身圖騰）。 */
    public static final String FLAG_TOTEM_TAUNT = "flag.totem_taunt";
    /** 2026-10：FLAT 1：圖騰不施法，改成你與圖騰之間連起焚燒的火鏈（焚鏈圖騰）。 */
    public static final String FLAG_SEARING_BOND = "flag.searing_bond";
    /** 2026-10：FLAT 1：圖騰放到上限時全部引爆（異常・焚鏈圖騰）。 */
    public static final String FLAG_TOTEM_DETONATE = "flag.totem_detonate";
    /** 2026-10：FLAT：圖騰被打（或你在圖騰旁邊被打）時，射出怨燼：造成圖騰最大生命 x% 的火焰傷害（基礎值，冷卻 1 秒；怨燼輔助）。 */
    public static final String TOTEM_VENGEFUL = "totem.vengeful";

    public static String damageConvert(final DamageType type) {
        return DAMAGE_CONVERT + type.tag();
    }

    /** 物理傷害的 x% 額外獲得為某個類型（百分比點數，FLAT）；後面接類型標籤，例如 damage.gain.fire。 */
    public static final String DAMAGE_GAIN = "damage.gain.";

    /** 「物理傷害額外獲得為這個類型」的屬性鍵。 */
    public static String damageGain(final DamageType type) {
        return DAMAGE_GAIN + type.tag();
    }

    // 元素異常（階段 3 批次 3）：攻擊方的用技能標籤＋傷害類型當情境查詢（「增加 x% 法術的點燃機率」要有 spell）
    /** FLAT：點燃機率 +x%（百分比點數，直接加在算出來的機率上）；INCREASED：增加 x% 點燃機率。 */
    public static final String IGNITE_CHANCE = "ignite.chance";
    /** INCREASED／MORE：點燃的傷害。 */
    public static final String IGNITE_MAGNITUDE = "ignite.magnitude";
    public static final String IGNITE_DURATION = "ignite.duration";
    /** INCREASED：冰緩效果（減速的幅度）。 */
    public static final String CHILL_EFFECT = "chill.effect";
    public static final String CHILL_DURATION = "chill.duration";
    /** INCREASED／MORE：凍結累積。 */
    public static final String FREEZE_BUILDUP = "freeze.buildup";
    public static final String FREEZE_DURATION = "freeze.duration";
    /** FLAT：感電機率 +x%；INCREASED：增加 x% 感電機率。 */
    public static final String SHOCK_CHANCE = "shock.chance";
    /** INCREASED：感電效果（目標受到的傷害增加多少）。 */
    public static final String SHOCK_EFFECT = "shock.effect";
    public static final String SHOCK_DURATION = "shock.duration";
    /** INCREASED：所有異常的持續時間（造成的；元素異常、流血、中毒都算）。 */
    public static final String AILMENT_DURATION = "ailment.duration";
    // 流血與中毒（PoE2 的物理／混沌異常）
    /** FLAT：流血機率 +x%（只有攻擊的物理傷害會流血）；INCREASED：增加 x% 機率。 */
    public static final String BLEED_CHANCE = "bleed.chance";
    /** INCREASED／MORE：流血的傷害。 */
    public static final String BLEED_MAGNITUDE = "bleed.magnitude";
    public static final String BLEED_DURATION = "bleed.duration";
    /** FLAT：中毒機率 +x%（物理與混沌傷害都會中毒，可以疊加）；INCREASED：增加 x% 機率。 */
    public static final String POISON_CHANCE = "poison.chance";
    /** INCREASED／MORE：中毒的傷害。 */
    public static final String POISON_MAGNITUDE = "poison.magnitude";
    public static final String POISON_DURATION = "poison.duration";
    /** 開關：這個技能不能造成元素異常（純化輔助）。 */
    public static final String FLAG_NO_AILMENTS = "flag.no_elemental_ailments";

    // 元素異常的防禦方
    /** INCREASED：異常門檻（越高越不容易被點燃、冰緩、凍結、感電）。 */
    public static final String AILMENT_THRESHOLD = "ailment.threshold";
    /** FLAT：避免被點燃的機率（百分比點數）。 */
    public static final String AVOID_IGNITE = "avoid.ignite";
    public static final String AVOID_CHILL = "avoid.chill";
    public static final String AVOID_FREEZE = "avoid.freeze";
    public static final String AVOID_SHOCK = "avoid.shock";
    public static final String AVOID_BLEED = "avoid.bleed";
    /** 暈眩累積（攻擊方：INCREASED／MORE；PoE2 的重暈）。 */
    public static final String STUN_BUILDUP = "stun.buildup";
    /** FLAT：破甲，擊中時破掉「減免前物理傷害 × x%」的護甲（2026-10 PoE2；對普通怪 ×3、魔法 ×2）。 */
    public static final String ARMOUR_BREAK = "armour.break";
    /** 2026-10-26：FLAT：擊中時另外破掉固定這麼多護甲（PoE2 的 Armour Piercing Rounds「Break X Armour」；「增加破甲」也乘）。 */
    public static final String ARMOUR_BREAK_FLAT = "armour.break_flat";
    // ---- 2026-10 PoE2 裝備詞綴 ----
    /** INCREASED：暴擊傷害加成（PoE2「增加 x% 暴擊傷害加成」：只乘加成的部分，200% 暴擊 → 加成 100% × (1 + x)）。 */
    public static final String CRIT_BONUS = "crit.bonus";
    /** FLAT：護甲的 x% 也套用在元素傷害上（在抗性之前減）。 */
    public static final String ARMOUR_ELEMENTAL = "armour.elemental";
    /** FLAT：額外的物理傷害減免（百分比點數，跟護甲相加，上限 90%）。 */
    public static final String PHYS_REDUCTION = "physical.reduction";
    /** INCREASED（負的 = 減少）：擊中你的暴擊傷害加成。 */
    public static final String CRIT_BONUS_TAKEN = "crit.bonus_taken";
    /** FLAT：擊殺時回復固定生命／魔力。 */
    public static final String LIFE_PER_KILL = "life.per_kill";
    public static final String MANA_PER_KILL = "mana.per_kill";
    /** FLAT：某一種元素的抗性上限（加在 resist.max 上）。 */
    public static String resistMax(final tw.exilecore.stats.DamageType type) {
        return RESIST_MAX + "." + type.tag();
    }
    /** FLAT：把「全部」傷害的 x% 額外獲得為這個類型（PoE2「獲得 x% 傷害為額外火焰傷害」；跟只算物理的 damage.gain 不同）。 */
    public static String damageExtra(final tw.exilecore.stats.DamageType type) {
        return DAMAGE_EXTRA + type.tag();
    }
    public static final String DAMAGE_EXTRA = "damage.extra.";
    /**
     * FLAT：每 100 點最大魔力，把「全部」傷害的 x% 額外獲得為這個類型（2026-10，照 PoE2 的 Archmage：祕法之心）。
     * 算法跟 {@link #damageExtra} 一樣，只是百分比 = x × 最大魔力 ÷ 100；引導技能不算。
     */
    public static String damageExtraPerMana(final tw.exilecore.stats.DamageType type) {
        return DAMAGE_EXTRA_PER_MANA + type.tag();
    }
    public static final String DAMAGE_EXTRA_PER_MANA = "damage.extra_per_mana.";
    /** FLAT：技能的魔力消耗多「最大魔力的 x%」（2026-10，祕法之心；加在基礎消耗上，再乘魔力消耗倍率；本來不用魔力的、引導技能不算）。 */
    public static final String SKILL_COST_MAX_MANA = "skill.cost_max_mana";
    /** 旗標：擊中蓄勢待暈的敵人時直接重暈（PoE2 的碎骨這類）。 */
    public static final String FLAG_CRUSH_PRIMED = "flag.crush_primed";
    // ---- 2026-10 PoE2 基石 ----
    /** 天賦樹的屬性點（PoE2）：+x 任一屬性，玩家自己選（存在角色進度 attr.<節點代號>）。 */
    public static final String ATTR_CHOICE = "attr.choice";
    public static final String FLAG_BLOOD_MAGIC = "flag.blood_magic";           // 血魔法：沒有魔力，技能改扣生命
    public static final String FLAG_ELDRITCH_BATTERY = "flag.eldritch_battery"; // 靈能電池：能量護盾全部變成最大魔力
    public static final String FLAG_BLACKFLAME = "flag.blackflame";             // 黑焰之約：火焰法術的火焰傷害全部轉成混沌
    public static final String FLAG_AVATAR_FIRE = "flag.avatar_of_fire";        // 火焰化身：75% 傷害轉成火焰，不造成非火焰傷害
    public static final String FLAG_PAIN_ATTUNEMENT = "flag.pain_attunement";   // 痛苦調和：滿血暴擊加成更少 30%，低血更多 30%
    public static final String FLAG_DANCE_WITH_DEATH = "flag.dance_with_death"; // 與死共舞：單手武器、副手空著時技能速度更多 25%
    public static final String FLAG_ZEALOTS_OATH = "flag.zealots_oath";         // 狂熱誓言：溢出的生命回復變成護盾，護盾不回充
    public static final String FLAG_ETERNAL_YOUTH = "flag.eternal_youth";       // 永恆青春：生命像護盾一樣回充，護盾不回充
    public static final String FLAG_BULWARK = "flag.bulwark";                   // 壁壘：翻滾不能躲傷害，翻滾中受到的擊中傷害更少 30%
    public static final String FLAG_UNWAVERING = "flag.unwavering_stance";      // 不動如山：不會被輕暈，不能翻滾
    public static final String FLAG_CRIMSON_ASSAULT = "flag.crimson_assault";   // 猩紅突襲：流血加劇、基礎 1 秒、更多 50%
    public static final String FLAG_ANCESTRAL_BOND = "flag.ancestral_bond";     // 祖靈契約：圖騰上限加倍
    // ---- 2026-10 PoE2 昇華 ----
    public static final String FLAG_STONE_SKIN = "flag.stone_skin";             // 泰坦：胸甲的護甲更多 50%
    public static final String FLAG_HULKING_FORM = "flag.hulking_form";         // 泰坦：天賦樹小點效果增加 50%
    public static final String DAMAGE_VS_STUNNED = "damage.vs_stunned";         // MORE：對重暈中的敵人
    /** 2026-10-21：受到的那一種傷害增加 x%（減益：凋息、枯蝕、縛根）；後面接傷害類型的標籤（physical、chaos…）。只在算擊中時疊上去。 */
    public static final String DEBUFF_TAKEN = "debuff.taken.";
    public static final String DAMAGE_VS_LOW_LIFE = "damage.vs_low_life";       // INCREASED：對低血（35% 以下）的敵人
    public static final String FLAG_IMPLODING = "flag.imploding_impacts";       // 戰爭使者：完全破甲讓所有擊中傷害都增加
    public static final String BLOCK_MAX = "block.max";                         // FLAT：格擋上限（加在 50 上）
    public static final String BLOCK_TAKEN = "block.taken";                     // FLAT：被格擋的擊中仍受到 x% 傷害
    public static final String FLAG_RENLY = "flag.renly_training";              // 戰爭使者：盾的基礎格擋率改成 35%
    public static final String FLAG_STAY_LIGHT = "flag.stay_light";             // 戰術家：閃避最多 50%、物理減傷最多 50%
    public static final String FLAG_AMAZON_CRIT = "flag.amazon_crit";           // 亞馬遜：命中率超過 100% 的部分的 25% 變成暴擊率
    public static final String FLAG_STALKING_PANTHER = "flag.stalking_panther"; // 亞馬遜：頭、腿、鞋的閃避加倍，胸甲的減半
    public static final String FLAG_LEECH_ELEMENTAL_ALL = "flag.leech_elemental_all"; // 元素傷害也照物理的偷取率偷取
    public static final String FLAG_LEECH_CHAOS = "flag.leech_chaos";           // 混沌傷害也照物理的偷取率偷取
    public static final String LEECH_SPELL = "leech.life_spell";                // FLAT：法術傷害的 x% 偷取為生命
    public static final String FLAG_MYSTIC_ATTUNEMENT = "flag.mystic_attunement"; // 儀祭者：戒指、項鍊的加成增加 25%
    public static final String LIFE_RECOVERY_PER_MISSING = "life.recovery_per_missing"; // FLAT：每少 5% 生命，生命回復速度增加 x%
    public static final String EVASION_ELEMENTAL = "evasion.elemental";         // FLAT：閃避的 x% 也像護甲一樣減元素傷害
    public static final String FLAG_UNSLOWABLE = "flag.unslowable";             // 開拓者：不會被冰緩
    public static final String FLAG_DOUBLE_POISON = "flag.double_poison";       // 開拓者：中毒的疊加上限加倍
    public static final String FLAG_SUNDER = "flag.sunder_enemies";             // 祈願者：暴擊無視敵人非負的元素抗性
    public static final String FLAG_PROTECT_HARM = "flag.protect_from_harm";    // 祈願者：護甲減傷照護甲＋閃避算
    public static final String FLAG_CHAOS_RES_DOUBLE = "flag.chaos_res_double"; // 夏烏拉：混沌抗性加倍
    public static final String FLAG_ALL_CHILL = "flag.all_damage_chills";       // 風暴編織者：所有傷害都算進冰緩
    public static final String FLAG_ALL_SHOCK = "flag.all_damage_shocks";       // 所有傷害都算進感電機率
    public static final String FLAG_ALL_IGNITE = "flag.all_damage_ignites";     // 所有傷害都算進點燃
    public static final String FLAG_PHASED_FORM = "flag.phased_form";           // 時空術士：受到的傷害更少 30%，4 秒後再受到那一擊 30%
    public static final String FLAG_GRASPING_WOUNDS = "flag.grasping_wounds";   // 血術士：擊中扣的生命 25% 改成 4 秒內慢慢扣
    public static final String BEIDAT_HAND = "flag.beidat_hand";                // 煉獄使者：每 8 最大生命 +1 最大護盾，保留 25% 生命
    public static final String BEIDAT_GAZE = "flag.beidat_gaze";                // 每 6 最大生命 +1 最大魔力，保留 25% 生命
    public static final String BEIDAT_WILL = "flag.beidat_will";                // 每 25 最大生命 +1 精魂，保留 25% 生命
    public static final String SELF_IGNITE_ON_CRIT = "self.ignite_on_crit";     // FLAT：暴擊時自己著火，每秒 x% 最大生命與護盾的火焰傷害
    public static final String FLAG_SPELL_BASE_CRIT = "flag.spell_base_crit_15"; // 血術士：法術基礎暴擊率 15%
    /** 2026-10-15：FLAT：這個法術的基礎暴擊率（百分比點數，照 PoE2 每個法術寶石自己的數字；skills.yml 的 modifiers）。沒寫 = 5%。 */
    public static final String SPELL_BASE_CRIT = "spell.base_crit";
    /** 2026-10-15：FLAT：格擋擊中時讓攻擊者中毒，視同造成這麼多基礎混沌傷害（蛛巢壁壘）。 */
    public static final String BLOCK_POISON = "block.poison";
    /** 2026-10-15：開關：閃電傷害也照物理的偷取率偷取生命（神諭之矛）。 */
    public static final String FLAG_LEECH_LIGHTNING = "flag.leech_lightning";
    public static final String CRIT_BONUS_PER_LIFE = "crit.bonus_per_50_life";  // FLAT：每 50 目前生命增加 x% 暴擊傷害加成
    public static final String FLAG_CRIMSON_POWER = "flag.crimson_power";       // 血術士：+最大生命＝胸甲上的能量護盾
    public static final String FLAG_SANGUIMANCY = "flag.sanguimancy";           // 血術士：技能另外扣同樣多的生命
    public static final String KILL_EXPLODE_CHANCE = "kill.explode_chance";     // FLAT：殺死的敵人 x% 機率爆炸（牠最大生命 100% 的物理傷害）
    /** 自己的暈眩門檻（防禦方：INCREASED，越高越不容易被暈）。 */
    public static final String STUN_THRESHOLD = "stun.threshold";
    public static final String STUN_DURATION = "stun.duration";
    public static final String AVOID_STUN = "avoid.stun";
    /** 電擊機率（FLAT %：閃電傷害擊中時有多少機率累積電擊；0 = 不累積）。 */
    public static final String ELECTROCUTE_CHANCE = "electrocute.chance";
    public static final String ELECTROCUTE_BUILDUP = "electrocute.buildup";
    public static final String ELECTROCUTE_DURATION = "electrocute.duration";
    public static final String AVOID_ELECTROCUTE = "avoid.electrocute";
    public static final String AVOID_POISON = "avoid.poison";
    /** INCREASED（負的 = 減少）：自己身上的元素異常持續時間（最多減到基礎的 10%）。 */
    public static final String SELF_AILMENT_DURATION = "ailment.self_duration";
    /** 避免異常（點燃、冰緩、凍結、感電、流血、中毒、暈眩、電擊）的上限：堆再多最多這麼多 %。要免疫用開關（{@link #FLAG_FREEZE_IMMUNE}）。 */
    public static final double AVOID_CAP = 75;
    /** 開關：無法被凍結（傳奇的「不會被凍結」）。避免凍結有上限，免疫用這個。 */
    public static final String FLAG_FREEZE_IMMUNE = "flag.freeze_immune";
    /** 2026-10：FLAT 1：免疫元素異常（點燃、冰緩、凍結、感電；三元守護）。 */
    public static final String FLAG_ELEMENTAL_AILMENT_IMMUNE = "flag.elemental_ailment_immune";

    // 特殊效果（傳奇裝備）：一律用 FLAT
    /** 每次擊中敵人回復幾點生命。 */
    public static final String LIFE_ON_HIT = "life.on_hit";
    /** 2026-10-26：FLAT：擊中每個敵人回復多少魔力（PoE2 的魔力吸取）。 */
    public static final String MANA_ON_HIT = "mana.on_hit";
    /** 擊殺時回復最大生命的 x%。 */
    public static final String LIFE_ON_KILL = "life.on_kill";
    /** 擊殺時回復最大魔力的 x%。 */
    public static final String MANA_ON_KILL = "mana.on_kill";
    /** 擊殺時回復最大能量護盾的 x%。 */
    public static final String ES_ON_KILL = "es.on_kill";
    /** 殺死的敵人爆炸，對周圍的敵人造成牠最大生命 x% 的火焰傷害。 */
    public static final String KILL_EXPLODE = "kill.explode";
    /** 擊殺時 x% 機率獲得疾速（4 秒：增加 20% 攻擊、施法與移動速度）。 */
    public static final String ONSLAUGHT_ON_KILL = "onslaught.on_kill";
    /** 殺死稀有怪物時獲得牠的詞綴幾秒（追逐級傳奇「逐星者」；FLAT 秒數，0 = 沒有）。 */
    public static final String STEAL_MODS = "steal.mods";
    /** 擊中時 x% 機率造成雙倍傷害（跟技能標籤）。 */
    public static final String DAMAGE_DOUBLE = "damage.double";
    /** 受到的傷害（扣生命的部分）x% 先從魔力扣（PoE 的心靈勝過物質）。 */
    public static final String DAMAGE_FROM_MANA = "damage.from_mana";
    /** 受到的傷害 x% 之後慢慢回成生命（存進偷取，跟生命偷取一起回）。 */
    public static final String LIFE_RECOUP = "life.recoup";
    /** 技能等級 +x（跟技能自己的標籤：spell、chaos、fire…；可以超過 20 級，最高 40）。 */
    public static final String SKILL_LEVEL = "skill.level";
    /** 地圖神龕的增益持續時間（INCREASED；傳奇「長明燈」）。 */
    public static final String SHRINE_DURATION = "shrine.duration";

    // ---- 2026-10 PoE2 系統補齊 ----
    /** INCREASED：對稀有、傳奇怪物（含 Boss）造成的傷害。 */
    public static final String DAMAGE_VS_RARE = "damage.vs_rare";
    /** INCREASED：對傳奇怪物與 Boss 造成的傷害。 */
    public static final String DAMAGE_VS_UNIQUE = "damage.vs_unique";
    /** FLAT／INCREASED：偏斜值（被擊中時照命中對偏斜值擲骰，偏斜的那一下傷害更少 {@link #DEFLECT_LESS}）。 */
    public static final String DEFLECTION = "deflection";
    /** FLAT：獲得閃避值的 x% 當偏斜值。 */
    public static final String DEFLECTION_FROM_EVASION = "deflection.from_evasion";
    /** 偏斜的擊中傷害更少多少。 */
    public static final double DEFLECT_LESS = 0.4;
    /** FLAT：近戰攻擊擊中時獲得幾點怒氣。 */
    public static final String RAGE_ON_HIT = "rage.on_hit";
    /** FLAT：怒氣上限多幾點（基礎 30）。 */
    public static final String RAGE_MAX = "rage.max";
    /** INCREASED：每點怒氣給的攻擊傷害（基礎每點 1%）。 */
    public static final String RAGE_EFFECT = "rage.effect";
    /** FLAT：擊中時 x% 機率讓敵人曝露（打出的那幾種元素的抗性 −20%，4 秒）。 */
    public static final String EXPOSURE_CHANCE = "exposure.chance";
    /** INCREASED：曝露的效果。 */
    public static final String EXPOSURE_EFFECT = "exposure.effect";
    /** FLAT：投射物攻擊擊中時 x% 機率定身敵人（不能移動，照樣能攻擊）。 */
    public static final String PIN_CHANCE = "pin.chance";
    /** INCREASED／MORE：對身上有這種異常的敵人造成的傷害（2026-10）：damage.vs_chilled／frozen／ignited／shocked／poisoned／bleeding。 */
    public static final String DAMAGE_VS_AILMENT = "damage.vs_";
    /** FLAT：擊殺後 4 秒內增加 x% 攻擊與施法速度（輔助寶石：打死敵人的那個技能）。 */
    public static final String ONKILL_SPEED = "onkill.speed";
    /** FLAT：擊殺後 4 秒內增加 x% 傷害。 */
    public static final String ONKILL_DAMAGE = "onkill.damage";
    /** FLAT：擊中時讓敵人掛上混沌持續傷害的減益（2026-10，PoE2 的汲魂彈、疫潮）：每秒傷害 = 這一下傷害的 x%。同一個技能的不疊加。 */
    public static final String DEBUFF_CHAOS = "debuff.chaos";
    /** FLAT：上面那個減益持續幾秒（沒寫 4 秒）。 */
    public static final String DEBUFF_DURATION = "debuff.duration";
    /** FLAT 1：掛著減益的敵人死掉時傳給附近的敵人（疫潮），每傳一手更多 100% 傷害、最多更多 300%。 */
    public static final String FLAG_DEBUFF_SPREAD = "flag.debuff_spread";
    /** FLAT：持續引導（蓄力）：最多幾層；連續放同一個技能每放一次加一層。 */
    public static final String CHANNEL_STAGES = "channel.stages";
    /** FLAT：持續引導每層更多 x% 傷害。 */
    public static final String CHANNEL_MORE = "channel.more";
    /** FLAT 1：觸發（輔助寶石）：攻擊暴擊時自動施放被輔助的法術。 */
    public static final String TRIGGER_CRIT = "trigger.crit";
    /** FLAT 1：被敵人擊中時自動施放被輔助的法術。 */
    public static final String TRIGGER_HIT_TAKEN = "trigger.hit_taken";
    /** FLAT 1：擊殺敵人時自動施放被輔助的法術。 */
    public static final String TRIGGER_KILL = "trigger.kill";
    /** FLAT 1：讓敵人點燃、冰緩、感電時自動施放被輔助的法術。 */
    public static final String TRIGGER_AILMENT = "trigger.ailment";
    /** 2026-10：翻滾時觸發。 */
    public static final String TRIGGER_DODGE = "trigger.dodge";
    /** 2026-10：弩的彈匣：多幾發（FLAT）、裝填速度（INCREASED／MORE）、不消耗弩箭的機率 %（FLAT）。 */
    public static final String CROSSBOW_CLIP = "crossbow.clip";
    /** 2026-10：蒺藜步（翻滾時在腳下撒鐵蒺）、幻弓影（弓的攻擊過一下由幻影再射一次）。 */
    public static final String FLAG_DODGE_CALTROPS = "flag.dodge_caltrops";
    // ---- 元素灌注（2026-10）：直接疊在身上，火、冰、電各自算層數 ----
    /** FLAT：放這個技能後獲得幾層「跟技能同元素」的灌注（技能有火焰標籤就是火焰灌注，好幾種元素各一層）。 */
    public static final String INFUSION_GAIN = "infusion.gain";
    /** FLAT：放這個技能後獲得幾層火焰灌注。 */
    public static final String INFUSION_GAIN_FIRE = "infusion.gain_fire";
    public static final String INFUSION_GAIN_COLD = "infusion.gain_cold";
    public static final String INFUSION_GAIN_LIGHTNING = "infusion.gain_lightning";
    /** FLAT：每種灌注最多多幾層（基礎 2 層）。 */
    public static final String INFUSION_MAX = "infusion.max";
    /** FLAT（開關）：放的時候把身上每種灌注各用掉一層，換加成。 */
    public static final String INFUSION_CONSUME = "infusion.consume";
    /** FLAT：每用掉一層灌注，這一下更多 x% 傷害。 */
    public static final String INFUSION_MORE = "infusion.more";
    /** FLAT：用掉某種灌注時，這一下獲得 x% 傷害為額外的那種元素傷害。 */
    public static final String INFUSION_EXTRA = "infusion.extra";
    /** FLAT（輔助）：被輔助的召喚技能叫出來的召喚物死掉時，回復 x% 最大魔力（2026-10）。 */
    public static final String MANA_ON_MINION_DEATH = "mana.on_minion_death";
    /** FLAT（輔助）：被輔助的召喚技能叫出來的召喚物死掉時，3 格內你的其他召喚物回復 x% 最大生命並清掉異常。 */
    public static final String MINION_DEATH_HEAL = "minion.death_heal";
    /** MORE（輔助）：被輔助的技能打死敵人時，生命藥劑／魔力藥劑／護符拿到的充能更多 x%。 */
    public static final String FLASK_KILL_LIFE = "flask.charges_on_kill.life";
    public static final String FLASK_KILL_MANA = "flask.charges_on_kill.mana";
    public static final String CHARM_KILL = "charm.charges_on_kill";
    /** FLAT：榴彈碰到地面就黏住，不再彈跳。 */
    public static final String FLAG_NO_BOUNCE = "flag.no_bounce";
    /** INCREASED／MORE：榴彈的引信時間。 */
    public static final String GRENADE_FUSE = "grenade.fuse";
    /** INCREASED／MORE：捷光環（herald）給的效果。 */
    public static final String HERALD_EFFECT = "herald.effect";
    /** FLAT：每一下傷害在（100 − x）%～（100 + 1.5x）% 之間隨機。 */
    public static final String DAMAGE_RANDOM = "damage.random";
    /** FLAT %：暴擊時再擲一次，成功的話暴擊傷害加成再算一次（超暴擊）。 */
    public static final String CRIT_SUPER = "crit.super_chance";
    public static final String FLAG_MIRAGE = "flag.mirage";
    /** 2026-10：封印（照 PoE2）：技能平常會慢慢蓄封印，最多 seal.max 個（FLAT），每 seal.interval 秒一個（FLAT；沒寫 = 施法時間的兩倍）；
     *  放的時候全部解開：每個封印多重複放 seal.repeat 次（重複的傷害少一半）、範圍增加 seal.area%、多 seal.projectiles 發投射物。 */
    public static final String SEAL_MAX = "seal.max";
    public static final String SEAL_INTERVAL = "seal.interval";
    public static final String SEAL_REPEAT = "seal.repeat";
    public static final String SEAL_AREA = "seal.area";
    public static final String SEAL_PROJECTILES = "seal.projectiles";
    /** 2026-10：冰晶：技能留下幾顆（FLAT）、要打幾下才碎（FLAT 加在基本 3 下上，INCREASED／MORE 乘上去）、
     *  霜蝕（冰晶周圍一直冰）、碎晶噴出冰片、冰鍍（打碎冰晶時得到附加冰冷的增益）。 */
    public static final String CRYSTAL_COUNT = "crystal.count";
    public static final String CRYSTAL_HITS = "crystal.hits";
    public static final String FLAG_HOARFROST = "flag.hoarfrost";
    public static final String FLAG_CRYSTAL_SHARDS = "flag.crystal_shards";
    public static final String FLAG_VERGLAS = "flag.verglas";
    /** 2026-10：連擊（近戰打擊打中累積，4 秒沒累積就歸零）：多累積一層的機率 %（FLAT）、連擊持續（INCREASED）、
     *  每層更多 x% 傷害（FLAT；最多 10 層）、終式：放的時候有 3 層以上就全部用掉、這一下更多 x% 傷害（FLAT）。 */
    public static final String COMBO_EXTRA_CHANCE = "combo.extra_chance";
    public static final String COMBO_DURATION = "combo.duration";
    public static final String DAMAGE_PER_COMBO = "damage.per_combo";
    public static final String COMBO_FINISHER = "combo.finisher";
    /** 2026-10：怒鑄：放的時候有 10 點怒氣就用掉，這一下更多 x% 傷害（FLAT）。 */
    public static final String RAGEFORGED = "rage.forged";
    public static final String CROSSBOW_RELOAD = "crossbow.reload_speed";
    public static final String CROSSBOW_AMMO_SAVE = "crossbow.ammo_save";
    /** 2026-10：擊中暈眩中的敵人時立刻裝滿（FLAT 1）。 */
    public static final String FLAG_RELOAD_ON_STUN = "flag.reload_on_stun";
    /** 2026-10：技能自己帶的：放了就把所有弩技能裝滿（急裝）。 */
    public static final String FLAG_RELOAD_ALL = "flag.reload_all";
    /** 2026-10：斷命打死時回復最大生命／魔力的 x%（FLAT）。 */
    public static final String LIFE_ON_CULL = "life.on_cull";
    public static final String MANA_ON_CULL = "mana.on_cull";
    /** 2026-10：投射物改成往四周一圈射出（FLAT 1）。 */
    public static final String FLAG_PROJECTILE_NOVA = "flag.projectile_nova";
    /** 2026-10：自己的召喚物死掉時觸發（在召喚物死掉的地方施放）。 */
    public static final String TRIGGER_MINION_DEATH = "trigger.minion_death";
    /** INCREASED／MORE：對身上有詛咒的敵人造成的傷害。 */
    /** 技能用（2026-10）：開關，焚魂這類精魂召喚：自己放火焰法術時才叫出來（不會自動補滿）。 */
    public static final String FLAG_SUMMON_ON_FIRE = "flag.summon_on_fire_spell";
    /** 技能用（2026-10）：開關，新星可以從自己還在飛的霜鑽那裡放出去（冰新星）。 */
    public static final String FLAG_NOVA_FROM_FROSTBOLT = "flag.nova_from_frostbolt";
    /** 技能用（2026-10）：開關，印記在你對牠打出暴擊時用掉，給一顆怒星（狙擊印記）。 */
    public static final String FLAG_MARK_CRIT = "flag.mark_consume_crit";
    /** 技能用（2026-10）：開關，英靈圖騰：圖騰不自己打，改成叫出英靈用面板上下一格的錘子攻擊技能。 */
    public static final String FLAG_TOTEM_LINKED = "flag.totem_linked_mace";
    /** 2026-10-26（PoE2 的 Lightning Warp）：瞄準敵人；生命低到可以撲殺就瞬移進去炸開，不然掛 4 秒標記、期間掉到門檻就自動瞬移。 */
    public static final String FLAG_LIGHTNING_WARP = "flag.lightning_warp";
    /** 2026-10-26：雷霆共鳴多打的那幾下更少幾 %（逐級，寫在 level_modifiers）。 */
    public static final String FULMINATION_LESS = "fulmination.less";
    /** 2026-10-26：折光之鏡每幾秒出現一面（逐級）。 */
    public static final String MIRROR_INTERVAL = "mirror.interval";
    /** 2026-10-26：鴉群密謀身邊有稀有、傳奇敵人時每幾秒加入一隻烏鴉（逐級）。 */
    public static final String RAVEN_INTERVAL = "raven.interval";
    /** 技能用（2026-10）：開關，喚屍：附近沒屍體時吃一顆銳星，叫出一隻強化殭屍加兩隻一般的。 */
    public static final String FLAG_POWER_RAISE = "flag.raise_with_power_charge";
    /** 技能用（2026-10）：FLAT，放完往後跳的力道（×0.01 = 每 tick 幾格；躍退射）。 */
    public static final String SKILL_RECOIL = "skill.recoil";
    public static final String DAMAGE_VS_CURSED = "damage.vs_cursed";
    /** INCREASED／MORE：對近身（3.5 格內）的敵人造成的傷害。 */
    public static final String DAMAGE_CLOSE = "damage.close";
    /** 2026-10：對滿血敵人的傷害（MORE）。 */
    public static final String DAMAGE_VS_FULL_LIFE = "damage.vs_full_life";
    /** 2026-10：對滿血敵人的暴擊率（MORE）。 */
    public static final String CRIT_VS_FULL_LIFE = "crit.vs_full_life";
    /** 2026-10：魔力在 90% 以上時的傷害（MORE）。 */
    public static final String DAMAGE_HIGH_MANA = "damage.high_mana";
    /** 2026-10：自己生命百分比比敵人低時更多 x% 傷害、比較高時更少 x/2%（FLAT）。 */
    public static final String DAMAGE_CLASH = "damage.clash";
    /** 近身的距離（格）。 */
    public static final double CLOSE_RANGE = 3.5;
    /** FLAT：擊中時 x% 機率讓敵人眩暈（造成的傷害更少、更容易被暈）。 */
    public static final String DAZE_CHANCE = "daze.chance";

    /** 格擋率的上限（百分比點數）：盾、詞綴、天賦、昇華、珠寶加起來最多這麼多。 */
    public static final double BLOCK_CAP = 50; // 2026-10：PoE2 格擋上限 50%

    // 開關（基石天賦）：值 > 0 就生效，一律用 FLAT 1
    public static final String FLAG_ALWAYS_HIT = "flag.always_hit";       // 攻擊必定命中
    public static final String FLAG_CULLING = "flag.culling";             // 撲殺（2026-10）：擊中後生命低於門檻直接倒下
    public static final String FLAG_NO_CRIT = "flag.no_crit";             // 無法暴擊
    public static final String FLAG_CHAOS_IMMUNE = "flag.chaos_immune";   // 免疫混沌傷害，最大生命 1
    public static final String FLAG_IRON_REFLEXES = "flag.iron_reflexes"; // 閃避轉護甲

    // ---- 2026-10：傳奇的新機制 ----
    /** 臨在（{@link tw.exilecore.combat.Presence}）：範圍內的敵人抗性降低 x%（後面接 fire／cold／lightning／chaos；FLAT）。 */
    public static final String PRESENCE_RESIST = "presence.resist.";
    /** FLAT：臨在範圍內的敵人受到的傷害增加 x%。 */
    public static final String PRESENCE_TAKEN = "presence.taken";
    /** FLAT：臨在範圍內的敵人造成的傷害更少 x%。 */
    public static final String PRESENCE_WEAKEN = "presence.weaken";
    /** FLAT：臨在範圍內的敵人移動速度變慢 x%。 */
    public static final String PRESENCE_SLOW = "presence.slow";
    /** FLAT：臨在範圍內的敵人每秒受到你最大生命 x% 的火焰傷害。 */
    public static final String PRESENCE_BURN = "presence.burn";
    /** INCREASED：臨在的範圍（面積）。 */
    public static final String PRESENCE_AREA = "presence.area";
    /** FLAT 1：擊中滿血的敵人時直接重暈（暈眩免疫中的不算）。 */
    public static final String FLAG_STUN_FULL_LIFE = "flag.stun_full_life";
    /** FLAT：你用擊中殺死被完全破甲的敵人時，牠碎裂爆開，對周圍造成牠最大生命 x% 的物理傷害。 */
    public static final String KILL_SHATTER = "kill.shatter";
    /** 裝備詞綴的條件（寫在屬性代號後面）：最近 4 秒被擊中過才有效／最近 4 秒沒被擊中才有效，例如 evasion@nohit。 */
    public static final String IF_HIT = "@hit";
    public static final String IF_NOT_HIT = "@nohit";
    /** 「最近」＝幾 tick（PoE 的 4 秒）。 */
    public static final long RECENTLY_TICKS = 80;

    // ---- 藥劑、護符本身的詞綴（2026-10，PoE2 的藥劑詞綴；只在那一瓶上生效，角色屬性表不讀） ----
    /** INCREASED：這瓶的回復量。 */
    public static final String FLASK_LOCAL_RECOVERY = "flask.local.recovery";
    /** INCREASED：生命（魔力）低於 35% 時喝，這瓶的回復量另外再增加。 */
    public static final String FLASK_LOCAL_RECOVERY_LOW = "flask.local.recovery_low";
    /** INCREASED：這瓶的回復速度（同樣的量在更短的時間內回完）。 */
    public static final String FLASK_LOCAL_SPEED = "flask.local.speed";
    /** FLAT：回復量的 x% 立即回復，剩下的照常慢慢回。 */
    public static final String FLASK_LOCAL_INSTANT = "flask.local.instant";
    /** FLAT：你的召喚物也回復這瓶回復量的 x%（照牠們的最大生命比例）。 */
    public static final String FLASK_LOCAL_MINION_HEAL = "flask.local.minion_heal";
    /** INCREASED：這瓶拿到的充能（殺怪、每分鐘）。 */
    public static final String FLASK_LOCAL_CHARGES_GAINED = "flask.local.charges_gained";
    /** INCREASED：這瓶每次用掉的充能（負的 = 用得少）。 */
    public static final String FLASK_LOCAL_CHARGES_USED = "flask.local.charges_used";
    /** INCREASED：這瓶的充能上限。 */
    public static final String FLASK_LOCAL_MAX_CHARGES = "flask.local.max_charges";
    /** FLAT：殺怪時 x% 機率多拿一點充能。 */
    public static final String FLASK_LOCAL_CHARGE_ON_KILL = "flask.local.charge_on_kill";
    /** FLAT：每分鐘自動拿到 x 點充能。 */
    public static final String FLASK_LOCAL_CHARGES_PER_MINUTE = "flask.local.charges_per_minute";
    /** FLAT：喝的時候另一種資源（生命藥劑扣魔力、魔力藥劑扣生命）扣掉回復量的 x%。 */
    public static final String FLASK_LOCAL_COST_OTHER = "flask.local.cost_other";
    /** INCREASED：這顆護符的效果持續時間。 */
    public static final String CHARM_LOCAL_DURATION = "charm.local.duration";
    /** FLAT：這顆護符觸發時回復 x 生命／魔力。 */
    public static final String CHARM_LOCAL_LIFE = "charm.local.life_on_use";
    public static final String CHARM_LOCAL_MANA = "charm.local.mana_on_use";
    /** FLAT：這顆護符觸發時獲得 x 點守護（效果期間先替你吃掉傷害，效果結束就消失）。 */
    public static final String CHARM_LOCAL_GUARD = "charm.local.guard";
    /** 全域（裝備、珠寶）：藥劑拿到的充能 INCREASED、用掉的充能 INCREASED、藥劑效果持續時間 INCREASED、x% 機率不消耗充能 FLAT。 */
    public static final String FLASK_CHARGES_GAINED = "flask.charges_gained";
    public static final String FLASK_CHARGES_USED = "flask.charges_used";
    public static final String FLASK_DURATION = "flask.duration";
    public static final String FLASK_FREE_CHANCE = "flask.free_chance";
    /** 全域：生命／魔力藥劑每分鐘自動拿到 x 點充能（FLAT）。 */
    public static final String FLASK_CHARGES_PER_MINUTE_LIFE = "flask.charges_per_minute.life";
    public static final String FLASK_CHARGES_PER_MINUTE_MANA = "flask.charges_per_minute.mana";
    /** 全域：只有生命／魔力藥劑拿到的充能 INCREASED（珠寶）。 */
    public static final String FLASK_CHARGES_GAINED_LIFE = "flask.charges_gained.life";
    public static final String FLASK_CHARGES_GAINED_MANA = "flask.charges_gained.mana";
    /** 全域：護符拿到的充能 INCREASED、用掉的充能 INCREASED、效果持續時間 INCREASED、x% 機率不消耗充能 FLAT、每分鐘自動充能 FLAT。 */
    public static final String CHARM_CHARGES_GAINED = "charm.charges_gained";
    public static final String CHARM_CHARGES_USED = "charm.charges_used";
    public static final String CHARM_DURATION = "charm.duration";
    public static final String CHARM_FREE_CHANCE = "charm.free_chance";
    public static final String CHARM_CHARGES_PER_MINUTE = "charm.charges_per_minute";
    /** INCREASED：翻滾的距離（2026-10 PoE2 的鞋子褻瀆詞綴）。 */
    public static final String DODGE_DISTANCE = "dodge.distance";
    /** 半徑珠寶自己的數值（2026-10）：FLAT 半徑多幾格；INCREASED 範圍內小天賦／核心的效果。珠寶本身的，不進角色屬性表。 */
    public static final String JEWEL_LOCAL_RADIUS = "jewel.local.radius";
    public static final String JEWEL_LOCAL_SMALL_EFFECT = "jewel.local.small_effect";
    public static final String JEWEL_LOCAL_NOTABLE_EFFECT = "jewel.local.notable_effect";
    /** 半徑珠寶詞綴的屬性前綴：{@code radius:damage} = 範圍內每個已配置的小天賦都多給這條。 */
    public static final String RADIUS_PREFIX = "radius:";
    /** FLAT：弓攻擊 x% 機率多射一發（超過 100% 的部分是必定多一發再擲剩下的；PoE2 0.5）。 */
    public static final String PROJECTILE_CHANCE = "skill.projectile_chance";

    // ---- 2026-10-21：PoE2 腐化詞綴補齊的機制 ----
    /** INCREASED：你造成的減速（冰緩、癱瘓、臨在的減速）幅度。 */
    public static final String SLOW_MAGNITUDE = "slow.magnitude";
    /** INCREASED：你身上減速（冰緩、Boss 的減速）的幅度（負的 = 減少）。 */
    public static final String SLOW_TAKEN = "slow.taken";
    /** FLAT：受到的傷害 x% 之後慢慢回成魔力（跟魔力偷取一起回）。 */
    public static final String MANA_RECOUP = "mana.recoup";
    /** FLAT：擊中 x% 機率致盲（{@link tw.exilecore.combat.HitEffects}：4 秒內命中與閃避更少 20%）。 */
    public static final String BLIND_CHANCE = "blind.chance";
    /** FLAT：擊中 x% 機率癱瘓（4 秒內移動速度變慢 30%，吃減速幅度）。 */
    public static final String MAIM_CHANCE = "maim.chance";
    /** 2026-10-23：FLAT：怪物打中玩家 x% 機率阻礙（4 秒內移動速度變慢 30%；PoE2）。 */
    public static final String HINDER_CHANCE = "hinder.chance";
    /** 2026-10-26：FLAT：玩家擊中敵人時 x% 機率阻礙（4 秒內移動速度變慢 30%；PoE2 的 Hinder，例如魔力吸取、靈體撕裂）。 */
    public static final String HINDER_ON_HIT = "hinder.on_hit";
    /** 2026-10-26：FLAT：擊中時給敵人幾層凋零（PoE2 的 Withered：每層受到的混沌傷害增加 5%，最多 10 層，4 秒；混沌弩箭）。 */
    public static final String WITHERED_ON_HIT = "withered.on_hit";
    /** 2026-10-26：FLAT：擊中時給敵人幾層暴擊弱點（PoE2 的 Critical Weakness：每層擊中牠的暴擊率 +0.5%，最多 20 層，4 秒；收割）。 */
    public static final String CRIT_WEAKNESS_ON_HIT = "crit_weakness.on_hit";
    /** 2026-10-26：FLAT（在敵人身上）：擊中牠的暴擊率加幾個百分點（暴擊弱點）。 */
    public static final String CRIT_CHANCE_TAKEN = "crit.chance_taken";
    /**
     * 2026-10-26（PoE2 Monster Effectiveness，怪物效能）：INCREASED／MORE。每 2% 效能 = 2% 更多韌性（受到的傷害 ÷ 效能倍率）、
     * 1% 更多經驗、1% 更多物品數量。地圖詞綴（monster:effectiveness，MORE）與星圖天賦（atlas.monster_effectiveness，INCREASED）給。
     */
    public static final String EFFECTIVENESS = "effectiveness";
    /** 2026-10-23：FLAT：怪物打中玩家 x% 機率上一層腐化之血（物理持續傷害，最多 10 層，每次打中重新計時；PoE2）。 */
    public static final String CORRUPTED_BLOOD_CHANCE = "corrupted_blood.chance";
    /** 2026-10-23：開關（珠寶的腐化詞綴，PoE2）：免疫癱瘓、不會被阻礙、不會被致盲、免疫腐化之血。 */
    public static final String FLAG_MAIM_IMMUNE = "flag.maim_immune";
    public static final String FLAG_HINDER_IMMUNE = "flag.hinder_immune";
    public static final String FLAG_BLIND_IMMUNE = "flag.blind_immune";
    public static final String FLAG_CORRUPTED_BLOOD_IMMUNE = "flag.corrupted_blood_immune";

    // ---- 2026-10-23：怪物詞綴的特殊行為（PoE2 稀有怪詞綴；只有怪物的屬性表會有，見 tw.exilecore.monster.MonsterMechanics）----
    /** 前綴：怪物特殊行為的屬性都以這個開頭（有就要追蹤）。 */
    public static final String MONSTER_PREFIX = "monster.";
    /** FLAT：每秒回復 x% 最大生命。 */
    public static final String MONSTER_REGEN = "monster.regen_percent";
    /** FLAT：多一層等於 x% 最大生命的護盾（先扣護盾，4 秒沒被打開始回充）。 */
    public static final String MONSTER_SHIELD = "monster.shield_percent";
    /** FLAT：熔岩護盾，吸收 x% 最大生命的傷害；被打破時爆炸（火焰），8 秒後回來。 */
    public static final String MONSTER_MAGMA = "monster.magma_barrier";
    /** FLAT：阻礙光環的半徑（格）。 */
    public static final String MONSTER_HINDER_AURA = "monster.hinder_aura";
    /** FLAT 1：走過的地上留下點燃／冰緩／感電的地面。 */
    public static final String MONSTER_TRAIL_FIRE = "monster.trail_fire";
    public static final String MONSTER_TRAIL_COLD = "monster.trail_cold";
    public static final String MONSTER_TRAIL_LIGHTNING = "monster.trail_lightning";
    /** FLAT：每 6 秒治療附近的怪物 x% 最大生命。 */
    public static final String MONSTER_HEAL_ALLIES = "monster.heal_allies";
    /** FLAT 1：引爆目標附近的屍體。 */
    public static final String MONSTER_CORPSE_EXPLODE = "monster.corpse_explode";
    /** FLAT 1：附近玩家的藥劑一直被吸走充能。 */
    public static final String MONSTER_FLASK_SIPHON = "monster.flask_siphon";
    /** FLAT：附近玩家的生命回復不能超過 x%。 */
    public static final String MONSTER_RECOVERY_CAP = "monster.recovery_cap";
    /** FLAT 1：叫出來的小怪更強（傷害增加 25%、生命更多 50%）。 */
    public static final String MONSTER_POWERFUL_MINIONS = "monster.powerful_minions";
    /** FLAT 1：定期讓附近的屍體爬起來當小怪。 */
    public static final String MONSTER_REVIVE = "monster.revive_minions";
    /** FLAT 1：被打中時有機率放出追人的閃電幻影。 */
    public static final String MONSTER_MIRAGE = "monster.lightning_mirage";
    /** FLAT 1：周圍一圈吸魔力、閃電傷害（中間安全）。 */
    public static final String MONSTER_MANA_SIPHON = "monster.mana_siphon";
    /** FLAT：時間泡泡的半徑（泡泡裡的玩家移動、攻擊、施法都變慢）。 */
    public static final String MONSTER_TEMPORAL_BUBBLE = "monster.temporal_bubble";
    /** FLAT：只有這麼多格以內的攻擊打得到牠。 */
    public static final String MONSTER_PROXIMAL = "monster.proximal";
    /** FLAT 1：附近的怪物死掉時吞魂（每層招式快 1%、受到的傷害少 1%，最多 50 層）。 */
    public static final String MONSTER_SOUL_EATER = "monster.soul_eater";
    /** FLAT：投射物射出時 x% 機率多穿透一個敵人（PoE2 箭袋的「x% 機率穿透」；超過 100% 的部分必定再多一個）。 */
    public static final String PIERCE_CHANCE = "skill.pierce_chance";
    /** FLAT：投射物碰到地形 x% 機率再連鎖一次（彈向最近的敵人）。 */
    public static final String CHAIN_TERRAIN = "skill.chain_terrain";
    /** FLAT：格擋時回復 x 生命／魔力。 */
    public static final String LIFE_ON_BLOCK = "life.on_block";
    public static final String MANA_ON_BLOCK = "mana.on_block";
    /** INCREASED：近戰打擊（打擊、扇形）的距離。 */
    public static final String MELEE_RANGE = "melee.range";
    /** INCREASED：只有魔力偷取的量（PoE2 珠寶「增加 x% 魔力偷取量」；leech.effect 是生命、魔力都算）。 */
    public static final String LEECH_MANA_EFFECT = "leech.mana_effect";
    /** FLAT：被敵人擊中時獲得幾點怒氣（PoE2 珠寶）。 */
    public static final String RAGE_WHEN_HIT = "rage.when_hit";

    public static boolean flag(final StatSheet sheet, final String flag) {
        return sheet.get(flag) > 0;
    }
}
