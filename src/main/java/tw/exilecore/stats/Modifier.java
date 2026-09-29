package tw.exilecore.stats;

import java.util.Set;

/**
 * 一條修飾器：對某個屬性做 FLAT／INCREASED／MORE 的改變。
 * <p>
 * {@code tags} 是這條修飾器「只對哪種情境生效」的條件：例如「增加 20% 火焰傷害」是
 * {@code stat = damage, tags = [fire]}，只有在計算帶 fire 標籤的傷害時才會被加總；
 * 沒有標籤的修飾器對該屬性的所有情境都生效。
 *
 * @param stat   屬性鍵，見 {@link StatKeys}
 * @param type   型態
 * @param value  數值；INCREASED／MORE 用小數（0.20 = 20%）
 * @param tags   生效條件（全部都要包含在查詢的情境標籤裡）
 * @param source 來源說明，只用來除錯（例如「短劍 T3 火焰附加」）
 */
public record Modifier(String stat, ModType type, double value, Set<String> tags, String source) {

    public Modifier {
        tags = Set.copyOf(tags);
    }

    public static Modifier flat(final String stat, final double value, final String source) {
        return new Modifier(stat, ModType.FLAT, value, Set.of(), source);
    }

    public static Modifier increased(final String stat, final double value, final String source) {
        return new Modifier(stat, ModType.INCREASED, value, Set.of(), source);
    }

    public static Modifier more(final String stat, final double value, final String source) {
        return new Modifier(stat, ModType.MORE, value, Set.of(), source);
    }

    public Modifier withTags(final Set<String> newTags) {
        return new Modifier(stat, type, value, newTags, source);
    }

    /** 這條修飾器在給定的情境標籤下是否生效：自己的標籤要全部出現在情境裡。 */
    public boolean appliesTo(final Set<String> contextTags) {
        return contextTags.containsAll(tags);
    }
}
