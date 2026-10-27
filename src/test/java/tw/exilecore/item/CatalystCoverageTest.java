package tw.exilecore.item;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-20：傳奇飾品上「獲得 x% 傷害為額外的某種傷害」、抗性以外的防禦、每擊殺回復都吃得到對應的催化劑。 */
class CatalystCoverageTest {

    private static Modifier mod(final String stat, final String... tags) {
        return new Modifier(stat, ModType.FLAT, 10, Set.of(tags), "test");
    }

    @Test
    void extraDamageAsElementsOrChaosIsBoosted() {
        assertTrue(Catalyst.ELEMENTAL.matches(mod("damage.extra.cold")), "長冬之戒：額外的冰冷傷害");
        assertTrue(Catalyst.ELEMENTAL.matches(mod("damage.extra.fire")));
        assertTrue(Catalyst.ELEMENTAL.matches(mod("damage.extra.lightning")));
        assertTrue(Catalyst.PHYS_CHAOS.matches(mod("damage.extra.chaos")), "裂痕之戒：額外的混沌傷害");
        assertFalse(Catalyst.ELEMENTAL.matches(mod("damage.extra.chaos")));
        assertFalse(Catalyst.PHYS_CHAOS.matches(mod("damage.extra.cold")));
    }

    @Test
    void ailmentsPenetrationDefencesAndKillsFollowTheirTags() {
        assertTrue(Catalyst.ELEMENTAL.matches(mod("ignite.magnitude")));
        assertTrue(Catalyst.ELEMENTAL.matches(mod("chill.effect")));
        assertTrue(Catalyst.ELEMENTAL.matches(mod(StatKeys.PENETRATION, "cold")));
        assertFalse(Catalyst.ELEMENTAL.matches(mod(StatKeys.PENETRATION, "chaos")));
        assertTrue(Catalyst.PHYS_CHAOS.matches(mod("bleed.chance")));
        assertTrue(Catalyst.PHYS_CHAOS.matches(mod("poison.magnitude")));
        assertTrue(Catalyst.DEFENCE.matches(mod(StatKeys.ES_RECHARGE)));
        assertTrue(Catalyst.DEFENCE.matches(mod("evasion@nohit")), "有條件的版本照本來的屬性算");
        assertTrue(Catalyst.LIFE.matches(mod(StatKeys.LIFE_PER_KILL)));
        assertTrue(Catalyst.MANA.matches(mod(StatKeys.MANA_PER_KILL)), "2026-10-26：魔力改成魔力催化劑（PoE2 的 Neural）；這次之前算生命催化劑");
        assertTrue(Catalyst.ATTACK.matches(mod(StatKeys.ACCURACY)));
        assertFalse(Catalyst.RESIST.matches(mod("avoid.ignite")), "避免點燃不是抗性");
        assertFalse(Catalyst.ELEMENTAL.matches(mod("avoid.ignite")));
    }
}
