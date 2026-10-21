package tw.exilecore.map;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 星圖（Atlas）的規則。2026-10-21 起星圖是程序生成的六角格（{@link AtlasGrid}、{@link AtlasProgress}），地圖「種類」的解鎖
 * 由格子決定；這裡的靜態方法照舊給所有地方用，只是「完成過的地圖」那個集合（{@link #view}）多了幾種記號：
 * <ul>
 *   <li>{@code 地圖代號}：這種地圖完成過（角色進度 {@code atlas.地圖代號}，首殺、星諭卡、任務照舊看這個）</li>
 *   <li>{@code unlocked:地圖代號}：這種地圖在星圖上解鎖了（至少一格解鎖）</li>
 *   <li>{@code frontier:地圖代號}：前線（解鎖了還沒完成的格子的種類；掉落時比較常掉）</li>
 *   <li>{@code citadel:終局代號}：攻破了那個終局的城塞（之後用碎片開）；角色進度 {@code citadel.終局代號}</li>
 *   <li>{@code cap:數字}：掉落上限用的「完成張數」（完成的格子 ÷ atlas.yml 的 per_cells）</li>
 * </ul>
 * 沒有這些記號的集合（舊的、測試裡手寫的）照以前的規則：相連的完成了就解鎖、最外圈全部完成解鎖終局。
 * 完成過的最高階（{@link #BEST_KEY}）決定終局能開到幾階。純邏輯。
 */
public final class Atlas {

    public static final String PROGRESS_PREFIX = "atlas.";
    /**
     * 完成過的最高階（數字）。故意不用 atlas. 開頭：任務、委託、試煉用「atlas. 開頭的鍵有幾個」數完成過幾張地圖。
     */
    public static final String BEST_KEY = "mapbest";
    public static final String UNLOCKED_TOKEN = "unlocked:";
    public static final String FRONTIER_TOKEN = "frontier:";
    public static final String CITADEL_TOKEN = "citadel:";
    public static final String CAP_TOKEN = "cap:";
    /** 這個集合是新星圖的（有記號）。 */
    private static final String MARK = "atlas:grid";

    private Atlas() {
    }

    /**
     * 新星圖的「完成過的地圖」集合（給所有靜態方法用）：完成過的種類、解鎖的種類、前線、攻破的城塞、掉落上限。
     *
     * @param progress 角色進度
     * @param atlas    這個角色的星圖進度
     */
    public static Set<String> view(final MapConfig config, final Map<String, String> progress, final AtlasProgress atlas) {
        final Set<String> set = new LinkedHashSet<>(completed(config, progress));
        set.add(MARK);
        for (final String id : atlas.completedSpecies()) {
            set.add(id);
        }
        for (final String id : atlas.unlockedSpecies()) {
            set.add(UNLOCKED_TOKEN + id);
        }
        for (final String id : atlas.frontierSpecies()) {
            set.add(FRONTIER_TOKEN + id);
        }
        for (final String id : atlas.breachedCitadels()) {
            set.add(CITADEL_TOKEN + id);
        }
        for (final MapConfig.MapDef map : config.finals()) {
            if (progress.containsKey(AtlasProgress.CITADEL_PREFIX + map.id())) {
                set.add(CITADEL_TOKEN + map.id());
            }
        }
        set.add(CAP_TOKEN + atlas.capUnits());
        return set;
    }

    /** 這個集合是不是新星圖的（有記號）。 */
    public static boolean isGrid(final Set<String> completed) {
        return completed != null && completed.contains(MARK);
    }

    /** 這種地圖在星圖上解鎖了（新星圖：至少一格解鎖）。 */
    public static boolean speciesUnlocked(final Set<String> completed, final String mapId) {
        return completed.contains(UNLOCKED_TOKEN + mapId);
    }

    /** 這種地圖是前線（解鎖了還沒完成的格子）。 */
    public static boolean frontier(final Set<String> completed, final String mapId) {
        return completed.contains(FRONTIER_TOKEN + mapId);
    }

    /** 攻破了這個終局的城塞。 */
    public static boolean citadelBreached(final Set<String> completed, final String finalId) {
        return completed.contains(CITADEL_TOKEN + finalId);
    }

    public static String key(final String mapId) {
        return PROGRESS_PREFIX + mapId;
    }

    /** 角色完成過的地圖（含終局）。 */
    public static Set<String> completed(final MapConfig config, final Map<String, String> progress) {
        final Set<String> set = new LinkedHashSet<>();
        for (final MapConfig.MapDef map : config.all()) {
            if (progress.containsKey(key(map.id()))) {
                set.add(map.id());
            }
        }
        return set;
    }

    /** 這張地圖能不能開（放進地圖裝置）。 */
    public static boolean unlocked(final MapConfig config, final MapConfig.MapDef map, final Set<String> completed) {
        if (map.isFinal()) {
            if (isGrid(completed)) {
                // 2026-10-21：新星圖：攻破這個終局的城塞（或以前就解鎖過終局）；requires 照舊
                // 以前解鎖過終局的角色，轉檔時四座城塞都標成攻破了（citadel.終局代號 = legacy），所以這裡只看城塞
                return citadelBreached(completed, map.id()) && finalRequirementsMet(config, map, completed);
            }
            return finalUnlocked(config, completed) && finalRequirementsMet(config, map, completed);
        }
        if (map.start() || completed.contains(map.id())) {
            return true;
        }
        if (isGrid(completed)) {
            return speciesUnlocked(completed, map.id()); // 2026-10-21：新星圖：解鎖看格子，不看相連
        }
        for (final String link : map.links()) {
            final MapConfig.MapDef other = config.find(link);
            if (other != null && !other.isFinal() && completed.contains(link)) {
                return true;
            }
        }
        return false;
    }

    /** 這個終局要先打贏的其他終局（maps.yml 的 finals.*.requires）都打贏了沒。 */
    public static boolean finalRequirementsMet(final MapConfig config, final MapConfig.MapDef map, final Set<String> completed) {
        final MapConfig.FinalInfo info = config.finalInfo(map.id());
        if (info == null) {
            return true;
        }
        for (final String required : info.requires()) {
            if (!completed.contains(required)) {
                return false;
            }
        }
        return true;
    }

    /** 還沒打贏、但這個終局要求先打贏的終局名稱（「天穹熔爐」）；都打贏了是空字串。 */
    public static String missingRequirements(final MapConfig config, final MapConfig.MapDef map, final Set<String> completed) {
        final MapConfig.FinalInfo info = config.finalInfo(map.id());
        if (info == null) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (final String required : info.requires()) {
            if (!completed.contains(required)) {
                final MapConfig.MapDef other = config.find(required);
                names.add(other == null ? required : other.name());
            }
        }
        return String.join("、", names);
    }

    /** 終局解鎖了沒：新星圖是攻破過任何一座城塞（或以前就解鎖過）；舊的是最外圈的地圖全部完成。 */
    public static boolean finalUnlocked(final MapConfig config, final Set<String> completed) {
        if (isGrid(completed)) {
            for (final String token : completed) {
                if (token.startsWith(CITADEL_TOKEN)) {
                    return true;
                }
            }
            return false; // 2026-10-21 漏洞修正：新星圖只看城塞（最外圈的種類在新星圖上很容易完成，不能當成解鎖）
        }
        return legacyFinalUnlocked(config, completed);
    }

    /** 舊規則：最外圈的地圖全部完成（轉檔前解鎖過終局的角色照舊算解鎖）。 */
    static boolean legacyFinalUnlocked(final MapConfig config, final Set<String> completed) {
        final int top = config.maxRing();
        if (top <= 0) {
            return false;
        }
        for (final MapConfig.MapDef map : config.ofRing(top)) {
            if (!completed.contains(map.id())) {
                return false;
            }
        }
        return true;
    }

    /** 完成過幾種一般地圖（不含終局；不看記號）。 */
    public static int completedSpecies(final MapConfig config, final Set<String> completed) {
        int count = 0;
        for (final MapConfig.MapDef map : config.maps()) {
            if (completed.contains(map.id())) {
                count++;
            }
        }
        return count;
    }

    /** 完成了幾張一般地圖（不含終局）；新星圖回傳掉落上限用的「完成張數」（cap: 記號）。 */
    public static int completedMaps(final MapConfig config, final Set<String> completed) {
        if (isGrid(completed)) {
            for (final String token : completed) {
                if (token.startsWith(CAP_TOKEN)) {
                    return parseTier(token.substring(CAP_TOKEN.length()));
                }
            }
        }
        int count = 0;
        for (final MapConfig.MapDef map : config.maps()) {
            if (completed.contains(map.id())) {
                count++;
            }
        }
        return count;
    }

    /** 掉落地圖時階級 +1 的機率：基本值＋每完成一張多一點。 */
    public static double tierUpChance(final MapConfig config, final int completedMaps) {
        return Math.min(1.0, config.drops().tierUpChance() + config.drops().tierUpPerCompleted() * completedMaps);
    }

    /** 掉落的地圖最高幾階：一開始 tier_cap_base，每完成一張不同的地圖 + tier_cap_per_map，最多到最高階。 */
    public static int dropTierCap(final MapConfig config, final int completedMaps) {
        final long cap = config.drops().tierCapBase() + (long) config.drops().tierCapPerMap() * Math.max(0, completedMaps);
        return (int) Math.max(1, Math.min(config.tiers().max(), cap));
    }

    /**
     * 完成過的最高階（終局能開到這一階）：存在 {@link #BEST_KEY}。還沒有這一筆的角色（地圖分階以前完成的）
     * 當作完成過的地圖裡最外圈的圈數（以前地圖的階級就是圈數）。都沒有回傳 0。
     */
    public static int bestTier(final MapConfig config, final Map<String, String> progress, final Set<String> completed) {
        int best = parseTier(progress.get(BEST_KEY));
        if (best <= 0) {
            for (final MapConfig.MapDef map : config.maps()) {
                if (completed.contains(map.id())) {
                    best = Math.max(best, map.ring());
                }
            }
        }
        return Math.min(best, config.tiers().max());
    }

    /** 存的種子；沒有或寫壞了是 0。 */
    public static long parseLong(final String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 存的最高階；沒有或寫壞了是 0。 */
    static int parseTier(final String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 星圖上兩張相連的地圖之間要畫連線的格子（兩個點的中點；相鄰或距離超過 2 格的不畫）。 */
    public record Connector(int col, int row, String from, String to) {
    }

    public static List<Connector> connectors(final MapConfig config) {
        final List<Connector> list = new ArrayList<>();
        final Set<String> done = new LinkedHashSet<>();
        for (final MapConfig.MapDef map : config.all()) {
            for (final String link : map.links()) {
                final MapConfig.MapDef other = config.find(link);
                if (other == null) {
                    continue;
                }
                final String pair = map.id().compareTo(other.id()) < 0 ? map.id() + "|" + other.id() : other.id() + "|" + map.id();
                if (!done.add(pair)) {
                    continue;
                }
                final int dc = other.col() - map.col();
                final int dr = other.row() - map.row();
                if (Math.abs(dc) > 2 || Math.abs(dr) > 2 || (dc & 1) != 0 || (dr & 1) != 0 || (dc == 0 && dr == 0)) {
                    continue;
                }
                list.add(new Connector(map.col() + dc / 2, map.row() + dr / 2, map.id(), other.id()));
            }
        }
        return list;
    }
}
