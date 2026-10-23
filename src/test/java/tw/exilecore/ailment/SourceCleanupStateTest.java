package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：玩家死掉、離開地圖時拿掉他留在怪物身上的減益與持續傷害，別人的留著。 */
class SourceCleanupStateTest {

    @Test
    void onlyThatPlayersEffectsAreRemoved() {
        final UUID gone = UUID.randomUUID();
        final UUID stays = UUID.randomUUID();
        final AilmentState state = new AilmentState();
        state.addIgnite(50, 100, gone);
        state.addPoison(10, 100, gone, 12);
        state.addPoison(10, 100, stays, 12);
        assertTrue(state.removeSource(gone));
        assertNull(state.ignite(10), "走掉的人的點燃拿掉了");
        assertEquals(1, state.poisons(10).size(), "別人的毒還在");

        final Debuffs debuffs = new Debuffs();
        final UUID monster = UUID.randomUUID();
        debuffs.apply(monster, new Debuffs.Debuff("frailty_curse", gone, 100, 0.2, false));
        debuffs.apply(monster, new Debuffs.Debuff("mark", stays, 100, 0.1, true));
        assertEquals(1, debuffs.removeSource(gone));
        assertEquals(List.of(), debuffs.modifiers(monster, gone, 10));
        assertTrue(debuffs.slow(monster, 10) >= 0);
    }
}
