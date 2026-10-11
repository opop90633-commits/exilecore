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
                .setBase(StatKeys.ARMOUR, 200)   // 200 / (200 + 10×20) = 50% 減免
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
    void elementalDamageUsesResistanceAndChaosDealsDoubleToEnergyShield() {
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
        assertEquals(155, result.esDamage(), 1e-9);                       // 火 + 物理 + 混沌 × 2 進護盾（PoE2）
        assertEquals(0, result.lifeDamage(), 1e-9);                       // 護盾夠厚，生命不扣

        // 護盾不夠：混沌以兩倍扣護盾，剩下的照原值扣生命
        final HitResult thin = DamageCalculator.calculate(attacker, SkillProfile.defaultAttack(Set.of()), defender, 55, new Random(2));
        assertEquals(55, thin.esDamage(), 1e-9);                          // 火 25 + 物理 10 + 混沌 10 × 2
        assertEquals(50, thin.lifeDamage(), 1e-9);                        // 混沌剩 50
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
    void blockChanceIsCappedAtFiftyPercent() {
        final StatSheet attacker = attackerWithSword();
        final StatSheet defender = new StatSheet().setBase(StatKeys.BLOCK_CHANCE, 140).setBase(StatKeys.RESIST_MAX, 75);
        final Random random = new Random(5);
        int blocked = 0;
        for (int i = 0; i < 4000; i++) {
            if (DamageCalculator.calculate(attacker, SkillProfile.defaultAttack(Set.of()), defender, 0, random).blocked()) {
                blocked++;
            }
        }
        // 堆到 140% 也只擋 50%（PoE2）：4000 次大約 2000 次
        assertEquals(50, StatKeys.BLOCK_CAP, 1e-9);
        assertTrue(blocked > 1850 && blocked < 2150, "擋下 " + blocked);
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
        assertEquals(0.5, Formulas.armourReduction(200, 20), 1e-9);
        assertEquals(Formulas.MAX_ARMOUR_REDUCTION, Formulas.armourReduction(1_000_000, 1), 1e-9);
        assertEquals(1.0, Formulas.hitChance(100, 0), 1e-9);
        assertEquals(13, Formulas.attackIntervalTicks(1.55));
        assertEquals(0.75, Formulas.resistReduction(120, 75), 1e-9);
        assertEquals(-0.6, Formulas.resistReduction(-90, 75), 1e-9);
    }

    @Test
    void physicalConversionUsesModifiersOfBothTypes() {
        final StatSheet attacker = new StatSheet();
        attacker.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"));
        attacker.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"));
        attacker.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.5, Set.of(Tags.PHYSICAL), "物理"));
        attacker.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.FIRE), "火焰"));
        final SkillProfile burning = new SkillProfile("burning_arrow", Set.of(Tags.ATTACK, Tags.PROJECTILE), 1.0,
                null, null, java.util.Map.of(DamageType.FIRE, 0.5));
        final DamagePacket expected = DamageCalculator.expectedOutgoing(attacker, burning);
        assertEquals(50 * 1.5, expected.get(DamageType.PHYSICAL), 1e-9, "剩下的物理只吃物理修飾器");
        assertEquals(50 * (1 + 0.5 + 1.0), expected.get(DamageType.FIRE), 1e-9, "轉換出來的火焰同時吃物理與火焰修飾器");

        // 全部轉換：沒有物理
        final SkillProfile full = new SkillProfile("x", Set.of(Tags.ATTACK), 1.0, null, null, java.util.Map.of(DamageType.COLD, 1.0));
        final DamagePacket allCold = DamageCalculator.expectedOutgoing(attacker, full);
        assertEquals(0, allCold.get(DamageType.PHYSICAL), 1e-9);
        assertEquals(100 * 1.5, allCold.get(DamageType.COLD), 1e-9);
    }

    @Test
    void spellDamageHasARange() {
        final DamagePacket min = new DamagePacket().set(DamageType.LIGHTNING, 10);
        final DamagePacket max = new DamagePacket().set(DamageType.LIGHTNING, 30);
        final SkillProfile arc = new SkillProfile("arc", Set.of(Tags.SPELL, Tags.LIGHTNING), 1.0, min, max, null);
        assertEquals(20, DamageCalculator.expectedOutgoing(new StatSheet(), arc).get(DamageType.LIGHTNING), 1e-9);
        final java.util.Random random = new java.util.Random(7);
        final StatSheet caster = new StatSheet().setBase(StatKeys.CRIT_MULTI, 150);
        boolean low = false;
        boolean high = false;
        for (int i = 0; i < 200; i++) {
            final double rolled = DamageCalculator.calculate(caster, arc, new StatSheet(), 0, random).outgoing().get(DamageType.LIGHTNING);
            assertTrue(rolled >= 10 - 1e-9, "至少下限（暴擊會更高）");
            low |= rolled < 15;
            high |= rolled > 25 && rolled <= 30;
        }
        assertTrue(low && high, "要擲出範圍兩端附近的值");
        assertEquals(1.5, DamageCalculator.castSpeedMultiplier(new StatSheet()
                .add(new Modifier(StatKeys.CAST_SPEED, ModType.INCREASED, 0.5, Set.of(), "施法速度")), Set.of(Tags.SPELL)), 1e-9);
    }

    @Test
    void skillElementTagsDoNotLeakIntoOtherDamageTypes() {
        final StatSheet attacker = new StatSheet();
        attacker.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"));
        attacker.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"));
        attacker.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 10, Set.of(Tags.ATTACK, Tags.FIRE), "附加火焰"));
        attacker.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 10, Set.of(Tags.ATTACK, Tags.FIRE), "附加火焰"));
        attacker.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.FIRE), "增加火焰"));
        // 技能自己帶 fire 標籤（焚羽箭），物理部分還是只算物理
        final SkillProfile fireSkill = new SkillProfile("x", Set.of(Tags.ATTACK, Tags.PROJECTILE, Tags.FIRE), 1.0, null);
        final DamagePacket expected = DamageCalculator.expectedOutgoing(attacker, fireSkill);
        assertEquals(100, expected.get(DamageType.PHYSICAL), 1e-9, "附加火焰與增加火焰不能算進物理");
        assertEquals(20, expected.get(DamageType.FIRE), 1e-9);
    }

    @Test
    void spellAddedDamageOnlyReachesSpellsTimesEffectiveness() {
        final StatSheet attacker = new StatSheet();
        attacker.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"));
        attacker.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"));
        // 攻擊附加 10 火焰、法術附加 20 冰冷
        attacker.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 10, Set.of(Tags.ATTACK, Tags.FIRE), "攻擊附加火焰"));
        attacker.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 10, Set.of(Tags.ATTACK, Tags.FIRE), "攻擊附加火焰"));
        attacker.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 20, Set.of(Tags.SPELL, Tags.COLD), "法術附加冰冷"));
        attacker.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 20, Set.of(Tags.SPELL, Tags.COLD), "法術附加冰冷"));

        // 熾星彈（效益 100%）：自己的 50 火焰 + 法術附加 20 冰冷；攻擊附加不算
        final SkillProfile fireball = new SkillProfile("fireball", Set.of(Tags.SPELL, Tags.PROJECTILE, Tags.FIRE), 1.0,
                new DamagePacket().set(DamageType.FIRE, 50));
        final DamagePacket spell = DamageCalculator.expectedOutgoing(attacker, fireball);
        assertEquals(50, spell.get(DamageType.FIRE), 1e-9, "攻擊附加的火焰不算進法術");
        assertEquals(20, spell.get(DamageType.COLD), 1e-9, "法術附加：熾星彈也吃冰冷的法術附加（PoE 規則）");
        assertEquals(0, spell.get(DamageType.PHYSICAL), 1e-9, "法術不用武器傷害");

        // 游雷（效益 50%）：法術附加打對折
        final SkillProfile spark = new SkillProfile("spark", Set.of(Tags.SPELL, Tags.PROJECTILE, Tags.LIGHTNING), 0.5,
                new DamagePacket().set(DamageType.LIGHTNING, 5));
        assertEquals(10, DamageCalculator.expectedOutgoing(attacker, spark).get(DamageType.COLD), 1e-9);

        // 攻擊：武器 + 攻擊附加；法術附加不算
        final DamagePacket attack = DamageCalculator.expectedOutgoing(attacker, SkillProfile.defaultAttack(Set.of(Tags.SWORD)));
        assertEquals(100, attack.get(DamageType.PHYSICAL), 1e-9);
        assertEquals(10, attack.get(DamageType.FIRE), 1e-9);
        assertEquals(0, attack.get(DamageType.COLD), 1e-9, "法術附加不算進攻擊");
    }

    @Test
    void skillLocalModifiersAndGainAsExtraOnlyAffectThatSkill() {
        final StatSheet attacker = attackerWithSword(); // 10 物理
        final SkillProfile plain = SkillProfile.defaultAttack(Set.of(Tags.SWORD));
        final SkillProfile supported = new SkillProfile("heavy_strike", plain.tags(), 1.0, null, null, java.util.Map.of(),
                java.util.List.of(
                        new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.4, Set.of(Tags.MELEE, Tags.PHYSICAL), "蠻力"),
                        new Modifier(StatKeys.damageGain(DamageType.FIRE), ModType.FLAT, 25, Set.of(), "引火"),
                        new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.FIRE), "測試")));
        final DamagePacket boosted = DamageCalculator.expectedOutgoing(attacker, supported);
        assertEquals(14, boosted.get(DamageType.PHYSICAL), 1e-9, "40% 更多近戰物理");
        // 額外獲得的火焰：10 × 25% = 2.5，吃火焰的增加（×2）也吃物理的更多（×1.4）
        assertEquals(2.5 * 2 * 1.4, boosted.get(DamageType.FIRE), 1e-9);
        assertEquals(10, DamageCalculator.expectedOutgoing(attacker, plain).get(DamageType.PHYSICAL), 1e-9,
                "輔助寶石只對掛著它的技能有效，玩家的屬性表不變");
        final HitResult hit = DamageCalculator.calculate(attacker, supported, new StatSheet().setBase(StatKeys.RESIST_MAX, 75), 0, new Random(3));
        assertEquals(14, hit.outgoing().get(DamageType.PHYSICAL), 1e-9, "實際命中也用疊好的屬性表（這把劍沒有暴擊）");
    }
    @Test
    void doubleDamageDoublesTheWholeHitAndFollowsSkillTags() {
        final StatSheet defender = new StatSheet().setBase(StatKeys.RESIST_MAX, 75);
        final SkillProfile attack = SkillProfile.defaultAttack(Set.of(Tags.SWORD));
        // 必定雙倍：10 → 20（武器沒有暴擊、必中，只有這一個擲骰）
        final StatSheet always = attackerWithSword().add(Modifier.flat(StatKeys.DAMAGE_DOUBLE, 100, "誓約"));
        final HitResult doubled = DamageCalculator.calculate(always, attack, defender, 0, new Random(1));
        assertEquals(20, doubled.outgoing().get(DamageType.PHYSICAL), 1e-9);
        assertEquals(20, doubled.lifeDamage(), 1e-9);
        // 只對法術的雙倍：攻擊不吃
        final StatSheet spellOnly = attackerWithSword()
                .add(new Modifier(StatKeys.DAMAGE_DOUBLE, ModType.FLAT, 100, Set.of(Tags.SPELL), "法術"));
        assertEquals(10, DamageCalculator.calculate(spellOnly, attack, defender, 0, new Random(1)).outgoing().get(DamageType.PHYSICAL), 1e-9);
        // 30%：大約三成的命中是 20
        final StatSheet sometimes = attackerWithSword().add(Modifier.flat(StatKeys.DAMAGE_DOUBLE, 30, "誓約"));
        final Random random = new Random(7);
        int big = 0;
        for (int i = 0; i < 4000; i++) {
            if (DamageCalculator.calculate(sometimes, attack, defender, 0, random).outgoing().get(DamageType.PHYSICAL) > 15) {
                big++;
            }
        }
        assertTrue(big > 1050 && big < 1350, "雙倍次數 " + big);
    }

    @Test
    void mindOverMatterTakesPartOfLifeDamageFromManaAndRecoupCountsAllDamageTaken() {
        // 100 扣生命：30% 改扣魔力
        final IncomingDamage split = IncomingDamage.split(20, 100, 500, 30, 10);
        assertEquals(20, split.es(), 1e-9);
        assertEquals(70, split.life(), 1e-9);
        assertEquals(30, split.mana(), 1e-9);
        assertEquals(12, split.recoup(), 1e-9, "（20 + 70 + 30）× 10%");
        // 魔力不夠：剩下的照扣生命
        final IncomingDamage dry = IncomingDamage.split(0, 100, 10, 30, 0);
        assertEquals(90, dry.life(), 1e-9);
        assertEquals(10, dry.mana(), 1e-9);
        assertEquals(0, dry.recoup(), 1e-9);
        // 沒有這兩個效果：原樣
        final IncomingDamage plain = IncomingDamage.split(5, 40, 500, 0, 0);
        assertEquals(5, plain.es(), 1e-9);
        assertEquals(40, plain.life(), 1e-9);
        assertEquals(0, plain.mana(), 1e-9);
        // 超過 100% 當 100%
        assertEquals(0, IncomingDamage.split(0, 50, 500, 250, 0).life(), 1e-9);
    }
}
