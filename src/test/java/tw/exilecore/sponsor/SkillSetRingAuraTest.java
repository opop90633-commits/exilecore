package tw.exilecore.sponsor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;

/** 2026-11-09 照站長「切換後光環格的技能會消失」：技能組存下來、換回來，光環格指著的還在（寶石留在面板）。 */
class SkillSetRingAuraTest {

    @Test
    void ringPointerSurvivesSwitchingSkillSets() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.add(new SkillLoadout.Entry("plain", 5));
        final int aura = loadout.add(new SkillLoadout.Entry("some_aura", 10));
        loadout.setRingAuraTarget(1, aura);
        loadout.bind(SkillLoadout.KEY_RIGHT, 0);
        final SkillLoadout back = SkillSetMenu.decode(SkillSetMenu.encode(loadout));
        assertEquals("some_aura", back.skill(aura).skillId(), "寶石還在面板上");
        assertEquals(aura, back.ringAuraTarget(1));
        assertEquals(-1, back.ringAuraTarget(0));
        assertEquals(0, back.bound(SkillLoadout.KEY_RIGHT));
    }

    @Test
    void oldStoredSetsReadWithEmptyRingSlots() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.add(new SkillLoadout.Entry("plain", 5));
        final String encoded = SkillSetMenu.encode(loadout);
        final String old = encoded.substring(0, encoded.length() - (SkillLoadout.RING_AURA_SLOTS * 3)); // 拿掉後面兩段「\u001F-1」
        final SkillLoadout back = SkillSetMenu.decode(old);
        assertEquals("plain", back.skill(0).skillId());
        assertEquals(-1, back.ringAuraTarget(0));
        assertEquals(-1, back.ringAuraTarget(1));
    }
}
