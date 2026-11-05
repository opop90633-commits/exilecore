package tw.exilecore.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** 2026-11-05 照站長「照原作」：武器上的「+x% 暴擊傷害加成」只算用這把武器的攻擊。 */
class WeaponCritBonusTest {

    @Test
    void weaponBonusOnlyForAttacks() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.CRIT_MULTI, 200);
        sheet.add(Modifier.flat(StatKeys.WEAPON_CRIT_BONUS, 25, "武器"));
        sheet.add(Modifier.increased(StatKeys.CRIT_BONUS, 2.1, "天賦"));
        // 攻擊：(100 + 25) × 3.1 = 387.5% → ×4.875
        assertEquals(4.875, DamageCalculator.critMultiplier(sheet, Set.of(Tags.ATTACK), null), 1e-9);
        // 法術不吃武器的：100 × 3.1 = 310% → ×4.1
        assertEquals(4.1, DamageCalculator.critMultiplier(sheet, Set.of(Tags.SPELL), null), 1e-9);
    }
}
