package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：昇華給的天賦點拿回去時的檢查、多配的天賦從外面退、鮮血沸騰的上限。 */
class AscBudgetTest {

    static AscendancyConfig config;
    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(Path.of("src/main/resources/data"));
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    @Test
    void weaponMasterGivesTenPointsAndRefundNeedsThemBack() {
        assertEquals(10, Ascension.extraPassivePoints(config.ascendancy("witchhunter").node("weapon_master")));
        assertEquals(0, Ascension.extraPassivePoints(config.ascendancy("witchhunter").node("cooldown_recovery_3")));
        assertEquals(0, Ascension.refundDebt(12, 10, 0), "還有 12 點沒配：可以退");
        assertEquals(7, Ascension.refundDebt(3, 10, 0), "只剩 3 點：先退 7 點");
        assertEquals(2, Ascension.refundDebt(3, 10, 5), "規則沒了自動退回的 5 點也算");
        assertEquals(0, Ascension.refundDebt(-1, 0, 0), "不給天賦點的不擋");
    }

    @Test
    void trimRemovesOutermostAndKeepsTheRestConnected() {
        final PassiveNode start = tree.start("marauder");
        // 從起點一路配出去 15 點
        final Set<String> allocated = new HashSet<>();
        for (int i = 0; i < 15; i++) {
            PassiveNode next = null;
            for (final PassiveNode node : tree.nodes()) {
                if (!node.isStart() && !allocated.contains(node.id()) && tree.canAllocate(allocated, start, node)) {
                    next = node;
                    break;
                }
            }
            assertTrue(next != null);
            allocated.add(next.id());
        }
        final List<String> removed = TreeBudget.trim(tree, allocated, start, Set.of(), 5);
        assertEquals(5, removed.size());
        assertEquals(10, allocated.size());
        assertEquals(allocated, tree.reachable(allocated, start), "剩下的都還接得到起點");
        assertEquals(List.of(), TreeBudget.trim(tree, allocated, start, Set.of(), 0));
    }

    @Test
    void bloodBoilIsCapped() {
        assertEquals(tw.exilecore.core.Units.metres(1.5) * Math.sqrt(1.2), AscGranted.boilRadius(1), 1e-9, "照 PoE2：1.5 格，每個血泡更多 20% 範圍");
        assertEquals(tw.exilecore.core.Units.metres(1.5) * Math.sqrt(5), AscGranted.boilRadius(AscGranted.BOIL_MAX), 1e-9);
        assertEquals(AscGranted.boilRadius(AscGranted.BOIL_MAX), AscGranted.boilRadius(500), 1e-9, "超過上限不再變大");
        int boil = 0;
        for (int i = 0; i < 100; i++) {
            boil = AscGranted.addBoil(boil);
        }
        assertEquals(AscGranted.BOIL_MAX, boil);
    }
}
