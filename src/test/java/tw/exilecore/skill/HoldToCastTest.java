package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按住左鍵連續施放：原版距離的判斷（方塊互動距離拉長以後，開門、開箱子只在原版的距離內）。 */
class HoldToCastTest {

    @Test
    void distanceToTheNearestPointOfTheBlock() {
        // 點在方塊裡：0
        assertEquals(0, SkillMath.distanceSquaredToBox(0.5, 0.5, 0.5, 0, 0, 0), 1e-9);
        // 正前方 3 格（到方塊的面，不是到中心）
        assertEquals(9, SkillMath.distanceSquaredToBox(0.5, 0.5, -3, 0, 0, 0), 1e-9);
        assertEquals(9, SkillMath.distanceSquaredToBox(4, 0.5, 0.5, 0, 0, 0), 1e-9);
        // 斜對角：三個方向都算
        assertEquals(3, SkillMath.distanceSquaredToBox(-1, -1, -1, 0, 0, 0), 1e-9);
        // 負座標的方塊
        assertEquals(1, SkillMath.distanceSquaredToBox(-10.5, 64.5, -2, -11, 64, -1), 1e-9);
    }

    @Test
    void onlyNearBlocksCountAsVanillaInteraction() {
        assertTrue(SkillMath.withinVanillaReach(0.5, 1.62, -4, 0, 1, 0), "4 格外的門：原版開得到");
        assertTrue(SkillMath.withinVanillaReach(0.5, 1.62, -5, 0, 1, 0), "容許 0.5 格");
        assertFalse(SkillMath.withinVanillaReach(0.5, 1.62, -6, 0, 1, 0));
        assertFalse(SkillMath.withinVanillaReach(0.5, 1.62, -40, 0, 1, 0), "方塊互動距離拉長後客戶端會送很遠的方塊過來");
        assertEquals(64, SkillMath.HOLD_CAST_REACH, 1e-9, "原版 block_interaction_range 的上限");
        assertEquals(4.5, SkillMath.VANILLA_BLOCK_REACH, 1e-9, "原版生存模式的方塊互動距離");
    }

    /** 2026-10：右鍵有時候會同時放出左鍵的技能（右鍵用工具、開門帶出來的揮手被當成左鍵）。 */
    @Test
    void onlySwingsWhileHoldingLeftCount() {
        // 左鍵點方塊（開始挖）在 tick 100：之後每 tick 的揮手都算
        assertTrue(SkillMath.heldSwing(true, 100, 100), "點下去那一下的揮手（同一個 tick，另外只算一次）");
        assertTrue(SkillMath.heldSwing(true, 100, 101));
        assertTrue(SkillMath.heldSwing(true, 100, 103), "伺服器偶爾一個 tick 收到兩下、下一個 tick 沒有：容許隔 3 tick");
        assertFalse(SkillMath.heldSwing(true, 100, 104), "隔太久：已經放開了（停止挖的封包被擋掉也不會一直算）");
        assertFalse(SkillMath.heldSwing(false, 100, 101), "放開了（收到停止挖）：右鍵用工具、開門、丟東西帶出來的揮手不算");
        assertFalse(SkillMath.heldSwing(false, -1, 5), "從來沒按左鍵");
        assertFalse(SkillMath.heldSwing(true, 100, 99), "時間倒退（換角色、重來）不算");
        assertEquals(3, SkillMath.HOLD_SWING_GAP);
    }

    @Test
    void airClicksRightAfterRightClickOrDropAreIgnored() {
        assertTrue(SkillMath.airClick(-1, -1, 200), "沒按右鍵、沒丟東西：左鍵點空氣照算");
        assertFalse(SkillMath.airClick(200, -1, 200), "同一個 tick 按了右鍵：那一下揮手是右鍵帶出來的");
        assertFalse(SkillMath.airClick(199, -1, 200), "右鍵與揮手的封包分到相鄰兩個 tick 也不算");
        assertFalse(SkillMath.airClick(198, -1, 200), "2026-10-27：網路抖動晚兩三個 tick 到的揮手也不算");
        assertTrue(SkillMath.airClick(196, -1, 200), "隔四個 tick：是真的左鍵");
        assertFalse(SkillMath.airClick(-1, 200, 200), "丟東西（Q）也會揮手");
        assertFalse(SkillMath.airClick(-1, 199, 200));
        assertFalse(SkillMath.airClick(-1, 197, 200));
        assertTrue(SkillMath.airClick(150, 150, 200));
    }
}
