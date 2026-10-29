package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照 PoE2：免疫元素異常（疾影之靴、三元守護）包含電擊；流血、中毒不算元素異常。 */
class ElementalImmunityTest {

    @Test
    void elementalImmunityCoversElectrocute() {
        final StatSheet immune = new StatSheet().add(Modifier.flat(StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE, 1, "x"));
        final Random random = new Random(1);
        for (final String stat : new String[] {StatKeys.AVOID_IGNITE, StatKeys.AVOID_CHILL, StatKeys.AVOID_FREEZE,
                StatKeys.AVOID_SHOCK, StatKeys.AVOID_ELECTROCUTE}) {
            assertTrue(AilmentService.avoided(immune, stat, random), stat);
        }
        assertFalse(AilmentService.avoided(new StatSheet(), StatKeys.AVOID_ELECTROCUTE, random), "沒免疫：照常被電擊");
    }
}
