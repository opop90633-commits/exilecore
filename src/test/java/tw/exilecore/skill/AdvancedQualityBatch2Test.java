package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11 照站長「一般品質再算一次的照原作改（第二批）」：這批高階奇術的額外品質效果（PoB2 altQualityStats／poe2db）。 */
class AdvancedQualityBatch2Test {

    static SkillConfig config;

    static final List<String> BATCH = List.of("thunderclap_arrow", "thunder_lure_thrust", "successive_shots", "gale_combo", "startling_thunder_bell",
            "hurricane_spear", "prey_kill_mark", "blazing_star_shell", "ember_envoy", "thunder_envoy", "frost_envoy", "bloodthirst_rite", "soulfire_rite",
            "field_quake_hammers", "mist_staff_sweep", "leaping_staff_crash", "fortress_crumble_shell", "freezing_rain_shell", "winter_shell",
            "frost_billow", "electrocute_shaft", "cold_congeal_shell", "kerosene_grenade", "blood_hunt", "dig_remains", "collapse_heart_chant",
            "grinding_will", "magic_surge", "lashing_thorns", "root_bind", "beast_blood_boil", "shield_drum", "ice_shuttle", "blood_proclaim",
            "crimson_wrath", "wilting_breath", "frost_arrowhead", "miasma_zone", "blink", "guard_moment", "spectral_step", "magic_spring_well",
            "insect_devour_horde", "pestilence_sac_burst", "earth_wall_strike", "breeze_dance_step", "magma_barrier", "battle_spirit_rise",
            "arcane_heart", "element_tide", "tripod_element_art", "blood_tear_sweep", "critical_guide_star", "roll_guide_star",
            "element_prayer_star", "scythe_prayer_star", "ward_prayer_star",
            // 2026-11-11 照站長「PoB2 找不到的 18 顆照 poe2db 改」
            "chill_fan_shafts", "summon_bone_heavy", "summon_bone_bowmen", "summon_bone_thundermage", "summon_bone_berserker",
            "summon_bone_frostmage", "soul_attach_art", "summon_bone_priest", "mirage_spirit_freeze", "cold_winter_gaze", "flame_core_detonation",
            // 2026-11-11 照站長「天柱雷、召喚骸骨焰術士、爆毒矢還沒做品質」
            "sky_column_thunder", "summon_bone_flamecaster", "toxic_blast_shaft");

    @BeforeAll
    static void load() throws Exception {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void everySkillInThisBatchHasItsOwnAdvancedEffectWithChineseText() {
        final List<String> bad = new java.util.ArrayList<>();
        for (final String id : BATCH) {
            assertNotNull(GemQuality.advancedModifiers(id, 20, "品質 20%"), id + " 沒有 advanced");
            final String text = SkillService.advancedQualityText(config.skill(id), 20);
            System.out.println(id + " → " + text);
            if (text == null || text.contains("再算一次") || text.matches(".*[a-z_]+\\.[a-z_]+.*")) {
                bad.add(id + "：" + text);
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void representativeTexts() {
        assertEquals("閃電傷害 30% 機率為幸運", SkillService.advancedQualityText(config.skill("thunderclap_arrow"), 20));
        assertEquals("鐘多響 10 次", SkillService.advancedQualityText(config.skill("startling_thunder_bell"), 20));
        assertEquals("每層冰甲增加 20% 護甲", SkillService.advancedQualityText(config.skill("winter_shell"), 20));
        assertEquals("被標記的敵人造成的傷害更少 10%", SkillService.advancedQualityText(config.skill("prey_kill_mark"), 20));
    }

    @Test
    void normalQualityMovedToAdvancedMatchesPoB2() {
        // PoB2 qualityStats：掠閃、獸血沸是空的；縛根只有裂隙；魔泉湧只有每個痕跡的魔力
        assertTrue(GemQuality.modifiers("blink", 20, "品質 20%").isEmpty());
        assertTrue(GemQuality.modifiers("beast_blood_boil", 20, "品質 20%").isEmpty());
        assertTrue(GemQuality.modifiers("root_bind", 20, "品質 20%").stream().noneMatch(m -> m.stat().equals("skill.limit")));
        assertTrue(GemQuality.modifiers("magic_spring_well", 20, "品質 20%").stream().noneMatch(m -> m.stat().equals("effect.mana.regen")));
        assertTrue(GemQuality.advancedModifiers("root_bind", 20, "品質 20%").stream().anyMatch(m -> m.stat().equals("skill.limit") && Math.abs(m.value() - 4) < 1e-9));
    }

    @Test
    void poe2dbOnlySkills() {
        assertEquals("投射物 50% 機率回到你身邊", SkillService.advancedQualityText(config.skill("cold_winter_gaze"), 20));
        assertEquals("每多一層，傷害型異常的幅度更多 40%", SkillService.advancedQualityText(config.skill("flame_core_detonation"), 20));
        assertTrue(GemQuality.modifiers("mirage_spirit_freeze", 20, "品質 20%").stream().noneMatch(m -> m.stat().equals("freeze.buildup")));
    }

    @Test
    void lastThreeFromPoe2db() {
        assertTrue(SkillService.advancedQualityText(config.skill("sky_column_thunder"), 20).contains("多劈 4 道雷"),
                SkillService.advancedQualityText(config.skill("sky_column_thunder"), 20));
        assertTrue(SkillService.advancedQualityText(config.skill("summon_bone_flamecaster"), 20).contains("最大生命 4% 的火焰傷害"),
                SkillService.advancedQualityText(config.skill("summon_bone_flamecaster"), 20));
        assertTrue(SkillService.advancedQualityText(config.skill("toxic_blast_shaft"), 20).contains("破甲量等於中毒傷害的 20%"),
                SkillService.advancedQualityText(config.skill("toxic_blast_shaft"), 20));
    }

    @Test
    void arcticArmourPerStage() {
        assertEquals(0.6, OriginalSkills.arcticArmour(20, 3), 1e-9);
        assertEquals(0, OriginalSkills.arcticArmour(20, 0), 1e-9);
    }

    @Test
    void manaTempestSpentShare() {
        assertEquals(0.30, ManaTempest.spentShare(0), 1e-9);
        assertEquals(0.22, ManaTempest.spentShare(-8), 1e-9);
        assertEquals(0, ManaTempest.spentShare(-50), 1e-9);
    }

    @Test
    void frozenOnlyDebuffCountsOnlyWhileFrozen() {
        final tw.exilecore.combat.Debuffs debuffs = new tw.exilecore.combat.Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        debuffs.apply(target, new tw.exilecore.combat.Debuffs.Debuff("frozen_taken." + owner, owner, 100, 0.4, false, tw.exilecore.stats.TreeConditions.VS_FROZEN));
        final java.util.Set<String> tags = java.util.Set.of("attack");
        assertEquals(1.0, debuffs.takenMultiplier(target, owner, 10, tw.exilecore.combat.CombatListener.frozenTags(tags, false)), 1e-9);
        assertEquals(1.4, debuffs.takenMultiplier(target, owner, 10, tw.exilecore.combat.CombatListener.frozenTags(tags, true)), 1e-9);
    }

    @Test
    void confluxPeriodFollowsDuration() {
        final UUID player = UUID.randomUUID();
        // 同一個 12 秒週期內元素不變
        final String first = AuraService.confluxElement(player, 0, 240);
        assertEquals(first, AuraService.confluxElement(player, 239, 240));
        assertEquals(AuraService.confluxElement(player, 0), AuraService.confluxElement(player, 0, AuraService.CONFLUX_PERIOD));
    }
}
