package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;

/** 2026-11-10 照站長「蝕咒裂估算補上漏算的」。 */
class HexblastEstimateTest {

    @Test
    void remainingCurseSecondsScaleTheBlast() {
        assertEquals(1 + 0.125 * 15, SkillService.hexblastRemainingFactor(12.5, 15 * 20 + 19), 1e-9, "15.9 秒 → 整 15 秒");
        assertEquals(1, SkillService.hexblastRemainingFactor(0, 400), 1e-9);
        assertEquals(1, SkillService.hexblastRemainingFactor(12.5, 10), 1e-9, "不到 1 秒");
    }

    @Test
    void inevitableCritExpectation() {
        final tw.exilecore.stats.StatSheet plain = new tw.exilecore.stats.StatSheet();
        assertEquals(1 + 0.257 * 3.61, DamageCalculator.expectedCritFactor(plain, 0.257, 4.61), 1e-9);
        final tw.exilecore.stats.StatSheet inevitable = new tw.exilecore.stats.StatSheet();
        inevitable.add(tw.exilecore.stats.Modifier.flat(tw.exilecore.ascendancy.AscKeys.INEVITABLE_CRIT, 1, "測試"));
        assertEquals(1 + 3.61 * 0.257 / (1 - 0.7 * (1 - 0.257)), DamageCalculator.expectedCritFactor(inevitable, 0.257, 4.61), 1e-9,
                "必然暴擊：每重擲一次加成更少 30%");
    }
}
