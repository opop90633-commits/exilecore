package tw.exilecore.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 右邊側欄：任務追蹤下面接「最近撿到」。 */
class SidebarLayoutTest {

    private static final List<String> QUEST = List.of("任務", "上岸", " 走到營地", "/quests 任務日誌");

    @Test
    void nothingToShowHidesTheSidebar() {
        assertTrue(SidebarLayout.compose(List.of(), List.of()).isEmpty());
        assertEquals(QUEST, SidebarLayout.compose(QUEST, List.of()), "只有任務：照舊");
    }

    @Test
    void guideGoesOnTop() {
        final List<String> guide = List.of("新手引導", "把技能綁到按鍵", "/skills");
        assertEquals(guide, SidebarLayout.compose(guide, List.of(), List.of()), "只有引導");
        assertEquals(List.of("新手引導", "把技能綁到按鍵", "/skills", "", "任務", "上岸", " 走到營地", "/quests 任務日誌"),
                SidebarLayout.compose(guide, QUEST, List.of()), "引導在最上面，任務的標題變成一行");
        assertEquals(QUEST, SidebarLayout.compose(List.of(), QUEST, List.of()), "沒有引導：照舊");
        final List<String> many = new ArrayList<>();
        many.add("任務");
        for (int i = 0; i < 20; i++) {
            many.add("行 " + i);
        }
        assertEquals(SidebarLayout.MAX_ROWS + 1, SidebarLayout.compose(guide, many, List.of()).size(), "放不下就砍最下面");
    }

    @Test
    void lootAloneGetsItsOwnTitle() {
        final List<String> lines = SidebarLayout.compose(List.of(), List.of(" 崇高石"));
        assertEquals(List.of(SidebarLayout.LOOT_TITLE, " 崇高石"), lines);
    }

    @Test
    void lootGoesUnderTheQuests() {
        final List<String> lines = SidebarLayout.compose(QUEST, List.of(" 星骸之握", " 崇高石"));
        assertEquals(List.of("任務", "上岸", " 走到營地", "/quests 任務日誌", "", SidebarLayout.LOOT_HEADER, " 星骸之握", " 崇高石"), lines);
    }

    @Test
    void questsYieldRowsWhenTheSidebarIsFull() {
        final List<String> quest = new ArrayList<>();
        quest.add("任務");
        for (int i = 0; i < 14; i++) {
            quest.add("行 " + i);
        }
        final SidebarLayout.Feed feed = new SidebarLayout.Feed();
        for (int i = 0; i < 8; i++) {
            feed.add(new SidebarLayout.Item("東西 " + i, 1), 100);
        }
        final List<String> loot = feed.rows();
        assertEquals(SidebarLayout.Feed.SHOWN + 1, loot.size(), "5 樣＋「還有 3 樣」");
        final List<String> lines = SidebarLayout.compose(quest, loot);
        assertEquals(1 + SidebarLayout.MAX_ROWS, lines.size(), "標題＋15 行");
        assertEquals("任務", lines.getFirst());
        assertEquals("行 6", lines.get(7), "任務留前 7 行，從最後面讓");
        assertEquals(SidebarLayout.LOOT_HEADER, lines.get(9));
        assertEquals("<gray> …還有 3 樣", lines.getLast());
        // 很長的任務追蹤（沒有撿到東西）最多 15 行
        quest.add("行 14");
        quest.add("行 15");
        assertEquals(1 + SidebarLayout.MAX_ROWS, SidebarLayout.compose(quest, List.of()).size());
    }

    @Test
    void feedMergesSameNamesAndExpires() {
        final SidebarLayout.Feed feed = new SidebarLayout.Feed();
        feed.add(new SidebarLayout.Item("<#aaaaaa>崇高石", 2), 100);
        feed.add(new SidebarLayout.Item("<#af6025>星骸之握", 1), 120);
        assertEquals(List.of(" <#af6025>星骸之握", " <#aaaaaa>崇高石<gray> ×2"), feed.rows(), "新的在最上面");
        feed.add(new SidebarLayout.Item("<#aaaaaa>崇高石", 3), 150);
        assertEquals(List.of(" <#aaaaaa>崇高石<gray> ×5", " <#af6025>星骸之握"), feed.rows(), "同名合併數量、移到最上面");
        assertFalse(feed.expire(119), "還沒到期");
        assertTrue(feed.expire(120), "星骸之握到期（崇高石重新計時了）");
        assertEquals(List.of(" <#aaaaaa>崇高石<gray> ×5"), feed.rows());
        assertTrue(feed.expire(150));
        assertTrue(feed.isEmpty());
        assertFalse(feed.expire(1000), "空的不用重畫");
    }

    @Test
    void feedKeepsOnlyTheNewest() {
        final SidebarLayout.Feed feed = new SidebarLayout.Feed();
        for (int i = 0; i < 50; i++) {
            feed.add(new SidebarLayout.Item("東西 " + i, 1), i);
        }
        assertEquals(SidebarLayout.Feed.MAX_ENTRIES, feed.size());
        assertEquals(" 東西 49", feed.rows().getFirst());
        assertEquals("<gray> …還有 " + (SidebarLayout.Feed.MAX_ENTRIES - SidebarLayout.Feed.SHOWN) + " 樣", feed.rows().getLast());
    }
}
