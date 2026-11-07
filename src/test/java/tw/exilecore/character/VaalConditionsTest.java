package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.unique.VaalKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.TreeConditions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：低血門檻、魔力低也算低血、被包圍少幾隻、偷取溢出到護盾、被擊中失去的生命。 */
class VaalConditionsTest {

    @Test
    void lowLifeThresholds() {
        assertTrue(ConditionTracker.lowLife(0.35, TreeConditions.LOW_FRACTION, 1, 0));
        assertFalse(ConditionTracker.lowLife(0.5, TreeConditions.LOW_FRACTION, 1, 0));
        assertTrue(ConditionTracker.lowLife(0.75, 0.75, 1, 0), "門檻改成 75%");
        assertFalse(ConditionTracker.lowLife(0.9, 0.75, 1, 0));
        assertTrue(ConditionTracker.lowLife(1, 0.35, 0.3, 0.35), "魔力 35% 以下也算低血");
        assertFalse(ConditionTracker.lowLife(1, 0.35, 0.3, 0), "沒有那條就不算");
        assertTrue(TreeConditions.isSelf(TreeConditions.POISONED));
        assertTrue(TreeConditions.isSelf(TreeConditions.TOTEM_PLACED));
    }

    @Test
    void surroundedCount() {
        assertEquals(5, ConditionTracker.surroundedCount(0));
        assertEquals(2, ConditionTracker.surroundedCount(3));
        assertEquals(1, ConditionTracker.surroundedCount(9), "最少 1 隻");
    }

    @Test
    void leechExcessToEnergyShield() {
        final PlayerState state = new PlayerState(UUID.randomUUID());
        state.sheet().setBase(StatKeys.LIFE_MAX, 1000);
        state.sheet().add(Modifier.flat(StatKeys.ES_MAX, 500, "測試"));
        state.setLife(1000);
        state.setEnergyShield(0);
        state.addHitLeech(100);
        state.recoverLeech(1.0);
        assertEquals(0, state.energyShield(), 1e-9, "沒有那條：滿血的偷取不給護盾");
        assertEquals(0, state.leechPool(), 1e-9);
        state.sheet().add(Modifier.flat(VaalKeys.FLAG_LEECH_EXCESS_TO_ES, 1, "測試"));
        state.setLife(950);
        state.addHitLeech(100);
        state.recoverLeech(1.0);
        assertEquals(1000, state.life(), 1e-6);
        assertEquals(50, state.energyShield(), 1e-6, "溢出的 50 回護盾");
        state.addHitLeech(100);
        state.recoverLeech(0.5);
        assertEquals(100, state.energyShield(), 1e-6);
        assertEquals(50, state.leechPool(), 1e-6, "生命滿了偷取還在（護盾還沒滿）");
        assertEquals(30, PlayerState.leechExcess(80, 50), 1e-9);
    }

    @Test
    void hitLifeLostWindow() {
        final PlayerState state = new PlayerState(UUID.randomUUID());
        state.noteHitLifeLost(100, 30);
        state.noteHitLifeLost(200, 70);
        assertEquals(100, state.hitLifeLost(250, 160), 1e-9);
        assertEquals(70, state.hitLifeLost(261, 160), 1e-9, "過了 8 秒的不算");
        assertEquals(0, state.hitLifeLost(400, 160), 1e-9);
    }
}
