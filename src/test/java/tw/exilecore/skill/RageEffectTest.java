package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.KeystoneKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/** 2026-11-06 照站長「照原作改」：怒氣效果＝floor(怒氣 ×(1 + 增加怒氣效果))；原初飢餓（No Rage effect）是 0。 */
class RageEffectTest {

    @Test
    void rageEffectScalesAndPrimalHungerZeroes() {
        assertEquals(30, SkillResources.rageEffect(30, new StatSheet()));
        final StatSheet boosted = new StatSheet();
        boosted.add(Modifier.increased(StatKeys.RAGE_EFFECT, 0.5, "測試"));
        assertEquals(45, SkillResources.rageEffect(30, boosted), "增加 50% 怒氣效果：30 點算 45");
        assertEquals(10, SkillResources.rageEffect(7, boosted), "無條件捨去：7 × 1.5 = 10.5 → 10");
        final StatSheet primal = new StatSheet();
        primal.add(Modifier.flat(KeystoneKeys.FLAG_PRIMAL_HUNGER, 1, "原初飢餓"));
        assertEquals(0, SkillResources.rageEffect(60, primal), "原初飢餓：怒氣沒有效果");
    }
}
