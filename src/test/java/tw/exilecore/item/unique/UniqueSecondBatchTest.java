package tw.exilecore.item.unique;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照 PoE2 0.5 第二批傳奇的新機制（純函式的部分）。 */
class UniqueSecondBatchTest {

    private static SkillProfile spell(final DamageType type, final double amount) {
        return new SkillProfile("test", Set.of(Tags.SPELL), 1.0, new DamagePacket().add(type, amount));
    }

    @Test
    void elementalConversionMovesTheBaseDamage() {
        // 濁源指環：元素傷害 100% 轉換為混沌
        final StatSheet sin = new StatSheet();
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
            sin.add(Modifier.flat(UniqueStats.convertFrom(type, DamageType.CHAOS), 100, "test"));
        }
        final DamagePacket fire = DamageCalculator.expectedOutgoing(sin, spell(DamageType.FIRE, 100));
        assertEquals(0, fire.get(DamageType.FIRE), 1e-9);
        assertEquals(100, fire.get(DamageType.CHAOS), 1e-6);
        // 轉過去的只吃新類型的修飾器：增加火焰傷害不算，增加混沌傷害算
        sin.add(Modifier.increased(StatKeys.DAMAGE, 1.0, "test").withTags(Set.of(Tags.FIRE)));
        sin.add(Modifier.increased(StatKeys.DAMAGE, 0.5, "test").withTags(Set.of(Tags.CHAOS)));
        assertEquals(150, DamageCalculator.expectedOutgoing(sin, spell(DamageType.FIRE, 100)).get(DamageType.CHAOS), 1e-6);
        // 藍焰護腕＋兄弟低語戒：火轉冰、冰轉電，轉過去的不會再轉一次
        final StatSheet chain = new StatSheet()
                .add(Modifier.flat(UniqueStats.convertFrom(DamageType.FIRE, DamageType.COLD), 100, "test"))
                .add(Modifier.flat(UniqueStats.convertFrom(DamageType.COLD, DamageType.LIGHTNING), 100, "test"));
        final double[] base = new double[DamageType.values().length];
        base[DamageType.FIRE.ordinal()] = 100;
        base[DamageType.COLD.ordinal()] = 40;
        UniqueHits.convertElements(base, chain, spell(DamageType.FIRE, 0));
        assertEquals(0, base[DamageType.FIRE.ordinal()], 1e-9);
        assertEquals(100, base[DamageType.COLD.ordinal()], 1e-9);
        assertEquals(40, base[DamageType.LIGHTNING.ordinal()], 1e-9);
    }

    @Test
    void cooldownEvadeAndWeaponCrit() {
        final StatSheet sheet = new StatSheet().add(Modifier.flat(UniqueStats.COOLDOWN_FLAT, -2, "逆漏法袍"));
        assertEquals(4, UniqueHits.cooldown(6, sheet, Set.of()), 1e-9);
        assertEquals(UniqueStats.COOLDOWN_FLAT_MIN, UniqueHits.cooldown(1, sheet, Set.of()), 1e-9, "最少 0.1 秒");
        assertEquals(0, UniqueHits.cooldown(0, sheet, Set.of()), 1e-9, "沒有冷卻的技能不會變成有冷卻");
        final StatSheet vigilant = new StatSheet().add(Modifier.flat(UniqueStats.EVADE_MAX, 50, "警望指環"));
        assertEquals(0.5, UniqueHits.evadeCap(vigilant, 0.2), 1e-9);
        assertEquals(0.8, UniqueHits.evadeCap(vigilant, 0.8), 1e-9);
        final StatSheet northpaw = new StatSheet().add(Modifier.flat(UniqueStats.CRIT_WEAPON_BASE, 7, "北爪護腕"));
        assertEquals(7, UniqueHits.weaponCrit(northpaw, 5), 1e-9);
        assertEquals(0, UniqueHits.weaponCrit(northpaw, 0), 1e-9, "基礎暴擊率 0 的不改");
        assertEquals(5, UniqueHits.weaponCrit(new StatSheet(), 5), 1e-9);
    }

    @Test
    void critIgnoresOnlyTheListedPositiveResistance() {
        final StatSheet choir = new StatSheet().add(Modifier.flat(UniqueStats.CRIT_IGNORE_RESIST, 1, "雷頌墜").withTags(Set.of(Tags.LIGHTNING)));
        final StatSheet monster = new StatSheet()
                .add(Modifier.flat(StatKeys.RESIST_LIGHTNING, 40, "test"))
                .add(Modifier.flat(StatKeys.RESIST_FIRE, 40, "test"));
        final StatSheet ignored = UniqueHits.critIgnore(monster, choir);
        assertEquals(0, ignored.get(StatKeys.RESIST_LIGHTNING), 1e-9);
        assertEquals(40, ignored.get(StatKeys.RESIST_FIRE), 1e-9);
        final StatSheet negative = new StatSheet().add(Modifier.flat(StatKeys.RESIST_LIGHTNING, -20, "test"));
        assertEquals(-20, UniqueHits.critIgnore(negative, choir).get(StatKeys.RESIST_LIGHTNING), 1e-9, "負的抗性照算");
    }

    @Test
    void derivedFromLifeManaChargesAndAttributes() {
        final StatSheet sheet = new StatSheet()
                .add(Modifier.flat(StatKeys.LIFE_MAX, 1000, "test"))
                .add(Modifier.flat(UniqueStats.ES_FROM_LIFE, 10, "冷蔑金冠"))
                .add(Modifier.flat(StatKeys.DEX, 45, "test"))
                .add(Modifier.flat(UniqueStats.PER_DEX_ATTACK_SPEED, 0.5, "知行捲布"))
                .add(Modifier.flat(UniqueStats.MOVEMENT_PER_FRENZY, 5, "暗芒戰靴"));
        final StatSheet derived = new StatSheet().addAll(UniqueDerived.second(sheet, id -> "frenzy".equals(id) ? 3 : 0));
        assertEquals(100, derived.flat(StatKeys.ES_MAX, Set.of()), 1e-9, "最大生命 1000 的 10%");
        assertEquals(0.02, derived.increased(StatKeys.ATTACK_SPEED, Set.of()), 1e-9, "敏捷 45：每 20 點 1%，算 4 個 10 點 × 0.5%");
        assertEquals(0.15, derived.increased(StatKeys.MOVEMENT_SPEED, Set.of()), 1e-9, "三顆怒星 × 5%");
    }

    @Test
    void ringsReflectAndAmplify() {
        final List<Modifier> mirror = List.of(Modifier.flat(UniqueStats.RING_REFLECT, 1, "映鏡指環"));
        final List<Modifier> other = List.of(Modifier.flat(StatKeys.LIFE_MAX, 50, "戒指"));
        final StatSheet none = new StatSheet();
        final List<Modifier> reflected = UniqueDerived.rings(none, List.of(mirror, other));
        assertEquals(50, new StatSheet().addAll(reflected).flat(StatKeys.LIFE_MAX, Set.of()), 1e-9, "另一枚戒指再算一次");
        assertTrue(UniqueDerived.rings(none, List.of(mirror, mirror)).isEmpty(), "兩枚都是映鏡：都不算");
        // 巧匠束帶：左手 +20%、右手 +30%
        final StatSheet belt = new StatSheet()
                .add(Modifier.increased(UniqueStats.RING_EFFECT_LEFT, 0.2, "巧匠束帶"))
                .add(Modifier.increased(UniqueStats.RING_EFFECT_RIGHT, 0.3, "巧匠束帶"));
        final List<Modifier> amplified = UniqueDerived.rings(belt, List.of(other, List.of(Modifier.flat(StatKeys.LIFE_MAX, 100, "戒指"))));
        assertEquals(50 * 0.2 + 100 * 0.3, new StatSheet().addAll(amplified).flat(StatKeys.LIFE_MAX, Set.of()), 1e-9);
    }
}
