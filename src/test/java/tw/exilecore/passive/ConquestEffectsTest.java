package tw.exilecore.passive;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：永恆珠寶的核心、基石照 PoE2 生效（ConquestEffects）。 */
class ConquestEffectsTest {

    @Test
    void noLineIsInactiveAnymore() {
        for (final TimelessJewels.Legion legion : TimelessJewels.legions().values()) {
            for (final List<TimelessJewels.Template> group : List.of(legion.notables(), legion.keystones())) {
                for (final TimelessJewels.Template template : group) {
                    for (final TimelessJewels.Line line : template.lines()) {
                        assertFalse(line.text().contains("尚未生效"), template.id() + "：" + line.text());
                    }
                }
            }
        }
    }

    @Test
    void loyaltyGivesMoreDamagePerTwoUndeadPower() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(ConquestEffects.LOYALTY, 1, "基石"));
        sheet.add(Modifier.flat(ConquestEffects.UNDEAD_POWER, 7, "召喚物"));
        ConquestEffects.derive(sheet);
        assertEquals(1.03, sheet.more(StatKeys.DAMAGE, java.util.Set.of()), 1e-9);
        assertTrue(ConquestEffects.undead("SKELETON"));
        assertFalse(ConquestEffects.undead("WOLF"));
    }

    @Test
    void typedExposureAddsToGenericExposure() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.increased(StatKeys.EXPOSURE_EFFECT, 0.1, "a"));
        sheet.add(Modifier.increased(ConquestEffects.EXPOSURE_EFFECT + "cold", 0.1, "b"));
        assertEquals(1.2 / 1.1, ConquestEffects.exposureScale(sheet, DamageType.COLD), 1e-9);
        assertEquals(1.0, ConquestEffects.exposureScale(sheet, DamageType.FIRE), 1e-9);
    }

    @Test
    void bloodRegenCountsLifeSpentInLastFourSeconds() {
        final UUID id = UUID.randomUUID();
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(ConquestEffects.BLOOD, 1, "基石"));
        ConquestEffects.lifeSpent(id, 40, 100);
        assertEquals(2, ConquestEffects.bloodRegen(id, sheet, 120), 1e-9);
        assertEquals(0, ConquestEffects.bloodRegen(id, sheet, 200), 1e-9); // 超過 4 秒
    }
}
