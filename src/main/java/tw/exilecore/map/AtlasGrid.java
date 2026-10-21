package tw.exilecore.map;

import java.util.ArrayList;
import java.util.List;

/**
 * 程序生成的星圖（2026-10-21，照 PoE2）：無限大的六角格，每個角色一個種子，格子的內容由「種子 + 座標」算出來，
 * 同一個角色不管什麼時候看都一樣，不用存整張圖。純邏輯，不依賴 Bukkit。
 * <p>
 * 六角格用軸座標 (q, r)：相鄰是 (+1,0) (+1,−1) (0,−1) (−1,0) (−1,+1) (0,+1)；距離 d = (|q| + |r| + |q+r|) / 2。
 * <ul>
 *   <li>空格：雜訊低於門檻的格子是空的，但相鄰的兩格不會同時是空的（雜訊比較小的那格留下），所以星圖一定連通。</li>
 *   <li>塔、城塞：雜訊低於 1/every 的格子是候選，附近（塔半徑 2、城塞半徑 4）有雜訊更小的候選就讓位。</li>
 *   <li>地圖種類：同一區（region_scale 格一區）抽同一種，從「最小距離 ≤ 這格距離」的種類裡照權重抽。</li>
 *   <li>階級：照距離的範圍（atlas.yml 的 tiers）再抽一個。腐化：以區為單位。</li>
 * </ul>
 */
public final class AtlasGrid {

    /** 格子的種類。 */
    public enum Kind { EMPTY, NORMAL, BOSS, HIDDEN, TOWER, CITADEL }

    /** 一種地圖在星圖上的規則。 */
    public record Species(String mapId, int minDistance, int weight) {
    }

    /**
     * 一格。
     *
     * @param mapId        這格開起來是哪一種地圖（空格、塔是 null）
     * @param tier         這格預設的階級（掉落用；實際開幾階照放進裝置的地圖物品）
     * @param corrupted    腐化區
     * @param citadelFinal 城塞：是哪個終局的入口（不是城塞是 null）
     * @param purified     淨化區（2026-10-21；不會跟腐化區重疊）
     */
    public record Cell(int q, int r, int distance, Kind kind, String mapId, int tier, boolean corrupted, String citadelFinal, boolean purified) {
        public Cell(final int q, final int r, final int distance, final Kind kind, final String mapId, final int tier, final boolean corrupted,
                    final String citadelFinal) {
            this(q, r, distance, kind, mapId, tier, corrupted, citadelFinal, false);
        }

        public boolean isEmpty() {
            return kind == Kind.EMPTY;
        }

        /** 開起來是一張一般地圖（一般、Boss、隱藏格）。 */
        public boolean isMap() {
            return kind == Kind.NORMAL || kind == Kind.BOSS || kind == Kind.HIDDEN;
        }

        public long key() {
            return AtlasGrid.key(q, r);
        }
    }

    /** 六個方向。 */
    public static final int[][] DIRECTIONS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};

    private static final int SALT_EMPTY = 1;
    private static final int SALT_TOWER = 2;
    private static final int SALT_CITADEL = 3;
    private static final int SALT_CITADEL_WHICH = 4;
    private static final int SALT_BOSS = 5;
    private static final int SALT_HIDDEN = 6;
    private static final int SALT_CORRUPT = 7;
    private static final int SALT_SPECIES = 8;
    private static final int SALT_TIER = 9;
    private static final int SALT_PURIFY = 10;
    private static final int TOWER_SPACING = 2;
    private static final int CITADEL_SPACING = 4;

    private final AtlasConfig config;
    private final long seed;
    private final List<Species> species;
    private final List<String> citadelFinals;

    /**
     * @param species       所有一般地圖（照設定檔順序）
     * @param citadelFinals 有城塞的終局（照 requires 的順序；空的 = 沒有城塞）
     */
    public AtlasGrid(final AtlasConfig config, final long seed, final List<Species> species, final List<String> citadelFinals) {
        this.config = config;
        this.seed = seed;
        this.species = List.copyOf(species);
        this.citadelFinals = List.copyOf(citadelFinals);
    }

    public long seed() {
        return seed;
    }

    public AtlasConfig config() {
        return config;
    }

    public List<Species> species() {
        return species;
    }

    // ---- 六角格的數學 ----

    public static int distance(final int q, final int r) {
        return (Math.abs(q) + Math.abs(r) + Math.abs(q + r)) / 2;
    }

    public static int distance(final int q1, final int r1, final int q2, final int r2) {
        return distance(q1 - q2, r1 - r2);
    }

    public static long key(final int q, final int r) {
        return ((long) q << 32) ^ (r & 0xffffffffL);
    }

    public static int qOf(final long key) {
        return (int) (key >> 32);
    }

    public static int rOf(final long key) {
        return (int) key;
    }

    /** 半徑 radius 內（含自己）的所有座標。 */
    public static List<long[]> within(final int q, final int r, final int radius) {
        final List<long[]> list = new ArrayList<>();
        for (int dq = -radius; dq <= radius; dq++) {
            for (int dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) {
                list.add(new long[] {q + dq, r + dr});
            }
        }
        return list;
    }

    // ---- 雜訊 ----

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** 這格、這個用途的雜訊：0（含）到 1（不含）。 */
    double noise(final int q, final int r, final int salt) {
        final long h = mix(seed ^ mix(q * 0x9E3779B97F4A7C15L + salt) ^ mix(r * 0xC2B2AE3D27D4EB4FL + salt * 7919L));
        return (h >>> 11) * 0x1.0p-53;
    }

    // ---- 生成 ----

    /** 這格是不是空的：雜訊低於門檻，而且相鄰沒有雜訊更小、也低於門檻的格子（所以空格不相鄰、星圖連通）。 */
    public boolean isEmpty(final int q, final int r) {
        if (distance(q, r) <= 1) {
            return false;
        }
        final double mine = noise(q, r, SALT_EMPTY);
        if (mine >= config.emptyThreshold()) {
            return false;
        }
        for (final int[] direction : DIRECTIONS) {
            final int nq = q + direction[0];
            final int nr = r + direction[1];
            if (distance(nq, nr) <= 1) {
                continue;
            }
            final double other = noise(nq, nr, SALT_EMPTY);
            if (other < config.emptyThreshold() && other < mine) {
                return false;
            }
        }
        return true;
    }

    private boolean towerCandidate(final int q, final int r) {
        return distance(q, r) >= config.tower().minDistance() && !isEmpty(q, r) && noise(q, r, SALT_TOWER) < 1.0 / config.tower().every();
    }

    private boolean isTower(final int q, final int r) {
        if (!towerCandidate(q, r)) {
            return false;
        }
        final double mine = noise(q, r, SALT_TOWER);
        for (final long[] at : within(q, r, TOWER_SPACING)) {
            final int oq = (int) at[0];
            final int or = (int) at[1];
            if ((oq != q || or != r) && towerCandidate(oq, or) && noise(oq, or, SALT_TOWER) < mine) {
                return false;
            }
        }
        return true;
    }

    private boolean citadelCandidate(final int q, final int r) {
        return !citadelFinals.isEmpty() && distance(q, r) >= config.citadels().minDistance() && !isEmpty(q, r)
                && noise(q, r, SALT_CITADEL) < 1.0 / config.citadels().every();
    }

    private boolean isCitadel(final int q, final int r) {
        if (!citadelCandidate(q, r)) {
            return false;
        }
        final double mine = noise(q, r, SALT_CITADEL);
        for (final long[] at : within(q, r, CITADEL_SPACING)) {
            final int oq = (int) at[0];
            final int or = (int) at[1];
            if ((oq != q || or != r) && citadelCandidate(oq, or) && noise(oq, or, SALT_CITADEL) < mine) {
                return false;
            }
        }
        return true;
    }

    private String citadelFinal(final int q, final int r) {
        final int index = (int) Math.floor(noise(q, r, SALT_CITADEL_WHICH) * citadelFinals.size());
        return citadelFinals.get(Math.min(citadelFinals.size() - 1, Math.max(0, index)));
    }

    /** 這格屬於哪一區（同一區同一種地圖）。 */
    private long region(final int q, final int r, final int scale) {
        return key(Math.floorDiv(q, scale), Math.floorDiv(r, scale));
    }

    private String speciesOf(final int q, final int r, final int distance) {
        if (species.isEmpty()) {
            return null;
        }
        final List<Species> eligible = new ArrayList<>();
        int totalWeight = 0;
        for (final Species candidate : species) {
            if (candidate.minDistance() <= distance) {
                eligible.add(candidate);
                totalWeight += candidate.weight();
            }
        }
        if (eligible.isEmpty()) {
            Species nearest = species.get(0);
            for (final Species candidate : species) {
                if (candidate.minDistance() < nearest.minDistance()) {
                    nearest = candidate;
                }
            }
            return nearest.mapId();
        }
        final long region = region(q, r, config.regionScale());
        final double roll = noise(qOf(region), rOf(region), SALT_SPECIES) * totalWeight;
        double acc = 0;
        for (final Species candidate : eligible) {
            acc += candidate.weight();
            if (roll < acc) {
                return candidate.mapId();
            }
        }
        return eligible.get(eligible.size() - 1).mapId();
    }

    private boolean isCorrupted(final int q, final int r, final int distance) {
        if (distance < config.corruption().minDistance()) {
            return false;
        }
        final int scale = Math.max(1, (int) Math.round(Math.sqrt(config.corruption().patch())));
        final long region = region(q, r, scale);
        return noise(qOf(region), rOf(region), SALT_CORRUPT) < config.corruption().chance();
    }

    /** 淨化區（2026-10-21）：跟腐化區一樣照區域擲，腐化的區域不會淨化。 */
    private boolean isPurified(final int q, final int r, final int distance, final boolean corrupted) {
        if (corrupted || distance < config.purification().minDistance()) {
            return false;
        }
        final int scale = Math.max(1, (int) Math.round(Math.sqrt(config.purification().patch())));
        final long region = region(q, r, scale);
        return noise(qOf(region), rOf(region), SALT_PURIFY) < config.purification().chance();
    }

    private int tierOf(final int q, final int r, final int distance) {
        final int[] range = config.tierRange(distance);
        final int lo = Math.min(range[0], range[1]);
        final int hi = Math.max(range[0], range[1]);
        return lo + (int) Math.floor(noise(q, r, SALT_TIER) * (hi - lo + 1));
    }

    /** 算過的格子（2026-10-21：一格要算二三十次雜訊，選單、掉落一直問同一批格子；最多留 CACHE 格）。 */
    private static final int CACHE = 8192;
    private final java.util.Map<Long, Cell> cache = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final java.util.Map.Entry<Long, Cell> eldest) {
            return size() > CACHE;
        }
    });

    /** 這一格的內容。起點 (0, 0) 回傳一個 NORMAL 的格子（營地；介面另外畫）。 */
    public Cell cell(final int q, final int r) {
        final long key = key(q, r);
        final Cell cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        final Cell computed = compute(q, r);
        cache.put(key, computed);
        return computed;
    }

    private Cell compute(final int q, final int r) {
        final int distance = distance(q, r);
        if (isEmpty(q, r)) {
            return new Cell(q, r, distance, Kind.EMPTY, null, 0, false, null);
        }
        if (isCitadel(q, r)) { // 城塞先於塔（同一格兩個都中時當城塞）
            return new Cell(q, r, distance, Kind.CITADEL, null, tierOf(q, r, distance), false, citadelFinal(q, r));
        }
        if (isTower(q, r)) {
            return new Cell(q, r, distance, Kind.TOWER, null, tierOf(q, r, distance), false, null);
        }
        final Kind kind = noise(q, r, SALT_BOSS) < config.bossChance() ? Kind.BOSS
                : noise(q, r, SALT_HIDDEN) < config.hiddenChance() ? Kind.HIDDEN : Kind.NORMAL;
        final boolean corrupted = isCorrupted(q, r, distance);
        return new Cell(q, r, distance, kind, speciesOf(q, r, distance), tierOf(q, r, distance), corrupted, null,
                isPurified(q, r, distance, corrupted));
    }

    public Cell cell(final long key) {
        return cell(qOf(key), rOf(key));
    }

    /** 相鄰的六格（含空格）。 */
    public List<Cell> neighbours(final int q, final int r) {
        final List<Cell> list = new ArrayList<>(6);
        for (final int[] direction : DIRECTIONS) {
            list.add(cell(q + direction[0], r + direction[1]));
        }
        return list;
    }

    /** 這座塔罩到的範圍（含塔自己）：半徑 = 設定的半徑 + 加成。 */
    public List<long[]> towerCoverage(final int q, final int r, final int extraRadius) {
        return within(q, r, Math.max(1, config.tower().radius() + Math.max(0, extraRadius)));
    }
}
