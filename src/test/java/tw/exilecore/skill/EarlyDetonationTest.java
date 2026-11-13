package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-12 照站長「再按一次提早炸，傷害按比例」。 */
class EarlyDetonationTest {

    @Test
    void damageScalesFromHalfToFull() {
        assertEquals(0.5, EarlyDetonation.scale(0, 60), 1e-9);
        assertEquals(0.75, EarlyDetonation.scale(30, 60), 1e-9);
        assertEquals(1.0, EarlyDetonation.scale(60, 60), 1e-9);
        assertEquals(1.0, EarlyDetonation.scale(90, 60), 1e-9);
        assertEquals(1.0, EarlyDetonation.scale(5, 0), 1e-9);
    }

    @Test
    void holdingTheKeyIsNotANewPress() {
        assertTrue(EarlyDetonation.fresh(null, 100));
        assertFalse(EarlyDetonation.fresh(99L, 100));   // 按住左鍵：每 tick
        assertFalse(EarlyDetonation.fresh(96L, 100));   // 按住右鍵：每 4 tick
        assertTrue(EarlyDetonation.fresh(90L, 100));
    }

    @Test
    void onlyTheFourSkills() {
        assertEquals(4, EarlyDetonation.SKILLS.size());
        assertTrue(EarlyDetonation.NO_SCALE.contains("chill_crystal_mine"));
        final EarlyDetonation early = EarlyDetonation.create();
        final java.util.UUID id = java.util.UUID.randomUUID();
        assertFalse(early.press(id, "fireball", 10));
        assertTrue(early.press(id, "blast_grenade", 10));
        assertFalse(early.press(id, "blast_grenade", 11));
        assertTrue(early.press(id, "blast_grenade", 30));
    }
}
