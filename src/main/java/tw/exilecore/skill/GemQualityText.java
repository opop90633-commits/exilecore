package tw.exilecore.skill;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-10-28 照 PoE2 0.5（站長「技能品質的部分也全部按照原作改」）：這次照原作接上的 111 顆寶石的品質說明（寶石、技能面板用）。
 * 樣板裡 {屬性} 換成品質給的數值（FLAT 照原值；INCREASED／MORE 乘 100），{-屬性} 是負號拿掉，{i:屬性} 是往 0 捨去的整數，
 * {m:屬性} 是公尺換成格（2026-10-29，{@link tw.exilecore.core.Units}）；
 * 同一個屬性有好幾條（元素潮汐的三種元素）取第一條。原作的效果我們沒有對應機制的（品質寫 []）照 {@link #NO_EFFECT} 寫原因。
 * 純邏輯，不依賴 Bukkit。
 */
final class GemQualityText {

    private GemQualityText() {
    }

    /** 技能代號 → 說明樣板。 */
    static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("potion_craft_insight", "生命、魔力藥劑每分鐘另外獲得 {effect.flask.charges_per_minute.life} 點充能"),
            Map.entry("grinding_will", "撲殺需要的層數少 {-grinding_will.cull}"),
            Map.entry("wood_armor", "被敵人擊中時另外獲得失去的能量護盾 {effect.wood_armor.armour}% 的護甲"),
            Map.entry("field_quake_stomp", "使用這個技能的動作期間暈眩門檻增加 {action.stun_threshold}%"),
            Map.entry("crimson_wrath", "增益另外增加 {effect.rage.effect}% 怒氣效果"),
            Map.entry("flame_blast_shaft", "最大階段 +{i:charge.stages_flat}"),
            Map.entry("blood_scent_seal", "被標記的目標另外照失血的 {bloodhound.stun_percent}% 累積重暈"),
            Map.entry("wild_beast_taming", "鬼火持續時間 +{tame.wisp_seconds} 秒、夥伴的保留效率增加 {-aura.reservation}%"), // 2026-10-30 照 PoE2 Tame Beast
            Map.entry("thunderbind_staff", "增加 {skill.duration}% 技能效果持續時間、{charged_staff.keep_chance}% 機率不用掉銳星"), // 2026-10-30 照 poe2db Charged Staff
            Map.entry("bone_thorn_ring", "定身敵人時當作傷害再更多 {pin.as_though_damage}%"),
            Map.entry("bone_rite", "被護住的召喚物護盾消失後 1 秒內受到的傷害更少 {offering.shield_lost_less}%"),
            Map.entry("dig_remains", "骨構體持續時間 +{skill.base_duration_flat} 秒、移動速度增加 {movement.speed}%"),
            Map.entry("thorn_body", "法術擊中時 {effect.hinder.on_hit}% 機率阻礙敵人"),
            Map.entry("battle_spirit_rise", "凍結、電擊、定身給戰星時 {effect.charge.event_extra.freeze}% 機率多一顆"),
            Map.entry("falling_star", "{spell.echo_chance}% 機率回響"),
            Map.entry("plague_taint", "施放時另外感染 {i:skill.extra_targets} 個敵人"),
            Map.entry("shield_restore_art", "增益持續時間 +{shield_restore_art.duration} 秒"),
            Map.entry("nether_contract", "更多 {damage}% 傷害"),
            Map.entry("rotting_corpse_blast", "毒雲持續時間 +{skill.base_duration_flat} 秒"), // 2026-10-28：腐屍爆有毒雲了（這次之前列在 NO_EFFECT）
            Map.entry("gnawing_bite", "每吃一具屍體 {corpse.flask_charge_chance}% 機率當作擊殺獲得藥劑與護符充能"),
            Map.entry("law_shield", "光環另外增加 {effect.es.recharge}% 能量護盾回充速度"),
            Map.entry("spear_recoil_leap", "{shockwave.extra_chance}% 機率多一道震波"),
            Map.entry("element_tide", "增益讓受影響的元素另外更多 {effect.damage}% 傷害"),
            Map.entry("tripod_element_art", "親和總和超過 250 時技能速度增加 {effect.attack.speed@trinityhigh}%"),
            Map.entry("starfire_bead_chain", "每一顆之前射過的火星再更多 {ember.more_per_previous}% 傷害"),
            Map.entry("root_bind", "{skill.additional_fissure_chance}% 機率多一道裂隙、同時上限 +{i:skill.limit} 道"),
            Map.entry("unquenched_fury", "每秒另外回復 {effect.rage.regen} 點怒氣"),
            Map.entry("blood_vein_tether", "多 {i:skill.chains} 道血脈（多一個目標）"),
            Map.entry("frost_tooth_stab", "{parried.keep_chance}% 機率不用掉被招架的減益"),
            Map.entry("corpse_devour_feast", "多吃 {i:corpses.flat} 具屍體（身邊超過 5 具時）"),
            Map.entry("flame_breath", "引導的前 {charge.rage_free_seconds} 秒另外不耗怒氣"),
            Map.entry("frostbind_seal", "被標記的敵人另外受到更多 {effect.freeze.buildup_taken}% 凍結累積"),
            Map.entry("winter_shell", "最大層數 +{i:arctic.stages}"),
            Map.entry("chill_crystal_mine", "曝露疊加的上限 +{exposure.cap_flat}%"),
            Map.entry("frost_drill", "穿透過敵人之後投射物更多 {projectile.pierced_more}% 傷害"),
            Map.entry("thunder_resonance", "額外擊中的「更少」少 {-thunder_resonance.less} 個百分點"),
            Map.entry("mist_staff_sweep", "1.5 公尺內的敵人更多 {damage.close_more}% 傷害（到 4 公尺遞減到沒有）"),
            Map.entry("remains_banquet", "殘骸另外存召喚物最大生命的 {grim.share}%"),
            Map.entry("ember_envoy", "另外 {herald.ash_overkill}% 過量擊殺傷害算進點燃（每秒）"),
            Map.entry("blood_proclaim", "爆炸另外多打失血的 {herald.blood_share}%"),
            Map.entry("frost_envoy", "碎裂爆炸半徑 +{m:herald.ice_radius} 格"), // 2026-10-29：品質給的是公尺，顯示換算後的格
            Map.entry("plague_envoy", "傳毒時阻礙的機率另外 +{herald.plague_hinder}%"),
            Map.entry("thunder_envoy", "落雷次數 +{i:herald.thunder_hits}"),
            Map.entry("erosion_curse_rift", "被移除的詛咒每剩 1 秒更多 {hexblast.more_per_second}% 傷害"),
            Map.entry("prey_kill_mark", "下一次暴擊被標記的敵人另外增加 {effect.crit.bonus_taken}% 暴擊傷害加成"),
            Map.entry("corrode_guard", "光環另外給 +{effect.resist.chaos}% 混沌抗性"),
            Map.entry("flame_scatter_fire", "每顆打中的碎片讓點燃持續時間增加 {ignite.duration_per_pellet}%"),
            Map.entry("iron_rampart_guard", "爆開的暈眩累積更多 {stun.buildup}%"),
            Map.entry("phantom_body", "另外 {effect.charge.remnant_extra}% 機率多留一個殘影"),
            Map.entry("strung_pearls", "重複的投射物「更少」少 {-barrage.less} 個百分點"),
            Map.entry("magma_barrier", "增益另外增加 {effect.block.chance}% 格擋機率"),
            Map.entry("evil_thought", "暴擊弱點持續時間 +{evil_thought.weakness_seconds} 秒"),
            Map.entry("magic_absorb_art", "吸取的魔力 +{mana.on_hit}"),
            Map.entry("magic_spring_well", "增益另外增加 {effect.mana.regen}% 魔力回復、擊殺回魔另外 +{effect.mana.on_kill}"),
            Map.entry("magic_surge", "離開之後效果再延續 {mana_tempest.linger} 秒"),
            Map.entry("element_prayer_star", "最大能量 +{meta.max_energy_flat}"),
            Map.entry("phantom_bow_shade", "幻影持續時間 +{skill.base_duration_flat} 秒（你停手之後幻影再多射這麼久）"),
            Map.entry("light_bend_mirror", "每面鏡子多複製 {i:mirror.copies} 發投射物"),
            Map.entry("armor_punch_slug", "穿透過敵人之後投射物更多 {projectile.pierced_more}% 傷害"),
            Map.entry("pestilence_sac_burst", "另外存中毒傷害的 {pestilence_sac_burst.percent}%"),
            Map.entry("pounce", "印記每個附近的敵人另外 +{pounce.mark_per_enemy}% 受到的傷害、上限另外 +{pounce.mark_cap}%"),
            Map.entry("filth_array", "儀式完成時 {corpse.save_chance}% 機率不毀掉用掉的屍體"), // 2026-10-29 照 PoE2：穢陣要屍體了（這次之前列在 NO_EFFECT）
            Map.entry("flame_guard", "光環另外給 +{effect.resist.fire}% 火焰抗性"),
            Map.entry("frost_guard", "光環另外給 +{effect.resist.cold}% 冰冷抗性"),
            Map.entry("thunder_guard", "光環另外給 +{effect.resist.lightning}% 閃電抗性"),
            Map.entry("myriad_arrow_fall", "箭落下的速度增加 {skill.projectile_speed}%"),
            Map.entry("corpse_summon", "強化殭屍的效果增加 {zombie.empower_effect}%"),
            Map.entry("blood_tear_sweep", "照衝刺距離更多傷害的上限另外 +{charge.distance_more}%"),
            Map.entry("brute_charge", "引導的前 {charge.rage_free_seconds} 秒另外不耗怒氣"),
            Map.entry("successive_shots", "最大熱度 +{i:crossbow.clip}"),
            Map.entry("triple_stab", "{spear_tip.extra_chance}% 機率多留一個矛頭"),
            Map.entry("insect_devour_horde", "蟲群攻擊半徑 +{m:swarm.radius} 格"), // 2026-10-29：同上
            Map.entry("molten_ball", "每彈（連鎖）過一次更多 {damage.more_per_chain}% 傷害"),
            Map.entry("field_quake_hammers", "使用這個技能的動作期間暈眩門檻增加 {action.stun_threshold}%"),
            Map.entry("collapse_heart_chant", "需要的連擊少 {-i:mantra.combo}"),
            Map.entry("servant_corpse_swap", "召喚物復活另外快 {servant_corpse_swap.slower}%、召喚物最大生命增加 {effect.life.max}%"), // 2026-10-29 照 PoE2：補上最大生命
            Map.entry("beast_blood_boil", "狂暴另外更多 {effect.damage}% 傷害"),
            Map.entry("gathered_scales", "增益另外增加 {effect.armour}% 護甲、{effect.armour.break}% 破甲"),
            Map.entry("snake_squall_thrust", "擊退距離更多 {skill.knockback}%"),
            Map.entry("earth_spike_eruption", "每根還在的地刺更多 {spike.more_per_spike}% 擊中傷害"),
            Map.entry("iron_wall_ram", "照衝刺距離更多傷害的上限另外 +{charge.distance_more}%"),
            Map.entry("earth_wall_strike", "被自己的技能打碎時再更多 {wall.self_more}% 傷害"),
            Map.entry("electrocute_shaft", "打中插著電殛棒的目標，獲得 {rod.gain_lightning}% 傷害為額外閃電傷害"),
            Map.entry("element_trace_surge", "凍結、點燃、感電時產生灌注殘骸的機率另外 +{effect.infusion.remnant_on_ailment}%"),
            Map.entry("summon_bone_priest", "召喚物：復活的骷髏 {cleric.revive_immune} 秒不會受到傷害"),
            Map.entry("summon_bone_flamecaster", "召喚物：多丟 {i:minion.extra_projectiles} 顆火彈"),
            Map.entry("summon_bone_berserker", "召喚物：怒氣效果增加 {minion.rage_effect}%"),
            Map.entry("soulfire_rite", "骨刺持續時間 +{skill.base_duration_flat} 秒"),
            Map.entry("spear_formation", "危害 {hazard.rearm_chance}% 機率重新布置"),
            Map.entry("raven_plot", "烏鴉群增加 {skill.area}% 範圍"),
            Map.entry("rushing_thunder_stomp", "{slam.aftershock_chance}% 機率多一次餘震"),
            Map.entry("thunder_cloud", "連鎖 +{i:skill.chains} 次、雷雲多放 {i:orb.extra_bolts} 道雷才消失"), // 2026-10-29 照 PoE2
            Map.entry("thunderfall_spear", "超載的矛上限 +{i:lance.overcharged_extra}"),
            Map.entry("steed_call", "戰馬更多 {life.max}% 最大生命"), // 2026-10-30 照 PoE2：沒騎時陪你打的戰馬會被打了（這次之前坐騎打不死，換成騎著時的重暈門檻）
            Map.entry("sunder", "照距離的命中懲罰減少 {-accuracy.range_penalty}%"),
            Map.entry("blazing_sun_spear", "{glory.retain_chance}% 機率用的時候保留 40% 榮耀"),
            Map.entry("thousand_weight_smash", "最大階段 +{i:charge.stages_flat}"),
            Map.entry("startling_thunder_bell", "鐘每被打一下震波更多 {bell.more_per_hit}% 傷害"),
            Map.entry("thunder_bound", "引爆矛的時候 {detonate.shocked_ground_chance}% 機率留下感電地面"),
            Map.entry("gathered_thundercloud", "濕透的敵人另外受到更多 {drenched.freeze_more}% 凍結累積、被感電的機率另外更多 {drenched.shock_more}%"),
            Map.entry("guard_moment", "祝福另外回復 {effect.blessing.life} 生命"),
            Map.entry("thornburr_steps", "鐵蒺上限 +{i:scatter_spikes.max}"),
            Map.entry("flame_vein_rift", "{slam.aftershock_chance}% 機率多一次餘震"),
            Map.entry("thunder_lure_seal", "被標記的敵人另外受到增加 {effect.electrocute.buildup_taken}% 電擊累積"),
            Map.entry("fallen_star_bear_roar", "增益另外獲得 {effect.damage.extra.fire}% 傷害為額外火焰傷害"),
            Map.entry("hurricane_spear", "旋風崩散每多一階再更多 {whirl.collapse_more_per_stage}% 傷害"),
            Map.entry("wingbeat_gust", "重暈蓄勢待暈的敵人時另外 {charge.event.stun_primed.power}% 機率獲得銳星"),
            Map.entry("wilting_breath", "凋零持續時間 +{withering.seconds} 秒"),
            Map.entry("wing_blade_slash", "增益另外獲得 {effect.damage.gain.lightning}% 傷害為閃電"),
            // 2026-10-30 照 PoE2：昇華賦予的技能（gem_quality.yml 的 asc.＊ 是昇華那邊照等級讀的數值，品質加在上面）
            Map.entry("destiny_attune", "殘影施放的頻率更多 {asc.visage_frequency}%"),
            Map.entry("calamity_descent", "天災觸發的頻率增加 {asc.apocalypse_frequency}%"),
            Map.entry("rose_jade_lord", "增益另外更多 {effect.damage}% 物理與混沌傷害"),
            Map.entry("seething_blood", "腐化之血持續時間 +{asc.boil_seconds} 秒"),
            Map.entry("become_fiend", "化魔時另外增加 {effect.cast.speed}% 施法速度"),
            Map.entry("jade_entomb", "每層玉石的守護 +{asc.jade_guard_percent}% 最大生命"),
            Map.entry("explosive_concoction", "多丟給 {i:asc.explosive_extra} 個點燃中的敵人"),
            Map.entry("hollow_focus", "鐘出現的半徑 -{-m:asc.bell_spawn} 格"),
            Map.entry("hollow_form", "{charge.sup.keep}% 機率不拿掉銳星但照樣算用掉"),
            Map.entry("hollow_resonance", "鐘響更多 {asc.resonance_stun}% 暈眩累積"),
            Map.entry("fated_pain", "一開始的半徑 +{asc.agony_radius} 格"),
            Map.entry("rift_dive", "霞瑤之炎的效果增加 {asc.flame_effect_inc}%"),
            Map.entry("sand_spirit_venom_word", "甲蟲上限 +{i:asc.beetle_max}"),
            Map.entry("vital_residue", "擊殺時產生痕跡的機率 +{asc.remnant_kill_chance}%"),
            Map.entry("calm_mind_trance", "能量護盾回充速度另外更多 {effect.es.recharge}%"),
            Map.entry("mirage_deadeye", "幻影持續時間 +{asc.mirage_seconds} 秒"),
            Map.entry("primal_bounty", "強化的投射物速度另外增加 {asc.bounty_speed}%"),
            Map.entry("rite_offering", "獲得的怪物詞綴持續時間 +{asc.sacrifice_seconds} 秒"),
            Map.entry("witch_barrier", "屏障另外吸收護甲與閃避總和的 {asc.ward_percent}%"),
            Map.entry("free_incarnation", "無縛持續時間 +{asc.buff_seconds} 秒"),
            Map.entry("righteous_shield", "每顆智慧寶珠另外增加 {asc.mote_regen}% 生命與魔力再生"),
            Map.entry("vivid_stampede", "震波半徑 +{m:asc.stag_radius} 格"),
            Map.entry("null_phantom", "爆炸半徑 +{m:asc.void_radius} 格"));

    /** 原作的品質效果我們沒有對應機制（品質寫 []）的：技能代號 → {原因樣板, 每 1% 品質的原作數值}；{v} 換成這個品質的數值。 */
    static final Map<String, Object[]> NO_EFFECT = Map.ofEntries(
            Map.entry("thorn_shaft", new Object[] {"這個技能的品質沒有效果", 0.5}),
            Map.entry("might_seal", new Object[] {"這個技能的品質沒有效果", 0.04}),
            Map.entry("leaping_staff_crash", new Object[] {"這個技能的品質沒有效果", 0.1}),
            Map.entry("breeze_dance_step", new Object[] {"這個技能的品質沒有效果", 0.02}),
            Map.entry("gap_opportunity", new Object[] {"這個技能的品質沒有效果", 1.0})); // 2026-10-30：原作打殘影的傷害給本體，我們沒有時空殘影

    /** 這個技能 quality% 的說明本體（不含「品質 +x%：」）；不是這次接上的回傳 null。 */
    static String body(final String skillId, final List<Modifier> mods, final int quality) {
        final Object[] none = NO_EFFECT.get(skillId);
        if (none != null) {
            final double value = ((Number) none[1]).doubleValue() * quality;
            return ((String) none[0]).replace("{v}", number(value));
        }
        final String template = TEMPLATES.get(skillId);
        if (template == null || mods == null) {
            return null;
        }
        final StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            final char c = template.charAt(i);
            final int end = c == '{' ? template.indexOf('}', i) : -1;
            if (end < 0) {
                text.append(c);
                i++;
                continue;
            }
            String key = template.substring(i + 1, end);
            final boolean negate = key.startsWith("-");
            if (negate) {
                key = key.substring(1);
            }
            final boolean integer = key.startsWith("i:");
            if (integer) {
                key = key.substring(2);
            }
            final boolean metres = key.startsWith("m:"); // 2026-10-29：公尺換成格
            if (metres) {
                key = key.substring(2);
            }
            double value = value(mods, key);
            if (metres) {
                value = tw.exilecore.core.Units.metres(value);
            }
            if (negate) {
                value = -value;
            }
            text.append(integer ? String.valueOf((int) (value + (value >= 0 ? 1e-9 : -1e-9))) : number(value));
            i = end + 1;
        }
        return text.toString();
    }

    /** 品質給的這個屬性的第一條（FLAT 原值；INCREASED／MORE 乘 100）；沒有回傳 0。 */
    static double value(final List<Modifier> mods, final String stat) {
        for (final Modifier modifier : mods) {
            if (modifier.stat().equals(stat)) {
                return modifier.type() == ModType.FLAT ? modifier.value() : modifier.value() * 100;
            }
        }
        return 0;
    }

    static String number(final double value) {
        final double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.format(Locale.ROOT, "%s", rounded);
    }
}
