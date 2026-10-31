package tw.exilecore.item;

import java.util.Map;

/**
 * 2026-10-31：改過代號的傳奇（舊代號 → 新代號）。舊物品、舊設定、舊指令裡寫的舊代號讀進來時一律換成新的，
 * 所以玩家手上的東西不受影響。
 */
public final class UniqueIds {

    public static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("chober_chaber", "devotee_maul"),
            Map.entry("bones_of_ullr", "whitebone_treads"),
            Map.entry("heroic_tragedy", "frost_elegy_jewel"),
            Map.entry("undying_hate", "abyss_grudge_jewel"),
            Map.entry("reverie", "hollow_dream"),
            Map.entry("atziris_contempt", "empress_sneer"),
            Map.entry("the_vertex", "faceless_mask"),
            Map.entry("sacred_flame", "hallowed_fire"),
            Map.entry("the_taming", "tamer_ring"),
            Map.entry("bereks_pass", "frostfire_ring"),
            Map.entry("bereks_respite", "firestorm_ring"),
            Map.entry("shavronnes_satchel", "wanderer_satchel"),
            Map.entry("geofris_sanctuary", "faith_sanctuary"),
            Map.entry("titanrot_plate", "rotgiant_plate"),
            Map.entry("wandering_reliquary", "roaming_reliquary"),
            Map.entry("barrow_coat", "tomb_dweller_coat"),
            Map.entry("icetomb_vestments", "frostcrypt_vestments"),
            Map.entry("kingsguard_plate", "royalward_plate"),
            Map.entry("dancing_mirage_jacket", "shadowdance_jacket"),
            Map.entry("darkray_sabatons", "duskgleam_sabatons"),
            Map.entry("powertread_shoes", "keenstep_shoes"),
            Map.entry("briarpatch_boots", "thornbush_boots"),
            Map.entry("wondertrap_slippers", "fortune_slippers"),
            Map.entry("errant_sabatons", "wayfarer_sabatons"),
            Map.entry("doomfletch_bow", "dreadfeather_bow"),
            Map.entry("blueflame_cuffs", "azureflame_cuffs"),
            Map.entry("northpaw_bracers", "northclaw_bracers"),
            Map.entry("applause_gloves", "ovation_gloves"),
            Map.entry("snakebite_bracers", "viperfang_bracers"),
            Map.entry("death_articulated", "bonejoint_gauntlets"),
            Map.entry("nightscale_gloves", "duskscale_gloves"),
            Map.entry("bronzebeard_crown", "copperbeard_crown"),
            Map.entry("forbidden_tiara", "veiled_tiara"),
            Map.entry("myris_hood", "shadowwife_hood"),
            Map.entry("ironride_helm", "ironrider_visor"),
            Map.entry("elevore_hood", "soaring_hood"),
            Map.entry("caelyn_wings", "wrathwing_helm"),
            Map.entry("devouring_diadem", "corpse_eater_circlet"),
            Map.entry("smiling_knight_helm", "grinning_knight_helm"),
            Map.entry("brotherhood_call_ring", "brother_thunder_ring"),
            Map.entry("brotherhood_whisper_ring", "brother_frost_ring"),
            Map.entry("damning_ring", "hexbind_ring"),
            Map.entry("vigilant_ring", "watchful_ring"),
            Map.entry("surrender_tower", "yielding_tower"),
            Map.entry("doomgate_tower", "fategate_tower"),
            Map.entry("bloodbarrier", "crimson_door_shield"),
            Map.entry("phoenix_crest", "ashrise_crest"),
            Map.entry("dionadair_tower", "unmoving_tower"),
            Map.entry("nightfall_tower", "duskfall_tower"),
            Map.entry("seeing_stars_mace", "starstruck_mace"),
            Map.entry("shyaba_maul", "unwavering_maul"),
            Map.entry("earthbound_staff", "groundbolt_staff"),
            Map.entry("collapsing_horizon", "fallen_horizon"),
            Map.entry("sentry_staff", "nightwatch_staff"),
            Map.entry("dark_defiler_sceptre", "gravedark_sceptre"),
            Map.entry("eternal_spark_focus", "undying_ember_focus"),
            Map.entry("threaded_light_focus", "woven_light_focus"),
            Map.entry("deathrattle_focus", "funeral_bell_focus"),
            Map.entry("blackflame", "shadowfire"),
            Map.entry("candlemaker", "wickmaker"),
            Map.entry("demon_stitcher", "seamwright"),
            Map.entry("dreadfist", "fearfist"),
            Map.entry("essentia_sanguis", "blood_drinker"),
            Map.entry("facebreaker", "toothcollector"),
            Map.entry("gravebind", "tombgrip"),
            Map.entry("horrors_flight", "terror_glide"),
            Map.entry("idle_hands", "restless_hands"),
            Map.entry("jarngreipr", "irongrip_gauntlets"),
            Map.entry("killjoy", "spoilsport"),
            Map.entry("lochtonial_caress", "mindtouch"),
            Map.entry("painters_servant", "crimson_brush"),
            Map.entry("plaguefinger", "festerfinger"),
            Map.entry("shackles_wretched", "misery_shackles"),
            Map.entry("sine_aequo", "peerless"),
            Map.entry("prisoners_manacles", "captive_manacles"),
            Map.entry("thunderfist", "stormfist"),
            Map.entry("masters_reach", "binder_grasp"),
            Map.entry("crown_of_eyes", "hundred_eye_crown"),
            Map.entry("atziri_acuity", "empress_insight"),
            Map.entry("kitoko_current", "kido_current"),
            Map.entry("maligaro_virtuosity", "marg_bloodgloves"),
            Map.entry("lavianga_spirits", "lavian_spring"),
            Map.entry("brynhand_club", "twinstrike_club"),
            Map.entry("daevata_spear", "thrustwind_spear"),
            Map.entry("taryn_shiver", "frostshiver_spear"),
            Map.entry("enezun_wand", "thrift_wand"),
            Map.entry("caged_god_pillar", "prisoned_god_staff"),
            Map.entry("hateforge_greaves", "spiteforge_greaves"),
            Map.entry("foxshade_vest", "foxshadow_vest"),
            Map.entry("redflare_wrap", "crimsonarc_wrap"),
            Map.entry("storm_choir_amulet", "thunder_chorus_amulet"),
            Map.entry("carnage_heart_amulet", "slaughter_heart_amulet"));

    private UniqueIds() {
    }

    /** 傳奇詞綴代號（unique.代號.序號）裡的舊傳奇代號換成新的；其他詞綴照舊。 */
    public static String currentAffix(final String affixId) {
        if (affixId == null || !affixId.startsWith(UniqueItem.AFFIX_PREFIX)) {
            return affixId;
        }
        final int dot = affixId.lastIndexOf('.');
        if (dot <= UniqueItem.AFFIX_PREFIX.length()) {
            return affixId;
        }
        final String renamed = RENAMED.get(affixId.substring(UniqueItem.AFFIX_PREFIX.length(), dot));
        return renamed == null ? affixId : UniqueItem.AFFIX_PREFIX + renamed + affixId.substring(dot);
    }

    /** 舊代號換成現在的代號；沒改過的照舊。 */
    public static String current(final String id) {
        return id == null ? null : RENAMED.getOrDefault(id, id);
    }
}
