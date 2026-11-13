package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-12 照站長「手感」：渡鴉俯衝的飛行時間、引導的提示列。 */
class FeelTweaksTest {

    @Test
    void diveTicksFollowDistance() {
        assertEquals(3, MinionAbilities.diveTicks(0));
        assertEquals(3, MinionAbilities.diveTicks(3));
        assertEquals(6, MinionAbilities.diveTicks(9));      // 每 tick 1.5 格
        assertEquals(12, MinionAbilities.diveTicks(18));
        assertEquals(12, MinionAbilities.diveTicks(40));    // 最多 0.6 秒
    }

    @Test
    void channelHintSaysHowToStop() {
        // 2026-11-13 照站長（A）：滑鼠鍵也是再按一次結束
        assertTrue(Signatures.channelHint("焚流", true).endsWith("焚流 引導中…（再按一次結束）"));
        assertTrue(Signatures.channelHint("焚流", false).endsWith("焚流 引導中…（再按一次結束）"));
    }

    @Test
    void pressAgainNeedsAGap() {
        // 2026-11-13 照站長（A）：按住左鍵每 tick、右鍵每 4 tick 送一次，停了超過這麼久再按才算「再按一下」
        assertEquals(3, Signatures.freshGap(tw.exilecore.character.SkillLoadout.KEY_LEFT));
        assertEquals(7, Signatures.freshGap(tw.exilecore.character.SkillLoadout.KEY_RIGHT));
    }

    @Test
    void actionMaskStartsAtOneSecond() {
        assertEquals(20, SkillService.ACTION_MASK_TICKS);
    }
}
