package tw.exilecore.stats;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatSheetTest {

    @Test
    void flatIncreasedAndMoreFollowThePoeFormula() {
        final StatSheet sheet = new StatSheet()
                .setBase(StatKeys.LIFE_MAX, 100)
                .add(Modifier.flat(StatKeys.LIFE_MAX, 50, "戒指"))
                .add(Modifier.increased(StatKeys.LIFE_MAX, 0.20, "天賦"))
                .add(Modifier.increased(StatKeys.LIFE_MAX, 0.10, "天賦"))
                .add(Modifier.more(StatKeys.LIFE_MAX, 0.10, "核心天賦"))
                .add(Modifier.more(StatKeys.LIFE_MAX, 0.10, "核心天賦"));

        // (100 + 50) × 1.30 × 1.1 × 1.1
        assertEquals(150 * 1.30 * 1.21, sheet.get(StatKeys.LIFE_MAX), 1e-9);
    }

    @Test
    void taggedModifiersOnlyApplyWhenAllTheirTagsAreInTheContext() {
        final StatSheet sheet = new StatSheet()
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.20, Set.of(Tags.FIRE), "火焰"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.10, Set.of(Tags.ATTACK), "攻擊"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.05, Set.of(), "通用"));

        assertEquals(0.35, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.FIRE, Tags.ELEMENTAL)), 1e-9);
        assertEquals(0.15, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.PHYSICAL)), 1e-9);
        assertEquals(0.05, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.SPELL, Tags.COLD)), 1e-9);
    }

    @Test
    void cacheIsInvalidatedOnChange() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.ARMOUR, 100);
        assertEquals(100, sheet.get(StatKeys.ARMOUR), 1e-9);
        sheet.add(Modifier.increased(StatKeys.ARMOUR, 0.5, "x"));
        assertEquals(150, sheet.get(StatKeys.ARMOUR), 1e-9);
        sheet.clearModifiers();
        assertEquals(100, sheet.get(StatKeys.ARMOUR), 1e-9);
    }

    @Test
    void reducedBelowZeroClampsToZero() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.ARMOUR, 100)
                .add(Modifier.increased(StatKeys.ARMOUR, -1.5, "詛咒"));
        assertEquals(0, sheet.get(StatKeys.ARMOUR), 1e-9);
    }
}
