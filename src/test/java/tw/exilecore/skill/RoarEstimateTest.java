package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長「網頁估算的爆裂點數要修」：重卒同時吼，只有一隻炸得到冰甲。 */
class RoarEstimateTest {

    @Test
    void onlyOneRoarBurstsNoMatterHowManyBrutes() {
        assertEquals(1.0, SkillService.roarShare(7), 1e-9, "7 隻霜術士：冰甲夠，一輪 8 秒一隻重卒的量（這次之前 4.67 倍）");
        assertEquals(1.0, SkillService.roarShare(0), 1e-9, "沒有霜術士：只炸凍結的敵人，一樣一隻");
        assertEquals(8.0 / 12.0, SkillService.roarShare(1), 1e-9, "一隻霜術士：冰甲 12 秒一次，跟不上 8 秒的咆哮");
    }
}
