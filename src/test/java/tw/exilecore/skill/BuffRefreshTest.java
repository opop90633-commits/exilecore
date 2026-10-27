package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 效能：同一個增益效果一樣只是重新計時，不重算屬性表。 */
class BuffRefreshTest {

    private static List<Modifier> mods(final double speed) {
        return List.of(Modifier.increased(StatKeys.ATTACK_SPEED, speed, "擊殺"), Modifier.increased(StatKeys.CAST_SPEED, speed, "擊殺"));
    }

    @Test
    void sameEffectStillActiveIsOnlyARefresh() {
        final SkillBuffs.Buff old = new SkillBuffs.Buff("onkill", "擊殺後", 200, mods(0.1));
        assertTrue(SkillBuffs.refreshOnly(old, mods(0.1), 100), "效果一樣、還沒過期：只延長時間");
    }

    @Test
    void newOrChangedOrExpiredBuffsRecalculate() {
        final SkillBuffs.Buff old = new SkillBuffs.Buff("onkill", "擊殺後", 200, mods(0.1));
        assertFalse(SkillBuffs.refreshOnly(null, mods(0.1), 100), "新的增益");
        assertFalse(SkillBuffs.refreshOnly(old, mods(0.2), 100), "效果變了");
        assertFalse(SkillBuffs.refreshOnly(old, mods(0.1), 200), "已經過期（屬性表裡已經沒有它）");
    }
}
