package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Corpses;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 Contagion（瘟疫）：死掉時連同其他混沌持續傷害減益一起傳、持續時間刷新、每傳一手更多 100%（最多 300%）、
 * 屍體帶著的瘟疫掛上去的樣子。這次之前只傳瘟疫自己。
 */
class ContagionSpreadTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void nothingSpreadsWithoutContagion() {
        final Map<String, AilmentState.Debuff> on = Map.of("soul_draw_shot", new AilmentState.Debuff(50, 1100, PLAYER, false, 0, 50, 80));
        assertTrue(AilmentService.spreadSet(on, 1000).isEmpty(), "沒有瘟疫：汲魂彈自己不傳");
        assertNull(AilmentService.spreader(AilmentService.spreadSet(on, 1000)));
    }

    @Test
    void contagionCarriesOtherChaosDebuffsAndRefreshesDurations() {
        final Map<String, AilmentState.Debuff> on = new LinkedHashMap<>();
        on.put("plague_taint", new AilmentState.Debuff(10, 1010, PLAYER, true, 0, 10, 100, 2.0));
        on.put("soul_draw_shot", new AilmentState.Debuff(50, 1005, PLAYER, false, 0, 50, 80));
        on.put("soul_split_shot", new AilmentState.Debuff(30, 999, PLAYER, false, 0, 30, 12)); // 已經結束的不傳
        final Map<String, AilmentState.Debuff> passed = AilmentService.spreadSet(on, 1000);
        assertEquals(2, passed.size());
        final AilmentState.Debuff contagion = passed.get("plague_taint");
        assertEquals(20, contagion.dps(), 1e-9, "傳一手更多 100%");
        assertEquals(1, contagion.spreads());
        assertEquals(1100, contagion.endTick(), "持續時間照完整的 5 秒刷新");
        assertEquals(2.0, contagion.radius(), 1e-9);
        assertTrue(contagion.spread());
        final AilmentState.Debuff drain = passed.get("soul_draw_shot");
        assertEquals(50, drain.dps(), 1e-9, "其他的減益不吃瘟疫的更多");
        assertEquals(1080, drain.endTick(), "汲魂彈也刷新");
        assertFalse(drain.spread(), "傳過去的汲魂彈自己不會再傳（要有瘟疫才傳）");
        assertEquals(contagion, AilmentService.spreader(passed));
    }

    @Test
    void spreadMoreIsCappedAtThreeHundredPercent() {
        final Map<String, AilmentState.Debuff> on = Map.of("plague_taint", new AilmentState.Debuff(40, 1010, PLAYER, true, 3, 10, 100));
        final AilmentState.Debuff passed = AilmentService.spreadSet(on, 1000).get("plague_taint");
        assertEquals(3, passed.spreads());
        assertEquals(40, passed.dps(), 1e-9, "最多更多 300%");
    }

    @Test
    void corpseContagionAppliesAsAnotherSpread() {
        final AilmentState.Debuff debuff = AilmentService.contagionDebuff(new Corpses.Contagion(PLAYER, 10, 2, 100, 1.7), 500);
        assertEquals(30, debuff.dps(), 1e-9);
        assertEquals(600, debuff.endTick());
        assertTrue(debuff.spread(), "被召喚物、爆炸傳到的也會在死掉時再傳");
        assertEquals(PLAYER, debuff.source());
        assertEquals(1.7, debuff.radius(), 1e-9);
        assertEquals(40, AilmentService.contagionDebuff(new Corpses.Contagion(PLAYER, 10, 9, 100, 1.7), 0).dps(), 1e-9, "一樣最多 300%");
    }

    @Test
    void corpsesRememberContagionUntilUsed() {
        final Corpses corpses = new Corpses();
        final UUID world = UUID.randomUUID();
        corpses.add(world, 0, 64, 0, 100, 0);
        corpses.add(world, 5, 64, 5, 100, 0);
        final Corpses.Contagion carried = new Corpses.Contagion(PLAYER, 10, 1, 100, 1.7);
        assertTrue(corpses.markContagion(world, 5, 5, carried, 1));
        assertFalse(corpses.markContagion(world, 9, 9, carried, 1), "那裡沒有屍體");
        final Corpses.Corpse marked = corpses.nearest(world, 5, 5, 0.5, 1);
        assertNotNull(marked);
        assertEquals(carried, corpses.contagion(marked));
        assertNull(corpses.contagion(corpses.nearest(world, 0, 0, 0.5, 1)), "沒掛瘟疫死的屍體不帶");
        corpses.remove(marked);
        assertNull(corpses.contagion(marked), "用掉就忘掉");
    }
}
