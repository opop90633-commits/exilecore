package tw.exilecore.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 市集與倉庫的關鍵字搜尋：空白隔開的每個字都要出現（不分大小寫）。純邏輯。
 * 例：「火焰 抗性」找名稱、說明或詞綴裡同時有「火焰」和「抗性」的東西。
 */
public final class MarketSearch {

    private MarketSearch() {
    }

    /** 把輸入切成關鍵字（小寫、去掉空的）。 */
    public static List<String> terms(final String query) {
        final List<String> terms = new ArrayList<>();
        if (query == null) {
            return terms;
        }
        for (final String part : query.trim().split("[\\s　]+")) {
            if (!part.isBlank()) {
                terms.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return terms;
    }

    /** text（已經是小寫）有沒有包含所有關鍵字；沒有關鍵字算符合。 */
    public static boolean matches(final String text, final List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        if (text == null) {
            return false;
        }
        for (final String term : terms) {
            if (!text.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
