package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長「磨蝕之志這個能有顯示嗎」：目標框上的字。 */
class AttritionLabelTest {

    @Test
    void showsStacksAndCullCountdown() {
        assertEquals("<#c8a2ff>磨蝕 12%／59% <gray>撲殺 25／31 秒", OriginalSkills.attritionLabel(25.5, 59, 31, false));
        assertEquals("<#c8a2ff>磨蝕 20%／59% <red>撲殺", OriginalSkills.attritionLabel(40, 59, 31, true));
        assertEquals("<#c8a2ff>磨蝕 40%（滿） <red>撲殺", OriginalSkills.attritionLabel(100, 40, 40, true));
    }
}
