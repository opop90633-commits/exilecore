package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;

import java.util.HashMap;
import java.util.Map;

/**
 * 2026-10-26：輔助寶石的顏色（PoE2 的紅＝力量、綠＝敏捷、藍＝智慧）。天賦樹「至少插著 5 顆紅色輔助寶石」「插著的輔助寶石大多是藍色」
 * 這些用（{@link tw.exilecore.stats.AttributeScalingKeys}）。
 * <p>
 * 有 PoE2 對應的照 RePoE（skill_gems.json 的 color）；我們自己的（終局、Boss 掉的那些）照效果的屬性歸類：
 * 火焰、物理近戰、護甲、磐星、戰吼、圖騰、流血是紅；投射物、閃電、中毒、怒星、速度是綠；法術、冰冷、混沌、召喚、詛咒、光環、銳星是藍。
 * 2026-10-26：I／II／III 分階的輔助只留最高階（supports.yml 的 merged），較低階的代號拿掉。
 * 2026-10-27：再拿掉漏掉的 increased_duration、cooldown_recovery、scattershot（併入 prolonged_duration、swift_cooldown、multiple_projectiles）。
 * 2026-10-28：overabundance 併入新加的 overabundance_ii（過剩 II，綠）。
 * 沒列的（白色的，例如 PoE2 的 Vruun's Aftermath）不算任何顏色。
 */
public final class SupportColours {

    public static final char RED = 'r';
    public static final char GREEN = 'g';
    public static final char BLUE = 'b';

    private static final Map<Character, String> LISTS = Map.of(
            RED, // PoE2 RePoE 紅色
            "bludgeon brutality concussive deep_cuts enraged_warcry_ii execute execute_ii execute_iii fire_penetration heavy_swing heft ignition "
                    + "infernal_legion_ii lacerate_iii life_leech mark_for_death_ii meat_shield paquates_pact "
                    + "prolonged_duration rage ruthless sturdy_totem zerphis_infamy "
                    // 自己歸類（火焰、物理近戰、護甲、戰吼、圖騰、流血、磐星）
                    + "added_fire searing_ii warcry_reach shrine_echo vengeful_embers totem_fury "
                    + "additional_strike shatter_armour_iii hemorrhage giant_slayer impact_area "
                    + "vs_ignited vs_bleeding skyburn_fury starfall_resonance starfall_core shattering_strike "
                    + "sacred_focus greed_lock chain_bind blood_tribute bone_tide charge_endurance "
                    // 2026-10-26 補齊（照 RePoE；疾樁 III、不倦是紅、冰噬 II 是藍——這次之前寫錯成藍／綠／綠）：沒列的原本都當白色
                    + "urgent_totems_iii tireless bloodlust first_blood immolate devastate see_red flamepierce echoing_cry "
                    + "greatwood clash elemental_armament knockback arms_length persistent_ground vitality cannibalism "
                    + "thornskin cool_headed herbalism armour_demolisher eternal_flame compressed_duration flame_pillar "
                    + "haemocrystals brambleslam crater corrupting_cry battershout volcanic_eruption aftershock tremors "
                    + "exposing_cry double_barrel auto_reload rageforged dazing_cry fan_the_flames impact_shockwave "
                    + "reverberate tectonic_slams premeditation raging_cry long_fuse short_fuse armour_explosion "
                    + "break_endurance enduring_impact lifetap jagged_ground helbryms_hide "
                    // 2026-10-27 照 PoE2 0.5 恢復的節流（Efficiency，RePoE 紅色）
                    + "efficiency "
                    // 2026-10-27 照 PoE2 0.5 補上的一般輔助（照 RePoE 的顏色；tools/apply_poe2_missing_supports.py）
                    + "ancestral_aid ancestral_call_iii barbs_iii behead_ii branching_fissures_ii brink_ii fire_exposure concoct_ii "
                    + "dauntless deadly_resolve defy_ii desperation direstrike_ii exploit_weakness fist_of_war_iii font_of_blood "
                    + "fresh_clip_ii remnant_potency_iii holy_descent incision minion_splash_ii opening_move quill_burst rally "
                    + "refraction_iii reinforced_totems_ii relentless_rage rending_apex rip rupture rusted_spikes spar retaliate_ii "
                    + "splinter_totem_ii steadfast_ii stoicism_ii stomping_ground syzygy tear unabating unbreakable undermine "
                    + "unyielding upheaval_ii vanguard_ii",
            GREEN, // PoE2 RePoE 綠色
            "chain charge_profusion close_quarters close_quarters_ii conduction culling_strike_ii electrocute "
                    + "envenom_iii faster_attacks faster_projectiles fork lightning_penetration longshot_ii "
                    + "multiple_projectiles overcharge pierce static_charge swift_affliction_iii "
                    + "swift_cooldown virulence "
                    // 自己歸類（投射物、閃電、中毒、怒星、速度、暴擊傷害）
                    + "critical_damage burst heavy_bolt rapid_reload point_blank myriad_stars rift_passage cluster_bombs "
                    + "pinning_iii dazing precision_aim vs_shocked storm_maze_heart worldtear "
                    + "vs_poisoned rift_echo coin_toss duelist_focus stolen_technique nameless_edge delusion_crit "
                    + "cull_frenzy "
                    // 2026-10-26 補齊（照 RePoE；疾樁 III、不倦是紅、冰噬 II 是藍——這次之前寫錯成藍／綠／綠）：沒列的原本都當白色
                    + "deliberation overreach lasting_shock escalating_poison volt corrosion excoriate stormchain cadence "
                    + "hit_and_run momentum overextend warm_blooded projectile_deceleration deadly_herald "
                    + "nova_projectiles life_drain soul_drain frozen_spite bursting_plague wind_wave charged_mark "
                    + "poison_spores caltrops maim blind ammo_conservation streamlined_rounds nimble_reload combo_finisher "
                    + "culmination practiced_combo flow lightning_attunement admixture dazzle lockdown overabundance_ii "
                    + "shocking_leap salvo murderous_intent unerring_power precision adhesive_grenades break_posture "
                    + "innervate bounty life_bounty mana_bounty charm_bounty heightened_charges inhibitor ferocity "
                    + "perfected_endurance ailith_chimes "
                    // 2026-10-27 照 PoE2 0.5 補上的一般輔助（照 RePoE 的顏色）
                    + "alignment_iii blindside charged_shots_ii lightning_exposure commiserate delayed_gratification "
                    + "delayed_reaction durability frenzied_riposte impale leverage malady mobility "
                    + "neural_overload outmaneuver perfection punch_through pursuit_iii retreat_iii rearm_ii ricochet_iii "
                    + "second_wind_iii slow_potency spectral_volley tumult untouchable window_of_opportunity_ii",
            BLUE, // PoE2 RePoE 藍色
            "chaos_attunement bhatairs_vengeance cold_penetration concentrated_effect considered_casting controlled_destruction deep_cold elemental_army "
                    + "elemental_focus faster_casting glacial increased_area mana_leech spell_echo "
                    // 自己歸類（法術、冰冷、混沌、召喚、詛咒、光環、銳星、暴擊率）
                    + "critical_strikes lingering minion_damage minion_life minion_speed minion_haste minion_frenzy giant_minions styx_ferry "
                    + "void_legion rift_surge altar_legion added_chaos abyssal_touch "
                    + "permafrost_heart tidal_resonance undertow_drag whale_breath mist_veil vs_chilled abyssal_grip oblivion_focus hex_exploit "
                    + "lingering_hex pact_seal channel_focus world_collapse phantom_double "
                    + "mirror_image dawn_blessing "
                    // 2026-10-26 補齊（照 RePoE；疾樁 III、不倦是紅、冰噬 II 是藍——這次之前寫錯成藍／綠／綠）：沒列的原本都當白色
                    + "ice_bite_ii fire_mastery cold_mastery lightning_mastery chaos_mastery physical_mastery deep_freeze "
                    + "intense_agony frostfire embitter brittle_armour freezefork expanse excise execrate hourglass "
                    + "heightened_curse focused_curse ritualistic_curse abiding_hex eternal_mark muster ambush zenith "
                    + "rising_tempest clarity strong_hearted extraction arcane_surge fiery_death bone_shrapnel "
                    + "electromagnetism elemental_discharge decaying_hex coursing_current mana_flare static_shocks "
                    + "frozen_vortex hinder glacier icicle hoarfrost crystalline_shards verglas unleash expand burgeon "
                    + "cold_attunement danse_macabre frost_nexus impending_doom minion_mastery pinpoint_critical "
                    + "potent_exposure spreading_frost withering_touch sacrificial_offering mysticism upwelling "
                    + "blazing_critical enervation bidding bidding_ii essence_harvest deathmarch last_rites fluke supercritical "
                    + "perpetual_charge creeping_chill empowered_sparks guatelitzi_ablation boundless_energy "
                    + "energy_retention energy_capacitor "
                    + "accelerated_growth advancing_storm morganas_tempest "
                    // 2026-10-27 照 PoE2 0.5 補上的一般輔助（照 RePoE 的顏色）
                    + "acrimony ambrosia_ii astral_projection biting_frost_ii catharsis chaotic_freeze cold_exposure commandment "
                    + "concussive_spells corpse_conservation crackling_barrier crazed_minions crescendo_iii cursed_ground derange "
                    + "drain_ailments encroaching_ground energy_barrier font_of_mana fortress_ii gambleshot gorge "
                    + "harmonic_remnants_ii magnetic_remnants hex_bloom inexorable_critical_ii last_gasp loyalty "
                    + "mark_of_siphoning_ii minion_pact_ii potential practical_magic_ii profanity_ii rime shock_conduction_ii "
                    + "shock_siphon spell_cascade stormfire thrill_of_the_kill_ii unbending volatile_power volatility wildfire "
                    + "wildshards_ii");

    private static final Map<String, Character> COLOUR = new HashMap<>();

    static {
        LISTS.forEach((colour, list) -> {
            for (final String id : list.split(" ")) {
                if (!id.isBlank()) {
                    COLOUR.put(id, colour);
                }
            }
        });
    }

    private SupportColours() {
    }

    /** 這顆輔助寶石的顏色（r／g／b）；沒有顏色回傳 0。 */
    public static char colour(final String supportId) {
        final Character colour = supportId == null ? null : COLOUR.get(supportId);
        return colour == null ? LineageRules.colour(supportId) : colour; // 2026-10-27：血脈輔助的顏色寫在 LineageRules
    }

    /** 技能面板上插著的輔助寶石：各色幾顆、總共幾顆（含白色的）。 */
    public record Counts(int red, int green, int blue, int total) {
        public static final Counts NONE = new Counts(0, 0, 0, 0);
    }

    /** 數技能面板上插著的輔助寶石（PoE2：所有技能寶石上插著的都算；宿主寶石裡的技能的輔助也算）。 */
    public static Counts count(final SkillLoadout loadout) {
        if (loadout == null) {
            return Counts.NONE;
        }
        final int[] counts = new int[4];
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            SkillLoadout.Entry entry = loadout.skill(i);
            while (entry != null) {
                for (final String id : entry.supportIds()) {
                    add(counts, id);
                }
                entry = entry.inner();
            }
        }
        return new Counts(counts[0], counts[1], counts[2], counts[3]);
    }

    /** 一串輔助寶石代號的顏色數（測試、規劃器用）。 */
    public static Counts count(final Iterable<String> supportIds) {
        final int[] counts = new int[4];
        for (final String id : supportIds) {
            add(counts, id);
        }
        return new Counts(counts[0], counts[1], counts[2], counts[3]);
    }

    private static void add(final int[] counts, final String id) {
        switch (colour(id)) {
            case RED -> counts[0]++;
            case GREEN -> counts[1]++;
            case BLUE -> counts[2]++;
            default -> { }
        }
        counts[3]++;
    }
}
