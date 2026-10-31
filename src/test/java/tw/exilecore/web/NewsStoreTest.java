package tw.exilecore.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照站長：更新公告：插件附的自動併進來，管理員改過的不蓋、刪掉的不加回來。 */
class NewsStoreTest {

    private static String bundled(final String title) {
        return "{\"posts\":[{\"id\":\"a\",\"date\":\"2026-10-31\",\"title\":\"" + title + "\",\"body\":\"- x\"},"
                + "{\"id\":\"b\",\"date\":\"2026-11-01\",\"title\":\"B\",\"body\":\"- y\"}]}";
    }

    @Test
    void mergesKeepsEditsAndDeletions() throws Exception {
        final Path dir = Files.createTempDirectory("news");
        final NewsStore store = new NewsStore(dir.resolve("news.json"));
        store.load(bundled("A"));
        assertEquals(2, store.posts().size());
        assertEquals("b", store.posts().get(0).id(), "新的在前");

        store.save("a", "2026-10-31", "A（改）", "- 改過");
        store.delete("b");
        final String admin = store.save(null, "2026-11-02", "管理員寫的", "## 嗨").id();

        final NewsStore again = new NewsStore(dir.resolve("news.json"));
        again.load(bundled("A2"));
        assertEquals(2, again.posts().size(), "刪掉的 b 不會再加回來");
        assertEquals(admin, again.posts().get(0).id());
        assertEquals("A（改）", again.posts().get(1).title(), "改過的不被插件附的蓋掉");
        assertTrue(new String(again.publicJson(), java.nio.charset.StandardCharsets.UTF_8).contains("管理員寫的"));

        final NewsStore fresh = new NewsStore(Files.createTempDirectory("news2").resolve("news.json"));
        fresh.load(bundled("A"));
        fresh.load(bundled("A2"));
        assertEquals("A2", fresh.posts().get(1).title(), "沒改過的換成插件的新版");
        assertThrows(IllegalArgumentException.class, () -> fresh.save(null, "11/01", "x", ""));
        assertFalse(fresh.delete("nope"));
    }
}
