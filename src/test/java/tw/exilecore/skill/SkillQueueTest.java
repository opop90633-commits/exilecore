package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10：按住左鍵連發時，剛按的數字鍵（戰吼…）不會被左鍵蓋掉，而且可以排比較久。 */
class SkillQueueTest {

    @Test
    void heldLeftDoesNotOverwriteQueuedHotbarKey() {
        final SkillRuntime runtime = new SkillRuntime();
        final int hotbar = SkillLoadout.KEYS; // 數字鍵的第一格
        runtime.queue(hotbar, null, 100);
        runtime.queue(SkillLoadout.KEY_LEFT, null, 101);
        assertEquals(hotbar, runtime.queuedKey);
        // 數字鍵排太久（逾時）之後，左鍵可以排進來
        runtime.queue(SkillLoadout.KEY_LEFT, null, 100 + SkillRuntime.HOTBAR_QUEUE_TIMEOUT + 1);
        assertEquals(SkillLoadout.KEY_LEFT, runtime.queuedKey);
        // 數字鍵可以蓋掉左鍵
        runtime.queue(hotbar + 1, null, 200);
        assertEquals(hotbar + 1, runtime.queuedKey);
        assertEquals(SkillRuntime.HOTBAR_QUEUE_TIMEOUT, SkillRuntime.queueTimeout(hotbar));
        assertEquals(SkillRuntime.QUEUE_TIMEOUT, SkillRuntime.queueTimeout(SkillLoadout.KEY_RIGHT));
    }
}
