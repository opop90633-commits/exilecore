package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SupportSystemKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2（PoB2 0.5）把靜電、磁暴、熔坑擊、拖滯換成真正的系統。 */
class SupportSystemsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static boolean has(final String support, final String stat) {
        return config.support(support).modifiers().stream().anyMatch(m -> m.stat().equals(stat));
    }

    // ---- 靜電 ----

    @Test
    void staticShockTargetsAttachedFirstThenNearest() {
        final List<Integer> picked = SupportSystemKeys.pulseTargets(List.of(5, 1, 9, 3, 7, 2, 8, 4), 9, i -> i, 6);
        assertEquals(List.of(9, 1, 2, 3, 4, 5), picked, "附著的那隻先，再照距離，最多 6 個");
        assertEquals(List.of(1, 2, 3), SupportSystemKeys.pulseTargets(List.of(3, 1, 2), null, i -> i, 6));
        assertEquals(3, SupportSystemKeys.pulseTargets(List.of(3, 1, 2), 4, i -> i, 0).size(), "不在範圍裡的附著目標不加進去；0 = 不限");
    }

    @Test
    void storedUsesRechargeOneAtATime() {
        final long[] slots = new long[3];
        assertTrue(SupportSystemKeys.usesReady(slots, 100));
        SupportSystemKeys.useOne(slots, 100, 8);
        SupportSystemKeys.useOne(slots, 100, 8);
        assertTrue(SupportSystemKeys.usesReady(slots, 100), "存 3 次：用了 2 次還有 1 次");
        SupportSystemKeys.useOne(slots, 100, 8);
        assertFalse(SupportSystemKeys.usesReady(slots, 107));
        assertTrue(SupportSystemKeys.usesReady(slots, 108), "一次一次充：第一次 0.4 秒後好");
        SupportSystemKeys.useOne(slots, 108, 8);
        assertFalse(SupportSystemKeys.usesReady(slots, 115));
    }

    @Test
    void staticShockData() {
        final SkillDefinition orb = config.skill(SupportProcs.STATIC_SHOCKS);
        assertEquals(3, config.cooldownUses(orb.id()), "PoB2 storedUses 3");
        assertEquals(0.4, orb.cooldown(), 1e-9);
        assertEquals(100, orb.params().zone().damage(), 1e-9, "每一波就是那張傷害表");
        assertEquals(0.65, orb.params().zone().interval(), 1e-9);
        assertEquals(5, orb.params().zone().max());
        assertEquals(6, SupportSystemKeys.STATIC_SHOCK_MAX_TARGETS);
    }

    // ---- 磁暴 ----

    @Test
    void riftPulseTiming() {
        assertEquals(17, SupportSystemKeys.riftIntervalTicks(0), "850 毫秒");
        assertEquals(10, SupportSystemKeys.riftIntervalTicks(3), "3 個感電：850 ÷ 1.75 ≈ 486 毫秒 ≈ 10 tick");
        assertEquals(1.0, SupportSystemKeys.riftPulseMore(0), 1e-9);
        assertEquals(1.8, SupportSystemKeys.riftPulseMore(4), 1e-9, "第 5 波：前面 4 波各更多 20%");
        assertFalse(SupportSystemKeys.riftFinalPulse(3));
        assertTrue(SupportSystemKeys.riftFinalPulse(4), "第 5 波是最後一波");
        assertFalse(SupportSystemKeys.riftTriggers(2));
        assertTrue(SupportSystemKeys.riftTriggers(3));
    }

    @Test
    void riftData() {
        final Modifier proc = config.support("magnetic_storm").modifiers().stream()
                .filter(m -> m.stat().equals(SupportProcs.PREFIX + SupportProcs.SHOCK_CLUSTER)).findFirst().orElseThrow();
        assertEquals(100, proc.value(), 1e-9, "40 是 4 公尺的範圍，不是機率");
        assertEquals(4, SupportProcs.SHOCK_CLUSTER_RADIUS, 1e-9);
        final SkillDefinition pulse = config.skill(SupportProcs.SHOCK_RIFT);
        final SkillDefinition last = config.skill(SupportProcs.SHOCK_RIFT_FINAL);
        assertNotNull(last);
        assertEquals(2, pulse.params().radius().last(), 1e-9);
        assertEquals(0.75, pulse.effectiveness().first(), 1e-9);
        assertEquals(1.5, last.effectiveness().first(), 1e-9);
    }

    // ---- 熔坑擊 ----

    @Test
    void fissuresSpreadEvenlyFromFacing() {
        final double[][] three = SupportSystemKeys.fissureDirections(0, 2, 3);
        assertEquals(3, three.length);
        assertArrayEquals(new double[] {0, 1}, three[0], 1e-9, "第一道朝面向");
        assertEquals(-Math.sqrt(3) / 2, three[1][0], 1e-9);
        assertEquals(-0.5, three[1][1], 1e-9, "120 度");
        assertEquals(1.0, Math.hypot(three[2][0], three[2][1]), 1e-9);
        assertArrayEquals(new double[] {0, 0.5, 1.0, 1.5}, SupportSystemKeys.fissureSteps(1.5, 0.5), 1e-9);
        assertEquals(2, SupportSystemKeys.fissureSteps(0, 0.5).length);
    }

    @Test
    void landingDetection() {
        assertFalse(SupportSystemKeys.landed(1, true, false), "剛放、還沒離地：先等");
        assertFalse(SupportSystemKeys.landed(6, false, true), "還在空中");
        assertTrue(SupportSystemKeys.landed(6, true, true), "離地過、踩到地：落地");
        assertTrue(SupportSystemKeys.landed(SupportSystemKeys.LAND_GRACE_TICKS, true, false), "一直沒離地（衝刺、沒跳起來）");
        assertTrue(SupportSystemKeys.landed(SupportSystemKeys.LAND_TIMEOUT_TICKS, false, true), "卡在空中也照當下的位置");
    }

    @Test
    void craterData() {
        assertTrue(has("molten_pit_strike", SupportProcs.PREFIX + SupportSystemKeys.LAND));
        assertFalse(has("molten_pit_strike", SupportProcs.PREFIX + "use"), "落地時，不是放的時候");
        assertEquals(3, config.support("molten_pit_strike").modifiers().stream()
                .filter(m -> m.stat().equals(SupportSystemKeys.CRATER_FISSURES)).mapToDouble(Modifier::value).sum(), 1e-9);
        final SkillDefinition fissure = config.skill(SupportProcs.CRATER);
        assertTrue(fissure.isAttack());
        assertEquals(0.35, fissure.effectiveness().first(), 1e-9);
        assertEquals(1.5, fissure.params().length().last(), 1e-9);
        assertEquals(6, fissure.params().zone().duration(), 1e-9);
        assertEquals(9, fissure.params().zone().max());
        assertNull(config.support("molten_pit_strike").refusal(config.skill("soaring_smash")));
    }

    // ---- 拖滯 ----

    @Test
    void hinderComesFromChaosDot() {
        assertTrue(has("hinder", SupportSystemKeys.HINDER_DOT));
        assertFalse(config.support("hinder").modifiers().stream().anyMatch(m -> m.stat().startsWith(SupportProcs.PREFIX)), "不是擊中時觸發");
        assertTrue(SupportGuards.hinders(config.support("hinder")));
        assertNull(config.support("hinder").refusal(config.skill("filth_array")), "穢陣（混沌地面）");
        assertNull(config.support("hinder").refusal(config.skill("soul_draw_shot")), "汲魂彈（逐級寫的混沌減益）");
        assertNull(config.support("hinder").refusal(config.skill("plague_taint")), "疫潮");
        assertNotNull(config.support("hinder").refusal(config.skill("blazing_star_shell")));
        assertFalse(ModifierText.describe(Modifier.flat(SupportSystemKeys.HINDER_DOT, 1, "x")).startsWith("特殊效果"));
        assertFalse(ModifierText.describe(Modifier.flat(SupportSystemKeys.CRATER_FISSURES, 3, "x")).startsWith("特殊效果"));
    }
}
