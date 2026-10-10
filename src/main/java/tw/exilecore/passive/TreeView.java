package tw.exilecore.passive;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 天賦樹畫面要畫什麼（純邏輯，視窗與網頁都照這個）：每一格的節點狀態、往上下左右的連線狀態，以及縮小地圖的分區。
 */
public final class TreeView {

    /** 節點的狀態。 */
    public enum NodeState {
        /** 已配置（含自己的起點）。 */
        OWNED,
        /** 路徑預覽上。 */
        PATH,
        /** 現在就能配（跟已配置的相鄰）。 */
        AVAILABLE,
        /** 還連不到。 */
        LOCKED
    }

    /** 連線的狀態。 */
    public enum EdgeState {
        /** 兩端都配了。 */
        OWNED,
        /** 路徑預覽上。 */
        PATH,
        /** 一端配了、另一端能配。 */
        LIT,
        /** 其他。 */
        DIM
    }

    /** 上、右、下、左。 */
    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * 一格。node 為 null 時是空格（只有背景）；edges 是上右下左（沒有連線為 null）。
     */
    public record Tile(int x, int y, PassiveNode node, NodeState state, EdgeState[] edges, boolean highlight, int background) {
    }

    /** 縮小地圖的一區。 */
    public record Region(int column, int row, int minX, int minY, int maxX, int maxY, List<PassiveNode> nodes,
                         int owned, int available, PassiveNode landmark) {

        public int centerX() {
            return (minX + maxX) / 2;
        }

        public int centerY() {
            return (minY + maxY) / 2;
        }

        public boolean contains(final int x, final int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private final PassiveTree tree;
    private final PassiveNode start;
    private final Set<String> allocated;
    private final Set<String> path;
    private final Set<String> highlights;

    public TreeView(final PassiveTree tree, final PassiveNode start, final Set<String> allocated, final Set<String> path,
                    final Set<String> highlights) {
        this.tree = tree;
        this.start = start;
        this.allocated = allocated;
        this.path = path;
        this.highlights = highlights;
    }

    public boolean owned(final PassiveNode node) {
        return start != null && (node.id().equals(start.id()) || allocated.contains(node.id()));
    }

    public NodeState state(final PassiveNode node) {
        if (owned(node)) {
            return NodeState.OWNED;
        }
        if (path.contains(node.id())) {
            return NodeState.PATH;
        }
        if (start != null && tree.canAllocate(allocated, start, node)) {
            return NodeState.AVAILABLE;
        }
        return NodeState.LOCKED;
    }

    public EdgeState edge(final PassiveNode a, final PassiveNode b) {
        final NodeState sa = state(a);
        final NodeState sb = state(b);
        if (sa == NodeState.OWNED && sb == NodeState.OWNED) {
            return EdgeState.OWNED;
        }
        final boolean onA = sa == NodeState.PATH || sa == NodeState.OWNED;
        final boolean onB = sb == NodeState.PATH || sb == NodeState.OWNED;
        if (onA && onB) {
            return EdgeState.PATH;
        }
        if (sa == NodeState.OWNED && sb == NodeState.AVAILABLE || sb == NodeState.OWNED && sa == NodeState.AVAILABLE) {
            return EdgeState.LIT;
        }
        return EdgeState.DIM;
    }

    public Tile tile(final int x, final int y) {
        final int background = Math.floorMod(x * 7 + y * 13 + x * y, 4);
        final PassiveNode node = tree.at(x, y);
        if (node == null) {
            return new Tile(x, y, null, null, new EdgeState[4], false, background);
        }
        final EdgeState[] edges = new EdgeState[4];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            final PassiveNode neighbour = tree.at(x + DIRECTIONS[i][0], y + DIRECTIONS[i][1]);
            if (neighbour != null) {
                edges[i] = edge(node, neighbour);
            }
        }
        return new Tile(x, y, node, state(node), edges, highlights.contains(node.id()), background);
    }

    // ---- 縮小地圖 ----

    /** 每區幾格寬（讓整棵樹橫向塞得進 columns 欄）。 */
    public static int regionWidth(final PassiveTree tree, final int columns) {
        final int width = tree.maxX() - tree.minX() + 1;
        return Math.max(1, (width + columns - 1) / columns);
    }

    public static int regionHeight(final PassiveTree tree, final int rows) {
        final int height = tree.maxY() - tree.minY() + 1;
        return Math.max(1, (height + rows - 1) / rows);
    }

    /** 整棵樹分成最多 columns × rows 區（照列、照欄的順序）。 */
    public List<Region> regions(final int columns, final int rows) {
        final int w = regionWidth(tree, columns);
        final int h = regionHeight(tree, rows);
        final List<Region> regions = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                final int minX = tree.minX() + column * w;
                final int minY = tree.minY() + row * h;
                if (minX > tree.maxX() || minY > tree.maxY()) {
                    continue;
                }
                final int maxX = minX + w - 1;
                final int maxY = minY + h - 1;
                final List<PassiveNode> nodes = new ArrayList<>();
                int owned = 0;
                int available = 0;
                PassiveNode landmark = null;
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        final PassiveNode node = tree.at(x, y);
                        if (node == null) {
                            continue;
                        }
                        nodes.add(node);
                        final NodeState state = state(node);
                        if (state == NodeState.OWNED) {
                            owned++;
                        } else if (state == NodeState.AVAILABLE) {
                            available++;
                        }
                        if (landmark == null || rank(node) > rank(landmark)) {
                            landmark = node;
                        }
                    }
                }
                regions.add(new Region(column, row, minX, minY, maxX, maxY, List.copyOf(nodes), owned, available, landmark));
            }
        }
        return regions;
    }

    private int rank(final PassiveNode node) {
        if (start != null && node.id().equals(start.id())) {
            return 5;
        }
        return switch (node.type()) {
            case KEYSTONE -> 4;
            case START -> 3;
            case NOTABLE -> 2;
            default -> 1;
        };
    }
}
