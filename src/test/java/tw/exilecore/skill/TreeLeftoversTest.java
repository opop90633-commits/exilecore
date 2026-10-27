package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.TreeConditions;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-27：天賦樹最後一批的純計算（身上幾個詛咒、存在範圍進出的條件標籤）。 */
class TreeLeftoversTest {

    @Test
    void curseCountCountsDistinctCurses() {
        final UUID source = UUID.randomUUID();
        final List<Debuffs.Debuff> active = List.of(
                new Debuffs.Debuff("entangle_curse.physical", source, 100, 0, false),
                new Debuffs.Debuff("entangle_curse.chaos", source, 100, 0, false), // 縛根一次好幾條，算一個
                new Debuffs.Debuff("map_curse.enfeeble", source, 100, 0, false),
                new Debuffs.Debuff("map_curse.temporal_chains", source, 100, 0, false),
                new Debuffs.Debuff("blind", source, 100, 0, false));
        assertEquals(3, TreeLeftovers.curseCount(active));
        assertEquals(0, TreeLeftovers.curseCount(List.of()));
    }

    @Test
    void presenceTagsLastFourSeconds() {
        final TreeLeftovers.Presence mark = new TreeLeftovers.Presence();
        mark.entered = 1000;
        assertEquals(Set.of(TreeConditions.VS_ENTERED_PRESENCE), TreeLeftovers.presenceTags(mark, 1040));
        assertEquals(Set.of(), TreeLeftovers.presenceTags(mark, 1100));
        mark.exited = 1090;
        assertEquals(Set.of(TreeConditions.VS_EXITED_PRESENCE), TreeLeftovers.presenceTags(mark, 1100));
    }
}
