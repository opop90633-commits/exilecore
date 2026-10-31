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
        debuffs.apply(boss, new Debuffs.Debuff("strength_sap_curse", me, 100, 0, false)); // 詛咒
        debuffs.applyMark(boss, new Debuffs.Debuff("blood_scent_seal", friend, 100, 0.1, true));
        debuffs.applyMark(boss, new Debuffs.Debuff("thunder_lure_seal", me, 100, 0.1, true));
        debuffs.applyMark(boss, new Debuffs.Debuff("frostbind_seal", me, 100, 0.1, true));
        assertFalse(debuffs.has(boss, "thunder_lure_seal", 10), "引雷印被霜縛印換掉");
        assertTrue(debuffs.has(boss, "frostbind_seal", 10));
        assertTrue(debuffs.has(boss, "strength_sap_curse", 10), "詛咒不受影響");
        assertTrue(debuffs.has(boss, "blood_scent_seal", 10), "別人的印記不受影響");
        debuffs.applyMark(add, new Debuffs.Debuff("frostbind_seal", me, 100, 0.1, true));
        assertFalse(debuffs.has(boss, "frostbind_seal", 10), "同一種放到別隻身上，舊的那隻也拿掉");
        assertTrue(debuffs.has(add, "frostbind_seal", 10));
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
        debuffs.applyMark(boss, new Debuffs.Debuff("thunder_lure_seal", me, 100, 0.1, true));
        debuffs.applyCurse(boss, new Debuffs.Debuff("soul_wither_curse", friend, 100, 0, false), "soul_wither_curse");
        debuffs.applyCurse(boss, new Debuffs.Debuff("wound_scar_curse", me, 100, 0, false), "wound_scar_curse");
        debuffs.applyCurse(other, new Debuffs.Debuff("strength_sap_curse", me, 100, 0, false), "strength_sap_curse");
        debuffs.applyCurse(boss, new Debuffs.Debuff("root_bind.physical", me, 100, 0.19, false, "physical"), "root_bind");
        debuffs.applyCurse(boss, new Debuffs.Debuff("root_bind", me, 100, 0, false), "root_bind");
        assertFalse(debuffs.has(boss, "wound_scar_curse", 10), "創痕被縛根換掉");
        assertTrue(debuffs.has(boss, "root_bind.physical", 10), "縛根的兩條都在");
        assertTrue(debuffs.has(boss, "root_bind", 10));
        assertTrue(debuffs.has(boss, "soul_wither_curse", 10), "別人的詛咒不受影響");
        assertTrue(debuffs.has(boss, "thunder_lure_seal", 10), "印記不受影響");
        assertTrue(debuffs.has(other, "strength_sap_curse", 10), "別隻身上的詛咒不受影響");
    }

    /** 2026-10-28 照 PoE2 Called Shots：預告射擊放的印記不受數量限制；印記被用掉（remove）時通知，到期、被換掉不算。 */
    @Test
    void calledShotsBypassesTheLimitAndConsumptionIsReported() {
        final Debuffs debuffs = new Debuffs();
        final UUID boss = UUID.randomUUID();
        final UUID add = UUID.randomUUID();
        final UUID me = UUID.randomUUID();
        final java.util.List<String> consumed = new java.util.ArrayList<>();
        debuffs.onMarkConsumed((target, id, source, now) -> consumed.add(id + "@" + (target.equals(boss) ? "boss" : "add")));
        debuffs.applyMark(boss, new Debuffs.Debuff("prey_kill_mark", me, 100, 0.1, true));
        debuffs.applyMark(add, new Debuffs.Debuff("prey_kill_mark", me, 100, 0.1, true), 0);
        assertTrue(debuffs.has(boss, "prey_kill_mark", 10), "不受數量限制：另一隻的印記還在");
        assertTrue(debuffs.has(add, "prey_kill_mark", 10));
        debuffs.applyMark(add, new Debuffs.Debuff("thunder_lure_seal", me, 100, 0.1, true)); // 一般放印記：照舊換掉
        assertFalse(debuffs.has(boss, "prey_kill_mark", 10));
        assertTrue(consumed.isEmpty(), "被換掉不算用掉");
        assertTrue(debuffs.remove(add, "thunder_lure_seal", me, 10));
        assertEquals(java.util.List.of("thunder_lure_seal@add"), consumed);
        debuffs.apply(boss, new Debuffs.Debuff("strength_sap_curse", me, 100, 0, false));
        debuffs.remove(boss, "strength_sap_curse", me, 10);
        assertEquals(1, consumed.size(), "不是印記不通知");
    }
}
