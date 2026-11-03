package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長（淵座束帶）：符文說明寫乘上符文效果增加之後的數字。 */
class RuneScaledTextTest {

    @Test
    void scalesEveryNumber() {
        assertEquals("+144 最大生命", Runes.scaledText("+75 最大生命", 1.92));
        assertEquals("+38 最大生命、+38 最大魔力", Runes.scaledText("+20 最大生命、+20 最大魔力", 1.92));
        assertEquals("增加 9.6% 最大生命", Runes.scaledText("增加 5% 最大生命", 1.92));
        assertEquals("+75 最大生命", Runes.scaledText("+75 最大生命", 1.0));
    }
}
