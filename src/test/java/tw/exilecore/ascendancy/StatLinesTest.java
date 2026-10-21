package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 昇華天賦的說明跟天賦樹同一種寫法；遊戲不會讀的寫法擋下來。 */
class StatLinesTest {

    static Modifier flat(final String stat, final double value, final String... tags) {
        return new Modifier(stat, ModType.FLAT, value, Set.of(tags), "測試");
    }

    static Modifier inc(final String stat, final double value, final String... tags) {
        return new Modifier(stat, ModType.INCREASED, value, Set.of(tags), "測試");
    }

    static Modifier more(final String stat, final double value, final String... tags) {
        return new Modifier(stat, ModType.MORE, value, Set.of(tags), "測試");
    }

    @Test
    void linesReadLikeThePassiveTree() {
        assertEquals("增加 40% 護甲", StatLines.describe(inc(StatKeys.ARMOUR, 0.4)));
        assertEquals("更多 15% 護甲", StatLines.describe(more(StatKeys.ARMOUR, 0.15)));
        assertEquals("+6% 格擋率", StatLines.describe(flat(StatKeys.BLOCK_CHANCE, 6)));
        assertEquals("+20 最大生命", StatLines.describe(flat(StatKeys.LIFE_MAX, 20)));
        assertEquals("每秒回復 10 生命", StatLines.describe(flat(StatKeys.LIFE_REGEN, 10)));
        assertEquals("增加 30% 魔力回復", StatLines.describe(inc(StatKeys.MANA_REGEN, 0.3)));
        assertEquals("+15% 混沌抗性", StatLines.describe(flat(StatKeys.RESIST_CHAOS, 15)));
        assertEquals("所有抗性上限 +2%", StatLines.describe(flat(StatKeys.RESIST_MAX, 2)));
        assertEquals("增加 25% 近戰物理傷害", StatLines.describe(inc(StatKeys.DAMAGE, 0.25, Tags.PHYSICAL, Tags.MELEE)));
        assertEquals("更多 12% 元素傷害", StatLines.describe(more(StatKeys.DAMAGE, 0.12, Tags.ELEMENTAL)));
        assertEquals("增加 30% 雙手武器傷害", StatLines.describe(inc(StatKeys.DAMAGE, 0.3, Tags.TWO_HAND)));
        assertEquals("使用弓時增加 8% 攻擊速度", StatLines.describe(inc(StatKeys.ATTACK_SPEED, 0.08, Tags.BOW)));
        assertEquals("使用錘時 +15% 暴擊傷害", StatLines.describe(flat(StatKeys.CRIT_MULTI, 15, Tags.MACE)));
        assertEquals("+25% 暴擊傷害", StatLines.describe(flat(StatKeys.CRIT_MULTI, 25)));
        assertEquals("更多 8% 攻擊速度", StatLines.describe(more(StatKeys.ATTACK_SPEED, 0.08)));
        assertEquals("增加 40% 暴擊率", StatLines.describe(inc(StatKeys.CRIT_CHANCE, 0.4)));
        assertEquals("+20 力量", StatLines.describe(flat(StatKeys.STR, 20)));
        assertEquals("增加 8% 移動速度", StatLines.describe(inc(StatKeys.MOVEMENT_SPEED, 0.08)));
        assertEquals("額外 1 個投射物", StatLines.describe(flat(StatKeys.SKILL_PROJECTILES, 1)));
        assertEquals("投射物多穿透 2 個敵人", StatLines.describe(flat(StatKeys.SKILL_PIERCE, 2)));
        assertEquals("減少 20% 魔力消耗", StatLines.describe(inc(StatKeys.SKILL_COST, -0.2)));
        assertEquals("造成的物理傷害有 2% 偷取為生命", StatLines.describe(flat(StatKeys.LEECH_LIFE, 2)));
        assertEquals("物理傷害的 15% 額外附加為冰冷傷害", StatLines.describe(flat(StatKeys.damageGain(tw.exilecore.stats.DamageType.COLD), 15)));
        assertEquals("點燃機率 +15%", StatLines.describe(flat(StatKeys.IGNITE_CHANCE, 15)));
        assertEquals("更多 30% 點燃傷害", StatLines.describe(more(StatKeys.IGNITE_MAGNITUDE, 0.3)));
        assertEquals("25% 機率避免被冰緩", StatLines.describe(flat(StatKeys.AVOID_CHILL, 25)));
        assertEquals("自己身上的元素異常持續時間減少 30%", StatLines.describe(inc(StatKeys.SELF_AILMENT_DURATION, -0.3)));
        assertEquals("增加 12.5% 施法速度", StatLines.describe(inc(StatKeys.CAST_SPEED, 0.125)));
    }

    @Test
    void equalElementalResistsMerge() {
        assertEquals(List.of("+12% 所有元素抗性", "增加 8% 最大生命"), StatLines.describeAll(List.of(
                flat(StatKeys.RESIST_FIRE, 12), flat(StatKeys.RESIST_COLD, 12), flat(StatKeys.RESIST_LIGHTNING, 12),
                inc(StatKeys.LIFE_MAX, 0.08))));
        assertEquals(List.of("+12% 火焰抗性", "+10% 冰冷抗性", "+12% 閃電抗性"), StatLines.describeAll(List.of(
                flat(StatKeys.RESIST_FIRE, 12), flat(StatKeys.RESIST_COLD, 10), flat(StatKeys.RESIST_LIGHTNING, 12))), "不一樣多就分開寫");
        assertEquals(List.of("+12% 火焰抗性", "+12% 冰冷抗性"), StatLines.describeAll(List.of(
                flat(StatKeys.RESIST_FIRE, 12), flat(StatKeys.RESIST_COLD, 12))), "少一種也分開寫");
    }

    @Test
    void writingsTheGameIgnoresAreRejected() {
        assertNull(StatLines.problem(inc(StatKeys.DAMAGE, 0.2, Tags.MELEE, Tags.PHYSICAL)));
        assertNull(StatLines.problem(flat(StatKeys.damageGain(tw.exilecore.stats.DamageType.LIGHTNING), 20)));
        assertNotNull(StatLines.problem(more(StatKeys.MOVEMENT_SPEED, 0.05)), "移動速度只讀 increased");
        assertNotNull(StatLines.problem(inc(StatKeys.RESIST_FIRE, 0.1)), "抗性只讀固定值");
        assertNotNull(StatLines.problem(flat(StatKeys.DAMAGE, 10)), "傷害只能增加或更多");
        assertNotNull(StatLines.problem(more(StatKeys.CRIT_CHANCE, 0.1)), "暴擊率只讀 increased");
        assertNotNull(StatLines.problem(flat(StatKeys.LIFE_REGEN, 5, Tags.FIRE)), "生命回復不能加標籤");
        assertNotNull(StatLines.problem(inc(StatKeys.DAMAGE, 0.1, "holy")), "不認得的標籤");
        // 2026-10：掉落數量、稀有度可以給了（掉落、地圖事件、劫盜都讀角色身上的 increased），但只認 increased
        assertNull(StatLines.problem(inc(StatKeys.DROP_QUANTITY, 0.1)));
        assertNotNull(StatLines.problem(flat(StatKeys.DROP_QUANTITY, 10)), "掉落數量只讀 increased");
        assertNotNull(StatLines.problem(inc("damage.gain.fire", 0.1)), "額外附加是固定值");
        assertNotNull(StatLines.problem(flat(StatKeys.LIFE_MAX, 0)), "0 沒有意義");
        assertTrue(StatLines.stats().contains("damage.gain.cold"));
        assertTrue(StatLines.stats().contains(StatKeys.RESIST_MAX));
    }
    @Test
    void specialEffectsFromUniquesReadLikeTheItem() {
        assertEquals("+1 全部技能等級", StatLines.describe(flat(StatKeys.SKILL_LEVEL, 1)));
        assertEquals("+1 法術技能等級", StatLines.describe(flat(StatKeys.SKILL_LEVEL, 1, Tags.SPELL)));
        assertEquals("+2 火焰法術技能等級", StatLines.describe(flat(StatKeys.SKILL_LEVEL, 2, Tags.SPELL, Tags.FIRE)));
        assertEquals("擊中時 10% 機率造成雙倍傷害", StatLines.describe(flat(StatKeys.DAMAGE_DOUBLE, 10)));
        assertEquals("攻擊每次擊中回復 15 生命", StatLines.describe(flat(StatKeys.LIFE_ON_HIT, 15, Tags.ATTACK)));
        assertEquals("擊殺時回復 4% 最大生命", StatLines.describe(flat(StatKeys.LIFE_ON_KILL, 4)));
        assertEquals("擊殺時回復 5% 最大魔力", StatLines.describe(flat(StatKeys.MANA_ON_KILL, 5)));
        assertEquals("擊殺時回復 3% 最大能量護盾", StatLines.describe(flat(StatKeys.ES_ON_KILL, 3)));
        assertEquals("擊殺時 25% 機率獲得疾速", StatLines.describe(flat(StatKeys.ONSLAUGHT_ON_KILL, 25)));
        assertEquals("你殺死的敵人爆炸，對周圍造成牠最大生命 10% 的火焰傷害", StatLines.describe(flat(StatKeys.KILL_EXPLODE, 10)));
        assertEquals("受到的傷害有 30% 先從魔力扣除", StatLines.describe(flat(StatKeys.DAMAGE_FROM_MANA, 30)));
        assertEquals("受到的傷害有 15% 之後回復為生命", StatLines.describe(flat(StatKeys.LIFE_RECOUP, 15)));
        assertEquals("多連鎖 1 次", StatLines.describe(flat(StatKeys.SKILL_CHAINS, 1)));
        // 昇華與天賦也能用（遊戲會讀的寫法）
        assertNull(StatLines.problem(flat(StatKeys.SKILL_LEVEL, 1, Tags.CHAOS)));
        assertNull(StatLines.problem(flat(StatKeys.LIFE_RECOUP, 10)));
        assertNotNull(StatLines.problem(inc(StatKeys.DAMAGE_DOUBLE, 0.1)), "機率是固定值");
        assertNotNull(StatLines.problem(flat(StatKeys.LIFE_ON_KILL, 3, Tags.SPELL)), "擊殺回復不分技能");
    }

    @Test
    void chargeSourcesReadLikeTheItems() {
        assertEquals("擊殺時 15% 機率獲得一顆怒星", StatLines.describe(flat(StatKeys.chargeChance("frenzy"), 15)));
        assertEquals("暴擊時 10% 機率獲得一顆銳星", StatLines.describe(flat(StatKeys.chargeChance("power"), 10)));
        assertEquals("近戰擊中時 5% 機率獲得一顆磐星", StatLines.describe(flat(StatKeys.chargeOnHit("endurance"), 5, Tags.MELEE)));
        assertEquals("+1 銳星上限", StatLines.describe(flat(StatKeys.chargeMax("power"), 1)));
        assertNull(StatLines.problem(flat(StatKeys.chargeOnHit("frenzy"), 5, Tags.ATTACK)), "昇華、稱號也能給");
        assertNull(StatLines.problem(flat(StatKeys.chargeMax("endurance"), 1)));
        assertNotNull(StatLines.problem(inc(StatKeys.chargeChance("frenzy"), 0.1)), "機率是固定值");
        assertNotNull(StatLines.problem(flat(StatKeys.chargeChance("frenzy"), 5, Tags.MELEE)), "擊殺時不分技能");
        assertNotNull(StatLines.problem(flat(StatKeys.chargeOnHit("frenzy"), 5, Tags.BOW)), "不能限定武器");
        assertNotNull(StatLines.problem(flat(StatKeys.chargeOnHit("frenzy"), 0)), "0 沒有意義");
        assertNotNull(StatLines.problem(flat(StatKeys.chargeOnHit("frenzzy"), 5)), "打錯字的戰星");
        assertNotNull(StatLines.problem(flat(StatKeys.chargeOnHit("frenzy"), 5, Tags.MINION)), "召喚物打的不算");
        assertTrue(StatLines.stats().contains("charge.on_hit.frenzy"));
        // 輔助寶石的說明（技能面板、寶石）也是同一句
        assertEquals("擊中時 20% 機率獲得一顆怒星", tw.exilecore.skill.ModifierText.describe(flat(StatKeys.chargeOnHit("frenzy"), 20)));
        assertEquals("攻擊擊中時 20% 機率獲得一顆怒星",
                tw.exilecore.skill.ModifierText.describe(flat(StatKeys.chargeOnHit("frenzy"), 20, Tags.ATTACK)));
    }

    /** 2026-10-22：額外元素傷害、最大混沌抗性照數值寫（以前招牌天賦寫死 10%／4%，戒指再加也一樣）；負抗性不會變成「+-」。 */
    @Test
    void extraDamageAndMaxResistUseTheRealValue() {
        assertEquals("獲得 18% 傷害為額外的冰冷傷害", StatLines.describe(flat(StatKeys.damageExtra(tw.exilecore.stats.DamageType.COLD), 18)));
        assertEquals("獲得 56.4% 傷害為額外的混沌傷害", StatLines.describe(flat(StatKeys.damageExtra(tw.exilecore.stats.DamageType.CHAOS), 56.4)));
        assertEquals("+13% 最大混沌抗性", StatLines.describe(flat(StatKeys.resistMax(tw.exilecore.stats.DamageType.CHAOS), 13)));
        assertTrue(StatLines.knows("damage.extra.lightning"));
        assertNull(StatLines.problem(flat("damage.extra.cold", 10)), "昇華檔還能用");
        assertNull(StatLines.problem(flat("resist.max.chaos", 10)));
        assertEquals("-33.6% 火焰抗性", tw.exilecore.skill.ModifierText.describe(flat(StatKeys.RESIST_FIRE, -33.6)));
        assertEquals("+20% 火焰抗性", tw.exilecore.skill.ModifierText.describe(flat(StatKeys.RESIST_FIRE, 20)));
    }
}
