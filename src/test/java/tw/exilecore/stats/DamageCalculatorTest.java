package tw.exilecore.stats;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageCalculatorTest {

    private static StatSheet attackerWithSword() {
        return new StatSheet()
                .setBase(StatKeys.ACCURACY, 1_000_000)   // 必中
                .setBase(StatKeys.CRIT_MULTI, 150)
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1.5, "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_CRIT, 0, "劍"));
    }

    @Test
    void physicalDamageIsReducedByArmourAndAbsorbedByEnergyShieldFirst() {
        final StatSheet attacker = attackerWithSword()
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.PHYSICAL), "天賦")); // 10 → 20
        final StatSheet defender = new StatSheet()
                .setBase(StatKeys.ARMOUR, 100)   // 100 / (100 + 5×20) = 50% 減免
                .setBase(StatKeys.RESIST_MAX, 75);

        final HitResult result = DamageCalculator.calculate(attacker, SkillProfile.defaultAttack(Set.of(Tags.SWORD)), defender, 4, new Random(1));

        assertTrue(result.hit());
        assertFalse(result.crit());
        assertEquals(20, result.outgoing().get(DamageType.PHYSICAL), 1e-9);
        assertEquals(10, result.dealt().get(DamageType.PHYSICAL), 1e-9);
        assertEquals(4, result.esDamage(), 1e-9);
        assertEquals(6, result.lifeDamage(), 1e-9);
    }

    @Test
    void elementalDamageUsesResistanceAndChaosBypassesEnergyShield() {
        final StatSheet attacker = attackerWithSword()
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 100, Set.of(Tags.ATTACK, Tags.FIRE), "戒指"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 100, Set.of(Tags.ATTACK, Tags.FIRE), "戒指"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 50, Set.of(Tags.ATTACK, Tags.CHAOS), "戒指"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 50, Set.of(Tags.ATTACK, Tags.CHAOS), "戒指"));
        final StatSheet defender = new StatSheet()
                .setBase(StatKeys.RESIST_FIRE, 90)   // 夾到上限 75
                .setBase(StatKeys.RESIST_CHAOS, -20)
                .setBase(StatKeys.RESIST_MAX, 75);

        final HitResult result = DamageCalculator.calculate(attacker, SkillProfile.defaultAttack(Set.of()), defender, 1000, new Random(2));

        assertEquals(25, result.dealt().get(DamageType.FIRE), 1e-9);      // 100 × (1 − 0.75)
        assertEquals(60, result.dealt().get(DamageType.CHAOS), 1e-9);     // 50 × (1 + 0.20)
        assertEquals(10, result.dealt().get(DamageType.PHYSICAL), 1e-9);  // 無護甲
        assertEquals(35, result.esDamage(), 1e-9);                        // 火 + 物理進護盾
        assertEquals(60, result.lifeDamage(), 1e-9);                      // 混沌直接扣生命
    }

    @Test
    void attacksMissAgainstOverwhelmingEvasionAtTheFivePercentFloor() {
        final StatSheet attacker = attackerWithSword().setBase(StatKeys.ACCURACY, 1);
        final StatSheet defender = new StatSheet().setBase(StatKeys.EVASION, 1_000_000).setBase(StatKeys.RESIST_MAX, 75);
        final Random random = new Random(3);
        int hits = 0;
        for (int i = 0; i < 4000; i++) {
            if (DamageCalculator.calculate(attacker, SkillProfile.defaultAttack(Set.of()), defender, 0, random).hit()) {
                hits++;
            }
        }
        // 下限 5%：4000 次大約 200 次
        assertTrue(hits > 120 && hits < 300, "命中次數 " + hits);
    }

    @Test
    void spellsAlwaysHitAndCritMultiplies() {
        final StatSheet attacker = new StatSheet()
                .setBase(StatKeys.ACCURACY, 0)
                .setBase(StatKeys.CRIT_MULTI, 200)
                .add(Modifier.increased(StatKeys.CRIT_CHANCE, 19.0, "必暴")); // 5% × 20 = 100%
        final StatSheet defender = new StatSheet().setBase(StatKeys.EVASION, 1_000_000).setBase(StatKeys.RESIST_MAX, 75);
        final SkillProfile fireball = new SkillProfile("fireball", Set.of(Tags.SPELL, Tags.FIRE), 1.0,
                new DamagePacket().set(DamageType.FIRE, 50));

        final HitResult result = DamageCalculator.calculate(attacker, fireball, defender, 0, new Random(4));
        assertTrue(result.hit());
        assertTrue(result.crit());
        assertEquals(100, result.lifeDamage(), 1e-9);
    }

    @Test
    void formulas() {
        assertEquals(0.5, Formulas.armourReduction(100, 20), 1e-9);
        assertEquals(Formulas.MAX_ARMOUR_REDUCTION, Formulas.armourReduction(1_000_000, 1), 1e-9);
        assertEquals(1.0, Formulas.hitChance(100, 0), 1e-9);
        assertEquals(13, Formulas.attackIntervalTicks(1.55));
        assertEquals(0.75, Formulas.resistReduction(120, 75), 1e-9);
        assertEquals(-0.6, Formulas.resistReduction(-90, 75), 1e-9);
    }
}
