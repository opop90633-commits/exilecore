package tw.exilecore.guild;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：整理寫的頁還沒確定存好前，存入／取出要一起寫（不然存失敗＋當機會複製物品）。 */
class StashPendingPagesTest {

    @Test
    void pagesStayUntilSaved() {
        final StashPendingPages pending = new StashPendingPages();
        final long sort = pending.begin(List.of(0, 1, 2));
        assertEquals(Set.of(0, 1, 2), pending.pages(), "寫出去還沒確定的都算");
        pending.saved(List.of(0, 1, 2), sort);
        assertTrue(pending.isEmpty(), "存好了就拿掉");
    }

    @Test
    void failedSaveKeepsPages() {
        final StashPendingPages pending = new StashPendingPages();
        pending.begin(List.of(0, 3));
        // 存失敗（或暫停寫入）不呼叫 saved：存入／取出時要一起寫
        assertEquals(Set.of(0, 3), pending.pages());
    }

    @Test
    void olderSaveDoesNotClearNewerWrite() {
        final StashPendingPages pending = new StashPendingPages();
        final long first = pending.begin(List.of(0, 1));
        final long second = pending.begin(List.of(1));
        pending.saved(List.of(0, 1), first);
        assertEquals(Set.of(1), pending.pages(), "第 2 頁之後又被寫，舊的寫完成不算數");
        pending.saved(List.of(1), second);
        assertTrue(pending.isEmpty());
    }
}
