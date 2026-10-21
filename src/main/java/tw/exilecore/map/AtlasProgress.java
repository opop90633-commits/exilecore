package tw.exilecore.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一個角色在星圖上的進度（2026-10-21）：完成了哪些格子，由此算出解鎖了哪些格子、哪些地圖種類、天賦點、掉落上限。純邏輯。
 * <p>
 * 存檔：完成的格子存成「q:r,q:r,…」（{@link #encode}），超過一格存不下時分成好幾個鍵（{@link #CELLS_KEY}、{@link #CELLS_KEY}2…）。
 */
public final class AtlasProgress {

    /** 角色進度：種子。故意不用 atlas. 開頭（任務、委託用「atlas. 開頭的鍵有幾個」數完成過幾張地圖）。 */
    public static final String SEED_KEY = "atlasseed";
    /** 角色進度：完成的格子（第 2 個以後的鍵是 atlascells2、atlascells3…）。 */
    public static final String CELLS_KEY = "atlascells";
    /** 角色進度：下次開圖要用的格子（q:r）；沒有就自動選。 */
    public static final String CHOSEN_KEY = "atlaschosen";
    /** 角色進度：攻破的城塞（終局代號；之後那個終局用碎片開）。前綴後面接終局代號。 */
    public static final String CITADEL_PREFIX = "citadel.";
    /** 一個進度欄位最多幾個字（資料庫 VARCHAR(4096)），留一點餘裕。 */
    public static final int CHUNK = 4000;
    /** 找最近的格子最多找到幾格遠（星圖再外面的交給玩家自己走）。 */
    public static final int SEARCH_RADIUS = 40;

    private final AtlasGrid grid;
    private final Set<Long> completed;
    private Set<Long> unlockedCache;
    private int unlockedFor = -1;

    public AtlasProgress(final AtlasGrid grid, final Collection<Long> completed) {
        this.grid = grid;
        this.completed = new LinkedHashSet<>(completed);
    }

    public AtlasGrid grid() {
        return grid;
    }

    /** 完成的格子（照完成順序）。 */
    public Set<Long> completed() {
        return java.util.Collections.unmodifiableSet(completed);
    }

    public boolean isCompleted(final int q, final int r) {
        return completed.contains(AtlasGrid.key(q, r));
    }

    /** 完成一格；本來就完成了回傳 false。 */
    public boolean complete(final int q, final int r) {
        final boolean added = completed.add(AtlasGrid.key(q, r));
        if (added) {
            unlockedFor = -1;
        }
        return added;
    }

    // ---- 解鎖 ----

    /** 解鎖了的格子（含完成的；不含空格）：起點周圍、每個完成的格子周圍、每座完成的塔周圍。 */
    public Set<Long> unlocked() {
        if (unlockedCache != null && unlockedFor == completed.size()) {
            return unlockedCache;
        }
        final Set<Long> set = new LinkedHashSet<>();
        final AtlasConfig.Unlock unlock = grid.config().unlock();
        addWithin(set, 0, 0, unlock.startRadius());
        for (final long key : completed) {
            final int q = AtlasGrid.qOf(key);
            final int r = AtlasGrid.rOf(key);
            final AtlasGrid.Cell cell = grid.cell(q, r);
            addWithin(set, q, r, cell.kind() == AtlasGrid.Kind.TOWER ? unlock.onTower() : unlock.onComplete());
        }
        set.remove(AtlasGrid.key(0, 0));
        unlockedCache = java.util.Collections.unmodifiableSet(set);
        unlockedFor = completed.size();
        return unlockedCache;
    }

    private void addWithin(final Set<Long> set, final int q, final int r, final int radius) {
        for (final long[] at : AtlasGrid.within(q, r, radius)) {
            final int aq = (int) at[0];
            final int ar = (int) at[1];
            if (!grid.isEmpty(aq, ar)) {
                set.add(AtlasGrid.key(aq, ar));
            }
        }
    }

    public boolean isUnlocked(final int q, final int r) {
        return unlocked().contains(AtlasGrid.key(q, r));
    }

    /** 解鎖了（含完成）的地圖種類。 */
    public Set<String> unlockedSpecies() {
        final Set<String> set = new LinkedHashSet<>();
        for (final long key : unlocked()) {
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.isMap() && cell.mapId() != null) {
                set.add(cell.mapId());
            }
        }
        return set;
    }

    /** 前線：解鎖了還沒完成的格子的地圖種類（掉落時比較常掉）。 */
    public Set<String> frontierSpecies() {
        final Set<String> set = new LinkedHashSet<>();
        for (final long key : unlocked()) {
            if (completed.contains(key)) {
                continue;
            }
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.isMap() && cell.mapId() != null) {
                set.add(cell.mapId());
            }
        }
        return set;
    }

    /** 完成過的地圖種類。 */
    public Set<String> completedSpecies() {
        final Set<String> set = new LinkedHashSet<>();
        for (final long key : completed) {
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.isMap() && cell.mapId() != null) {
                set.add(cell.mapId());
            }
        }
        return set;
    }

    /** 攻破的城塞：哪些終局的入口完成了。 */
    public Set<String> breachedCitadels() {
        final Set<String> set = new LinkedHashSet<>();
        for (final long key : completed) {
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.kind() == AtlasGrid.Kind.CITADEL && cell.citadelFinal() != null) {
                set.add(cell.citadelFinal());
            }
        }
        return set;
    }

    /**
     * 開這種地圖預設用哪一格：解鎖了、種類相同、還沒完成的裡面離起點最近的；都完成了就最近的完成格；沒有回傳 null。
     */
    public AtlasGrid.Cell defaultCell(final String mapId) {
        AtlasGrid.Cell best = null;
        AtlasGrid.Cell bestDone = null;
        for (final long key : unlocked()) {
            final AtlasGrid.Cell cell = grid.cell(key);
            if (!cell.isMap() || !mapId.equals(cell.mapId())) {
                continue;
            }
            if (completed.contains(key)) {
                if (bestDone == null || closer(cell, bestDone)) {
                    bestDone = cell;
                }
            } else if (best == null || closer(cell, best)) {
                best = cell;
            }
        }
        return best != null ? best : bestDone;
    }

    private static boolean closer(final AtlasGrid.Cell a, final AtlasGrid.Cell b) {
        if (a.distance() != b.distance()) {
            return a.distance() < b.distance();
        }
        return a.key() < b.key();
    }

    /** 解鎖了、還沒完成的塔（照距離）。 */
    public List<AtlasGrid.Cell> openTowers() {
        final List<AtlasGrid.Cell> list = new ArrayList<>();
        for (final long key : unlocked()) {
            if (completed.contains(key)) {
                continue;
            }
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.kind() == AtlasGrid.Kind.TOWER) {
                list.add(cell);
            }
        }
        list.sort((a, b) -> closer(a, b) ? -1 : closer(b, a) ? 1 : 0);
        return list;
    }

    /** 解鎖了、還沒完成的城塞（照距離）。 */
    public List<AtlasGrid.Cell> openCitadels() {
        final List<AtlasGrid.Cell> list = new ArrayList<>();
        for (final long key : unlocked()) {
            if (completed.contains(key)) {
                continue;
            }
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.kind() == AtlasGrid.Kind.CITADEL) {
                list.add(cell);
            }
        }
        list.sort((a, b) -> closer(a, b) ? -1 : closer(b, a) ? 1 : 0);
        return list;
    }

    /** 完成的塔（含座標，石板用）。 */
    public List<AtlasGrid.Cell> completedTowers() {
        final List<AtlasGrid.Cell> list = new ArrayList<>();
        for (final long key : completed) {
            final AtlasGrid.Cell cell = grid.cell(key);
            if (cell.kind() == AtlasGrid.Kind.TOWER) {
                list.add(cell);
            }
        }
        return list;
    }

    // ---- 點數 ----

    /** 完成了幾格一般地圖（一般、Boss、隱藏）。 */
    public int completedMapCells() {
        int count = 0;
        for (final long key : completed) {
            if (grid.cell(key).isMap()) {
                count++;
            }
        }
        return count;
    }

    public int completedTowerCount() {
        return completedTowers().size();
    }

    public int completedCitadelCount() {
        return breachedCitadels().size();
    }

    /** 走到最遠的距離。 */
    public int farthest() {
        int far = 0;
        for (final long key : completed) {
            far = Math.max(far, AtlasGrid.distance(AtlasGrid.qOf(key), AtlasGrid.rOf(key)));
        }
        return far;
    }

    /** 完成的格子給的天賦點（不含終局、極境、階級；那些照 atlas_tree.yml）。 */
    public int cellPoints() {
        final AtlasConfig.Points points = grid.config().points();
        final int cells = completedMapCells();
        int total = Math.min(cells, points.softCap()) * points.perCell();
        if (cells > points.softCap()) {
            total += (cells - points.softCap()) / points.afterCapEvery();
        }
        total += completedTowerCount() * points.perTower();
        total += completedCitadelCount() * points.perCitadel();
        return total;
    }

    /** 掉落上限用的「完成張數」：完成的一般格子 ÷ per_cells（舊規則一張一階）。 */
    public int capUnits() {
        return completedMapCells() / Math.max(1, grid.config().dropCap().perCells());
    }

    // ---- 存檔 ----

    /** 完成的格子 → 字串「q:r,q:r,…」。 */
    public static String encode(final Collection<Long> cells) {
        final StringBuilder sb = new StringBuilder();
        for (final long key : cells) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(AtlasGrid.qOf(key)).append(':').append(AtlasGrid.rOf(key));
        }
        return sb.toString();
    }

    /** 字串 → 完成的格子（寫壞的略過）。 */
    public static List<Long> decode(final String stored) {
        final List<Long> list = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return list;
        }
        for (final String part : stored.split(",")) {
            final String[] pair = part.trim().split(":");
            if (pair.length != 2) {
                continue;
            }
            try {
                list.add(AtlasGrid.key(Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim())));
            } catch (final NumberFormatException ignored) {
                // 寫壞的略過
            }
        }
        return list;
    }

    /** 一格 → 「q:r」。 */
    public static String encodeOne(final int q, final int r) {
        return q + ":" + r;
    }

    /** 「q:r」→ 一格；寫壞的回傳 null。 */
    public static long[] decodeOne(final String stored) {
        final List<Long> list = decode(stored);
        return list.isEmpty() ? null : new long[] {AtlasGrid.qOf(list.get(0)), AtlasGrid.rOf(list.get(0))};
    }

    /** 分成好幾個欄位存：第 i 個的鍵（1 起）。 */
    public static String cellsKey(final int index) {
        return index <= 1 ? CELLS_KEY : CELLS_KEY + index;
    }

    /** 完成的格子分成幾段（每段最多 {@link #CHUNK} 字），依序對應 cellsKey(1)、cellsKey(2)…；空的回傳空清單。 */
    public static List<String> chunks(final Collection<Long> cells) {
        final List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (final long key : cells) {
            final String one = AtlasGrid.qOf(key) + ":" + AtlasGrid.rOf(key);
            if (current.length() > 0 && current.length() + 1 + one.length() > CHUNK) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append(',');
            }
            current.append(one);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** 從角色進度讀完成的格子（所有分段）。 */
    public static List<Long> readCells(final Map<String, String> progress) {
        final List<Long> list = new ArrayList<>();
        for (int i = 1; i < 1000; i++) {
            final String stored = progress.get(cellsKey(i));
            if (stored == null) {
                break;
            }
            list.addAll(decode(stored));
        }
        return list;
    }

    // ---- 舊存檔轉檔 ----

    /**
     * 舊的星圖（14 格固定）轉成格子：每一種完成過的地圖，從起點往外找最近的那種格子，連同路上的格子一起標成完成
     * （星圖才會連通、解鎖才會接得上）。回傳要標成完成的格子（照順序）。
     */
    public static List<Long> migrate(final AtlasGrid grid, final Collection<String> completedSpecies) {
        final List<Long> result = new ArrayList<>();
        final Set<Long> done = new LinkedHashSet<>();
        for (final String mapId : completedSpecies) {
            final List<Long> path = pathToSpecies(grid, done, mapId);
            for (final long key : path) {
                if (done.add(key)) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    /** 從起點（或已完成的格子）出發、只走非空格，找最近的那種地圖的格子；回傳要新完成的格子（含目標）。 */
    static List<Long> pathToSpecies(final AtlasGrid grid, final Set<Long> done, final String mapId) {
        final Map<Long, Long> parent = new HashMap<>();
        final Deque<Long> queue = new ArrayDeque<>();
        final Set<Long> seen = new HashSet<>();
        final long origin = AtlasGrid.key(0, 0);
        seen.add(origin);
        queue.add(origin);
        for (final long key : done) {
            if (seen.add(key)) {
                queue.add(key);
            }
        }
        while (!queue.isEmpty()) {
            final long current = queue.poll();
            final int q = AtlasGrid.qOf(current);
            final int r = AtlasGrid.rOf(current);
            if (AtlasGrid.distance(q, r) > SEARCH_RADIUS) {
                continue;
            }
            for (final int[] direction : AtlasGrid.DIRECTIONS) {
                final int nq = q + direction[0];
                final int nr = r + direction[1];
                final long next = AtlasGrid.key(nq, nr);
                if (!seen.add(next) || grid.isEmpty(nq, nr)) {
                    continue;
                }
                final AtlasGrid.Cell cell = grid.cell(nq, nr);
                if (!cell.isMap()) {
                    continue; // 2026-10-21 漏洞修正：不能穿過塔與城塞（不然轉檔會順便把它們標成完成）
                }
                parent.put(next, current);
                if (cell.isMap() && mapId.equals(cell.mapId())) {
                    final List<Long> path = new ArrayList<>();
                    long step = next;
                    while (step != origin && !done.contains(step)) {
                        path.add(step);
                        final Long up = parent.get(step);
                        if (up == null) {
                            break;
                        }
                        step = up;
                    }
                    java.util.Collections.reverse(path);
                    return path;
                }
                queue.add(next);
            }
        }
        return List.of();
    }
}
