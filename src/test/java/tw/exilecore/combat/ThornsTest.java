package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 荊棘（反彈近戰攻擊的物理傷害）、能量護盾回充、怪物招式冷卻的公式。 */
class ThornsTest {

    @Test
    void thornsRangeComesFromTheSheetAndOnlyMeleeTriggersIt() {
        assertNull(Thorns.range(new StatSheet()), "沒有荊棘");
        final StatSheet sheet = new StatSheet()
                .add(Modifier.flat(StatKeys.THORNS_MIN, 20, "胸甲"))
                .add(Modifier.flat(StatKeys.THORNS_MAX, 40, "胸甲"))
                .add(Modifier.flat(StatKeys.THORNS_MAX, 10, "盾"));
        final double[] range = Thorns.range(sheet);
        assertEquals(20, range[0], 1e-9);
        assertEquals(50, range[1], 1e-9);
        final double[] odd = Thorns.range(new StatSheet().add(Modifier.flat(StatKeys.THORNS_MIN, 30, "x")).add(Modifier.flat(StatKeys.THORNS_MAX, 10, "x")));
        assertEquals(10, odd[0], 1e-9, "下限比上限大就夾成上限");
        assertTrue(Thorns.triggersOn(true, Set.of(Tags.ATTACK, Tags.MELEE)));
        assertFalse(Thorns.triggersOn(false, Set.of(Tags.ATTACK, Tags.MELEE)),
                "怪物的普攻一律標成近戰：骷髏的箭、烈焰人的火球打到時 damager 是投射物，不是直接打的，不反彈");
        assertFalse(Thorns.triggersOn(true, Set.of(Tags.ATTACK, Tags.PROJECTILE)), "遠程招式不會被反彈");
        assertFalse(Thorns.triggersOn(true, Set.of(Tags.SPELL, Tags.AREA)), "Boss 的範圍招式不會被反彈");
    }

    @Test
    void thornsHitIsPhysicalScalesWithIncreasesIgnoresAddedDamageAndNeverCrits() {
        final StatSheet player = new StatSheet()
                .setBase(StatKeys.CRIT_MULTI, 150)
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.5, Set.of(Tags.PHYSICAL), "天賦"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.5, Set.of(Tags.THORNS), "天賦"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.SPELL), "法術天賦（荊棘不吃）"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 100, Set.of(Tags.ATTACK, Tags.PHYSICAL), "戒指"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 100, Set.of(Tags.ATTACK, Tags.PHYSICAL), "戒指"))
                .add(new Modifier(StatKeys.CRIT_CHANCE, ModType.INCREASED, 100, Set.of(), "銳星滿到不行"));
        final SkillProfile thorns = Thorns.profile(40, 40);
        assertFalse(thorns.isAttack(), "不擲命中、不會被格擋");
        assertTrue(thorns.tags().contains(Tags.THORNS));
        final StatSheet monster = new StatSheet().setBase(StatKeys.RESIST_MAX, 75);
        for (int seed = 0; seed < 20; seed++) {
            final HitResult result = DamageCalculator.calculate(player, thorns, monster, 0, new Random(seed));
            assertTrue(result.hit());
            assertFalse(result.crit(), "荊棘不會暴擊");
            assertEquals(80, result.dealt().get(DamageType.PHYSICAL), 1e-9, "40 ×（1 + 50% 物理 + 50% 荊棘）");
            assertEquals(0, result.dealt().get(DamageType.FIRE), 1e-9);
        }
        // 怪物的護甲照常減免
        final StatSheet armoured = new StatSheet().setBase(StatKeys.ARMOUR, 400).setBase(StatKeys.RESIST_MAX, 75);
        final HitResult reduced = DamageCalculator.calculate(player, thorns, armoured, 0, new Random(1));
        assertEquals(Formulas.armourReduction(400, 80), 1 - reduced.dealt().get(DamageType.PHYSICAL) / 80, 1e-9);
    }

    @Test
    void energyShieldRechargeSpeedAndDelayFormulas() {
        assertEquals(100, Formulas.esRechargePerSecond(300, 1.0), 1e-9, "基礎：每秒最大值的三分之一");
        assertEquals(140, Formulas.esRechargePerSecond(300, 1.4), 1e-9, "增加 40% 回充速度");
        assertEquals(0, Formulas.esRechargePerSecond(300, -1), 1e-9);
        assertEquals(40, Formulas.esRechargeDelayTicks(1.0), "基礎 2 秒");
        assertEquals(28, Formulas.esRechargeDelayTicks(0.7), "縮短 30%");
        assertEquals(8, Formulas.esRechargeDelayTicks(0.05), "最短剩兩成");
        final StatSheet sheet = new StatSheet()
                .add(Modifier.increased(StatKeys.ES_RECHARGE, 0.4, "腰帶"))
                .add(Modifier.increased(StatKeys.ES_DELAY, -0.3, "項鍊"));
        assertEquals(1.4, sheet.apply(StatKeys.ES_RECHARGE, 1.0, Set.of()), 1e-9);
        assertEquals(28, Formulas.esRechargeDelayTicks(sheet.apply(StatKeys.ES_DELAY, 1.0, Set.of())));
    }

    @Test
    void monsterSkillCooldownsShrinkButNotBelowAQuarter() {
        assertEquals(1.0, Formulas.monsterCooldownFactor(1.0), 1e-9);
        assertEquals(0.7, Formulas.monsterCooldownFactor(new StatSheet().add(Modifier.increased(StatKeys.SKILL_COOLDOWN, -0.3, "地圖"))
                .apply(StatKeys.SKILL_COOLDOWN, 1.0, Set.of())), 1e-9, "地圖詞綴：怪物招式冷卻減少 30%");
        assertEquals(Formulas.MIN_MONSTER_COOLDOWN, Formulas.monsterCooldownFactor(0.05), 1e-9);
    }
}
