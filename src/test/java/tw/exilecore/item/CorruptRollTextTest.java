package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長「蝕變石變動詞綴要顯示骰出的數值 × 額外倍率」。 */
class CorruptRollTextTest {

    @Test
    void corruptedShowsRollTimesFactor() {
        final String text = Affix.substituteWithRanges("增加 {value}% 荊棘傷害", Map.of("value", 48), Map.of("value", new int[] {35, 40}), 1.22);
        assertTrue(text.contains("48"), text);
        assertTrue(text.contains("(39×1.22)"), text);
        assertFalse(text.contains("35"), text);
    }

    @Test
    void normalKeepsRange() {
        final String text = Affix.substituteWithRanges("增加 {value}% 荊棘傷害", Map.of("value", 39), Map.of("value", new int[] {35, 40}), 1);
        assertTrue(text.contains("(35–40)"), text);
    }
}
