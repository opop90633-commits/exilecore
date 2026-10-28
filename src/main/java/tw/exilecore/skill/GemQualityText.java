package tw.exilecore.skill;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-10-28 照 PoE2 0.5（站長「技能品質的部分也全部按照原作改」）：這次照原作接上的 111 顆寶石的品質說明（寶石、技能面板用）。
 * 樣板裡 {屬性} 換成品質給的數值（FLAT 照原值；INCREASED／MORE 乘 100），{-屬性} 是負號拿掉，{i:屬性} 是往 0 捨去的整數；
 * 同一個屬性有好幾條（元素潮汐的三種元素）取第一條。原作的效果我們沒有對應機制的（品質寫 []）照 {@link #NO_EFFECT} 寫原因。
 * 純邏輯，不依賴 Bukkit。
 */
final class GemQualityText {

    private GemQualityText() {
    }

    /** 技能代號 → 說明樣板。 */
    static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("alchemists_boon", "生命、魔力藥劑每分鐘另外獲得 {effect.flask.charges_per_minute.life} 點充能"),
            Map.entry("attrition", "撲殺需要的層數少 {-attrition.cull}"),
            Map.entry("barkskin", "被敵人擊中時另外獲得失去的能量護盾 {effect.barkskin.armour}% 的護甲"),
            Map.entry("bear_slam", "使用這個技能的動作期間暈眩門檻增加 {action.stun_threshold}%"),
            Map.entry("berserk", "增益另外增加 {effect.rage.effect}% 怒氣效果"),
            Map.entry("blast_arrow", "最大階段 +{i:charge.stages_flat}"),
            Map.entry("bloodhounds_mark", "被標記的目標另外照失血的 {bloodhound.stun_percent}% 累積重暈"),
            Map.entry("bone_cage", "定身敵人時當作傷害再更多 {pin.as_though_damage}%"),
            Map.entry("bone_offering", "被護住的召喚物護盾消失後 1 秒內受到的傷害更少 {offering.shield_lost_less}%"),
            Map.entry("bone_spikes", "骨構體持續時間 +{skill.base_duration_flat} 秒、移動速度增加 {movement.speed}%"),
            Map.entry("briarpatch", "法術擊中時 {effect.hinder.on_hit}% 機率阻礙敵人"),
            Map.entry("combat_frenzy", "凍結、電擊、定身給戰星時 {effect.charge.event_extra.freeze}% 機率多一顆"),
            Map.entry("comet", "{spell.echo_chance}% 機率回響"),
            Map.entry("contagion", "施放時另外感染 {i:skill.extra_targets} 個敵人"),
            Map.entry("convalescence", "增益持續時間 +{convalescence.duration} 秒"),
            Map.entry("dark_pact", "更多 {damage}% 傷害（原作另外犧牲召喚物生命，這裡照比例換算）"),
            Map.entry("devour", "每吃一具屍體 {corpse.flask_charge_chance}% 機率當作擊殺獲得藥劑與護符充能"),
            Map.entry("discipline", "光環另外增加 {effect.es.recharge}% 能量護盾回充速度"),
            Map.entry("disengage", "{shockwave.extra_chance}% 機率多一道震波"),
            Map.entry("elemental_conflux", "增益讓受影響的元素另外更多 {effect.damage}% 傷害"),
            Map.entry("elemental_trinity", "親和總和超過 250 時技能速度增加 {effect.attack.speed@trinityhigh}%"),
            Map.entry("ember_volley", "每一顆之前射過的火星再更多 {ember.more_per_previous}% 傷害"),
            Map.entry("entangle", "{skill.additional_fissure_chance}% 機率多一道裂隙"),
            Map.entry("eternal_rage", "每秒另外回復 {effect.rage.regen} 點怒氣"),
            Map.entry("exsanguinate", "多 {i:skill.chains} 道血脈（多一個目標）"),
            Map.entry("fangs_of_frost", "{parried.keep_chance}% 機率不用掉被招架的減益"),
            Map.entry("feast_of_flesh", "多吃 {i:corpses.flat} 具屍體（身邊超過 5 具時）"),
            Map.entry("flame_breath", "引導的前 {charge.rage_free_seconds} 秒另外不耗怒氣"),
            Map.entry("freezing_mark", "被標記的敵人另外受到更多 {effect.freeze.buildup_taken}% 凍結累積"),
            Map.entry("frost_aegis", "最大層數 +{i:arctic.stages}"),
            Map.entry("frost_mine", "曝露疊加的上限 +{exposure.cap_flat}%"),
            Map.entry("frostbolt", "穿透過敵人之後投射物更多 {projectile.pierced_more}% 傷害"),
            Map.entry("fulmination", "額外擊中的「更少」少 {-fulmination.less} 個百分點"),
            Map.entry("gale_sweep", "1.5 公尺內的敵人更多 {damage.close_more}% 傷害（到 4 公尺遞減到沒有）"),
            Map.entry("grim_feast", "殘骸另外存召喚物最大生命的 {grim.share}%"),
            Map.entry("herald_of_ash", "另外 {herald.ash_overkill}% 過量擊殺傷害算進點燃（每秒）"),
            Map.entry("herald_of_blood", "爆炸另外多打失血的 {herald.blood_share}%"),
            Map.entry("herald_of_ice", "碎裂爆炸半徑 +{herald.ice_radius} 公尺"),
            Map.entry("herald_of_plague", "傳毒時阻礙的機率另外 +{herald.plague_hinder}%"),
            Map.entry("herald_of_thunder", "落雷次數 +{i:herald.thunder_hits}"),
            Map.entry("hex_burst", "被移除的詛咒每剩 1 秒更多 {hexblast.more_per_second}% 傷害"),
            Map.entry("hunters_mark", "下一次暴擊被標記的敵人另外增加 {effect.crit.bonus_taken}% 暴擊傷害加成"),
            Map.entry("impurity", "光環另外給 +{effect.resist.chaos}% 混沌抗性"),
            Map.entry("incendiary_shot", "每顆打中的碎片讓點燃持續時間增加 {ignite.duration_per_pellet}%"),
            Map.entry("iron_ward", "爆開的暈眩累積更多 {stun.buildup}%"),
            Map.entry("lingering_illusion", "另外 {effect.charge.remnant_extra}% 機率多留一個殘影"),
            Map.entry("linked_volley", "重複的投射物「更少」少 {-barrage.less} 個百分點"),
            Map.entry("magma_barrier", "增益另外增加 {effect.block.chance}% 格擋機率"),
            Map.entry("malice", "暴擊弱點持續時間 +{malice.weakness_seconds} 秒"),
            Map.entry("mana_drain", "吸取的魔力 +{mana.on_hit}"),
            Map.entry("mana_remnants", "增益另外增加 {effect.mana.regen}% 魔力回復、擊殺回魔另外 +{effect.mana.on_kill}（原作每顆殘留物多回魔，照比例換算）"),
            Map.entry("mana_storm", "離開之後效果再延續 {mana_tempest.linger} 秒"),
            Map.entry("meta_elemental_invocation", "最大能量 +{meta.max_energy_flat}"),
            Map.entry("mirage_archer", "幻影持續時間 +{skill.base_duration_flat} 秒（你停手之後幻影再多射這麼久）"),
            Map.entry("mirror_of_refraction", "每面鏡子多複製 {i:mirror.copies} 發投射物"),
            Map.entry("piercing_rounds", "穿透過敵人之後投射物更多 {projectile.pierced_more}% 傷害"),
            Map.entry("plague_bearer", "另外存中毒傷害的 {plague_bearer.percent}%"),
            Map.entry("pounce", "印記每個附近的敵人另外 +{pounce.mark_per_enemy}% 受到的傷害、上限另外 +{pounce.mark_cap}%"),
            Map.entry("purity_of_fire", "光環另外給 +{effect.resist.fire}% 火焰抗性"),
            Map.entry("purity_of_ice", "光環另外給 +{effect.resist.cold}% 冰冷抗性"),
            Map.entry("purity_of_lightning", "光環另外給 +{effect.resist.lightning}% 閃電抗性"),
            Map.entry("rain_of_arrows", "箭落下的速度增加 {skill.projectile_speed}%"),
            Map.entry("raise_zombie", "強化殭屍的效果增加 {zombie.empower_effect}%"),
            Map.entry("rake", "照衝刺距離更多傷害的上限另外 +{charge.distance_more}%"),
            Map.entry("rampage", "引導的前 {charge.rage_free_seconds} 秒另外不耗怒氣"),
            Map.entry("rapid_fire", "最大熱度 +{i:crossbow.clip}"),
            Map.entry("rapid_thrust", "{spear_tip.extra_chance}% 機率多留一個矛頭"),
            Map.entry("ravenous_swarm", "蟲群攻擊半徑 +{swarm.radius} 公尺"),
            Map.entry("rolling_magma", "每彈（連鎖）過一次更多 {damage.more_per_chain}% 傷害"),
            Map.entry("rolling_slam", "使用這個技能的動作期間暈眩門檻增加 {action.stun_threshold}%"),
            Map.entry("ruin_mantra", "需要的連擊少 {-i:mantra.combo}"),
            Map.entry("sacrifice", "召喚物復活另外快 {sacrifice.slower}%"),
            Map.entry("savage_fury", "狂暴另外更多 {effect.damage}% 傷害"),
            Map.entry("scavenged_plating", "增益另外增加 {effect.armour}% 護甲、{effect.armour.break}% 破甲（原作甲片持續時間更長，照平均層數換算）"),
            Map.entry("serpent_fury", "擊退距離更多 {skill.knockback}%"),
            Map.entry("shatter_spikes", "每根還在的地刺更多 {spike.more_per_spike}% 擊中傷害"),
            Map.entry("shield_rush", "照衝刺距離更多傷害的上限另外 +{charge.distance_more}%"),
            Map.entry("shield_wall", "被自己的技能打碎時再更多 {wall.self_more}% 傷害"),
            Map.entry("shock_arrow", "打中插著電殛棒的目標，獲得 {rod.gain_lightning}% 傷害為額外閃電傷害"),
            Map.entry("siphon_elements", "凍結、點燃、感電時產生灌注殘骸的機率另外 +{effect.infusion.remnant_on_ailment}%"),
            Map.entry("skeletal_cleric", "召喚物：復活的骷髏 {cleric.revive_immune} 秒不會受到傷害"),
            Map.entry("skeletal_pyromancer", "召喚物：多丟 {i:minion.extra_projectiles} 顆火彈"),
            Map.entry("skeletal_reaver", "召喚物：怒氣效果增加 {minion.rage_effect}%"),
            Map.entry("soul_offering", "骨刺持續時間 +{skill.base_duration_flat} 秒"),
            Map.entry("spear_thicket", "危害 {hazard.rearm_chance}% 機率重新布置"),
            Map.entry("spiraling_conspiracy", "烏鴉群增加 {skill.area}% 範圍"),
            Map.entry("stampede", "{slam.aftershock_chance}% 機率多一次餘震"),
            Map.entry("storm_javelin", "超載的矛上限 +{i:lance.overcharged_extra}"),
            Map.entry("summon_mount", "騎著時的重暈門檻更多 {mount.life_more}%（原作坐騎更多最大生命）"),
            Map.entry("sunder", "照距離的命中懲罰減少 {-accuracy.range_penalty}%"),
            Map.entry("sunfire_spear", "{glory.retain_chance}% 機率用的時候保留 40% 榮耀"),
            Map.entry("supercharged_slam", "最大階段 +{i:charge.stages_flat}"),
            Map.entry("tempest_bell", "鐘每被打一下震波更多 {bell.more_per_hit}% 傷害"),
            Map.entry("thunder_leap", "引爆矛的時候 {detonate.shocked_ground_chance}% 機率留下感電地面"),
            Map.entry("thunderstorm", "濕透的敵人另外受到更多 {drenched.freeze_more}% 凍結累積、被感電的機率另外更多 {drenched.shock_more}%"),
            Map.entry("time_of_need", "祝福另外回復 {effect.blessing.life} 生命"),
            Map.entry("trail_of_caltrops", "鐵蒺上限 +{i:caltrops.max}"),
            Map.entry("volcanic_fissure", "{slam.aftershock_chance}% 機率多一次餘震"),
            Map.entry("voltaic_mark", "被標記的敵人另外受到增加 {effect.electrocute.buildup_taken}% 電擊累積"),
            Map.entry("walking_calamity", "增益另外獲得 {effect.damage.extra.fire}% 傷害為額外火焰傷害"),
            Map.entry("whirlwind_lance", "旋風崩散每多一階再更多 {whirl.collapse_more_per_stage}% 傷害"),
            Map.entry("wing_blast", "重暈蓄勢待暈的敵人時另外 {charge.event.stun_primed.power}% 機率獲得銳星"),
            Map.entry("withering_presence", "凋零持續時間 +{withering.seconds} 秒"),
            Map.entry("wyvern_rend", "增益另外獲得 {effect.damage.gain.lightning}% 傷害為閃電"));

    /** 原作的品質效果我們沒有對應機制（品質寫 []）的：技能代號 → {原因樣板, 每 1% 品質的原作數值}；{v} 換成這個品質的數值。 */
    static final Map<String, Object[]> NO_EFFECT = Map.ofEntries(
            Map.entry("beast_companion", new Object[] {"原作是鬼火持續時間 +{v} 秒（只跟捕捉野獸有關），我們沒有捕捉野獸，所以沒有效果", 0.08}),
            Map.entry("bramble_arrow", new Object[] {"原作是藤蔓另外照中毒每秒傷害的 {v}% 造成混沌傷害，我們的荊棘矢沒有「植物中毒」，所以沒有效果", 0.5}),
            Map.entry("decompose", new Object[] {"原作是毒雲持續時間 +{v} 秒，我們的腐屍爆沒有毒雲，所以沒有效果", 0.05}),
            Map.entry("profane_ritual", new Object[] {"原作是 {v}% 機率不毀掉屍體，我們的穢陣不用屍體，所以沒有效果", 0.5}),
            Map.entry("sigil_of_power", new Object[] {"原作是力印半徑 +{v} 公尺，我們的力印是跟著你的增益（不用站在印裡），所以沒有效果", 0.04}),
            Map.entry("vault_strike", new Object[] {"原作是破勢多撐 {v} 次擊中，我們的躍棍崩擊沒有破勢，所以沒有效果", 0.1}),
            Map.entry("wind_dancer", new Object[] {"原作是疾風爆炸半徑 +{v} 公尺，我們的風之舞步沒有被擊中時的疾風爆炸，所以沒有效果", 0.02}));

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
            double value = value(mods, key);
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
