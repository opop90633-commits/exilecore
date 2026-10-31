package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Contagion（瘟疫）：死掉的敵人身上的詛咒傳給附近的敵人，持續時間刷新。這次之前不傳。 */
class DebuffsContagionTest {

    @Test
    void cursesSpreadWithRefreshedDuration() {
        final Debuffs debuffs = new Debuffs();
        final UUID player = UUID.randomUUID();
        final UUID dead = UUID.randomUUID();
        final UUID near = UUID.randomUUID();
        debuffs.applyCurse(dead, new Debuffs.Debuff("despair", player, 300, 0.2, false, "chaos"), "despair");
        debuffs.noteCurseLength(dead, "despair", 200);
        debuffs.apply(dead, new Debuffs.Debuff("monster_blind", player, 300, 0, false)); // 不是詛咒：不傳
        assertEquals(1, debuffs.spreadCurses(dead, near, 250));
        assertEquals(1, debuffs.active(near, 250).size());
        final Debuffs.Debuff passed = debuffs.active(near, 250).getFirst();
        assertEquals("despair", passed.id());
        assertEquals(450, passed.untilTick(), "刷新成完整的持續時間");
        assertEquals(0.2, passed.taken(), 1e-9);
        assertTrue(debuffs.cursed(near, 400) || debuffs.isCurse("despair"));
    }

    @Test
    void unknownLengthKeepsRemainingTime() {
        final Debuffs debuffs = new Debuffs();
        final UUID player = UUID.randomUUID();
        final UUID dead = UUID.randomUUID();
        final UUID near = UUID.randomUUID();
        debuffs.applyCurse(dead, new Debuffs.Debuff("strength_sap_curse", player, 300, 0, false), "strength_sap_curse");
        debuffs.spreadCurses(dead, near, 100);
        assertEquals(300, debuffs.active(near, 100).getFirst().untilTick());
    }

    @Test
    void severalCursesFromOnePlayerAllSpread() {
        final Debuffs debuffs = new Debuffs();
        final UUID player = UUID.randomUUID();
        final UUID dead = UUID.randomUUID();
        final UUID near = UUID.randomUUID();
        debuffs.applyCurse(dead, new Debuffs.Debuff("a_curse", player, 300, 0.1, false), "a_curse", 2);
        debuffs.applyCurse(dead, new Debuffs.Debuff("b_curse", player, 300, 0.1, false), "b_curse", 2);
        assertEquals(2, debuffs.spreadCurses(dead, near, 10));
        assertEquals(2, debuffs.active(near, 10).size(), "傳過去不會互相擠掉");
    }
}
