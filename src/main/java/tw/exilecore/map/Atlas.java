package tw.exilecore.map;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 星圖（Atlas）的規則：完成（殺死 Boss）的地圖點亮，跟它相連的地圖解鎖；
 * {@code start: true} 的地圖一開始就解鎖；最外圈的地圖全部完成後解鎖終局。
 * 地圖物品的階級（T1–T16）跟星圖無關：解鎖的地圖哪一階都能開；完成過的最高階（{@link #BEST_KEY}）決定終局能開到幾階，
 * 完成過幾張不同的地圖決定掉落的地圖最高幾階。
 * 進度存在角色的 progress：{@code atlas.地圖代號}、{@code mapbest}。純邏輯。
 */
public final class Atlas {

    public static final String PROGRESS_PREFIX = "atlas.";
    /**
     * 完成過的最高階（數字）。故意不用 atlas. 開頭：任務、委託、試煉用「atlas. 開頭的鍵有幾個」數完成過幾張地圖。
     */
    public static final String BEST_KEY = "mapbest";

    private Atlas() {
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
            return finalUnlocked(config, completed) && finalRequirementsMet(config, map, completed);
        }
        if (map.start() || completed.contains(map.id())) {
            return true;
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

    /** 最外圈的地圖全部完成了沒（終局解鎖）。 */
    public static boolean finalUnlocked(final MapConfig config, final Set<String> completed) {
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

    /** 完成了幾張一般地圖（不含終局）。 */
    public static int completedMaps(final MapConfig config, final Set<String> completed) {
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
