package tw.exilecore.salvage;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 傳奇三換一（拆解台的「傳奇三換一」）：放進 {@code inputs} 件傳奇，換一件隨機的傳奇。純計算，不依賴 Bukkit。
 * <ul>
 *   <li>能放的：已鑑定、沒鎖定的傳奇。極境專屬不能放（換掉太可惜）；已經不在 uniques.yml 的也不能放。</li>
 *   <li>追逐級（2026-10）：放 {@code inputs} 件追逐級，必定換一件隨機追逐級（{@link #chaseOutcomes}；可能換到同一種；不能跟一般傳奇混放）。</li>
 *   <li>換出來的池子：uniques.yml 的 sources 有 salvage.yml 的 {@code unique_recipe.sources} 其中一個
 *       （預設：地圖 Boss 池、六種地圖事件、兩個具名 Boss）。追逐級、極境專屬不管怎麼寫都不在池子裡。</li>
 *   <li>物品等級 = 放進去最高的那件；傳奇的 drop_level 要到。</li>
 *   <li>不會換到放進去的那幾種（{@code exclude_inputs}；池子只剩那幾種時例外）。</li>
 * </ul>
 */
public final class UniqueRecipe {

    /** 預設的池子：地圖 Boss 池、六種地圖事件、兩個具名 Boss（終局自己的、只能賭的、追逐級、極境專屬都不換）。 */
    public static final List<String> DEFAULT_SOURCES = List.of("map_boss", "shrine", "strongbox", "breach", "exile", "ritual", "delirium",
            "royal_tomb", "observatory");
    /** 最少、最多放幾件。 */
    public static final int MIN_INPUTS = 2;
    public static final int MAX_INPUTS = 6;

    private UniqueRecipe() {
    }

    /**
     * salvage.yml 的 unique_recipe。
     *
     * @param enabled       開不開（拆解台上有沒有這個按鈕）
     * @param inputs        放幾件換一件
     * @param sources       換出來的池子（uniques.yml 的 sources）
     * @param excludeInputs 不會換到放進去的那幾種
     */
    public record Settings(boolean enabled, int inputs, List<String> sources, boolean excludeInputs) {

        public Settings {
            sources = List.copyOf(sources);
        }

        public static Settings defaults() {
            return new Settings(true, 3, DEFAULT_SOURCES, true);
        }

        static Settings parse(final YamlNode node) {
            final int inputs = node.integer("inputs", 3);
            if (inputs < MIN_INPUTS || inputs > MAX_INPUTS) {
                throw new IllegalArgumentException(node.path() + ".inputs：要在 " + MIN_INPUTS + " 到 " + MAX_INPUTS + " 之間");
            }
            final List<String> sources = new ArrayList<>();
            for (final String source : node.has("sources") ? node.strings("sources") : DEFAULT_SOURCES) {
                final String id = source.trim().toLowerCase(Locale.ROOT);
                if (!id.isEmpty() && !sources.contains(id)) {
                    sources.add(id);
                }
            }
            if (sources.isEmpty()) {
                throw new IllegalArgumentException(node.path() + ".sources：至少要一個（uniques.yml 的 sources，例如 map_boss）");
            }
            return new Settings(node.bool("enabled", true), inputs, sources, node.bool("exclude_inputs", true));
        }

        /** 「三換一」的「三」。 */
        public String countText() {
            return switch (inputs) {
                case 2 -> "二";
                case 3 -> "三";
                case 4 -> "四";
                case 5 -> "五";
                case 6 -> "六";
                default -> String.valueOf(inputs);
            };
        }

        /** 功能的名字：傳奇三換一。 */
        public String title() {
            return "傳奇" + countText() + "換一";
        }
    }

    /** 追逐級、極境專屬：一般的三換一不會換到（追逐級另外照 {@link #chaseOutcomes} 換；極境專屬不能放）。 */
    public static boolean protectedUnique(final UniqueItem unique) {
        if (unique.chase()) {
            return true;
        }
        for (final String source : unique.sources()) {
            if (source.equals("chase") || source.startsWith("uber")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 這件為什麼不能放（給玩家看）；可以放回傳 null。
     *
     * @param definition 這件傳奇在 uniques.yml 的定義（找不到 null）
     */
    public static String problem(final ItemData data, final UniqueItem definition) {
        if (data == null || data.rarity() != Rarity.UNIQUE) {
            return "只能放傳奇";
        }
        if (data.uuid() == null) {
            return "這件的物品資料不完整，不能換";
        }
        if (!data.identified()) {
            return "還沒鑑定：先找鑑定師鑑定";
        }
        if (data.locked()) {
            return "已鎖定（右鍵解除鎖定）";
        }
        if (definition == null) {
            return "這件傳奇已經沒有了，不能換";
        }
        if (protectedUnique(definition) && !definition.chase()) {
            return "極境專屬傳奇不能放（換掉太可惜）";
        }
        return null;
    }

    /** 已經放進來的是追逐級（firstChase）時，這件能不能一起放；可以回傳 null。 */
    public static String mixProblem(final boolean firstChase, final boolean chase) {
        if (firstChase == chase) {
            return null;
        }
        return firstChase ? "追逐級只能跟追逐級一起換" : "追逐級不能跟一般傳奇混放（要放就全部放追逐級）";
    }

    /**
     * 追逐級三換一（2026-10）：可能換到的追逐級（必定是追逐級、全部現役的追逐級隨機；放進去的那幾種也可能換到，不看 exclude_inputs）。
     */
    public static List<UniqueItem> chaseOutcomes(final Collection<UniqueItem> all, final int itemLevel) {
        final List<UniqueItem> pool = new ArrayList<>();
        for (final UniqueItem unique : all) {
            if (unique.chase() && unique.weight() > 0 && unique.dropLevel() <= itemLevel) {
                pool.add(unique);
            }
        }
        return pool;
    }

    /** 換出來的物品等級：放進去最高的那件。 */
    public static int itemLevel(final Collection<ItemData> inputs) {
        int level = 1;
        for (final ItemData data : inputs) {
            level = Math.max(level, data.ilvl());
        }
        return level;
    }

    /**
     * 可能換到的傳奇（再依各自的權重抽一件）。
     *
     * @param all       全部的傳奇
     * @param inputIds  放進去的那幾件是哪幾種（傳奇代號）
     * @param itemLevel 換出來的物品等級（{@link #itemLevel}）
     */
    public static List<UniqueItem> outcomes(final Collection<UniqueItem> all, final Settings settings, final Collection<String> inputIds,
                                            final int itemLevel) {
        final List<UniqueItem> pool = new ArrayList<>();
        for (final UniqueItem unique : all) {
            if (unique.weight() <= 0 || unique.dropLevel() > itemLevel || protectedUnique(unique)) {
                continue;
            }
            for (final String source : settings.sources()) {
                if (unique.droppedBy(source)) {
                    pool.add(unique);
                    break;
                }
            }
        }
        return exclude(pool, settings, inputIds);
    }

    private static List<UniqueItem> exclude(final List<UniqueItem> pool, final Settings settings, final Collection<String> inputIds) {
        if (settings.excludeInputs()) {
            final List<UniqueItem> fresh = new ArrayList<>();
            for (final UniqueItem unique : pool) {
                if (!inputIds.contains(unique.id())) {
                    fresh.add(unique);
                }
            }
            if (!fresh.isEmpty()) {
                return fresh;
            }
        }
        return pool;
    }
}
