package tw.exilecore.passive;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09 照站長「模組右上新增打開網頁天賦規劃器與匯入代碼」：狀態帶 imp／web，匯入代碼的檢查。 */
class TreeFeedToolsTest {

    @Test
    void stateCarriesImportFlagAndPlannerUrl() {
        final JsonObject root = new JsonObject();
        TreeFeed.tools(root, "https://x/tree#EC1.2.witch.AAAA");
        assertTrue(root.get("imp").getAsBoolean());
        assertEquals("https://x/tree#EC1.2.witch.AAAA", root.get("web").getAsString());
        final JsonObject off = new JsonObject();
        TreeFeed.tools(off, null);
        assertEquals("", off.get("web").getAsString(), "網頁沒開：空字串（模組把按鈕灰掉）");
    }

    @Test
    void importCodeIsTrimmedAndLimited() {
        final JsonObject ok = new JsonObject();
        ok.addProperty("code", "  EC1.2.witch.AAAA*WzIsWzFdLFtdXQ  ");
        assertEquals("EC1.2.witch.AAAA*WzIsWzFdLFtdXQ", TreeFeed.importCode(ok));
        final JsonObject empty = new JsonObject();
        empty.addProperty("code", "   ");
        assertNull(TreeFeed.importCode(empty));
        assertNull(TreeFeed.importCode(new JsonObject()));
        final JsonObject huge = new JsonObject();
        huge.addProperty("code", "x".repeat(TreeFeed.MAX_IMPORT + 1));
        assertNull(TreeFeed.importCode(huge));
    }
}
