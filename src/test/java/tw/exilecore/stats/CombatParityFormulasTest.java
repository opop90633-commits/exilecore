package tw.exilecore.stats;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.Leech;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-27：戰鬥公式照 PoE2 0.5（Path of Building PoE2 的 CalcDefence／CalcOffence／CalcSetup）的數字例子。 */
class CombatParityFormulasTest {

    @Test
    void monsterHitChanceUsesTheRefMonsterFormula() {
        // 1 − 0.95 × 1000 ÷ (1000 + 4 × 490) = 67.9% 被打中 → 閃 32%（舊的玩家打怪公式只閃 23%）
        assertEquals(0.32, 1 - Formulas.monsterHitChance(490, 1000), 0.005);
        assertEquals(0.3209, 1 - Formulas.monsterHitChance(490, 1000), 1e-4);
        assertEquals(0.23, 1 - Formulas.hitChance(490, 1000), 0.01, "玩家打怪的公式不變");
        assertEquals(1.0, Formulas.monsterHitChance(490, 0), 1e-9);
        assertEquals(0.05, Formulas.monsterHitChance(1, 1e9), 1e-6, "閃避最多 95%");
    }

    @Test
    void deflectChanceUsesTheRefFormula() {
        // 不被偏斜 = 490 ÷ (490 + 120) × 150 − 50 = 70.49% → 偏斜約 29.5%
        assertEquals(0.295, Formulas.deflectChance(490, 1000), 0.001);
        assertEquals(0.295, DamageCalculator.deflectChance(490, 1000), 0.001);
        assertEquals(0, Formulas.deflectChance(490, 0), 1e-9);
        assertEquals(0.95, Formulas.deflectChance(10, 1_000_000), 1e-9, "偏斜最多 95%");
        assertEquals(0, Formulas.deflectChance(1_000_000, 10), 1e-5, "命中遠高於偏斜值：幾乎 0");
    }

    @Test
    void playerSideDefendersAreHitWithTheMonsterFormula() {
        final StatSheet monster = new StatSheet().setBase(StatKeys.EVASION, 1000);
        final StatSheet player = new StatSheet().setBase(StatKeys.EVASION, 1000).setBase(DamageCalculator.FLAG_PLAYER_SIDE, 1);
        assertEquals(Formulas.hitChance(490, 1000), DamageCalculator.hitChanceAgainst(monster, 490, 1000), 1e-9);
        assertEquals(Formulas.monsterHitChance(490, 1000), DamageCalculator.hitChanceAgainst(player, 490, 1000), 1e-9);
    }

    @Test
    void conversionHappensOnBaseAndOnlyFinalTypeModifiersApply() {
        // 100 物理、50% 轉火焰、物理 25% 額外獲得為冰冷；增加 100% 物理、50% 火焰、200% 冰冷
        final StatSheet attacker = new StatSheet()
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 100, Set.of(Tags.PHYSICAL), "武器"))
                .add(Modifier.flat(StatKeys.damageGain(DamageType.COLD), 25, "引冰"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.PHYSICAL), "物理"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.5, Set.of(Tags.FIRE), "火焰"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 2.0, Set.of(Tags.COLD), "冰冷"));
        final SkillProfile skill = new SkillProfile("x", Set.of(Tags.ATTACK), 1.0, null, null, java.util.Map.of(DamageType.FIRE, 0.5));
        final DamagePacket out = DamageCalculator.expectedOutgoing(attacker, skill);
        assertEquals(50 * 2.0, out.get(DamageType.PHYSICAL), 1e-9, "剩下 50 物理 × 增加 100%");
        assertEquals(50 * 1.5, out.get(DamageType.FIRE), 1e-9, "轉過去的 50 只吃火焰的 50%");
        assertEquals(50 * 0.25 * 3.0, out.get(DamageType.COLD), 1e-9, "物理的額外獲得用轉換後的 50 物理算，只吃冰冷的 200%");
    }

    @Test
    void monstersCapPhysicalReductionAt75AndPlayersAt90() {
        final SkillProfile hit = new SkillProfile("x", Set.of(Tags.SPELL, Tags.PHYSICAL), 1.0, new DamagePacket().set(DamageType.PHYSICAL, 100));
        final StatSheet monster = new StatSheet().setBase(StatKeys.ARMOUR, 1_000_000).setBase(StatKeys.RESIST_MAX, 75);
        final StatSheet player = new StatSheet().setBase(StatKeys.ARMOUR, 1_000_000).setBase(StatKeys.RESIST_MAX, 75)
                .setBase(DamageCalculator.FLAG_PLAYER_SIDE, 1);
        assertEquals(25, DamageCalculator.calculate(new StatSheet(), hit, monster, 0, new Random(1)).dealt().get(DamageType.PHYSICAL), 1e-6);
        assertEquals(10, DamageCalculator.calculate(new StatSheet(), hit, player, 0, new Random(1)).dealt().get(DamageType.PHYSICAL), 1e-6);
    }

    @Test
    void leechInstancesAreCappedAndRecoverOverOneSecondUpToTwentyPercent() {
        final Leech leech = new Leech();
        // 一份最多最大值的 10%
        assertEquals(100, leech.addHit(500, 1000), 1e-9);
        assertEquals(50, leech.tick(0.5, 1000, 1.0), 1e-9, "1 秒回完：半秒回一半");
        assertEquals(50, leech.tick(1.0, 1000, 1.0), 1e-9);
        assertEquals(0, leech.pending(), 1e-9);
        // 三份同時：想回 300/秒，上限每秒 20% = 200
        leech.addHit(100, 1000);
        leech.addHit(100, 1000);
        leech.addHit(100, 1000);
        assertEquals(200, leech.tick(1.0, 1000, 1.0), 1e-9);
        assertEquals(100, leech.pending(), 1e-9, "超過上限的放慢，不會不見");
        assertEquals(100, leech.tick(1.0, 1000, 1.0), 1e-9);
        // 不是擊中的「慢慢回」：每秒 10%，沒有一份的上限
        leech.addSlow(300, 1000);
        assertEquals(100, leech.tick(1.0, 1000, 1.0), 1e-9);
    }
}
