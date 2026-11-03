package tw.exilecore.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長「空座指環新增一個可放光環類的格子（效果算開啟）」：技能面板的光環格。 */
class RingAuraSlotTest {

    @Test
    void ringSlotCountsOnlyWhileWorn() {
        final SkillLoadout loadout = new SkillLoadout();
        final SkillLoadout.Entry aura = new SkillLoadout.Entry("some_aura", 10);
        loadout.set(SkillLoadout.RING_AURA, aura);
        assertEquals(aura, loadout.skill(SkillLoadout.RING_AURA));
        assertFalse(loadout.entriesInKeyOrder().contains(aura), "沒戴空座指環：不開");
        loadout.setRingAuraNow(true);
        assertTrue(loadout.entriesInKeyOrder().contains(aura), "戴著：開著");
        assertFalse(loadout.bind(0, SkillLoadout.RING_AURA), "光環格不綁按鍵");
        final SkillLoadout copy = loadout.copy();
        assertEquals(aura, copy.skill(SkillLoadout.RING_AURA), "存檔快照也有");
        assertTrue(copy.entriesInKeyOrder().contains(aura));
        assertTrue(loadout.remove(SkillLoadout.RING_AURA));
        assertEquals(null, loadout.skill(SkillLoadout.RING_AURA));
    }
}
