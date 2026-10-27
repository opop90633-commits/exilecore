package tw.exilecore.ascendancy;

import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-27：天賦樹配的點比能用的點多（昇華給的天賦點被管理員重置、改版退回）時，從最外面退掉多出來的點（純邏輯）。
 * 這次之前只檢查相連，可用天賦點會變成負的、多配的天賦一直有效。
 * 資料庫沒記配點的先後，所以退「離起點最遠、退掉之後其他都還接得到起點」的那一點，當作最後配的。
 */
public final class TreeBudget {

    private TreeBudget() {
    }

    /**
     * 從 allocated 裡退掉 count 點（直接改 allocated），回傳退掉的節點；退不動（每一點都卡著別的）就停下來。
     */
    public static List<String> trim(final PassiveTree tree, final Set<String> allocated, final PassiveNode start,
                                    final Set<String> rules, final int count) {
        final List<String> removed = new ArrayList<>();
        while (removed.size() < count) {
            final Map<String, Integer> depth = depths(tree, allocated, start, rules);
            String pick = null;
            int best = -1;
            for (final String id : allocated) {
                final PassiveNode node = tree.node(id);
                if (node == null) {
                    pick = id; // 樹上已經沒有的：先退
                    break;
                }
                final int d = depth.getOrDefault(id, Integer.MAX_VALUE);
                if ((d > best || d == best && pick != null && id.compareTo(pick) > 0) && tree.canRefund(allocated, start, node, rules)) {
                    best = d;
                    pick = id;
                }
            }
            if (pick == null) {
                break;
            }
            allocated.remove(pick);
            removed.add(pick);
        }
        return removed;
    }

    /** 從出發點只走已配的節點，每個節點離出發點幾步。 */
    private static Map<String, Integer> depths(final PassiveTree tree, final Set<String> allocated, final PassiveNode start, final Set<String> rules) {
        final Map<String, Integer> depth = new HashMap<>();
        final Deque<PassiveNode> queue = new ArrayDeque<>();
        for (final PassiveNode seed : tree.seeds(start, rules)) {
            depth.put(seed.id(), 0);
            queue.add(seed);
        }
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            final int next = depth.get(current.id()) + 1;
            for (final PassiveNode neighbour : tree.neighbours(current)) {
                if (allocated.contains(neighbour.id()) && !depth.containsKey(neighbour.id())) {
                    depth.put(neighbour.id(), next);
                    queue.add(neighbour);
                }
            }
        }
        return depth;
    }
}
