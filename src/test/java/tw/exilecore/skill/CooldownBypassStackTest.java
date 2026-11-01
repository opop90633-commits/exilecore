package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01：冷卻次數 2 次（再起）的技能，兩次都在冷卻時跳過冷卻放，不會再往後排（這次之前每跳過一次多排一整段，疊到 400 秒）。 */
class CooldownBypassStackTest {

    @Test
    void bypassWhileAllUsesPendingDoesNotQueueMore() {
        final SkillRuntime runtime = new SkillRuntime();
        runtime.cooldownUses = id -> 2;
        runtime.startCooldown("cry", 0, 267);
        runtime.startCooldown("cry", 1, 267);
        assertEquals(266, runtime.cooldownLeft("cry", 1));
        for (int i = 0; i < 30; i++) {
            runtime.startCooldown("cry", 2 + i, 267); // 跳過冷卻連放 30 次
        }
        assertEquals(267 - 40, runtime.cooldownLeft("cry", 40));
        assertEquals(0, runtime.cooldownLeft("cry", 267));
        runtime.startCooldown("cry", 267, 267); // 第一次好了才再排：排在第二次之後
        assertEquals(534 + 1 - 300, runtime.cooldownLeft("cry", 300) + 0 * 1, 1);
    }
}
