package tw.exilecore.stats;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.PlayerState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：「最近…」的條件（PoE2 recently）。 */
class RecentlyTest {

    @Test
    void parsesConditionsAndNegations() {
        assertNull(Recently.condition("damage"));
        assertEquals(new Recently.Condition(Recently.HIT, false), Recently.condition("evasion@hit"));
        assertEquals(new Recently.Condition(Recently.HIT, true), Recently.condition("evasion@nohit"));
        assertEquals(new Recently.Condition(Recently.PROJECTILE_HIT, false), Recently.condition("damage@projhit"));
        assertEquals(new Recently.Condition(Recently.STUN, true), Recently.condition("stun.threshold@nostun"));
        assertEquals(new Recently.Condition(Recently.LIFE_FLASK, false), Recently.condition("life.regen@lifeflask"));
        assertNull(Recently.condition("damage@tuesday"), "不認得的條件");
        assertEquals("damage", Recently.stat("damage@projhit"));
        assertEquals("damage", Recently.stat("damage"));
        assertTrue(Recently.hasCondition("damage@projhit"));
        assertFalse(Recently.hasCondition("damage"));
        assertEquals("@nohit", Recently.condition("x@nohit").key());
        assertEquals(StatKeys.IF_HIT, Recently.condition("x@hit").key());
        assertEquals("最近用投射物攻擊擊中過", Recently.describe(Recently.condition("x@projhit")));
        assertEquals("最近沒有被重暈過", Recently.describe(Recently.condition("x@nostun")));
    }

    @Test
    void stateRemembersEventsForFourSeconds() {
        final PlayerState state = new PlayerState(java.util.UUID.randomUUID());
        assertFalse(state.recently(Recently.DODGE, 1000));
        state.markRecent(Recently.DODGE, 1000);
        assertTrue(state.recently(Recently.DODGE, 1000 + StatKeys.RECENTLY_TICKS));
        assertFalse(state.recently(Recently.DODGE, 1000 + StatKeys.RECENTLY_TICKS + 1));
        state.setLastHitTick(2000);
        assertEquals(2000, state.lastHitTick());
        assertTrue(state.recently(Recently.HIT, 2040));
        assertFalse(state.hitConditions());
        state.setConditionsEvaluated(java.util.Map.of("@hit", true));
        assertTrue(state.hitConditions());
    }
}
