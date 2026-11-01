package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2 Navira's Embrace：終幻．娜瑟菈自帶的指令。 */
class NaviraEmbraceTest {

    @Test
    void durationGrowsWithNaviraLevel() {
        assertEquals(5 + 10 / 99.0, AscGranted.embraceSeconds(1), 1e-9, "娜瑟菈 2 級");
        assertEquals(5 + 10 * 39 / 99.0, AscGranted.embraceSeconds(20), 1e-9);
        // poe2db 的 68 級怪物頁面寫 11.77 秒（娜瑟菈等級 68 = 技能 34 級）
        assertEquals(11.77, AscGranted.embraceSeconds(34), 0.01);
    }

    @Test
    void bubbleTakesSeventyPercentUpToItsLife() {
        assertEquals(70, AscGranted.bubbleTake(100, 500), 1e-9);
        assertEquals(30, AscGranted.bubbleTake(100, 30), 1e-9, "最多吃到泡泡剩下的生命");
        assertEquals(0, AscGranted.bubbleTake(100, 0), 1e-9);
    }

    @Test
    void manaFollowsTheRefRange() {
        assertEquals(7, AscGranted.embraceMana(1));
        assertEquals(75, AscGranted.embraceMana(20));
    }
}
