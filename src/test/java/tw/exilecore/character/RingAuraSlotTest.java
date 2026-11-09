package tw.exilecore.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-04 照站長「空座指環新增一個可放光環類的格子（效果算開啟）」：技能面板的光環格。
 * 2026-11-09 照站長「額外的光環格子可以不要把整顆寶石都放進去嗎」：光環格只指著面板上的那一格，寶石留在面板。
 */
class RingAuraSlotTest {

    @Test
    void pointerTurnsThePanelAuraOnOnlyWhileWorn() {
        final SkillLoadout loadout = new SkillLoadout();
        final SkillLoadout.Entry aura = new SkillLoadout.Entry("some_aura", 10);
        final int position = loadout.add(aura);
        assertTrue(loadout.setRingAuraTarget(0, position));
        assertEquals(aura, loadout.skill(position), "寶石留在面板上");
        assertNull(loadout.skill(SkillLoadout.RING_AURA), "光環格裡沒有整顆寶石");
        assertFalse(loadout.ringAuraActive(position), "沒戴空座指環：不開");
        loadout.setRingAuraNow(true);
        assertTrue(loadout.ringAuraActive(position), "戴著：開著（不用綁按鍵）");
        assertEquals(position, loadout.find("some_aura"));
        assertFalse(loadout.bind(0, SkillLoadout.RING_AURA), "光環格不綁按鍵");
        final SkillLoadout copy = loadout.copy();
        assertEquals(position, copy.ringAuraTarget(0), "存檔快照也有");
        assertTrue(copy.ringAuraActive(position));
        // 拿掉空座指環：光環格關掉，寶石完全不動
        loadout.setRingAuraSlots(0);
        assertFalse(loadout.ringAuraActive(position));
        assertEquals(aura, loadout.skill(position), "拿掉空座指環後寶石還在面板上");
        assertEquals(position, loadout.ringAuraTarget(0), "指著的還記著，戴回去就開");
    }

    @Test
    void ringAurasComeLastAndSecondSlotNeedsTwoRings() {
        final SkillLoadout loadout = new SkillLoadout();
        final SkillLoadout.Entry ringed = new SkillLoadout.Entry("ringed_aura", 10);
        final SkillLoadout.Entry plain = new SkillLoadout.Entry("plain", 10);
        final SkillLoadout.Entry second = new SkillLoadout.Entry("other_aura", 8);
        final int ringedAt = loadout.add(ringed);
        loadout.add(plain);
        final int secondAt = loadout.add(second);
        loadout.setRingAuraTarget(0, ringedAt);
        loadout.setRingAuraTarget(1, secondAt);
        loadout.setRingAuraSlots(1);
        final List<SkillLoadout.Entry> order = loadout.entriesInKeyOrder();
        assertEquals(ringed, order.getLast(), "光環格指著的排最後（精魂不夠時最後開）");
        assertEquals(3, order.size(), "沒有重複");
        assertFalse(loadout.ringAuraActive(secondAt), "只戴一個：第二格不開");
        loadout.setRingAuraSlots(2);
        assertTrue(loadout.ringAuraActive(secondAt), "戴兩個：第二格也開");
        // 同一個光環不會兩格都指：改指到第二格，第一格清掉
        assertTrue(loadout.setRingAuraTarget(1, ringedAt));
        assertEquals(-1, loadout.ringAuraTarget(0));
        assertEquals(ringedAt, loadout.ringAuraTarget(1));
        assertFalse(loadout.setRingAuraTarget(0, 40), "空格子指不上去");
        assertFalse(loadout.setRingAuraTarget(0, SkillLoadout.RING_AURA), "不能指光環格自己");
    }

    @Test
    void removingOrReplacingThePanelSkillClearsThePointer() {
        final SkillLoadout loadout = new SkillLoadout();
        final int position = loadout.add(new SkillLoadout.Entry("some_aura", 10));
        loadout.setRingAuraTarget(0, position);
        loadout.set(position, new SkillLoadout.Entry("some_aura", 11)); // 升級、掛輔助：同一個技能，還指著
        assertEquals(position, loadout.ringAuraTarget(0));
        loadout.set(position, new SkillLoadout.Entry("something_else", 1)); // 換成別的技能
        assertEquals(-1, loadout.ringAuraTarget(0));
        loadout.set(position, new SkillLoadout.Entry("some_aura", 10));
        loadout.setRingAuraTarget(0, position);
        assertTrue(loadout.remove(position), "取出成寶石");
        assertEquals(-1, loadout.ringAuraTarget(0), "取出後光環格清掉");
    }

    @Test
    void legacyRingGemsMoveBackToThePanel() {
        final SkillLoadout loadout = new SkillLoadout();
        final SkillLoadout.Entry first = new SkillLoadout.Entry("some_aura", 10);
        final SkillLoadout.Entry second = new SkillLoadout.Entry("other_aura", 8);
        loadout.add(new SkillLoadout.Entry("plain", 5));
        loadout.set(SkillLoadout.RING_AURA, first); // 舊存檔：整顆放在光環格
        loadout.set(SkillLoadout.RING_AURA2, second);
        assertEquals(List.of(), loadout.migrateLegacyRingAuras());
        assertNull(loadout.skill(SkillLoadout.RING_AURA));
        assertNull(loadout.skill(SkillLoadout.RING_AURA2));
        assertEquals(first, loadout.skill(loadout.ringAuraTarget(0)), "搬到面板空格，光環格指著它");
        assertEquals(second, loadout.skill(loadout.ringAuraTarget(1)));
        assertEquals(List.of(), loadout.migrateLegacyRingAuras(), "再跑一次不做事");
    }

    @Test
    void legacyRingGemComesBackWhenThePanelIsFull() {
        final SkillLoadout loadout = new SkillLoadout();
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            loadout.add(new SkillLoadout.Entry("filler_" + i, 1));
        }
        final SkillLoadout.Entry legacy = new SkillLoadout.Entry("some_aura", 10);
        loadout.set(SkillLoadout.RING_AURA, legacy);
        assertEquals(List.of(legacy), loadout.migrateLegacyRingAuras(), "面板滿了：拿出來交給呼叫的人變回寶石");
        assertNull(loadout.skill(SkillLoadout.RING_AURA));
        assertEquals(-1, loadout.ringAuraTarget(0));
    }
}
