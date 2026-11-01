package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentConfig;
import tw.exilecore.ailment.AilmentState;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SupportKeys;
import tw.exilecore.stats.SupportRuleKeys;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 的 allowed_types／excluded_types：流血、深切、推擊、久地、熔坑擊、躍電、拖滯、不退、播火、怒鑄，與震眩的眩暈累積條。 */
class SupportTypeParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static String refusal(final String support, final String skill) {
        assertNotNull(config.support(support), support);
        assertNotNull(config.skill(skill), skill);
        return config.support(support).refusal(config.skill(skill));
    }

    @Test
    void damageSupportsTakeSpells() {
        for (final String id : new String[] {"rend_cut_iii", "initial_crack_ii", "grave_wound", "blood_collapse", "knockback", "heavy_shove", "fury_cast"}) {
            assertNull(refusal(id, "blazing_star_shell"), id + "：會造成傷害的法術可以掛");
            assertNull(refusal(id, "spine_crush"), id + "：攻擊可以掛");
        }
        assertNotNull(refusal("quick_return", "summon_bone_priest"), "迅返不能修改召喚物的技能");
    }

    @Test
    void deepCutsOnlyLowersHits() {
        for (final String id : new String[] {"grave_wound", "blood_collapse"}) {
            assertTrue(config.support(id).modifiers().stream().anyMatch(m -> m.stat().equals(SupportKeys.DAMAGE_HIT) && m.value() < 0), id);
            assertTrue(config.support(id).modifiers().stream().noneMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.DAMAGE)), id + "：持續傷害不受影響");
        }
    }

    @Test
    void jumpingAndGroundRules() {
        assertNull(refusal("molten_pit_strike", "soaring_smash"), "騰躍砸擊是跳躍的近戰");
        assertNotNull(refusal("molten_pit_strike", "thousand_weight_smash"), "不跳的猛擊不行");
        assertNull(refusal("vault_spark", "soaring_smash"));
        assertNotNull(refusal("vault_spark", "blink"), "瞬移不是跳躍");
        assertNotNull(refusal("vault_spark", "spine_crush"));
        assertNull(refusal("enduring_terrain", "kerosene_grenade"), "火油榴彈留下油地");
        assertNotNull(refusal("enduring_terrain", "flame_curtain"), "焰幕不是地面效果（原作 Flame Wall）");
        assertNull(refusal("sow_fire", "mist_staff_sweep"), "疾風攻擊");
        assertNotNull(refusal("sow_fire", "spine_crush"), "不是疾風技能");
        assertNotNull(refusal("hinder", "blazing_star_shell"), "火焰投射物法術沒有混沌持續傷害");
        assertNotNull(refusal("stand_fast", "charged_blow"), "引導、蓄力的技能不行");
        assertFalse(config.support("enduring_terrain").worksOnHost());
    }

    @Test
    void newStatsHavePlayerText() {
        for (final String stat : new String[] {SupportRuleKeys.DAZE_BUILDUP, SupportRuleKeys.KNOCKBACK_ON_HIT}) {
            assertFalse(ModifierText.describe(Modifier.flat(stat, 100, "x")).startsWith("特殊效果"), stat);
        }
        assertTrue(config.support("shock_dizzy").modifiers().stream().anyMatch(m -> m.stat().equals(SupportRuleKeys.DAZE_BUILDUP)));
        assertTrue(config.support("shock_dizzy").modifiers().stream().noneMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.DAZE_CHANCE)),
                "照 PoE2 Daze：累積條，不擲機率");
    }

    @Test
    void dazeMeterFillsFromPhysicalOverStunThreshold() {
        final AilmentConfig.MeterSettings daze = new AilmentConfig().daze();
        assertEquals(0.5, daze.buildupFor(100, 200, 1.0, false), 1e-9, "暈眩門檻一半的物理傷害＝半條");
        final AilmentState.Meter meter = new AilmentState.Meter();
        assertFalse(meter.add(0.6, 0, daze.decayDelayTicks(false), daze.decayPerTick(false)));
        assertTrue(meter.add(0.5, 1, daze.decayDelayTicks(false), daze.decayPerTick(false)), "累積到 100% 就滿");
        meter.trigger(1, 160, 0);
        assertFalse(meter.add(2.0, 2, daze.decayDelayTicks(false), daze.decayPerTick(false)), "眩暈中不累積");
        assertEquals(0, meter.buildup(), 1e-9);
    }
}
