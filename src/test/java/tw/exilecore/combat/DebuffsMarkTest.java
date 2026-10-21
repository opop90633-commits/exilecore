package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-22：照 PoE2，同一個玩家同時只能有一個印記；放新的會拿掉舊的（不管在哪隻身上）。詛咒、別人的印記不受影響。 */
class DebuffsMarkTest {

    @Test
    void onlyOneMarkPerPlayer() {
        final Debuffs debuffs = new Debuffs();
        final UUID boss = UUID.randomUUID();
        final UUID add = UUID.randomUUID();
        final UUID me = UUID.randomUUID();
        final UUID friend = UUID.randomUUID();
        debuffs.apply(boss, new Debuffs.Debuff("enfeeble_curse", me, 100, 0, false)); // 詛咒
        debuffs.applyMark(boss, new Debuffs.Debuff("bloodhounds_mark", friend, 100, 0.1, true));
        debuffs.applyMark(boss, new Debuffs.Debuff("voltaic_mark", me, 100, 0.1, true));
        debuffs.applyMark(boss, new Debuffs.Debuff("freezing_mark", me, 100, 0.1, true));
        assertFalse(debuffs.has(boss, "voltaic_mark", 10), "引雷印被霜縛印換掉");
        assertTrue(debuffs.has(boss, "freezing_mark", 10));
        assertTrue(debuffs.has(boss, "enfeeble_curse", 10), "詛咒不受影響");
        assertTrue(debuffs.has(boss, "bloodhounds_mark", 10), "別人的印記不受影響");
        debuffs.applyMark(add, new Debuffs.Debuff("freezing_mark", me, 100, 0.1, true));
        assertFalse(debuffs.has(boss, "freezing_mark", 10), "同一種放到別隻身上，舊的那隻也拿掉");
        assertTrue(debuffs.has(add, "freezing_mark", 10));
        assertEquals(2, debuffs.active(boss, 10).size());
    }

    /** 2026-10-22：一隻敵人身上同時只能有你的一個詛咒；縛根一次放好幾條算同一個；別人的詛咒、印記不受影響。 */
    @Test
    void onlyOneCursePerPlayerPerEnemy() {
        final Debuffs debuffs = new Debuffs();
        final UUID boss = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final UUID me = UUID.randomUUID();
        final UUID friend = UUID.randomUUID();
        debuffs.applyMark(boss, new Debuffs.Debuff("voltaic_mark", me, 100, 0.1, true));
        debuffs.applyCurse(boss, new Debuffs.Debuff("despair_curse", friend, 100, 0, false), "despair_curse");
        debuffs.applyCurse(boss, new Debuffs.Debuff("vulnerability_curse", me, 100, 0, false), "vulnerability_curse");
        debuffs.applyCurse(other, new Debuffs.Debuff("enfeeble_curse", me, 100, 0, false), "enfeeble_curse");
        debuffs.applyCurse(boss, new Debuffs.Debuff("entangle.physical", me, 100, 0.19, false, "physical"), "entangle");
        debuffs.applyCurse(boss, new Debuffs.Debuff("entangle", me, 100, 0, false), "entangle");
        assertFalse(debuffs.has(boss, "vulnerability_curse", 10), "創痕被縛根換掉");
        assertTrue(debuffs.has(boss, "entangle.physical", 10), "縛根的兩條都在");
        assertTrue(debuffs.has(boss, "entangle", 10));
        assertTrue(debuffs.has(boss, "despair_curse", 10), "別人的詛咒不受影響");
        assertTrue(debuffs.has(boss, "voltaic_mark", 10), "印記不受影響");
        assertTrue(debuffs.has(other, "enfeeble_curse", 10), "別隻身上的詛咒不受影響");
    }
}
