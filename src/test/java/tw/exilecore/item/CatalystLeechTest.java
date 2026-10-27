package tw.exilecore.item;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-19：飾品上「攻擊造成的物理傷害偷取為生命／魔力」吃攻擊催化劑與生命催化劑（PoE2 的標籤：攻擊、生命）。 */
class CatalystLeechTest {

    private static Modifier mod(final String stat, final String... tags) {
        return new Modifier(stat, ModType.FLAT, 7.5, Set.of(tags), "test");
    }

    @Test
    void attackLeechIsBoostedByAttackAndLifeCatalysts() {
        final Modifier life = mod(StatKeys.LEECH_LIFE, "attack");
        final Modifier mana = mod(StatKeys.LEECH_MANA, "attack");
        assertTrue(Catalyst.ATTACK.matches(life));
        assertTrue(Catalyst.ATTACK.matches(mana));
        assertTrue(Catalyst.LIFE.matches(life));
        // 2026-10-26 照 PoE2：魔力的偷取吃魔力催化劑（Neural）；這次之前生命催化劑也算魔力
        assertFalse(Catalyst.LIFE.matches(mana));
        assertTrue(Catalyst.MANA.matches(mana));
        assertFalse(Catalyst.RESIST.matches(life));
        assertFalse(Catalyst.CASTER.matches(life), "攻擊的偷取不吃法術催化劑");
        assertFalse(Catalyst.ATTACK.matches(mod(StatKeys.LEECH_LIFE)), "沒有攻擊標籤的偷取不吃攻擊催化劑");
    }
}
