package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：怪物名字、Boss 血條後面寫曝露。 */
class ExposureTextTest {

    @Test
    void listsExposedElementsWithSeconds() {
        final UUID src = UUID.randomUUID();
        final List<Debuffs.Debuff> active = List.of(
                new Debuffs.Debuff("exposure.fire", src, 100, 0, false, null, List.of(Modifier.flat(DamageType.FIRE.mitigationStat(), -20, "曝露")), 0),
                new Debuffs.Debuff("oil_exposure", src, 60, 0, false, null, List.of(Modifier.flat(DamageType.COLD.mitigationStat(), -10, "火油曝露")), 0),
                new Debuffs.Debuff("other", src, 60, 0, false, null, List.of(Modifier.flat(DamageType.LIGHTNING.mitigationStat(), -10, "臨在")), 0));
        final List<String> parts = AilmentService.exposureParts(active, 20, true);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).contains("曝·火") && parts.get(0).contains("4.0s"));
        assertTrue(parts.get(1).contains("曝·冰"));
        assertEquals(2, AilmentService.exposureParts(active, 20, false).size());
    }
}
