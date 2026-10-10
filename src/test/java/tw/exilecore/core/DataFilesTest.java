package tw.exilecore.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFilesTest {

    private static final List<String> PATHS = List.of("data/a.yml", "data/sub/b.yml");

    private final Map<String, String> jar = new HashMap<>();

    private DataFiles.Report sync(final Path root) throws IOException {
        return DataFiles.sync(root, PATHS, path -> {
            final String content = jar.get(path);
            return content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        }, "20260930-120000");
    }

    private static String read(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void freshInstallCopiesEverything(@TempDir final Path root) throws IOException {
        jar.put("data/a.yml", "a: 1");
        jar.put("data/sub/b.yml", "b: 1");
        final DataFiles.Report report = sync(root);
        assertEquals(PATHS, report.added());
        assertNull(report.backup());
        assertEquals("b: 1", read(root.resolve("data/sub/b.yml")));
        assertTrue(Files.exists(root.resolve(DataFiles.HASH_FILE)));
    }

    @Test
    void untouchedFilesFollowNewDefaultsEditedFilesAreKept(@TempDir final Path root) throws IOException {
        jar.put("data/a.yml", "a: 1");
        jar.put("data/sub/b.yml", "b: 1");
        sync(root);

        // 使用者改了 b，然後出了新版
        Files.writeString(root.resolve("data/sub/b.yml"), "b: 99 # 我改的", StandardCharsets.UTF_8);
        jar.put("data/a.yml", "a: 2");
        jar.put("data/sub/b.yml", "b: 2");
        final DataFiles.Report report = sync(root);

        assertEquals(List.of("data/a.yml"), report.updated());
        assertEquals(List.of("data/sub/b.yml"), report.conflicts());
        assertEquals("a: 2", read(root.resolve("data/a.yml")));
        assertEquals("b: 99 # 我改的", read(root.resolve("data/sub/b.yml")), "改過的檔案不能被蓋掉");
        assertEquals("b: 2", read(root.resolve("data/sub/b.yml.new")));

        // 沒有新版時什麼都不做
        final DataFiles.Report again = sync(root);
        assertTrue(again.updated().isEmpty() && again.added().isEmpty());
        assertEquals(List.of("data/sub/b.yml"), again.conflicts(), "還是提醒要合併");
    }

    @Test
    void legacyInstallIsBackedUpThenReplaced(@TempDir final Path root) throws IOException {
        // 舊版安裝：有資料檔、沒有紀錄檔
        Files.createDirectories(root.resolve("data/sub"));
        Files.writeString(root.resolve("data/a.yml"), "a: old", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/sub/b.yml"), "b: old", StandardCharsets.UTF_8);
        jar.put("data/a.yml", "a: new");
        jar.put("data/sub/b.yml", "b: new");

        final DataFiles.Report report = sync(root);
        assertNotNull(report.backup());
        assertEquals("a: old", read(report.backup().resolve("data/a.yml")));
        assertEquals("b: old", read(report.backup().resolve("data/sub/b.yml")));
        assertEquals("a: new", read(root.resolve("data/a.yml")));
        assertEquals(PATHS, report.updated());
        assertFalse(Files.exists(root.resolve("data/a.yml.new")));

        // 之後就是一般的追蹤模式
        final DataFiles.Report next = sync(root);
        assertNull(next.backup());
        assertTrue(next.updated().isEmpty() && next.conflicts().isEmpty());
    }

    @Test
    void indexParsingSkipsCommentsAndBlankLines() {
        final byte[] index = "# 註解\n\ndata/a.yml\r\n  data/sub/b.yml  \n".getBytes(StandardCharsets.UTF_8);
        assertEquals(PATHS, DataFiles.parseIndex(index));
        assertEquals(64, DataFiles.sha256(new byte[0]).length());
    }
}
