package tw.exilecore.stats;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02 照站長（PoB2 CalcDefence 的 EHP、Maximum Hit Taken、Number of Hits）：EffectiveHitPool 的手算對照。
 * 減傷、扣護盾、心靈昇華都是遊戲同一套純函式（DamageCalculator、IncomingDamage），最後一組直接拿 DamageCalculator.calculate 比。
 */
class EffectiveHitPoolTest {

    /** 玩家這一邊的空白防禦方（抗性上限 75、物理減免上限 90%）。 */
    private static StatSheet player() {
        return new StatSheet().setBase(StatKeys.RESIST_MAX, 75).setBase(DamageCalculator.FLAG_PLAYER_SIDE, 1);
    }

    private static EffectiveHitPool.Pools life(final double life) {
        return new EffectiveHitPool.Pools(life, 0, 0, 0);
    }

    /** 不暴擊、不格擋的敵人（命中很高，沒閃避的防禦方一定被打中）。 */
    private static EffectiveHitPool.Enemy enemy(final DamagePacket damage) {
        return new EffectiveHitPool.Enemy(damage, 0, 1000, 0, 30, 0.5);
    }

    private static DamagePacket hit(final DamageType type, final double amount) {
        return new DamagePacket().set(type, amount);
    }

    @Test
    void armourIsIteratedAgainstTheSizeOfTheHit() {
        // 護甲 10000、生命 1000：受到 = X × 10X ÷ (10000 + 10X) = 1000 → X² − 1000X − 10⁶ = 0 → X = 500 × (1 + √5) ≈ 1618.03
        final StatSheet d = player().setBase(StatKeys.ARMOUR, 10000);
        final double max = EffectiveHitPool.maxHit(d, DamageType.PHYSICAL, life(1000), null);
        assertEquals(500 * (1 + Math.sqrt(5)), max, 0.01);
        // 小的一下護甲擋得多：100 → 10000 ÷ 11000 擋 90.9%，夾到上限 90% → 受到 10
        assertEquals(10, EffectiveHitPool.taken(d, hit(DamageType.PHYSICAL, 100), new StatSheet()).get(DamageType.PHYSICAL), 1e-9);
        // 1000 → 10000 ÷ 20000 擋 50% → 500
        assertEquals(500, EffectiveHitPool.taken(d, hit(DamageType.PHYSICAL, 1000), new StatSheet()).get(DamageType.PHYSICAL), 1e-9);
    }

    @Test
    void resistanceIsCappedAndPenetrated() {
        final StatSheet d = player().setBase(StatKeys.RESIST_FIRE, 90); // 夾到 75
        assertEquals(250, EffectiveHitPool.taken(d, hit(DamageType.FIRE, 1000), new StatSheet()).get(DamageType.FIRE), 1e-9);
        assertEquals(4000, EffectiveHitPool.maxHit(d, DamageType.FIRE, life(1000), null), 0.01);
        // +5% 火焰抗性上限：80% → 200、最多吃 5000
        final StatSheet capped = player().setBase(StatKeys.RESIST_FIRE, 90).add(Modifier.flat(StatKeys.resistMax(DamageType.FIRE), 5, "測"));
        assertEquals(5000, EffectiveHitPool.maxHit(capped, DamageType.FIRE, life(1000), null), 0.01);
        // 超級巔峰 Boss 的 8% 元素穿透：75 → 67 → 330
        final EffectiveHitPool.Enemy uber = new EffectiveHitPool.Enemy(hit(DamageType.FIRE, 1000), 8, 1000, 0, 30, 0.5);
        assertEquals(330, EffectiveHitPool.taken(d, hit(DamageType.FIRE, 1000), EffectiveHitPool.enemySheet(uber)).get(DamageType.FIRE), 1e-9);
    }

    @Test
    void mindOverMatterSharesTheLifePartWithMana() {
        // 生命 1000、魔力 500、心靈昇華 40%：每下 100 → 魔力 40、生命 60；12 下後魔力剩 20、生命 280；
        // 第 13 下魔力 20、生命 80（剩 200）；再 2 下死：15 下 ＝ 1500（生命＋魔力）
        final StatSheet d = player().add(Modifier.flat(StatKeys.DAMAGE_FROM_MANA, 40, "測"));
        final EffectiveHitPool.Pools pools = new EffectiveHitPool.Pools(1000, 0, 500, 0);
        assertEquals(15, EffectiveHitPool.hitsToDie(d, hit(DamageType.PHYSICAL, 100), pools), 1e-6);
        final EffectiveHitPool.Result r = EffectiveHitPool.compute(d, pools, enemy(hit(DamageType.PHYSICAL, 100)));
        assertEquals(1500, r.ehp(), 1e-3);
        // 最多吃得下一下：X ≤ 1250 時魔力扣 0.4X；超過時魔力 500 扣完，生命扣 X − 500 < 1000 → 1500
        assertEquals(1500, EffectiveHitPool.maxHit(d, DamageType.PHYSICAL, pools, null), 0.01);
    }

    @Test
    void chaosInoculationHasOneLifeAndIsImmuneToChaos() {
        final StatSheet d = player().setBase(StatKeys.FLAG_CHAOS_IMMUNE, 1);
        final EffectiveHitPool.Pools pools = new EffectiveHitPool.Pools(1, 2000, 0, 0);
        assertTrue(Double.isNaN(EffectiveHitPool.maxHit(d, DamageType.CHAOS, pools, null)), "混沌免疫");
        assertTrue(Double.isInfinite(EffectiveHitPool.compute(d, pools, enemy(hit(DamageType.CHAOS, 500))).ehp()), "全混沌打不死");
        // 物理 500：4 下打掉護盾 2000，第 5 下只要 1/500 就死（生命 1）→ 4.002 下
        assertEquals(4.002, EffectiveHitPool.hitsToDie(d, hit(DamageType.PHYSICAL, 500), pools), 1e-6);
        assertEquals(2001, EffectiveHitPool.maxHit(d, DamageType.PHYSICAL, pools, null), 0.01);
    }

    @Test
    void chaosDealsDoubleToEnergyShieldAndBypassGoesStraightToLife() {
        // PoE2：混沌對能量護盾兩倍（不是穿過護盾）：護盾 1000 只擋 500 混沌，再加生命 1000 → 1500
        final StatSheet d = player();
        final EffectiveHitPool.Pools pools = new EffectiveHitPool.Pools(1000, 1000, 0, 0);
        assertEquals(1500, EffectiveHitPool.maxHit(d, DamageType.CHAOS, pools, null), 0.01);
        assertEquals(2000, EffectiveHitPool.maxHit(d, DamageType.FIRE, pools, null), 0.01);
        // 受到的傷害 50% 跳過能量護盾：一半直接扣生命 → 0.5X < 1000 → 2000（護盾還剩）
        final StatSheet bypass = player().add(Modifier.flat(TreeEffectKeys.ES_BYPASS, 50, "測"));
        assertEquals(2000, EffectiveHitPool.maxHit(bypass, DamageType.PHYSICAL, new EffectiveHitPool.Pools(1000, 3000, 0, 0), null), 0.01);
    }

    @Test
    void guardAbsorbsBeforeLife() {
        // 守護 300：前 3 下全吃掉，之後 10 下打掉生命 1000 → 13 下
        assertEquals(13, EffectiveHitPool.hitsToDie(player(), hit(DamageType.PHYSICAL, 100), new EffectiveHitPool.Pools(1000, 0, 0, 300)), 1e-6);
        assertEquals(1300, EffectiveHitPool.maxHit(player(), DamageType.PHYSICAL, new EffectiveHitPool.Pools(1000, 0, 0, 300), null), 0.01);
    }

    @Test
    void avoidanceIsFoldedIntoTheNumberOfHits() {
        // 閃避 4000 對命中 1000：被打中 1 − 0.95 × 4000 ÷ 8000 = 52.5%；格擋 40% 只擋攻擊（一半）→ 每一下平均受到 80%
        // 生命 1000、每下 100：打中 1000 ÷ 80 = 12.5 下 → 12.5 ÷ 0.525 = 23.81 下 → EHP 2380.95
        final StatSheet d = player().setBase(StatKeys.EVASION, 4000).setBase(StatKeys.BLOCK_CHANCE, 40);
        final EffectiveHitPool.Result r = EffectiveHitPool.compute(d, life(1000), enemy(hit(DamageType.PHYSICAL, 100)));
        assertEquals(0.525, r.hitChance(), 1e-9);
        assertEquals(0.8, r.damageChance(), 1e-9);
        assertEquals(12.5, r.damagingHits(), 1e-6);
        assertEquals(12.5 / 0.525, r.hits(), 1e-6);
        assertEquals(1250 / 0.525, r.ehp(), 1e-3);
        // 偏斜 1000 對命中 490：不被偏斜 = 490 ÷ 610 × 150 − 50 = 70.49% → 偏斜 29.51%，偏斜的少 40%
        final StatSheet deflect = player().setBase(StatKeys.DEFLECTION, 1000);
        final double chance = 1 - (490.0 / 610 * 150 - 50) / 100;
        final EffectiveHitPool.Enemy e = new EffectiveHitPool.Enemy(hit(DamageType.PHYSICAL, 100), 0, 490, 0, 30, 0.5);
        assertEquals(1 - chance * 0.4, EffectiveHitPool.damageChance(deflect, e), 1e-9);
    }

    @Test
    void enemyCritIsAnExpectedMultiplier() {
        // 怪物暴擊 5%、暴擊加成 30%：1 + 0.05 × 0.3 = 1.015；「擊中你的暴擊傷害加成減少 50%」→ 1.0075
        final EffectiveHitPool.Enemy e = new EffectiveHitPool.Enemy(hit(DamageType.PHYSICAL, 100), 0, 1000, 0.05, 30, 0.5);
        assertEquals(1.015, EffectiveHitPool.critEffect(player(), e), 1e-9);
        final StatSheet less = player().add(Modifier.increased(StatKeys.CRIT_BONUS_TAKEN, -0.5, "測"));
        assertEquals(1.0075, EffectiveHitPool.critEffect(less, e), 1e-9);
        // EHP 照暴擊前的原始傷害算（PoB2 totalEnemyDamageIn）：生命 1015、每下 100 × 1.015 → 10 下 → 1000
        assertEquals(1000, EffectiveHitPool.compute(player(), life(1015), e).ehp(), 1e-3);
    }

    @Test
    void pob2DefaultEnemyAndLevels() {
        // 巔峰 Boss：五種加起來 ＝ 傷害表 × 12；物理、火、冰、電各 1、混沌 0.4（÷ 4.4）
        final EffectiveHitPool.Enemy e = EffectiveHitPool.pob2Enemy(EffectiveHitPool.Tier.PINNACLE, 100, 1000, null);
        assertEquals(1200, e.total(), 1e-9);
        assertEquals(1200 / 4.4, e.damage().get(DamageType.PHYSICAL), 1e-9);
        assertEquals(1200 / 4.4 * 0.4, e.damage().get(DamageType.CHAOS), 1e-9);
        assertEquals(3, e.penetration(), 1e-9);
        assertEquals(1.5, EffectiveHitPool.Tier.NORMAL.totalMultiplier(), 1e-9);
        assertEquals(6, EffectiveHitPool.Tier.BOSS.totalMultiplier(), 1e-9);
        assertEquals(15, EffectiveHitPool.Tier.UBER.totalMultiplier(), 1e-9);
        // 全物理
        final EffectiveHitPool.Enemy phys = EffectiveHitPool.pob2Enemy(EffectiveHitPool.Tier.NORMAL, 100, 1000, new double[] {1, 0, 0, 0, 0});
        assertEquals(150, phys.damage().get(DamageType.PHYSICAL), 1e-9);
        assertEquals(0, phys.damage().get(DamageType.FIRE), 1e-9);
        // 等級：一般照角色等級，巔峰以上至少 T16
        assertEquals(40, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.BOSS, 0, 40, 84, 100));
        assertEquals(87, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.PINNACLE, 0, 40, 84, 100), "終局 Boss：最高階＋3");
        assertEquals(91, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.UBER, 0, 40, 84, 100), "極境：最高階＋7");
        assertEquals(92, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.APEX, 0, 40, 84, 100), "巔峰：最高階＋3＋5");
        assertEquals(84, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.PINNACLE, 60, 40, 84, 100));
        assertEquals(90, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.UBER, 90, 40, 84, 100));
        assertEquals(100, EffectiveHitPool.enemyLevel(EffectiveHitPool.Tier.NORMAL, 150, 40, 84, 100));
    }

    @Test
    void sameNumbersAsTheGamesOwnHit() {
        // 遊戲打一下（DamageCalculator.calculate，必中、不暴擊）：減免後與扣護盾、生命跟 EHP 用的一模一樣
        final StatSheet attacker = new StatSheet()
                .setBase(StatKeys.ACCURACY, 1_000_000)
                .setBase(StatKeys.CRIT_MULTI, 130)
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 300, Set.of(Tags.PHYSICAL), "怪"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 300, Set.of(Tags.PHYSICAL), "怪"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 200, Set.of(Tags.ATTACK, Tags.CHAOS), "怪"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 200, Set.of(Tags.ATTACK, Tags.CHAOS), "怪"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1, "怪"))
                .add(Modifier.flat(StatKeys.WEAPON_CRIT, 0, "怪"));
        final StatSheet d = player().setBase(StatKeys.ARMOUR, 3000).setBase(StatKeys.RESIST_CHAOS, 20)
                .add(Modifier.flat(TreeEffectKeys.ES_BYPASS, 10, "測"));
        final HitResult game = DamageCalculator.calculate(attacker, SkillProfile.defaultAttack(Set.of()), d, 250, new Random(7));
        assertTrue(game.hit());
        final DamagePacket mine = EffectiveHitPool.taken(d, game.outgoing(), new StatSheet());
        for (final DamageType type : DamageType.values()) {
            assertEquals(game.dealt().get(type), mine.get(type), 1e-9, type.name());
        }
        final double[] after = EffectiveHitPool.afterHit(d, mine, new double[] {5000, 250, 0, 0});
        assertEquals(game.lifeDamage(), 5000 - after[0], 1e-9);
        assertEquals(game.esDamage(), 250 - after[1], 1e-9);
    }
}
