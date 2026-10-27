package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28：資源條上的弩熱度。 */
class HeatHudTest {

    @Test
    void showsHeatAndOverheat() {
        assertEquals("", OriginalSkills.heatText(0, 40, -1), "沒有熱度不顯示");
        assertTrue(OriginalSkills.heatText(12, 40, -1).contains("熱度 <white>12<gray>/40"));
        assertTrue(OriginalSkills.heatText(35, 40, -1).startsWith("<#ff3b1f>"), "快滿了變紅");
        assertTrue(OriginalSkills.heatText(0, 0, 130).contains("過熱 <white>6.5"));
    }
}
