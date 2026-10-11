package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 技能音效（2026-10）：自己的照常、別人的小聲、各自可以關；複擊的面板每秒傷害。 */
class SkillSoundTest {

    @Test
    void ownSkillKeepsItsVolumeUnlessMuted() {
        assertEquals(0.8f, Fx.heard(0.8f, true, false, false), 1e-6);
        assertEquals(0f, Fx.heard(0.8f, true, true, false), 1e-6);
        // 「別人的技能特效」關掉不影響自己的技能
        assertEquals(0.8f, Fx.heard(0.8f, true, false, true), 1e-6);
    }

    @Test
    void othersSkillsAreQuieterAndCanBeHidden() {
        assertEquals(0.8f * Fx.OTHERS_VOLUME, Fx.heard(0.8f, false, false, false), 1e-6);
        assertEquals(0f, Fx.heard(0.8f, false, false, true), 1e-6);
        // 自己的「技能音效」關掉不會讓別人的也消失（那是另一個開關）
        assertEquals(0.8f * Fx.OTHERS_VOLUME, Fx.heard(0.8f, false, true, false), 1e-6);
    }

    @Test
    void repeatsEachTakeOneAttackInterval() {
        // 沒有複擊：跟以前一樣，一輪是間隔與冷卻比較長的那個
        assertEquals(2.0, SkillService.hitsPerSecond(10, 0, 0), 1e-9);
        assertEquals(0.5, SkillService.hitsPerSecond(10, 40, 0), 1e-9);
        // 三連刺（再打兩下）：三下花三次間隔，每秒還是 2 下，不是 6 下
        assertEquals(2.0, SkillService.hitsPerSecond(10, 0, 2), 1e-9);
        // 有冷卻的複擊：兩下的間隔＋冷卻，一輪打三下
        assertEquals(20.0 * 3 / (2 * 10 + 30), SkillService.hitsPerSecond(10, 30, 2), 1e-9);
    }
}
