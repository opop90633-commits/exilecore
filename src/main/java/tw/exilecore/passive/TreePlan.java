package tw.exilecore.passive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 匯入規劃（/tree import）：算出照規劃要「配哪些、退哪些」。純邏輯。
 * <ul>
 *   <li>規劃以外的節點全部退掉（退掉的點數拿回來用）。</li>
 *   <li>從起點沿著規劃往外配：已經配了的優先保留（不花點），其他的照離起點近的先配，點數用完就停。
 *       規劃裡已經配了、但這次接不回起點的也會退掉，那些點數一樣拿來配。</li>
 *   <li>規劃裡、但這次點數不夠連過去的，留到之後（升級後再匯入一次就會接著配）。</li>
 * </ul>
 * 結果一定是一棵連得到起點的樹（照配點規則）。
 */
public final class TreePlan {

    /**
     * 匯入的結果。
     *
     * @param result  套用後的配點（不含起點）
     * @param added   要新配的（照配的順序）
     * @param removed 要退掉的
     * @param later   規劃裡、這次點數不夠沒配到的
     */
    public record Plan(Set<String> base, Set<String> result, List<PassiveNode> added, List<PassiveNode> removed,
                       List<PassiveNode> later) {

        public boolean changes() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    /** 規劃不合規則（給玩家看的原因）。 */
    public static final class InvalidPlan extends Exception {
        public InvalidPlan(final String message) {
            super(message);
        }
    }

    private TreePlan() {
    }

    /**
     * @param current   目前的配點（不含起點）
     * @param planned   規劃（不含起點）
     * @param available 目前可用的天賦點
     */
    public static Plan compute(final PassiveTree tree, final PassiveNode start, final Set<String> current, final Set<String> planned,
                               final int available) throws InvalidPlan {
        final Set<String> plan = new LinkedHashSet<>();
        for (final String id : planned) {
            final PassiveNode node = tree.node(id);
            if (node == null) {
                throw new InvalidPlan("規劃裡有不存在的節點 " + id);
            }
            if (!node.isStart()) {
                plan.add(id);
            }
        }
        if (tree.reachable(plan, start).size() != plan.size()) {
            throw new InvalidPlan("規劃有一部分連不到你的起點（可能是別的職業的規劃）");
        }
        final Map<String, Integer> distance = distances(tree, start, plan);
        final Map<String, Integer> fileOrder = new HashMap<>();
        for (final PassiveNode node : tree.ordered()) {
            fileOrder.put(node.id(), fileOrder.size());
        }
        final int points = Math.max(0, available);
        // 規劃以外的一定會退掉，這些點數一定拿得回來
        int budget = points;
        for (final String id : current) {
            if (!plan.contains(id)) {
                budget++;
            }
        }
        Grown best = grow(tree, start, current, plan, distance, fileOrder, budget);
        // 規劃裡已經配了、但這次接不回起點的也會退掉，點數一樣拿回來：預算一次多給一點再算，
        // 直到付不起（多配的點把本來要退的接回來，退的變少）或規劃已經配完
        while (true) {
            final Grown next = grow(tree, start, current, plan, distance, fileOrder, budget + 1);
            if (next.added().size() == best.added().size() || next.added().size() > points + next.removedFrom(current)) {
                break;
            }
            best = next;
            budget++;
        }
        final Set<String> included = best.included();
        final List<PassiveNode> added = best.added();
        final List<PassiveNode> removed = new ArrayList<>();
        for (final String id : current) {
            if (!included.contains(id)) {
                final PassiveNode node = tree.node(id);
                if (node != null) {
                    removed.add(node);
                }
            }
        }
        final List<PassiveNode> later = new ArrayList<>();
        for (final String id : plan) {
            if (!included.contains(id)) {
                later.add(tree.node(id));
            }
        }
        return new Plan(Set.copyOf(current), Set.copyOf(included), List.copyOf(added), List.copyOf(removed), List.copyOf(later));
    }

    /** 從起點沿著規劃長出來的結果。 */
    private record Grown(Set<String> included, List<PassiveNode> added) {

        /** 目前的配點裡，有幾個沒長到（會退掉）。 */
        int removedFrom(final Set<String> current) {
            int count = 0;
            for (final String id : current) {
                if (!included.contains(id)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 已經配了的先拿（不花點），其他的照離起點近、再照樹檔順序，花 budget 點。同樣的輸入結果一定一樣，預算越多長得越遠。 */
    private static Grown grow(final PassiveTree tree, final PassiveNode start, final Set<String> current, final Set<String> plan,
                              final Map<String, Integer> distance, final Map<String, Integer> fileOrder, final int budget) {
        int left = budget;
        final Set<String> included = new LinkedHashSet<>();
        final List<PassiveNode> added = new ArrayList<>();
        final Set<String> frontier = new LinkedHashSet<>();
        expand(tree, start, plan, included, frontier);
        while (!frontier.isEmpty()) {
            String pick = null;
            for (final String id : frontier) {
                if (current.contains(id)) {
                    pick = id;
                    break;
                }
            }
            if (pick == null) {
                if (left <= 0) {
                    break;
                }
                pick = frontier.stream()
                        .min(Comparator.<String>comparingInt(id -> distance.getOrDefault(id, Integer.MAX_VALUE))
                                .thenComparingInt(id -> fileOrder.getOrDefault(id, Integer.MAX_VALUE)))
                        .orElseThrow();
                left--;
                added.add(tree.node(pick));
            }
            frontier.remove(pick);
            included.add(pick);
            expand(tree, tree.node(pick), plan, included, frontier);
        }
        return new Grown(included, added);
    }

    /** 從 node 往外看：規劃裡、還沒拿的鄰居放進候選。 */
    private static void expand(final PassiveTree tree, final PassiveNode node, final Set<String> plan, final Set<String> included,
                               final Set<String> frontier) {
        for (final PassiveNode neighbour : tree.neighbours(node)) {
            if (plan.contains(neighbour.id()) && !included.contains(neighbour.id())) {
                frontier.add(neighbour.id());
            }
        }
    }

    /** 規劃裡每個節點離起點幾步（只走規劃裡的節點）。 */
    private static Map<String, Integer> distances(final PassiveTree tree, final PassiveNode start, final Collection<String> plan) {
        final Map<String, Integer> distance = new HashMap<>();
        final Deque<PassiveNode> queue = new ArrayDeque<>();
        queue.add(start);
        distance.put(start.id(), 0);
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            for (final PassiveNode neighbour : tree.neighbours(current)) {
                if (plan.contains(neighbour.id()) && !distance.containsKey(neighbour.id())) {
                    distance.put(neighbour.id(), distance.get(current.id()) + 1);
                    queue.add(neighbour);
                }
            }
        }
        return distance;
    }
}
