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

    /**
     * 一格。node 為 null 時是空格（只有背景，或有連線經過）；edges 是上右下左（沒有連線為 null）。
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
    /** 2026-10-26 照 PoE2：昇華給的配點規則（{@link PassiveTree#RULES}）。 */
    private final Set<String> rules;
    /** 2026-10-26 照站長：角色的昇華代號（觀星專屬節點只有觀星看得到，見 {@link PassiveTree#hidden}）。 */
    private final String ascendancy;

    public TreeView(final PassiveTree tree, final PassiveNode start, final Set<String> allocated, final Set<String> path,
                    final Set<String> highlights) {
        this(tree, start, allocated, path, highlights, Set.of());
    }

    public TreeView(final PassiveTree tree, final PassiveNode start, final Set<String> allocated, final Set<String> path,
                    final Set<String> highlights, final Set<String> rules) {
        this(tree, start, allocated, path, highlights, rules, PassiveTree.SHOW_ALL);
    }

    public TreeView(final PassiveTree tree, final PassiveNode start, final Set<String> allocated, final Set<String> path,
                    final Set<String> highlights, final Set<String> rules, final String ascendancy) {
        this.ascendancy = ascendancy;
        this.rules = rules;
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
        if (start != null && tree.canAllocate(allocated, start, node, rules)) {
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

    /** 這一格看得到的節點（藏起來的當作空格）。 */
    public PassiveNode at(final int x, final int y) {
        final PassiveNode node = tree.at(x, y);
        return node == null || tree.hidden(node, ascendancy) ? null : node;
    }

    public Tile tile(final int x, final int y) {
        final int background = Math.floorMod(x * 7 + y * 13 + x * y, 4);
        final PassiveNode node = at(x, y);
        // 2026-10-26：連線照 tree.json 的 links 畫（不相鄰的連線走過中間的空格）；同一個方向有好幾條線時取最亮的
        final EdgeState[] edges = new EdgeState[4];
        for (final PassiveTree.CellWire wire : tree.wiresAt(x, y)) {
            if (tree.hidden(wire.link().a(), ascendancy) || tree.hidden(wire.link().b(), ascendancy)) {
                continue; // 連到藏起來的節點的線也不畫
            }
            EdgeState state = null;
            for (int i = 0; i < 4; i++) {
                if ((wire.directions() & (1 << i)) == 0) {
                    continue;
                }
                if (state == null) {
                    state = edge(wire.link().a(), wire.link().b());
                }
                if (edges[i] == null || state.ordinal() < edges[i].ordinal()) {
                    edges[i] = state;
                }
            }
        }
        if (node == null) {
            return new Tile(x, y, null, null, edges, false, background);
        }
        return new Tile(x, y, node, state(node), edges, highlights.contains(node.id()), background);
    }

    /** 這一格有沒有連線經過（空格才有意義）。 */
    public static boolean hasWire(final Tile tile) {
        for (final EdgeState edge : tile.edges()) {
            if (edge != null) {
                return true;
            }
        }
        return false;
    }

    /** 這一格的連線裡最亮的狀態（沒有線為 null）。 */
    public static EdgeState brightest(final Tile tile) {
        EdgeState best = null;
        for (final EdgeState edge : tile.edges()) {
            if (edge != null && (best == null || edge.ordinal() < best.ordinal())) {
                best = edge;
            }
        }
        return best;
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
                        final PassiveNode node = at(x, y);
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
