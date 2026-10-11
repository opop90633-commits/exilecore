package tw.exilecore.guild;

import org.junit.jupiter.api.Test;
import tw.exilecore.stash.StashCategory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 公會倉庫的頁籤（2026-10）：名稱與分類，存在倉庫表的第 -1「頁」。 */
class GuildStashTabsTest {

    @Test
    void pagesStartUnnamedAndTakeEverything() {
        final GuildStash stash = new GuildStash();
        stash.ensurePages(3);
        assertEquals("第 2 頁", stash.tabLabel(1));
        assertEquals("", stash.tabName(1));
        assertEquals(StashCategory.ALL, stash.tabCategory(1));
        assertEquals(List.of(StashCategory.ALL, StashCategory.ALL), stash.tabCategories(2));
        assertEquals(0, stash.serializeMeta().length, "沒改過的頁不用存");
    }

    @Test
    void namesAreTrimmedAndCutToTheLimit() {
        final GuildStash stash = new GuildStash();
        stash.ensurePages(2);
        stash.setTab(0, "  武器\t庫\n ", StashCategory.WEAPON);
        assertEquals("武器 庫", stash.tabName(0));
        stash.setTab(1, "一二三四五六七八九十一二三四五", StashCategory.ARMOUR);
        assertEquals(GuildStash.TAB_NAME_MAX, stash.tabName(1).codePointCount(0, stash.tabName(1).length()));
        // 頁籤不能設成通貨、地圖（那不是物品頁的分類）：當成全部
        stash.setTab(1, "", StashCategory.CURRENCY);
        assertEquals(StashCategory.ALL, stash.tabCategory(1));
        assertEquals("第 2 頁", stash.tabLabel(1));
    }

    @Test
    void tabsSurviveSavingAndLoading() {
        final GuildStash stash = new GuildStash();
        stash.ensurePages(4);
        stash.setTab(0, "武器庫", StashCategory.WEAPON);
        stash.setTab(2, "", StashCategory.JEWELLERY);
        stash.setTab(3, "雜物", StashCategory.ALL);
        final GuildStash loaded = GuildStash.decode(Map.of(GuildStash.META_PAGE, stash.serializeMeta()), 4);
        assertEquals(4, loaded.pageCount(), "頁籤那一筆不是物品頁");
        assertEquals("武器庫", loaded.tabLabel(0));
        assertEquals(StashCategory.WEAPON, loaded.tabCategory(0));
        assertEquals(StashCategory.ALL, loaded.tabCategory(1));
        assertEquals(StashCategory.JEWELLERY, loaded.tabCategory(2));
        assertEquals("雜物", loaded.tabName(3));
        assertEquals(List.of(StashCategory.WEAPON, StashCategory.ALL, StashCategory.JEWELLERY, StashCategory.ALL), loaded.tabCategories(4));
    }

    @Test
    void brokenLinesAreSkipped() {
        final byte[] stored = "x\tWEAPON\t壞的\n1\tNOPE\t看不懂的分類\n2\tRING\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final GuildStash loaded = GuildStash.decode(Map.of(GuildStash.META_PAGE, stored), 3);
        assertEquals("第 1 頁", loaded.tabLabel(0));
        assertEquals("看不懂的分類", loaded.tabName(1));
        assertEquals(StashCategory.ALL, loaded.tabCategory(1));
        assertEquals(StashCategory.RING, loaded.tabCategory(2));
    }
}
