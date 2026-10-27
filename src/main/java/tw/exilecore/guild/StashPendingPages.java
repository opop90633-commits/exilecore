package tw.exilecore.guild;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 公會倉庫裡「不跟玩家存檔一起寫」（整理、重新分頁、補正裝備）的頁，資料庫還沒確定存好的（純邏輯，不依賴 Bukkit）。
 * <p>
 * 2026-10-27：整理把東西從 A 頁搬到 B 頁、那次存檔失敗（或暫停寫入時沒寫），接著有人從 B 頁拿走、只存 B 頁跟他的背包——
 * 資料庫裡 A 頁還留著那件，當機重開就複製一件。現在存入／取出時把這裡記的頁一起寫進那個玩家的交易。
 * 每次寫都有編號：較新的寫還沒完成時，舊的寫完成不會把頁拿掉。
 */
final class StashPendingPages {

    private final Map<Integer, Long> pages = new HashMap<>();
    private long sequence;

    /** 開始寫這幾頁（還沒確定）；回傳這次的編號。 */
    long begin(final Collection<Integer> written) {
        final long id = ++sequence;
        for (final int page : written) {
            pages.put(page, id);
        }
        return id;
    }

    /** 編號 id 那次寫成功：那之後沒再動過的頁拿掉。 */
    void saved(final Collection<Integer> written, final long id) {
        for (final int page : written) {
            pages.remove(page, id);
        }
    }

    /** 還沒確定存好的頁（由小到大）。 */
    Set<Integer> pages() {
        return new TreeSet<>(pages.keySet());
    }

    boolean isEmpty() {
        return pages.isEmpty();
    }
}
