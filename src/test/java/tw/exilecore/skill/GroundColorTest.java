package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 2026-10-31：玩家地面效果不用紅色（跟 Boss 警示圈分開）。 */
class GroundColorTest {

    @Test
    void redsArePushedToOrangeOthersStay() {
        assertNotEquals(0xB3121F, GroundDecals.awayFromRed(0xB3121F), "血色要換");
        assertNotEquals(0xFF2A2A, GroundDecals.awayFromRed(0xFF2A2A), "紅色要換");
        assertEquals(0xBFEFFF, GroundDecals.awayFromRed(0xBFEFFF), "冰藍不動");
        assertEquals(0x7FBF3F, GroundDecals.awayFromRed(0x7FBF3F), "毒綠不動");
        assertEquals(0xFF7A1A, GroundDecals.awayFromRed(0xFF7A1A), "火焰橘不動");
    }
}
