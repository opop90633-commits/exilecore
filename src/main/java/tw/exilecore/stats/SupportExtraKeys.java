package tw.exilecore.stats;

/**
 * 2026-10-27 照站長「PoE2 0.5 還沒有的一般輔助寶石（不含血脈）全部補上」：新輔助用到的屬性鍵與原作數值（不放進 {@link StatKeys}，方便合併）。
 * 機制在 {@code tw.exilecore.skill.SupportExtras}（擊中前後、放技能前後、每 5 tick）；能不能掛的防呆在 {@code tw.exilecore.skill.SupportGuards}。
 * 數值是 supports.yml 裡寫的（照 poe2db／RePoE），這裡只有鍵與固定的原作常數。
 */
public final class SupportExtraKeys {

    private SupportExtraKeys() {
    }

    // ---- 放技能的那段時間（PoE2「while performing」「while using」）：using. 開頭 → 放的時候給你一個增益（去掉開頭的屬性），持續攻擊／施法時間 ----
    /** 前綴：放被輔助的技能的那段時間給你的屬性（Unbreakable：暈眩門檻）。 */
    public static final String USING_PREFIX = "using.";
    /** 前綴：同上，但每 0.25 秒的攻擊／施法時間給一份（Unabating、Untouchable、Unbending、Unyielding）。 */
    public static final String USING_QUARTER_PREFIX = "using_qs.";
    /** 前綴：引導被輔助的技能時給你的屬性（Steadfast）。 */
    public static final String CHANNELLING_PREFIX = "channelling.";
    /**
     * 前綴：被輔助的技能產生的殘骸本身帶的屬性（殘骸類：Harmonic／Magnetic Remnants、Remnant Potency）。2026-10-27 照 PoE2：
     * 跟著每一個殘骸走（{@code ChargeEffects#dropInfusionRemnant} 讀產生它的技能的屬性表）；這次之前是「放技能後 12 秒內撿的殘骸都算」的增益。
     */
    public static final String REMNANT_PREFIX = "remnant_support.";
    /** FLAT %：殘骸能從遠 x% 的地方撿（Harmonic、Magnetic Remnants）。 */
    public static final String REMNANT_RANGE = REMNANT_PREFIX + "range";
    /** FLAT %：產生殘骸時 x% 機率多產生一個（Harmonic Remnants）。 */
    public static final String REMNANT_EXTRA = REMNANT_PREFIX + "extra";
    /** FLAT %：殘骸效果增加 x%（Remnant Potency）。 */
    public static final String REMNANT_EFFECT = REMNANT_PREFIX + "effect";
    /** FLAT 秒：殘骸撿到 x 秒後才生效（Remnant Potency）。 */
    public static final String REMNANT_DELAY = REMNANT_PREFIX + "delay";

    // ---- 擊中前的條件加成 ----
    /** MORE：對致盲的敵人暴擊率與暴擊傷害加成更多（Blindside）。 */
    public static final String CRIT_VS_BLINDED = "crit.vs_blinded";
    /** 旗標：不會致盲（Blindside）。 */
    public static final String FLAG_CANNOT_BLIND = "flag.cannot_blind";
    /** 旗標：不會癱瘓（Undermine）。 */
    public static final String FLAG_CANNOT_MAIM = "flag.cannot_maim";
    /** FLAT：用掉敵人的凍結，這一下更多 x% 傷害（Biting Frost）。 */
    public static final String CONSUME_FREEZE = "damage.consume_freeze";
    /** FLAT：用掉凍結時對傳奇再更多 x%（Biting Frost）。 */
    public static final String CONSUME_FREEZE_UNIQUE = "damage.consume_freeze_unique";
    /** FLAT：用掉敵人的完全破甲，這一下更多 x% 傷害（Exploit Weakness）。 */
    public static final String CONSUME_BROKEN = "damage.consume_broken_armour";
    /** FLAT：擊中重暈中的敵人時加劇流血（Rupture）。 */
    public static final String AGGRAVATE_HEAVY_STUNNED = "bleed.aggravate_heavy_stunned";
    /** FLAT：最近 2 秒投射物攻擊擊中過敵人時近戰更多 x%（Pursuit）。 */
    public static final String PURSUIT = "damage.melee_after_projectile";
    /** FLAT：最近 2 秒近戰擊中過敵人時投射物更多 x%（Retreat）。 */
    public static final String RETREAT = "damage.projectile_after_melee";
    /** Pursuit／Retreat 的「最近」：2 秒。 */
    public static final long PURSUIT_TICKS = 40;
    /** FLAT：每秒增加 x% 暴擊率（最多 {@link #INEXORABLE_CAP}%），暴擊時歸零（Inexorable Critical）。 */
    public static final String INEXORABLE = "crit.inexorable";
    /** FLAT：每秒暴擊傷害加成更少 x%（最多 {@link #INEXORABLE_BONUS_CAP}%）。 */
    public static final String INEXORABLE_BONUS = "crit.inexorable_bonus";
    public static final double INEXORABLE_CAP = 200;
    public static final double INEXORABLE_BONUS_CAP = 50;
    /** FLAT：每秒更多 x% 傷害，最多 {@link #STOICISM_CAP}%；翻滾、突進歸零（Stoicism）。 */
    public static final String STOICISM = "damage.stoicism";
    public static final double STOICISM_CAP = 20;
    /** FLAT：靜止 1.5 秒才能用；每靜止 0.25 秒擊中更多 x%，最多 {@link #DAUNTLESS_CAP}%（Dauntless）。 */
    public static final String DAUNTLESS = "damage.dauntless";
    public static final double DAUNTLESS_CAP = 45;
    public static final long DAUNTLESS_TICKS = 30;
    /** 旗標：被包圍時攻擊的擊中傷害幸運（Defy）。 */
    public static final String FLAG_LUCKY_SURROUNDED = "flag.lucky_when_surrounded";
    /** 旗標：被包圍時魔力不夠也能放（Defy、Desperation）。 */
    public static final String FLAG_FREE_SURROUNDED = "flag.mana_free_when_surrounded";
    /** FLAT：每 100 智慧非異常的持續傷害更多 x%；放的時候扣等同智慧的能量護盾（Derange）。 */
    public static final String DERANGE = "damage.dot_per_100_int";
    /** FLAT：完美時機放開的那一下更多 x%（Window of Opportunity）。 */
    public static final String PERFECT_MORE = "damage.perfect_timing";
    /** FLAT %：完美時機的區間更短 x%（Window of Opportunity；跟「增加完美時機區間」加在一起）。 */
    public static final String PERFECT_WINDOW_SHORTER = "perfect_timing.window_shorter";
    /** FLAT：完美時機時獲得閃避值 x% 的偏斜值 8 秒（Window of Opportunity）。 */
    public static final String PERFECT_DEFLECT = "deflection.on_perfect_timing";
    /** FLAT：完美時機時獲得幾層完美（Perfection：每層更多 5%，最多 4 層，10 秒；沒抓到完美時機全部失去）。 */
    public static final String PERFECTION = "perfection.on_perfect_timing";
    public static final double PERFECTION_MORE = 5;
    public static final int PERFECTION_MAX = 4;
    public static final long PERFECTION_TICKS = 200;
    /** FLAT：這個技能過去 8 秒每裝填一顆弩矢更多 x%（Fresh Clip；SkillService 裝填時記下來）。 */
    public static final String FRESH_CLIP = "damage.per_reloaded_bolt";
    public static final long FRESH_CLIP_TICKS = 160;
    /** FLAT：每 3 發一發充能射擊，獲得 x% 傷害為額外閃電（Charged Shots）。 */
    public static final String CHARGED_SHOTS = "charged_shots.gain";
    public static final int CHARGED_SHOTS_EVERY = 3;
    /** FLAT：瞄著指示的方向攻擊時多射 x 發（Alignment）。 */
    public static final String ALIGNMENT = "alignment.projectiles";
    public static final double ALIGNMENT_ANGLE = 40;
    /** 對準之後過多久才指示下一個方向（PoE2 called_shot_aiming_delay_ms 1000）。 */
    public static final long ALIGNMENT_DELAY_TICKS = 20;
    /** FLAT：這一下用掉附近幾隻召喚物的生命（你最大生命的 x%）換更多傷害（Minion Pact）。 */
    public static final String MINION_PACT_LIFE = "minion_pact.life";
    /** FLAT：Minion Pact 的更多傷害。 */
    public static final String MINION_PACT_MORE = "minion_pact.more";
    public static final double MINION_PACT_RANGE = 6;
    /** FLAT：最多用掉 5 格內 x 具屍體，每具混沌更多 {@link #PROFANITY_MORE}（Profanity）。 */
    public static final String PROFANITY = "profanity.corpses";
    public static final String PROFANITY_MORE = "profanity.more";
    public static final double PROFANITY_RANGE = 5;

    // ---- 放的時候用掉的資源 ----
    /** FLAT：放的時候用掉魔力藥劑最大充能的 x%（Ambrosia）。 */
    public static final String AMBROSIA = "flask.consume_mana";
    /** FLAT：每用掉一點魔力藥劑充能獲得 x% 傷害為額外閃電（Ambrosia）。 */
    public static final String AMBROSIA_GAIN = "flask.consume_mana_gain";
    /** FLAT：放的時候用掉生命藥劑最大充能的 x%（Concoct）。 */
    public static final String CONCOCT = "flask.consume_life";
    /** FLAT：每用掉一點生命藥劑充能流血更多 x%（Concoct）。 */
    public static final String CONCOCT_BLEED = "flask.consume_life_bleed";
    /** FLAT：消耗一顆怒星，破甲等同物理傷害 x%（Tumult）。 */
    public static final String TUMULT = "charge.frenzy_armour_break";
    /** FLAT：消耗一顆銳星，暴擊率更多 x%；這個技能不會產生銳星（Potential）。 */
    public static final String POTENTIAL = "charge.power_crit";
    /** FLAT：消耗全部磐星，每顆回復最大生命 x%；這個技能不會產生磐星（Rally）。 */
    public static final String RALLY = "charge.endurance_life";
    /** FLAT：花掉 x 點怒氣之後，下一次用這個技能給 {@link #RELENTLESS_GAIN} 點怒氣（Relentless Rage）。 */
    public static final String RELENTLESS = "rage.relentless_spend";
    public static final String RELENTLESS_GAIN = "rage.relentless_gain";
    /** FLAT：要有 x 層易爆才能用；用的時候引爆隨機層數（Catharsis）。 */
    public static final String CATHARSIS = "volatility.required";
    /** FLAT：暴擊時 x% 機率獲得一層易爆（Volatility）。 */
    public static final String VOLATILITY_ON_CRIT = "volatility.on_crit";
    /** FLAT：被強化時使用獲得 x 層易爆（Volatile Power）。 */
    public static final String VOLATILITY_ON_EMPOWERED = "volatility.on_empowered_use";
    /** FLAT：條件需求更難 x%，達成後 10 秒內可以多用一次（Delayed Gratification）。 */
    public static final String GRATIFICATION = "conditional.harder";
    public static final long GRATIFICATION_TICKS = 200;
    /** FLAT：放的時候扣同等你智慧的能量護盾（Derange 的代價，放在說明；機制用 {@link #DERANGE}）。 */
    public static final double DERANGE_PER = 100;

    // ---- 先祖強化 ----
    /** FLAT：格擋 x 次之後才能用，用的時候先祖強化（Ancestral Aid）。 */
    public static final String ANCESTRAL_AID = "ancestral.aid_blocks";
    /** FLAT：每 x 秒一次先祖強化（Ancestral Call）。 */
    public static final String ANCESTRAL_CALL = "ancestral.call_seconds";
    /** FLAT：每 x 秒一次加倍效果的先祖強化（Fist of War）。 */
    public static final String FIST_OF_WAR = "ancestral.fist_seconds";
    /** 旗標：連段的最後一擊先祖強化（Crescendo）。 */
    public static final String CRESCENDO = "ancestral.final_strike";
    /** FLAT %：連段的最後一擊破甲，等同造成的攻擊傷害 x%（Rending Apex）。 */
    public static final String RENDING_APEX = "final_strike.armour_break";
    /** 這一下是連段的最後一擊（SupportExtras.beforeUse 加在技能描述上的記號）。 */
    public static final String FINAL_STRIKE_MARK = "final_strike.this";
    /**
     * 2026-10-27 照 PoE2（PerformsFinalStrike）：有最後一擊的打擊，快速連用時第幾下是最後一擊（寒冰震波 3、原獸連擊 3、風暴連擊 4、撕咬 4；
     * RePoE 的 stat set：IceStrikeThirdAttack、PrimalStrikesFinal、TempestFlurryFinalStrike、WolfShredFinal）。
     */
    public static final java.util.Map<String, Integer> FINAL_STRIKE_SKILLS = java.util.Map.of("ice_strike", 3, "primal_strikes", 3,
            "tempest_flurry", 4, "wolf_shred", 4);
    /** 「快速連用」：跟上一下隔多久以內算連段（沒寫明，用 2 秒）。 */
    public static final long FINAL_STRIKE_WINDOW = 40;

    // ---- 擊中之後 ----
    /** FLAT：擊中滿血的敵人時眩暈（Opening Move）。 */
    public static final String DAZE_FULL_LIFE = "daze.vs_full_life";
    /** FLAT：物理傷害暴擊時眩暈（Concussive Spells）。 */
    public static final String DAZE_PHYSICAL_CRIT = "daze.on_physical_crit";
    /** FLAT 秒：點燃時曝露（Fire Exposure）。 */
    public static final String EXPOSURE_ON_IGNITE = "exposure.on_ignite";
    /** FLAT 秒：冰冷傷害暴擊時曝露（Cold Exposure）。 */
    public static final String EXPOSURE_ON_COLD_CRIT = "exposure.on_cold_crit";
    /** FLAT 秒：感電時曝露（Lightning Exposure）。 */
    public static final String EXPOSURE_ON_SHOCK = "exposure.on_shock";
    /** FLAT %：擊中時穿刺的機率（Impale）。 */
    public static final String IMPALE_ON_HIT = "impale.on_hit";
    /** 旗標：不會抽出穿刺（Impale）。 */
    public static final String FLAG_NO_EXTRACT = "flag.cannot_extract_impale";
    /** FLAT：最近每用掉一層切割流血更多 x%，最多 30%（Incision）。 */
    public static final String INCISION_BLEED = "incision.bleed_more";
    public static final double INCISION_BLEED_CAP = 30;
    /** 旗標：擊中蓄勢待電的敵人直接電擊（Neural Overload）。 */
    public static final String FLAG_OVERLOAD = "flag.electrocute_primed";
    /** 旗標：對點燃中而且完全破甲的敵人有重壓擊（Crushing Blow），重暈牠們時永久恫嚇（Syzygy）。 */
    public static final String FLAG_SYZYGY = "flag.syzygy";
    /** 旗標：暈眩累積到不了 100%（Brink）。 */
    public static final String FLAG_STUN_CANNOT_REACH = "flag.stun_cannot_reach";
    /** 旗標：流血、中毒的基礎機率互換（Malady）。 */
    public static final String FLAG_MALADY = "flag.malady";
    /** FLAT %：被這個技能感電的敵人，受到點燃時另外受到點燃傷害 x% 的閃電傷害（Stormfire；AilmentService 的點燃每一跳讀 SupportExtras#stormfire）。 */
    public static final String STORMFIRE = "ignite.stormfire";
    /** FLAT：施加持續傷害時用掉敵人一種異常，持續傷害更多 x%（Drain Ailments）。 */
    public static final String DRAIN_AILMENTS = "damage.dot_drain_ailment";
    /** FLAT：你身上有同一種異常時，施加的異常更多 x%，並移除你身上的（Commiserate）。 */
    public static final String COMMISERATE = "ailment.commiserate";
    /** 旗標：混沌傷害也累積凍結（對受到被輔助技能持續傷害的敵人；Chaotic Freeze）。 */
    public static final String FLAG_CHAOTIC_FREEZE = "flag.chaotic_freeze";
    /** FLAT %：感電時 x% 機率讓 {@link #SHOCK_NEARBY_RADIUS} 格內另一個敵人也感電（Shock Conduction）。 */
    public static final String SHOCK_NEARBY = "shock.conduct_nearby";
    public static final double SHOCK_NEARBY_RADIUS = 2.5;
    /** 旗標：擊中濕透的敵人一定感電（Shock Conduction）。 */
    public static final String FLAG_SHOCK_DRENCHED = "flag.shock_drenched";
    /** 2026-10-27 照 PoE2 Drenched：濕透（積雷雲範圍內的敵人；離開後還留 2 秒）：感電機率更多 50%、受到的凍結累積更多 25%。 */
    public static final String DRENCHED_ID = "drenched";
    public static final String FLAG_DRENCHED = "flag.drenched";
    public static final String SHOCK_CHANCE_TAKEN = "shock.chance_taken";
    public static final String FREEZE_BUILDUP_TAKEN = "freeze.buildup_taken";
    public static final long DRENCHED_TICKS = 40;
    public static final double DRENCHED_SHOCK = 50;
    public static final double DRENCHED_FREEZE = 25;
    /** 會讓敵人濕透的技能（PoE2 Thunderstorm）。 */
    public static final java.util.Set<String> DRENCHING_SKILLS = java.util.Set.of("thunderstorm");
    /** FLAT：打死感電中的敵人回復最大能量護盾 x%（Shock Siphon）。 */
    public static final String SHOCK_SIPHON = "es.on_kill_shocked";
    /** FLAT %：打死流血中的敵人時擊殺效果發生兩次的機率（Tear）。 */
    public static final String TEAR = "kill.twice_vs_bleeding";
    /** FLAT：打死流血中的敵人留下血之殘骸，撿到 5 秒內生命再生速度增加 x%（Rip）。 */
    public static final String RIP = "remnant.blood_regen";
    public static final long RIP_TICKS = 100;
    /** FLAT：撲殺時 8 秒內攻擊獲得 x% 傷害為額外閃電（Thrill of the Kill）。 */
    public static final String THRILL = "cull.thrill_lightning";
    /** FLAT：Thrill of the Kill 的增益另外增加感電機率 x%。 */
    public static final String THRILL_SHOCK = "cull.thrill_shock";
    public static final long THRILL_TICKS = 160;
    /** FLAT：重暈敵人時 8 秒內法術傷害增加 x%（Practical Magic）。 */
    public static final String PRACTICAL_MAGIC = "stun.spell_damage_buff";
    public static final long PRACTICAL_MAGIC_TICKS = 160;
    /** FLAT：暈眩敵人時每 10 點力量獲得 x 守護（Vanguard）。 */
    public static final String VANGUARD = "guard.on_stun_per_10_str";
    /** FLAT：重暈敵人時每 10 點力量另外獲得 x 守護（Vanguard）。 */
    public static final String VANGUARD_HEAVY = "guard.on_heavy_stun_per_10_str";
    public static final long VANGUARD_TICKS = 80;
    /** FLAT 格：點燃擴散到這麼近、待滿 1 秒的敵人（Wildfire）。 */
    public static final String WILDFIRE = "ignite.spread_radius";
    /** FLAT：消耗癱瘓，破掉牠 x% 的護甲（Undermine）。 */
    public static final String UNDERMINE = "armour.break_consume_maim";
    /** FLAT：對被招架的敵人破甲，等同物理傷害 x%（Outmaneuver）。 */
    public static final String OUTMANEUVER = "armour.break_vs_parried";
    /** FLAT：消耗被招架的減益給一顆怒星（Frenzied Riposte）。 */
    public static final String RIPOSTE = "charge.frenzy_consume_parried";
    /** FLAT：擊退時讓敵人成為好目標，每格擊退距離下一次投射物攻擊增加 x% 傷害（Punch Through）。 */
    public static final String PUNCH_THROUGH = "easy_target.per_metre";
    public static final long EASY_TARGET_TICKS = 160;
    /** FLAT：打死稀有怪偷走牠兩個詞綴 x 秒（Behead）。 */
    public static final String BEHEAD = "kill.behead_seconds";
    /** FLAT：荊棘反彈後，下一次擊中再造成兩次荊棘傷害（Barbs：次數）。 */
    public static final String BARBS = "thorns.barbs_hits";
    /** FLAT：荊棘反彈後，接下來 x 次擊中觸發刺爆（Quill Burst）。 */
    public static final String QUILL_BURST = "thorns.quill_hits";
    /** 荊棘反彈後幾 tick 內算數。 */
    public static final long THORNS_WINDOW = 160;
    /** FLAT：引導時獲得最大魔力 x% 的閃電荊棘傷害（Crackling Barrier）。 */
    public static final String CRACKLING = "thorns.mana_lightning";
    /** 閃電荊棘的下限、上限（{@code tw.exilecore.combat.Thorns} 讀）。 */
    public static final String THORNS_LIGHTNING_MIN = "thorns.lightning_min";
    public static final String THORNS_LIGHTNING_MAX = "thorns.lightning_max";
    /** FLAT：被印記的敵人受到你的物理攻擊傷害，x% 偷取為生命與魔力（Mark of Siphoning）。 */
    public static final String MARK_SIPHON = "mark.siphon_leech";
    /** FLAT：受到持續傷害的敵人生命再生減少 x%（Acrimony）。 */
    public static final String ACRIMONY = "dot.enemy_regen";
    public static final long ACRIMONY_TICKS = 80;
    /** FLAT：戰旗範圍內（插旗的地方、旗子的半徑、持續時間）的敵人每 1000 護甲 x% 元素曝露，最多 80%（Refraction）。 */
    public static final String REFRACTION = "banner.refraction";
    public static final double REFRACTION_CAP = 80;
    /** FLAT：圖騰四周 4 格的血之泉／魔之泉：盟友偷取圖騰物理傷害 x%（Font of Blood／Mana）。 */
    public static final String FONT_BLOOD = "totem.font_blood";
    public static final String FONT_MANA = "totem.font_mana";
    public static final double FONT_RADIUS = 4;
    /** FLAT：引導時每秒一層必殺決心，被擊中時用掉一層朝對方斬一刀（Deadly Resolve：最多層數）。 */
    public static final String DEADLY_RESOLVE = "channel.deadly_resolve";
    public static final long DEADLY_RESOLVE_TICKS = 200;
    public static final long DEADLY_RESOLVE_GAP = 6;
    /** 2026-10-27 照 PoE2 TriggeredDeadlyResolvePlayer：每 x 點護甲斬擊更多 1%（1–40 級），最多更多 200%。 */
    public static final int[] DEADLY_RESOLVE_ARMOUR_PER = {5, 5, 6, 6, 7, 7, 8, 8, 9, 11, 12, 15, 19, 24, 30, 46, 62, 76, 90, 108,
            108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108, 108};
    public static final double DEADLY_RESOLVE_CAP = 200;

    /** 必殺決心的斬擊在這個等級更多幾倍（1 ＋ 護甲 ÷ x ÷ 100，最多 3 倍）。 */
    public static double deadlyResolveScale(final double armour, final int level) {
        final int per = DEADLY_RESOLVE_ARMOUR_PER[Math.max(1, Math.min(DEADLY_RESOLVE_ARMOUR_PER.length, level)) - 1];
        return 1 + Math.min(DEADLY_RESOLVE_CAP, Math.max(0, armour) / per) / 100.0;
    }

    /** FLAT 秒：放的時候被暈眩，能量護盾立刻開始回充，x 秒內不會被傷害打斷（Energy Barrier）。 */
    public static final String ENERGY_BARRIER = "es.recharge_on_stunned";
    /** MORE：輕暈門檻（只有輕暈；Unbreakable）。 */
    public static final String LIGHT_STUN_THRESHOLD = "stun.light_threshold";
    /** MORE：重暈門檻（只有重暈；Unbreakable）。 */
    public static final String HEAVY_STUN_THRESHOLD = "stun.heavy_threshold";
    /** 旗標：用突進技能時每走一步震出一陣震波（Stomping Ground）。 */
    public static final String STOMPING = "travel.stomping_ground";
    /** 一步多長（PoE2 是角色的腳步；我們沒有走路動畫，照跑步的步幅 1.5 格算一步）。 */
    public static final double STOMPING_STEP = 1.5;
    /** FLAT 秒：落地時留下聖化地面 x 秒（Holy Descent）。 */
    public static final String HOLY_DESCENT = "leap.consecrated_seconds";
    public static final double CONSECRATED_RADIUS = 2.2;
    /** 聖化地面：每秒回復最大生命 5%、詛咒效果減少 50%。 */
    public static final double CONSECRATED_REGEN = 5;

    // ---- 投射物、範圍、地面 ----
    /** 旗標：投射物隨機分裂、連鎖或穿透一次（Gambleshot）。 */
    public static final String FLAG_GAMBLESHOT = "projectile.gambleshot";
    /** 旗標：穿透所有目標；投射物數量的加成改成從地形連鎖的次數（Ricochet）。 */
    public static final String FLAG_RICOCHET = "projectile.ricochet";
    /** FLAT %：法術投射物 x% 機率多射 8 發繞一圈（Wildshards）。 */
    public static final String WILDSHARDS = "projectile.wildshards";
    public static final int WILDSHARDS_COUNT = 8;
    /** 旗標：每 3 秒沒有產生光影投射物時發射存起來的光影投射物（Spectral Volley）。 */
    public static final String FLAG_SPECTRAL = "projectile.spectral_volley";
    public static final int SPECTRAL_MAX = 5;
    public static final long SPECTRAL_TICKS = 60;
    /** FLAT：光影投射物更多 x%。 */
    public static final String SPECTRAL_MORE = "projectile.spectral_more";
    /** FLAT：往目標區域兩側各連瀑 x 次（Spell Cascade）。 */
    public static final String CASCADE = "spell.cascade";
    /** FLAT：連瀑的範圍更少 x%。 */
    public static final String CASCADE_AREA = "spell.cascade_area";
    /** 旗標：新星技能在準心指的地方放，不是在你身邊（Astral Projection）。 */
    public static final String FLAG_ASTRAL = "nova.at_target";
    /** FLAT：裂地多分岔 x 道（Branching Fissures）。 */
    public static final String FISSURE_BRANCHES = "fissure.branches";
    /** FLAT：分岔出去的那幾道更少 x% 傷害（Branching Fissures）。 */
    public static final String FISSURE_BRANCH_DAMAGE = "fissure.branch_damage";
    /** FLAT：分岔出去的那幾道更少 x% 範圍（Branching Fissures）。 */
    public static final String FISSURE_BRANCH_AREA = "fissure.branch_area";
    /** FLAT：多裂開 x 道（Upheaval）。 */
    public static final String FISSURE_EXTRA = "fissure.additional";
    /** FLAT：地面效果每秒範圍增加 x%（Encroaching Ground；最多 {@link #ENCROACH_MAX_KEY}）。 */
    public static final String ENCROACH = "ground.encroach_per_second";
    public static final String ENCROACH_MAX_KEY = "ground.encroach_max";
    /** FLAT：在被輔助的技能造成的冰緩地面上的敵人，凍結累積像多 x% 傷害（Rime）。 */
    public static final String RIME = "ground.rime_freeze";
    /** FLAT：詛咒改成留下一塊詛咒地面，最多 x 塊（Cursed Ground）。 */
    public static final String CURSED_GROUND = "curse.ground_zones";
    /** FLAT 格：被詛咒的敵人死掉時，詛咒傳給這麼近的敵人（Hex Bloom）。 */
    public static final String HEX_BLOOM = "curse.bloom_radius";
    /** FLAT：圍成一圈的牆（Fortress）。 */
    public static final String FLAG_FORTRESS = "wall.fortress";
    /** FLAT：圖騰最多分裂 x 次（Splinter Totem：用一次技能後分裂成兩根更小的圖騰；SkillDeployables）。 */
    public static final String SPLINTER = "totem.splinter";
    /** FLAT %：每分裂一次傷害、最大生命、範圍各更少 x%，技能速度更少 y%（Splinter Totem）。 */
    public static final String SPLINTER_DAMAGE = "totem.splinter_damage";
    public static final String SPLINTER_LIFE = "totem.splinter_life";
    public static final String SPLINTER_AREA = "totem.splinter_area";
    public static final String SPLINTER_SPEED = "totem.splinter_speed";
    /** 旗標：這個技能同時只能有一根圖騰（Splinter Totem）。 */
    public static final String TOTEM_ONLY_ONE = "totem.only_one";
    /** 同時只有一根在分裂：分裂完過這麼久，別根才能再分裂。 */
    public static final long SPLINTER_GAP = 10;

    /** 分裂了 times 次的圖騰：這個屬性（更少 x%／次）的倍率。 */
    public static double splinterScale(final double lessPerSplinter, final int times) {
        return Math.max(0.05, 1 - lessPerSplinter / 100.0 * Math.max(0, times));
    }

    /** FLAT %：危險物件（冰晶、鐵蒺、矛陣的矛）觸發之後 x% 機率重新布置（Rearm）。 */
    public static final String REARM = "hazard.rearm";
    /** FLAT：危險物件（冰晶、鐵蒺、矛陣的矛）只在放下的那一刻或時間到時觸發、持續時間更少 70%、更多 x% 傷害（Delayed Reaction）。 */
    public static final String DELAYED_REACTION = "hazard.delayed";
    /** Delayed Reaction 的持續時間倍率（更少 70%）。 */
    public static final double DELAYED_REACTION_DURATION = 0.3;
    /** Rearm：觸發後多久重新布置（原作「a short duration」，沒寫明：用 1 秒）。 */
    public static final long REARM_TICKS = 20;

    // ---- 召喚物 ----
    /** 旗標：召喚物的打擊有擴散傷害（Minion Splash）。 */
    public static final String FLAG_MINION_SPLASH = "minion.splash";
    /** 擴散打到旁邊敵人的範圍與比例（PoE2 Melee Splash）。 */
    public static final double MINION_SPLASH_RADIUS = 2;
    public static final double MINION_SPLASH_SHARE = 0.6;
    /** FLAT 秒：召喚物生命歸零後 x 秒才死（Last Gasp）。 */
    public static final String LAST_GASP = "minion.last_gasp_seconds";
    /** FLAT：最近復活過的召喚物更多 x% 傷害（Crazed Minions）。 */
    public static final String CRAZED = "minion.crazed_more";
    public static final long CRAZED_TICKS = 80;
    /** 旗標：召喚物用指令以外的技能不造成傷害；牠們的指令冷卻回復速度增加 100%（Commandment；MinionService）。 */
    public static final String FLAG_COMMANDMENT = "minion.commandment";
    public static final double COMMANDMENT_RECOVERY = 1.0;
    /** FLAT：你受到擊中的傷害有 x% 先由夥伴承受（Loyalty）。 */
    public static final String LOYALTY = "minion.loyalty";
    /** FLAT：每用掉一具屍體給 x 點生命／魔力藥劑充能（Gorge）。 */
    public static final String GORGE = "corpse.flask_charges";

    // ---- 其他 ----
    /** FLAT：被輔助的常駐增益開著時，你在低血時攻擊傷害增加 x%（Direstrike）。 */
    public static final String DIRESTRIKE = "persistent.direstrike";
    /** FLAT：擊中的暈眩像多造成「你重暈條的百分比 × x%」的傷害（Spar、Retaliate）。 */
    public static final String POISE_STUN = "stun.from_poise";
    /** FLAT：攻擊傷害最多更多 x%，照你重暈條的比例（Retaliate）。 */
    public static final String POISE_DAMAGE = "damage.from_poise";
    /** 旗標：只是給防呆認的（Durability：要會留下冰晶的技能）。 */
    public static final String DURABILITY = "objects.durability";
    /** 旗標：只是給防呆認的（Slow Potency：要會緩速的技能）。 */
    public static final String SLOW_POTENCY = "debuff.slow_potency";

    // ---- 冷卻 ----
    /** 旗標：冷卻次數加倍；用了進入冷卻時，每秒冷卻回復最大生命 x%（Second Wind）。 */
    public static final String SECOND_WIND = "cooldown.second_wind";
}
