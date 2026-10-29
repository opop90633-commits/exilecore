package tw.exilecore.item.unique;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照 PoE2 Crown of Eyes（百目冠）：法術傷害的增加與減少也套用在攻擊上。 */
class SpellDamageToAttacksTest {

    @Test
    void copiesIncreasedSpellDamageToAttacks() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.8, Set.of(Tags.SPELL), "天賦"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.3, Set.of(Tags.SPELL, Tags.FIRE), "戒指"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, -0.1, Set.of(Tags.SPELL), "減少"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.5, Set.of(Tags.SPELL), "更多不算"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.2, Set.of(Tags.ATTACK), "攻擊本來的"));
        assertTrue(UniqueStats.spellDamageToAttacks(sheet).isEmpty(), "沒戴就沒有");
        sheet.add(Modifier.flat(UniqueStats.SPELL_DAMAGE_TO_ATTACKS, 1, "百目冠"));
        final List<Modifier> copies = UniqueStats.spellDamageToAttacks(sheet);
        assertEquals(3, copies.size());
        assertTrue(copies.stream().allMatch(m -> m.type() == ModType.INCREASED && m.tags().contains(Tags.ATTACK) && !m.tags().contains(Tags.SPELL)));
        assertTrue(copies.stream().anyMatch(m -> m.tags().equals(Set.of(Tags.ATTACK, Tags.FIRE)) && Math.abs(m.value() - 0.3) < 1e-9));
        sheet.addAll(copies);
        assertEquals(0.8 + 0.3 - 0.1 + 0.2, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.FIRE)), 1e-9);
    }
}
