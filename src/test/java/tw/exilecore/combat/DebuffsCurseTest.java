package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-27：詛咒持續時間無限（昇華・不斷爭吵）只對怪物；競技場裡對玩家照原本的持續時間。 */
class DebuffsCurseTest {

    @Test
    void infiniteCurseOnlyOnNonPlayers() {
        final Debuffs.Debuff curse = new Debuffs.Debuff("wound_scar_curse", UUID.randomUUID(), 300, 0.2, false);
        assertEquals(Long.MAX_VALUE / 4, Debuffs.placedCurse(curse, true, false).untilTick(), "怪物身上無限");
        assertEquals(300, Debuffs.placedCurse(curse, true, true).untilTick(), "玩家身上照原本的持續時間");
        assertEquals(300, Debuffs.placedCurse(curse, false, false).untilTick(), "沒有無限就照原本的");
    }
}
