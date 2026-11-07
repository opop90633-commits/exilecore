package tw.exilecore.item.unique;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeKeys;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：噬魂者、最近的法術暴擊、暴擊殺死、技能自己有沒有消耗生命。 */
class VaalOffenceTest {

    @Test
    void recentSpellCritsDropAfterFourSeconds() {
        final ArrayDeque<Long> ticks = new ArrayDeque<>();
        ticks.add(0L);
        ticks.add(50L);
        ticks.add(70L);
        assertEquals(3, VaalOffence.recentCount(ticks, 80));
        assertEquals(2, VaalOffence.recentCount(ticks, 81), "81 tick 前的那次過了最近");
        assertEquals(0, VaalOffence.recentCount(ticks, 1000));
        assertEquals(0, VaalOffence.recentCount(null, 0));
    }

    @Test
    void soulsCapAndDecay() {
        final VaalOffence.Souls souls = new VaalOffence.Souls();
        for (int i = 0; i < 60; i++) {
            VaalOffence.eat(souls, 100);
        }
        assertEquals(VaalOffence.SOUL_MAX, souls.count, "最多 50 個");
        assertEquals(0, VaalOffence.decay(souls, 179), "4 秒內不衰退");
        assertEquals(1, VaalOffence.decay(souls, 180));
        assertEquals(2, VaalOffence.decay(souls, 200), "之後每 0.5 秒少一個");
        VaalOffence.eat(souls, 200);
        assertEquals(48, souls.count);
        assertEquals(0, VaalOffence.decay(souls, 270), "吞到了重新計時");
    }

    @Test
    void critKillNeedsSameKillerAndRecentCrit() {
        final UUID me = UUID.randomUUID();
        assertTrue(VaalOffence.wasCritKill(new VaalOffence.LastHit(me, 100, true), me, 100));
        assertFalse(VaalOffence.wasCritKill(new VaalOffence.LastHit(me, 100, false), me, 100), "最後一下沒暴擊");
        assertFalse(VaalOffence.wasCritKill(new VaalOffence.LastHit(me, 100, true), UUID.randomUUID(), 100), "別人打的");
        assertFalse(VaalOffence.wasCritKill(new VaalOffence.LastHit(me, 90, true), me, 100), "很久以前（之後是持續傷害殺的）");
        assertFalse(VaalOffence.wasCritKill(null, me, 100));
    }

    @Test
    void costsLifeIsPerSkill() {
        final Set<String> spell = Set.of(Tags.SPELL);
        final StatSheet plain = new StatSheet();
        assertFalse(UniqueCosts.costsLife(plain, spell, 10));
        final StatSheet corrupted = new StatSheet();
        corrupted.add(Modifier.flat(TreeKeys.LIFE_COST_PERCENT, 50, "已腐化寶石"));
        assertTrue(UniqueCosts.costsLife(corrupted, spell, 10), "已腐化寶石的技能");
        assertFalse(UniqueCosts.costsLife(corrupted, spell, 0), "沒有魔力消耗");
        final StatSheet extra = new StatSheet();
        extra.add(Modifier.flat(VaalKeys.SKILL_EXTRA_LIFE_COST_OF_MANA, 100, "瓦爾"));
        assertTrue(UniqueCosts.costsLife(extra, spell, 10));
        final StatSheet blood = new StatSheet();
        blood.add(Modifier.flat(StatKeys.FLAG_BLOOD_MAGIC, 1, "血魔法"));
        assertTrue(UniqueCosts.costsLife(blood, spell, 10));
    }

    @Test
    void extraLifeOfManaFollowsManaPaid() {
        final StatSheet sheet = new StatSheet();
        assertEquals(0, UniqueCosts.extraLifeOfMana(sheet, Set.of(), 20), 1e-9);
        sheet.add(Modifier.flat(VaalKeys.SKILL_EXTRA_LIFE_COST_OF_MANA, 100, "瓦爾"));
        assertEquals(20, UniqueCosts.extraLifeOfMana(sheet, Set.of(), 20), 1e-9, "100% 魔力消耗");
        assertEquals(0, UniqueCosts.extraLifeOfMana(sheet, Set.of(), 0), 1e-9);
    }
}
