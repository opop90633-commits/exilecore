package tw.exilecore.currency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-10-28 照站長「洗裝台要有下一頁的按鈕」：星籤、通貨一起翻頁，頁數照多的那一邊（每頁各 12 格）。
 * 這次之前只看通貨的頁數，星籤（26 種）超過 12 種的選不到。
 */
class CraftBenchPagingTest {

    @Test
    void pagesCoverWhicheverListIsLonger() {
        assertEquals(1, CraftBenchMenu.pages(0, 0));
        assertEquals(1, CraftBenchMenu.pages(12, 12), "剛好一頁");
        assertEquals(2, CraftBenchMenu.pages(13, 3), "星籤 13 種：要第二頁（這次之前看不到第 13 種）");
        assertEquals(3, CraftBenchMenu.pages(26, 5), "PoE2 的 26 種星籤全部翻得到");
        assertEquals(9, CraftBenchMenu.pages(4, 100), "精髓、催化劑很多：照通貨的頁數");
    }
}
