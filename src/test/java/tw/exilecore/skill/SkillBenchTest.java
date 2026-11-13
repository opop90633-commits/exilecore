package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-12 照站長「自動量技能（/ec balance bench），第一版只量單體」：報表的分組、中位數、標記。 */
class SkillBenchTest {

    @Test
    void medianSkipsZeros() {
        assertEquals(0, SkillBench.median(List.of()));
        assertEquals(0, SkillBench.median(List.of(0.0, 0.0)));
        assertEquals(200, SkillBench.median(List.of(100.0, 200.0, 0.0, 900.0)));   // 0（量不到）不算
        assertEquals(150, SkillBench.median(List.of(100.0, 200.0)));
    }

    @Test
    void flagsWeakAndMissing() {
        assertEquals("量不到", SkillBench.flag(0, 1000, 5));
        assertEquals("偏弱", SkillBench.flag(599, 1000, 5));        // 中位數六成以下
        assertEquals("", SkillBench.flag(600, 1000, 5));
        assertEquals("", SkillBench.flag(100, 1000, 1));            // 一個人一組不比
    }

    @Test
    void retryPicksZeroAndBelowMedian() {
        // 2026-11-13 照站長「只單獨量那些量不到的與低於中位數的」
        final java.util.Map<String, Double> dps = new java.util.LinkedHashMap<>();
        dps.put("a", 0.0);
        dps.put("b", 100.0);
        dps.put("c", 200.0);
        dps.put("d", 300.0);
        // 2026-11-13：跟目標的 9 成比（b 的目標 100 → 90 以下才算低；c 照預設 2,544；d 的目標 300 → 270 以下）
        final java.util.Map<String, Double> targets = java.util.Map.of("b", 100.0, "d", 300.0);
        assertEquals(java.util.Set.of("a"), SkillBench.retryIds("量不到", dps, targets));
        assertEquals(java.util.Set.of("c"), SkillBench.retryIds("低於中位數", dps, targets));
        assertEquals(java.util.Set.of("a", "c"), SkillBench.retryIds("重測", dps, targets));
    }

    @Test
    void closeSkillsStandNextToTheDummy() {
        // 2026-11-13 照站長「0 的原因可能跟技能的落點有關係」
        final org.bukkit.Location dummy = new org.bukkit.Location(null, 0, 64, 0);
        final org.bukkit.Location base = new org.bukkit.Location(null, 3, 64, 0);
        assertEquals(base, SkillBench.standFor(base, dummy, false));
        final org.bukkit.Location near = SkillBench.standFor(base, dummy, true);
        assertEquals(SkillBench.CLOSE_DISTANCE, near.getX(), 1e-9);
        assertEquals(0, near.getZ(), 1e-9);
        assertEquals(java.util.List.of(60, 40), java.util.List.of(SkillBench.PULSE.get("starfire_bead_chain")[0], SkillBench.PULSE.get("starfire_bead_chain")[1]));
        assertEquals(true, SkillBench.UNMEASURABLE.containsKey("shield_brace"));
        // 2026-11-13：放在準心地上的低頭看假人腳下（3 格外約 27 度）
        final org.bukkit.Location feet = SkillBench.lookAtFeet(base, dummy);
        assertEquals(true, feet.getPitch() > 20 && feet.getPitch() < 35);
        assertEquals(10, SkillBench.windowSeconds(10, 4), 1e-9);
        assertEquals(24, SkillBench.windowSeconds(10, 12), 1e-9); // 冷卻 12 秒 → 量 24 秒
        // 2026-11-13：連量 3 次取平均
        final double[] mean = SkillBench.mean(java.util.List.of(new double[] {100, 1000, 10}, new double[] {200, 2000, 20}, new double[] {300, 3000, 30}));
        assertEquals(200, mean[0], 1e-9);
        assertEquals(20, mean[2], 1e-9);
        assertEquals(3, SkillBench.REPEATS);
        // 2026-11-13 照站長「有辦法選擇只測試 1 次嗎」
        assertEquals(1, SkillBench.repeatsIn("重測 1次"));
        assertEquals("重測", SkillBench.withoutRepeats("重測 1次"));
        assertEquals(null, SkillBench.withoutRepeats("1次"));
        assertEquals(3, SkillBench.repeatsIn("弓"));
        assertEquals(5, SkillBench.repeatsIn("9次"));
    }

    @Test
    void weaponFamily() {
        assertEquals("弓", SkillBench.family(Set.of("bow", "ranged", "two_hand")));
        assertEquals("長棍", SkillBench.family(Set.of("quarterstaff", "melee", "two_hand")));
        assertEquals("其他", SkillBench.family(Set.of("melee")));
    }
}
