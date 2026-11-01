package tw.exilecore.item;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照站長：傳奇腐化「重骰數值 0.78～1.22 倍」連固定值（資料檔寫死的數字）也會變。 */
class CorruptFixedValueTest {

    @Test
    void fixedValuesScaleAndRound() {
        assertEquals(12, AffixMod.scaleFixed(10, 1.22), 1e-9);
        assertEquals(8, AffixMod.scaleFixed(10, 0.78), 1e-9);
        assertEquals(1, AffixMod.scaleFixed(1, 0.78), 1e-9, "整數至少留 1");
        assertEquals(-12, AffixMod.scaleFixed(-10, 1.2), 1e-9);
        assertEquals(0.6, AffixMod.scaleFixed(0.5, 1.2), 1e-9, "小數留一位");
    }

    @Test
    void flagsAndVariablesDoNotScale() {
        final AffixMod fixed = new AffixMod("attack.speed", ModType.INCREASED, null, -10, true, Set.of());
        final AffixMod flag = new AffixMod("flag.no_mana_leech", ModType.FLAT, null, 1, false, Set.of());
        final AffixMod rolled = new AffixMod("life.max", ModType.FLAT, "value", 0, false, Set.of());
        assertTrue(fixed.scalesFixed());
        assertFalse(flag.scalesFixed());
        assertFalse(rolled.scalesFixed());
        assertEquals(-0.12, fixed.toModifier(Map.of(), "x", 1.2).value(), 1e-9);
        assertEquals(1, flag.toModifier(Map.of(), "x", 1.2).value(), 1e-9);
        assertEquals(50, rolled.toModifier(Map.of("value", 50), "x", 1.2).value(), 1e-9, "擲出來的變數腐化時已經乘過，這裡不再乘");
    }

    @Test
    void corruptPercentIsKeptOnCopy() {
        final RolledAffix rolled = new RolledAffix("x", 1, Map.of("value", 10));
        assertEquals(1.0, rolled.fixedScale(), 1e-9);
        rolled.setCorrupt(112);
        assertEquals(1.12, rolled.copy().fixedScale(), 1e-9);
    }
}
