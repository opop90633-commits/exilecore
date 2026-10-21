package tw.exilecore.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一張地圖吃到的星圖天賦（開地圖的人點的，整張地圖、整個隊伍都算）：效果鍵（{@link AtlasTree#STATS}）→ 加總的數值。純資料。
 * 百分比的效果存的是百分比點數（20 = 20%）；次數類（多幾張、多幾個）是整數。
 */
public record AtlasBonus(Map<String, Double> values) {

    /** 沒有任何天賦（特別的實例、極境、沒點天賦的人開的地圖）。 */
    /** 核心「魔王的宴席」：地圖 Boss 的掉落（傳奇機率、地圖、星諭卡）的倍率（2026-10-21：×2 → ×1.5）。 */
    public static final double BOSS_FEAST = 1.5;

    public static final AtlasBonus NONE = new AtlasBonus(Map.of());

    public AtlasBonus {
        values = Map.copyOf(values);
    }

    /** 這個效果的數值（沒有是 0）。 */
    public double get(final String stat) {
        final Double value = values.get(stat);
        return value == null ? 0 : value;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 多一條效果（加在原本的上面）；回傳新的。 */
    public AtlasBonus plus(final String stat, final double value) {
        final Map<String, Double> next = new java.util.LinkedHashMap<>(values);
        next.merge(stat, value, Double::sum);
        return new AtlasBonus(next);
    }

    /** 「增加 x%」→ 小數（20 → 0.2）。 */
    public double increased(final String stat) {
        return Math.max(0, get(stat)) / 100.0;
    }

    /** 次數類（多幾張、多幾個）：無條件捨去。 */
    public int count(final String stat) {
        return (int) Math.floor(Math.max(0, get(stat)) + 1e-9);
    }

    // ---- 常用的 ----

    /** 掉落的地圖高一階的機率（加在基本機率上，小數）。 */
    public double tierUp() {
        return increased("atlas.tier_up");
    }

    /** 地圖怪掉地圖的機率乘上 1 + 這個。 */
    public double mapDrop() {
        return increased("atlas.map_drop");
    }

    /** 地圖 Boss 多掉幾張地圖。 */
    public int bossMaps() {
        return count("atlas.boss_maps");
    }

    /** 地圖怪給的經驗乘上 1 + 這個。 */
    public double experience() {
        return increased("atlas.experience");
    }

    /** 事件效果的鍵：atlas.神龕／寶箱／裂隙／流寇／深淵裂縫.什麼（2026-10-18：＋abyss；safe 不照權重抽、也沒有專屬，沒有這些鍵）。 */
    static String kindKey(final MapEventConfig.Kind kind, final String what) {
        return "atlas." + kind.name().toLowerCase(Locale.ROOT) + "." + what;
    }

    /** 2026-10-21 核心「單一信仰」選的事件的記號鍵（atlas.faith.shrine…；值 1 = 選了這種）。 */
    public static String faithKey(final MapEventConfig.Kind kind) {
        return "atlas.faith." + kind.name().toLowerCase(Locale.ROOT);
    }

    /** 這種事件出現的權重乘上 1 + 這個。 */
    public double eventWeight(final MapEventConfig.Kind kind) {
        return increased(kindKey(kind, "weight"));
    }

    /** 每張地圖保證多幾個這種事件。 */
    public int extraEvents(final MapEventConfig.Kind kind) {
        return count(kindKey(kind, "extra"));
    }

    /** 這種事件掉專屬傳奇的機率乘上 1 + 這個。 */
    public double eventUnique(final MapEventConfig.Kind kind) {
        return increased(kindKey(kind, "unique"));
    }

    /** 說明（介面、地圖裝置）：照效果的固定順序，一條一行。 */
    public List<String> describe() {
        final List<String> lines = new ArrayList<>();
        for (final String stat : AtlasTree.STATS.keySet()) {
            final double value = get(stat);
            if (value > 0) {
                lines.add(AtlasTree.describe(stat, value));
            }
        }
        for (final String stat : AtlasTree.DRAWBACKS.keySet()) { // 2026-10-21：核心的代價
            final double value = get(stat);
            if (value > 0) {
                lines.add(AtlasTree.describe(stat, value));
            }
        }
        return lines;
    }
}
