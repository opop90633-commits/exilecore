package tw.exilecore.stats;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.unique.VaalKeys;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：穿透下限、暴擊幸運、獻祭之心的瓦爾詞綴（每 100 生命／魔力）。 */
class VaalOffenceStatsTest {

    @Test
    void penetrationMinimumLetsResistGoNegative() {
        assertEquals(-20, DamageCalculator.penetrated(10, 30, -50), 1e-9, "可以穿到負的");
        assertEquals(-50, DamageCalculator.penetrated(10, 80, -50), 1e-9, "最低 -50");
        assertEquals(-60, DamageCalculator.penetrated(-60, 30, -50), 1e-9, "本來就比下限低的不再降");
        assertEquals(0, DamageCalculator.penetrated(10, 30), 1e-9, "沒有下限的照舊到 0");
    }

    @Test
    void penetrationMinimumTakesLowestElementalSource() {
        final StatSheet attacker = new StatSheet();
        assertEquals(0, DamageCalculator.penetrationMinimum(attacker, DamageType.FIRE, Set.of()), 1e-9);
        attacker.add(Modifier.flat(VaalKeys.PENETRATION_MINIMUM, -50, "原型"));
        attacker.add(Modifier.flat(VaalKeys.PENETRATION_MINIMUM, -30, "別的"));
        assertEquals(-50, DamageCalculator.penetrationMinimum(attacker, DamageType.LIGHTNING, Set.of()), 1e-9, "取最低、不相加");
        assertEquals(0, DamageCalculator.penetrationMinimum(attacker, DamageType.CHAOS, Set.of()), 1e-9, "只算元素");
    }

    @Test
    void luckierKeepsHigherPerType() {
        final DamagePacket first = new DamagePacket().set(DamageType.FIRE, 10).set(DamageType.COLD, 30);
        DamageCalculator.luckier(first, new DamagePacket().set(DamageType.FIRE, 20).set(DamageType.COLD, 5));
        assertEquals(20, first.get(DamageType.FIRE), 1e-9);
        assertEquals(30, first.get(DamageType.COLD), 1e-9);
    }

    @Test
    void rathpithVaalScalingOnlyNonChannellingSpells() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.LIFE_MAX, 450).setBase(StatKeys.MANA_MAX, 320);
        sheet.add(Modifier.increased(VaalKeys.AILMENT_MAGNITUDE_SPELL_PER_100_LIFE, 0.03, "獻祭之心"));
        sheet.add(Modifier.increased(VaalKeys.CRIT_SPELL_PER_100_MANA, 0.03, "獻祭之心"));
        sheet.add(Modifier.increased(VaalKeys.DAMAGE_NONCHANNEL_SPELL_PER_100_MANA, 0.06, "獻祭之心"));
        AttributeScalingKeys.applyDerived(sheet, AttributeScalingKeys.Gear.NONE, AttributeScalingKeys.Supports.NONE);
        final Set<String> spell = Set.of(Tags.SPELL, Tags.NON_CHANNELLING);
        assertEquals(0.12, sheet.increased(StatKeys.IGNITE_MAGNITUDE, spell), 1e-9, "450 生命 → 4 份 3%");
        assertEquals(0.12, sheet.increased(StatKeys.SHOCK_EFFECT, spell), 1e-9);
        assertEquals(0.09, sheet.increased(StatKeys.CRIT_CHANCE, spell), 1e-9, "320 魔力 → 3 份 3%");
        assertEquals(0.18, sheet.increased(StatKeys.DAMAGE, spell), 1e-9, "320 魔力 → 3 份 6%");
        assertEquals(0, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.SPELL)), 1e-9, "引導法術不算");
        assertEquals(0, sheet.increased(StatKeys.IGNITE_MAGNITUDE, Set.of(Tags.ATTACK)), 1e-9, "攻擊不算");
    }
}
