package tw.exilecore.item;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * 2026-10-28 照 PoE2：傳奇的稀有度分級（uniques.yml 的 {@code rarity:}；對照表也寫在 uniques.yml 檔頭）。
 * <p>
 * poe2db 的傳奇頁沒有稀有度欄位（只有「限定」＝掉落限制），所以分級照社群通用的 PoE2 傳奇分級
 * （NeverSink 的 PoE2 物品過濾器 0.10.4a 的傳奇分層：hideable → 常見、t3 → 少見、t2 → 稀有、t1 → 很稀有；
 * 同一個基底好幾件傳奇時照那一件個別調整），追逐級（{@code chase: true}）就是極稀有。
 * <ul>
 *   <li>{@link #dropWeight}：全域掉落池（地圖 Boss 的 map_boss 池、一般怪物的全域傳奇）裡的權重——沒寫 {@code weight:} 時用這個。</li>
 *   <li>{@link #gambleWeight}：商人賭博、僥倖石的權重——同一個基底能賭到的傳奇權重加起來 ÷ 100（最多 1）乘在賭到傳奇的機率上，
 *       賭到傳奇時照這個權重挑哪一件（{@link #factor}）。</li>
 * </ul>
 * 這次之前沒有稀有度：全域傳奇一律 weight 100，賭博、僥倖石每個基底賭到傳奇的機率一樣。
 */
public enum UniqueRarity {
    /** 常見（NeverSink：hideable）。 */
    COMMON("常見", 100, 100),
    /** 少見（t3）。 */
    UNCOMMON("少見", 50, 40),
    /** 稀有（t2）。 */
    RARE("稀有", 20, 12),
    /** 很稀有（t1）。 */
    VERY_RARE("很稀有", 8, 4),
    /** 極稀有（追逐級；chase: true 的走 chase 池，這兩個權重只給沒標追逐級、卻寫極稀有的用）。 */
    EXTREMELY_RARE("極稀有", 3, 1);

    /** 全域掉落的來源（sources 有任何一個就是全域掉落）。 */
    public static final Set<String> GLOBAL_SOURCES = Set.of("map_boss", "chance", "gamble", "chase");

    private final String label;
    private final int dropWeight;
    private final int gambleWeight;

    UniqueRarity(final String label, final int dropWeight, final int gambleWeight) {
        this.label = label;
        this.dropWeight = dropWeight;
        this.gambleWeight = gambleWeight;
    }

    public String label() {
        return label;
    }

    public int dropWeight() {
        return dropWeight;
    }

    public int gambleWeight() {
        return gambleWeight;
    }

    /** 讀 uniques.yml 的 rarity（common、uncommon、rare、very_rare、extremely_rare；也收中文）；沒寫回傳 null。 */
    public static UniqueRarity parse(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String key = text.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (final UniqueRarity rarity : values()) {
            if (rarity.name().toLowerCase(Locale.ROOT).equals(key) || rarity.label.equals(text.trim())) {
                return rarity;
            }
        }
        throw new IllegalArgumentException("rarity 要寫 common、uncommon、rare、very_rare、extremely_rare，卻是「" + text + "」");
    }

    /** 全域掉落（任何地圖 Boss、僥倖石、賭博、追逐級）；不是的就是限定掉落（只有特定 Boss、地圖事件、劫盜、巔峰…）。 */
    public static boolean global(final UniqueItem unique) {
        for (final String source : unique.sources()) {
            if (GLOBAL_SOURCES.contains(source)) {
                return true;
            }
        }
        return unique.chase();
    }

    /** 禁止掉落（照 PoE2 的 No longer obtainable：sources 是空的，已經掉出來的照常能用）。 */
    public static boolean disabled(final UniqueItem unique) {
        return unique.sources().isEmpty() && !unique.chase();
    }

    /**
     * 一個基底的傳奇機率倍率：能出的傳奇的權重加起來 ÷ 100，最多 1（常見的一件就是原本的機率；只有很稀有的一件是 4%）。
     * 沒有傳奇回傳 0。
     */
    public static double factor(final Collection<UniqueItem> uniques, final ToDoubleFunction<UniqueItem> weight) {
        double total = 0;
        for (final UniqueItem unique : uniques) {
            total += Math.max(0, weight.applyAsDouble(unique));
        }
        return Math.min(1, total / 100.0);
    }
}
