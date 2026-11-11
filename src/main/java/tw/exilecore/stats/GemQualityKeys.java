package tw.exilecore.stats;

/**
 * 2026-10-28 照 PoE2 0.5（站長「技能品質的部分也全部按照原作改」）：技能寶石品質（data/gem_quality.yml）用到、引擎原本沒有的屬性鍵。
 * 這次之前這 112 顆寶石的品質原作屬性我們沒有對應（tools/audit_poe2_quality.py 的 UNREP），照通用規則每 1% 增加 1% 傷害（或 0.5% 效果）。
 * <p>
 * 都寫在技能自己的修飾器（品質）裡，用到的地方照技能的組合讀（{@code SkillBuild.qualityFlat}、{@code SkillZones.localFlat}），
 * 或常駐技能照玩家技能面板上那顆寶石讀（{@code SkillService.qualityFlat}）。單位寫在每一條；FLAT 的照原作的單位（秒、公尺、%）。
 * 「增益本身的數值」（PoE2 的 *_to_apply、Buff grants x）不在這裡：寫成 effect.＊（{@code SkillBuild.EFFECT_PREFIX}），加在增益原本那一條上。
 */
public final class GemQualityKeys {

    private GemQualityKeys() {
    }

    // ---- 捷光環（AuraService）----

    /** FLAT（每秒 %）：燼使的點燃另外照過量擊殺傷害的 x%（PoE2 herald_of_ash_burning_%_overkill_damage_per_minute ÷ 60）。 */
    public static final String HERALD_ASH_OVERKILL = "herald.ash_overkill";
    /** 2026-10-30 照 PoE2 Arc 的額外品質效果：FLAT 每剩一次連鎖，這一下更多 x% 擊中傷害（SkillExecutor.chainBeam）。 */
    public static final String CHAIN_MORE_PER_REMAINING = "chain.more_per_remaining";
    /** 2026-10-30 照 PoE2 Cast on Minion Death 的額外品質效果：FLAT 插槽法術被這顆主要寶石觸發時傷害增加 x%（SkillService.metaCast）。 */
    public static final String META_TRIGGERED_DAMAGE = "meta.triggered_damage";
    /** FLAT（%）：血宣的爆炸另外多打失血的 x%（加在 15% 上）。 */
    public static final String HERALD_BLOOD_SHARE = "herald.blood_share";
    /** FLAT（公尺）：霜使的碎裂爆炸半徑 +x（加在基礎半徑上，再乘範圍）。 */
    public static final String HERALD_ICE_RADIUS = "herald.ice_radius";
    /** FLAT（%）：疫使傳毒時阻礙的機率另外 +x。 */
    public static final String HERALD_PLAGUE_HINDER = "herald.plague_hinder";
    /** FLAT：雷使落雷的次數 +x（無條件捨去）。 */
    public static final String HERALD_THUNDER_HITS = "herald.thunder_hits";

    // ---- 常駐技能（OriginalSkills、PersistentBuffs、GrantedAuras、StoredReleases…）----

    /** FLAT（%）：骸餘饗的殘骸存召喚物最大生命的比例 +x（加在 35% 上）。 */
    public static final String GRIM_SHARE = "grim.share";
    /** FLAT：蒺藜步的鐵蒺上限 +x（無條件捨去）。 */
    public static final String CALTROPS_MAX = "scatter_spikes.max";
    /** FLAT：折光之鏡每面鏡子複製的投射物 +x（無條件捨去）。 */
    public static final String MIRROR_COPIES = "mirror.copies";
    /** FLAT（%）：瘟囊爆存的中毒傷害比例 +x（加在 4% 上）。 */
    public static final String PLAGUE_BEARER_PERCENT = "pestilence_sac_burst.percent";
    /** FLAT（秒）：惡念給的暴擊弱點持續時間 +x。 */
    public static final String MALICE_WEAKNESS_SECONDS = "evil_thought.weakness_seconds";
    /** FLAT（秒）：凋息給的凋零持續時間 +x。 */
    public static final String WITHERED_SECONDS = "withering.seconds";
    /** FLAT（公尺）：蟲噬群的蟲群攻擊半徑 +x。 */
    public static final String SWARM_RADIUS = "swarm.radius";
    /** FLAT（秒）：骸骨祭司復活的骷髏不會受到傷害 x 秒。 */
    public static final String CLERIC_REVIVE_IMMUNE = "cleric.revive_immune";
    /** FLAT（%）：召喚物的怒氣效果增加 x%（骸骨狂卒：每點怒氣的傷害、攻擊速度）。 */
    public static final String MINION_RAGE_EFFECT = "minion.rage_effect";
    /** FLAT（%）：喚屍強化殭屍的效果增加 x%（受到的傷害更少、擊中傷害更多、移動速度更多都照比例放大）。 */
    public static final String ZOMBIE_EMPOWER_EFFECT = "zombie.empower_effect";
    /** FLAT：召喚物（骸骨焰術士）多丟幾顆投射物（無條件捨去）。 */
    public static final String EXTRA_PROJECTILES = "minion.extra_projectiles";
    /** FLAT：最大能量 +x（固定值，引星：元祈引星）。 */
    public static final String META_MAX_ENERGY = "meta.max_energy_flat";
    /** FLAT（%）：每顆戰星的「多一顆」機率，照給戰星的事件（charge.event_extra.事件；戰意昂揚的品質：凍結、電擊、定身）。 */
    public static final String CHARGE_EVENT_EXTRA = "charge.event_extra.";

    // ---- 一般技能 ----

    /** FLAT（秒）：技能的基礎持續時間 +x（再乘持續時間倍率；魂火祭的骨刺、腐屍爆的毒雲、掘骸的骨構體）。 */
    public static final String DURATION_FLAT = "skill.base_duration_flat";
    /** FLAT：引導的最大階段 +x（無條件捨去；爆焰矢、千鈞砸）。 */
    public static final String CHARGE_STAGES = "charge.stages_flat";
    /** FLAT（秒）：引導的前 x 秒不耗怒氣（加在技能原本的上面；熾息、蠻衝）。 */
    public static final String RAGE_FREE_SECONDS = "charge.rage_free_seconds";
    /** FLAT（%）：照衝刺距離更多傷害的上限 +x（鐵壁衝撞的 100%、裂血掠）。 */
    public static final String DISTANCE_MORE = "charge.distance_more";
    /** FLAT（%）：法術 x% 機率回響（隕星）。 */
    public static final String ECHO_CHANCE = "spell.echo_chance";
    /** FLAT（%）：投射物穿透過敵人之後更多 x% 傷害（霜鑽、穿甲重彈）。 */
    public static final String PIERCED_MORE = "projectile.pierced_more";
    /** FLAT（%）：每連鎖（彈跳）過一次更多 x% 傷害（熔球）。 */
    public static final String MORE_PER_CHAIN = "damage.more_per_chain";
    /** FLAT（%）：近身更多 x% 傷害：1.5 公尺內全部、4 公尺以外沒有，中間照距離遞減（嵐棍橫掃）。 */
    public static final String CLOSE_MORE = "damage.close_more";
    /** FLAT（%）：使用技能的動作期間暈眩門檻增加 x%（震野踏、震野雙錘）。 */
    public static final String STUN_THRESHOLD_WHILE_USING = "action.stun_threshold";
    /** FLAT（%）：x% 機率不用掉被招架的減益（霜牙刺）。 */
    public static final String KEEP_PARRIED = "parried.keep_chance";
    /** FLAT（%）：x% 機率多一道震波（回槍躍）。 */
    public static final String EXTRA_SHOCKWAVE = "shockwave.extra_chance";
    /** FLAT（%）：每多射一顆餘燼更多 x% 傷害（星火連珠）。 */
    public static final String MORE_PER_EMBER = "ember.more_per_previous";
    /** FLAT（%）：被移除的詛咒每剩 1 秒更多 x% 傷害（蝕咒裂）。 */
    public static final String MORE_PER_CURSE_SECOND = "hexblast.more_per_second";
    /** FLAT（%）：每顆命中的碎片讓點燃持續時間增加 x%（燃焰散射）。 */
    public static final String IGNITE_DURATION_PER_PELLET = "ignite.duration_per_pellet";
    /** FLAT（%）：每根地刺更多 x% 傷害（地刺崩裂）。 */
    public static final String MORE_PER_SPIKE = "spike.more_per_spike";
    /** FLAT（%）：鐘每被打一下震波更多 x% 傷害（驚雷之鐘）。 */
    public static final String MORE_PER_BELL_HIT = "bell.more_per_hit";
    /** FLAT（%）：x% 機率多留一個矛頭（三連刺）。 */
    public static final String EXTRA_SPEAR_TIP = "spear_tip.extra_chance";
    /** FLAT（%）：引爆矛時 x% 機率留下感電地面（雷躍）。 */
    public static final String SHOCKED_GROUND_CHANCE = "detonate.shocked_ground_chance";
    /** FLAT（%）：使用時 x% 機率只用掉 60% 榮耀（熾陽之矛：保留 40%）。 */
    public static final String GLORY_RETAIN_CHANCE = "glory.retain_chance";
    /** FLAT（%）：x% 機率不消耗榮耀（天罰巨鎚的額外品質效果；2026-11-08）。 */
    public static final String GLORY_NOT_CONSUME_CHANCE = "glory.not_consume_chance";
    /** INCREASED：引爆時間增減（子母榴彈的額外品質效果；2026-11-08，ProjectileEngine.detonationScale）。 */
    public static final String DETONATION_TIME = "skill.detonation_time";
    /** FLAT：疫潮施放時另外感染的敵人數 +x（無條件捨去）。 */
    public static final String EXTRA_TARGETS = "skill.extra_targets";
    /** FLAT：噬屍宴吃的屍體 +x（無條件捨去）。 */
    public static final String CORPSES_FLAT = "corpses.flat";
    /** FLAT（%）：吃屍體時 x% 機率當作擊殺獲得藥劑與護符充能（噬咬）。 */
    public static final String CORPSE_FLASK_CHANCE = "corpse.flask_charge_chance";
    /** FLAT（秒）：離開魔湧之後效果延續 x 秒。 */
    public static final String LINGER_SECONDS = "mana_tempest.linger";
    /** FLAT（%）：重複的投射物「更少 x%」減少 x 個百分點（連珠：負的）。 */
    public static final String BARRAGE_LESS = "barrage.less";
    /** FLAT（%）：被自己的技能打碎時更多 x% 傷害（土壁擊，加在 100% 上）。 */
    public static final String WALL_SELF_MORE = "wall.self_more";
    /** FLAT（%）：危害 x% 機率重新布置（矛陣）。 */
    public static final String HAZARD_REARM = "hazard.rearm_chance";
    /** 2026-11-10 照站長「擲雷槍品質照原作」：FLAT：打中炸開時多射幾發雷彈（擲雷槍，無條件捨去；ProjectileEngine.splash）。 */
    public static final String SPLASH_TARGETS_EXTRA = "splash.targets_extra";
    /** FLAT：同時最多幾支超載的矛 +x（霆落矛，無條件捨去）。 */
    public static final String OVERCHARGED_LIMIT = "lance.overcharged_extra";
    /** 2026-11-10 照 PoE2 擲雷槍額外的品質效果：消耗怒星的好處（分裂支數）有 x% 機率加倍。 */
    public static final String DOUBLE_CHARGE_BENEFIT = "lance.double_charge_chance";
    /** FLAT（%）：對插了電殛棒的目標，擊中獲得 x% 傷害為額外閃電（電殛矢）。 */
    public static final String ROD_GAIN_LIGHTNING = "rod.gain_lightning";
    /** FLAT：雷雲多放 x 道雷才消失（風暴漩渦 Orb of Storms，無條件捨去；2026-10-29 照 PoE2）。 */
    public static final String ORB_EXTRA_BOLTS = "orb.extra_bolts";
    /** FLAT（%）：淋濕的敵人凍結累積更多 x%（積雷雲）。 */
    public static final String DRENCHED_FREEZE_MORE = "drenched.freeze_more";
    /** FLAT（%）：淋濕的敵人被感電的機率更多 x%（積雷雲）。 */
    public static final String DRENCHED_SHOCK_MORE = "drenched.shock_more";
    /** FLAT（%）：照附近每個敵人，被印記的敵人受到的傷害增加 x%（掠影撲的獵手印記）。 */
    public static final String MARK_PER_ENEMY = "pounce.mark_per_enemy";
    /** FLAT（%）：同上的上限 +x。 */
    public static final String MARK_CAP = "pounce.mark_cap";
    /** FLAT：心咒需要的連擊 +x（負的 = 少；無條件捨去到整數）。 */
    public static final String MANTRA_COMBO = "mantra.combo";
    /** FLAT（%）：曝露疊加的上限 +x（寒晶雷）。 */
    public static final String EXPOSURE_CAP = "exposure.cap_flat";
    /** FLAT（%）：被標記的目標身上失血的另外 x% 算成重暈累積（血嗅印，加在 300% 上）。 */
    public static final String BLOOD_TO_STUN = "bloodhound.stun_percent";
    /** FLAT（%）：護盾消失後 1 秒內受到的傷害更少 x%（骸骨祭給的召喚物）。 */
    public static final String SHIELD_LOST_LESS = "offering.shield_lost_less";
    /** 喚駒（Rhoa Mount）的技能代號。 */
    public static final String MOUNT_SKILL = "steed_call";
    /** FLAT（%）：坐騎更多 x% 最大生命——坐騎打不死，換成騎著時的重暈門檻更多 x%（AilmentService）。 */
    public static final String MOUNT_LIFE_MORE = "mount.life_more";
    // ---- 2026-11-11 照站長「一般品質再算一次的照原作改（第二批）」：高階奇術的額外品質效果（PoB2 altQualityStats／poe2db）----

    /** FLAT（%）：捷放出來的那一下傷害增加 x%（燼使的點燃、霜使的碎裂、雷使的落雷；herald_of_*_damage_+%）。 */
    public static final String HERALD_DAMAGE = "herald.damage";
    /** FLAT：獸血沸攻擊擊中多獲得 x 點獸性（無條件捨去；wild_beast_energy_per_hit）。 */
    public static final String FURY_PER_HIT = "fury.per_hit";
    /** FLAT（秒）：元素潮汐換元素的週期 +x（base_skill_effect_duration）。 */
    public static final String CONFLUX_SECONDS = "conflux.seconds";
    /** FLAT（%）：磨蝕之志用的存在範圍增加 x%（presence_area_+%；半徑照 √）。 */
    public static final String PRESENCE_AREA = "grinding_will.presence_area";
    /** FLAT：連段的終結打擊多打 x 下（無條件捨去；風暴連擊 final_strike_number_of_spirit_strikes）。 */
    public static final String FINAL_EXTRA_HITS = "stage.final_extra_hits";
    /** FLAT：鐘多響 x 次（無條件捨去；驚雷之鐘 bell_hit_limit）。 */
    public static final String BELL_HIT_LIMIT = "bell.hit_limit";
    /** FLAT（%）：旋風被元素地面強化時，崩散獲得 x% 傷害為那種元素（颶風槍 windstorm_gain_all_damage_%_as_corresponding_element_if_empowered）。 */
    public static final String WHIRL_EMPOWERED_GAIN = "whirl.empowered_gain";
    /** FLAT（%）：x% 機率另外往四周一圈多射 8 發（熾星彈 spell_skill_%_chance_to_fire_8_additional_projectiles_in_nova）。 */
    public static final String NOVA_EIGHT_CHANCE = "projectile.nova_eight_chance";
    /** FLAT（%）：凜冬之殼每層給 x% 增加護甲（arctic_armour_armour_granted_+%_per_stage）。 */
    public static final String ARCTIC_ARMOUR_PER_STAGE = "arctic.armour_per_stage";
    /** FLAT（%）：插著電殛棒的敵人受到的傷害增加 x%（electrocuting_arrow_damage_taken_+%）。 */
    public static final String ROD_DAMAGE_TAKEN = "rod.damage_taken";
    /** FLAT（%）：被霜浪打中的敵人凍結時受到的傷害增加 x%（frozen_monsters_take_increased_damage）。 */
    public static final String FROZEN_DAMAGE_TAKEN = "frozen.damage_taken";
    /** FLAT（%）：血獵的血爆另外讓被炸到的失去用掉失血的 x%（blood_hunt_explosion_%_blood_loss_to_deal_as_life_loss）。 */
    public static final String BLOOD_HUNT_LIFE_LOSS = "blood_hunt.life_loss";
    /** FLAT（秒）：技能的次要效果持續時間 +x（崩滅心咒的紫焰；base_secondary_skill_effect_duration）。 */
    public static final String SECONDARY_DURATION_FLAT = "skill.secondary_duration_flat";
    /** FLAT（百分點，負的）：魔湧裡花掉的魔力、生命加到每秒消耗的比例（30%）少 x（mana_tempest_mana_cost_%_to_add_to_cost_per_second）。 */
    public static final String TEMPEST_SPENT_SHARE = "mana_tempest.spent_share";
    /** FLAT：多強化 x 次攻擊（無條件捨去；霜鏃 shearing_bolts_number_of_empowered_attacks）。 */
    public static final String EMPOWERED_ATTACKS = "skill.empowered_attacks";
    /** FLAT（秒）：離開區域之後增益多留 x 秒（瘴域 toxic_domain_buff_linger_duration_ms）。 */
    public static final String BUFF_LINGER_SECONDS = "buff.linger_seconds";
    /** FLAT（跟位移逐級表同單位）：位移距離 +x（掠閃 blink_travel_distance）。 */
    public static final String DASH_DISTANCE_FLAT = "dash.distance_flat";
    /** FLAT：增益最多 +x 層（無條件捨去；幻步 ghost_dance_max_stacks）。 */
    public static final String MAX_STACKS = "buff.max_stacks";
    /** FLAT（%）：疾風每一階另外更多 x% 傷害（風之舞步 wind_dancer_damage_+%_final_per_stage）。 */
    public static final String GALE_MORE_PER_STAGE = "gale.more_per_stage";
    /** FLAT（秒，負的）：反覆的間隔 +x（蟲噬群每群的間隔、凋息每層凋零的間隔）。 */
    public static final String REPEAT_INTERVAL_SECONDS = "skill.repeat_interval_flat";
    /** FLAT（%）：x% 物理轉成隨機元素（三相訣的增益；DamageCalculator 照期望值平均分給三種元素）。 */
    public static final String CONVERT_RANDOM_ELEMENT = "damage.convert_random_element";
    /** FLAT（%）：閃電傷害 x% 機率為幸運（雷鳴箭、引霆突 chance_for_extra_damage_roll_with_lightning_damage_%）。 */
    public static final String LIGHTNING_LUCKY = "damage.lucky_lightning_chance";
    /** FLAT（%）：被印記標記的敵人造成的傷害更少 x%（獵殺印記 gem_quality_marked_enemy_damage_dealt_+%_final；SkillExecutor 另外掛的減益）。 */
    public static final String MARK_ENEMY_LESS = "mark.enemy_damage_less";
    /** 2026-11-11 照站長「PoB2 找不到的 18 顆照 poe2db 改」：FLAT（%）：每一發投射物 x% 機率回歸（寒冬之眼）。 */
    public static final String RETURN_CHANCE = "projectile.return_chance";
    /** 2026-11-11 照站長「天柱雷、召喚骸骨焰術士、爆毒矢還沒做品質」：爆裂殉身另外附加被引爆召喚物最大生命 x% 的火焰傷害（Skeletal Arsonist 的額外品質）。 */
    public static final String DEMISE_LIFE_PERCENT = "minion.demise_life_percent";
    /** 2026-11-11 同上：破甲量等於中毒傷害的 x%（Poisonburst Arrow 的額外品質；中毒施加時照這層毒的總傷害算）。 */
    public static final String POISON_ARMOUR_BREAK = "poison.armour_break";
    /** 2026-11-11：FLAT（%）：每多一層引導，傷害型異常的幅度更多 x%（爆炎 Flameblast）。 */
    public static final String AILMENT_MORE_PER_STAGE = "ailment.more_per_stage";
}
