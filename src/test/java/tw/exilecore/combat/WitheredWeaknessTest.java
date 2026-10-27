package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.StatKeys;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-26：凋零、暴擊弱點（PoE2 的 Withered、Critical Weakness）的層數。 */
class WitheredWeaknessTest {

    @Test
    void witheredStacksUpToTenAndOnlyAddsChaosTaken() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID source = UUID.randomUUID();
        HitEffects.addWithered(debuffs, target, source, 0, 3, HitEffects.WITHERED_TICKS);
        assertEquals(3, HitEffects.stacks(debuffs, target, "withered.", 1));
        assertEquals(0.15, debuffs.typedTaken(target, source, 1, DamageType.CHAOS), 1e-9);
        assertEquals(0, debuffs.typedTaken(target, source, 1, DamageType.FIRE), 1e-9);
        assertEquals(1.0, debuffs.takenMultiplier(target, source, 1, java.util.Set.of("chaos")), 1e-9, "不重複算到一般的受到傷害增加");
        HitEffects.addWithered(debuffs, target, source, 10, 12, HitEffects.WITHERED_TICKS);
        assertEquals(10, HitEffects.stacks(debuffs, target, "withered.", 11), "最多 10 層");
        assertEquals(0.5, debuffs.typedTaken(target, source, 11, DamageType.CHAOS), 1e-9);
        assertEquals(0, HitEffects.stacks(debuffs, target, "withered.", 10 + HitEffects.WITHERED_TICKS + 1), "4 秒後全部消失");
    }

    @Test
    void critWeaknessStacksUpToTwenty() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID source = UUID.randomUUID();
        HitEffects.addCritWeakness(debuffs, target, source, 0, 25, HitEffects.CRIT_WEAKNESS_TICKS);
        assertEquals(20, HitEffects.stacks(debuffs, target, "crit_weakness.", 1));
        final double total = debuffs.modifiers(target, source, 1).stream()
                .filter(m -> m.stat().equals(StatKeys.CRIT_CHANCE_TAKEN)).mapToDouble(m -> m.value()).sum();
        assertEquals(10, total, 1e-9, "20 層 × 0.5%");
    }
}
