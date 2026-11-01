package tw.exilecore.monster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照站長：Boss 血條後面寫剩多少護盾。 */
class ShieldTextTest {
    @Test
    void percentOfShieldLeft() {
        assertEquals("", MonsterMechanics.shieldText(null));
        assertEquals("", MonsterMechanics.shieldText(new double[] {0, 0}));
        assertEquals(" <#7fc8ff>護盾 25%", MonsterMechanics.shieldText(new double[] {250, 1000}));
        assertEquals(" <#7fc8ff>護盾 0%", MonsterMechanics.shieldText(new double[] {0, 1000}));
    }
}
