package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照站長：咒爆——身上的詛咒被別次施放、別塊地面的同一個詛咒蓋掉時引爆；同一個來源自己刷新不算。 */
class CurseOverwriteTest {

    @Test
    void overwriteByAnotherSourceBlasts() {
        final Map<String, SupportProcs.CurseSource> sources = new HashMap<>();
        final Object groundA = new Object();
        final Object groundB = new Object();
        assertFalse(SupportProcs.overwritten(sources, "k", groundA, 0, 15), "第一次放上去不炸");
        assertFalse(SupportProcs.overwritten(sources, "k", groundA, 1, 16), "同一塊地面刷新不炸");
        assertTrue(SupportProcs.overwritten(sources, "k", groundB, 2, 17), "另一塊地面蓋掉就炸");
        assertTrue(SupportProcs.overwritten(sources, "k", new Object(), 3, 200), "再放一次蓋掉就炸");
        assertFalse(SupportProcs.overwritten(sources, "k", new Object(), 300, 400), "舊的已經結束了才放：不算蓋掉（結束時已經炸過）");
        assertFalse(SupportProcs.overwritten(sources, "other", groundA, 301, 316), "別的敵人分開算");
        assertFalse(SupportProcs.overwritten(sources, "b", "p:blasphemy:x", 0, 10));
        assertFalse(SupportProcs.overwritten(sources, "b", "p:blasphemy:x", 1, 11), "咒環自己刷新（同一個字串）不炸");
    }
}
