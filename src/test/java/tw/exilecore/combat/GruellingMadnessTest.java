package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-12：殘忍瘋狂照原作加強其他減速；霆落矛的衍生子技能在戰鬥數據算在霆落矛上。 */
class GruellingMadnessTest {

    @Test
    void ownSlowScalesWithStacks() {
        assertEquals(0, Presence.gruellingSlow(0), 1e-9);
        assertEquals(0.15, Presence.gruellingSlow(1), 1e-9);
        assertEquals(0.285, Presence.gruellingSlow(10), 1e-9);
        assertEquals(0.285, Presence.gruellingSlow(15), 1e-9); // 最多 10 層
    }

    @Test
    void amplifiesOtherSlows() {
        // 沒有殘忍瘋狂：照原樣
        assertEquals(0.3, Debuffs.amplified(0.3, 0), 1e-9);
        // 10 層（×1.9）：別的 30% 減速變 57%
        assertEquals(0.57, Debuffs.amplified(0.3, Presence.gruellingSlow(10)), 1e-9);
        // 別的減速比牠自己的弱時取比較大的
        assertEquals(0.15, Debuffs.amplified(0.05, Presence.gruellingSlow(1)), 1e-9);
        // 定身照舊；加強之後不會變成定身
        assertEquals(1.0, Debuffs.amplified(1.0, Presence.gruellingSlow(10)), 1e-9);
        assertEquals(0.99, Debuffs.amplified(0.8, Presence.gruellingSlow(10)), 1e-9);
    }

    @Test
    void stormLanceSubSkillsCountAsStormLance() {
        assertEquals("thunderfall_spear", CombatListener.meterParent("storm_lance_bolt"));
        assertEquals("thunderfall_spear", CombatListener.meterParent("storm_lance_burst"));
        assertEquals("thunderfall_spear.zone", CombatListener.meterParent("storm_lance_ground.zone"));
        assertEquals("spinning_spear_strike", CombatListener.meterParent("whirling_slash_collapse"));
        assertEquals("fireball.blast", CombatListener.meterParent("fireball.blast"));
    }
}
