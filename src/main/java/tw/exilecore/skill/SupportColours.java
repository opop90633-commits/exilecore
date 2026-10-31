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
            "sinking_blow savage_cruelty awe_shock grave_wound angry_roar_ii execute final_sentence_ii final_sentence_iii melt_break sinking_hammer ponderous_weight burning_will "
                    + "burning_servant_ii rend_cut_iii life_leech deep_brand_ii flesh_bulwark blood_oath_howl "
                    + "long_stay rage merciless_heart firm_post blood_renown "
                    // 自己歸類（火焰、物理近戰、護甲、戰吼、圖騰、流血、磐星）
                    + "added_fire prairie_blaze_ii far_howl sacred_niche_echo grudge_cinder post_wrath "
                    + "sweep_strike break_plate_iii blood_collapse colossus_butcher quake_zone "
                    + "vs_ignited vs_bleeding sky_scorch_wrath star_remains_resonance meteor_core realm_break_spread "
                    + "holy_light_focus covet_chest_lock shackle_entangle blood_levy marrow_surge gather_bedrock "
                    // 2026-10-26 補齊（照 RePoE；疾樁 III、不倦是紅、冰噬 II 是藍——這次之前寫錯成藍／綠／綠）：沒列的原本都當白色
                    + "swift_post_iii endless_howl gore_hunt initial_crack_ii scorch_hunt crumbling_force blood_linger cinder_through quake_howl "
                    + "thick_post back_to_wall element_blade knockback heavy_shove enduring_terrain life_spring devour_kill "
                    + "spike_plate evade_burn mellow_potion dismantle_plate long_blaze shrink_time blaze_column "
                    + "blood_crystallize thorn_quake molten_pit_strike rot_howl plate_break_roar magma_spurt aftershock chain_quake "
                    + "reveal_howl twin_chamber jolt_load fury_cast dizzy_howl sow_fire quake_ring "
                    + "low_resound twin_quake stored_howl gather_fury_howl slow_detonate rapid_detonate plate_burst "
                    + "bedrock_shatter bedrock_quake blood_pay shattered_earth dormant_shell "
                    // 2026-10-27 照 PoE2 0.5 恢復的節流（Efficiency，RePoE 紅色）
                    + "cost_saving "
                    // 2026-10-27 照 PoE2 0.5 補上的一般輔助（照 RePoE 的顏色；tools/apply_poe2_missing_supports.py）
                    + "forebear_help forebear_summon_iii reverse_hook_iii head_sever_ii split_crack_ii threshold_ii flame_reveal mix_dose_ii "
                    + "fearless_heart decisive_kill resist_order_ii dire_straits final_blow_ii seize_gap battle_fist_iii blood_spring "
                    + "new_magazine_ii bone_effect_iii sacred_fall cut_wound servant_spatter_ii first_strike thorn_blast reform_ranks "
                    + "bent_light_iii firm_totem_ii ceaseless_fury split_summit shred_blood vein_break corroded_thorn friendly_duel strike_back_ii "
                    + "split_totem_ii steady_guard_ii iron_grit_ii tread_quake merged_orbit split_wound unflinching_will never_broken dig_weakness "
                    + "stand_fast land_uplift_ii front_line_ii",
            GREEN, // PoE2 RePoE 綠色
            "chain full_stars hug_range hug_range_ii conduction slay_cut_ii electrocute "
                    + "poison_temper_iii quick_strike swift_bolt_iii split_shot thunder_pass far_pierce_ii "
                    + "light_split blazing_volt pierce thunder_smite rapid_corrode_iii "
                    + "quick_return potent_toxin "
                    // 自己歸類（投射物、閃電、中毒、怒星、速度、暴擊傷害）
                    + "critical_damage burst weighty_arrow quick_load_gear point_blank star_panorama crack_corridor extra_shells "
                    + "ground_nail_iii shock_dizzy focus_mind vs_shocked thunder_prison_heart realm_pierce_rip "
                    + "vs_poisoned void_crack_echo coin_flip duel_concentration pilfered_move unnamed_blade delirium_smash "
                    + "slay_gather_fury "
                    // 2026-10-26 補齊（照 RePoE；疾樁 III、不倦是紅、冰噬 II 是藍——這次之前寫錯成藍／綠／綠）：沒列的原本都當白色
                    + "held_breath wide_stance enduring_thunder layered_miasma draw_lightning rust_plate strip_element leaping_thunder beat_combo "
                    + "swift_edge charge_impetus all_in_gamble evade_freeze slow_shell stern_proclaim "
                    + "ring_shot reap_life siphon_soul shatter_chill pest_rupture quake_gale volt_sigil_field "
                    + "fungal_blast scatter_spikes maim blind arrow_saver rapid_chamber quick_chamber final_form "
                    + "peak_point_ii skilled_move flow thunder_infuse twin_corrode steady_strike lockdown brim_over_ii "
                    + "vault_spark sealed_arrows resolve_howl howl_aim precision sticky_bomb daze_breaker "
                    + "thunder_momentum bounty life_reward mana_reward ward_reward double_star star_suppress mad_speed "
                    + "whole_bedrock bell_star "
                    // 2026-10-27 照 PoE2 0.5 補上的一般輔助（照 RePoE 的顏色）
                    + "forward_bias_iii shadow_ambush filled_shot_ii thunder_reveal shared_ailment deferred_joy "
                    + "late_blast sturdy_gear counter_stab stab_nail pry_force disease_shift light_body "
                    + "volt_load clever_break flawless_form breach_hit chase_strike_iii withdraw_strike_iii redeploy_ii bounce_shot_iii "
                    + "rise_again_iii slow_vigor light_shadow unrest_stir shadowless_step good_chance_ii",
            BLUE, // PoE2 RePoE 藍色
            "decay_infuse frost_grudge_howl chill_through condense_power charged_chant fine_control abyss_floor_chill element_corps "
                    + "purify_essence quick_chant ice_seal zone_expand mana_draw twin_chant "
                    // 自己歸類（法術、冰冷、混沌、召喚、詛咒、光環、銳星、暴擊率）
                    + "savage_flare echo_remnant minion_damage minion_life minion_speed drive_servants bloodthirst_summon huge_soul nether_river_crossing "
                    + "void_star_army crack_outpour ritual_dais_army added_chaos chasm_touch "
                    + "eternal_frost_heart ebb_flow_resonance dark_current_pull whale_exhale fog_gauze vs_chilled chasm_grasp extinction_focus curse_seize "
                    + "curse_extend blood_oath_seal build_poise realm_return_void void_star_clone "
                    + "looking_glass_copy daybreak_boon "
                    // 2026-10-26 補齊（照 RePoE；疾樁 III、不倦是紅、冰噬 II 是藍——這次之前寫錯成藍／綠／綠）：沒列的原本都當白色
                    + "frost_gnaw_ii ember_marrow frost_marrow thunder_marrow abyss_marrow iron_marrow frost_seal "
                    + "fast_decay cold_burn congealed_frost_blade frost_crack_plate frost_branch stored_zone stored_edge stored_haste stored_might "
                    + "stern_hex quick_hex broad_hex enduring_curse long_brand host_blades ambush brim_mana "
                    + "element_surge spring_mana evade_shock blood_erosion surging_chant corpse_burn shard_crack "
                    + "magnetic_storm element_collapse rotting_curse darting_volt magic_flame still_charge "
                    + "frost_whirl hinder thick_crystal brittle_crystal frost_etch_crystal crystal_spike frost_shell sealed_chant seal_widen rising_blaze "
                    + "frost_infuse bone_dance frozen_ground curse_blast servant_marrow keen_point "
                    + "deep_reveal frost_creep blight_touch life_donation shield_chant surge_servant "
                    + "ember_surge volt_drain bone_command bone_command_ii soul_reap bone_mercy perish_blast dice_luck blast_peak "
                    + "eternal_star spread_cold thunder_star rite_star surge_power_ii "
                    + "power_return power_expand "
                    + "hasten_bloom marching_gale sweeping_gale "
                    // 2026-10-27 照 PoE2 0.5 補上的一般輔助（照 RePoE 的顏色）
                    + "grudge_corrode jade_nectar_ii star_cast gnaw_cold_ii cleanse_rite muddled_freeze frost_reveal army_order "
                    + "quake_magic spare_corpse volt_screen mad_servant rising_swell_iii hex_field mad_chaos "
                    + "draw_blight spread_field power_ward mana_spring ring_wall_ii wager_shot corpse_glutton "
                    + "harmony_bones_ii magnet_bones curse_blossom certain_fate_ii final_breath faithful_servant "
                    + "drain_sigil_ii servant_bond_ii hidden_power real_spell_ii corpse_defile_ii mist_frost relay_thunder_ii "
                    + "draw_thunder linked_falls thunder_burn slay_intent_ii never_submit blast_energy volatility field_fire "
                    + "mad_fragments_ii");

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
