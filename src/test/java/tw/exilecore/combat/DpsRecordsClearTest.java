package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：管理網頁清除輸出紀錄（單一玩家、單一角色、全部玩家）。 */
class DpsRecordsClearTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path dir;

    private static DpsRecords.Entry entry(final String uuid, final String player, final String character, final double damage) {
        return new DpsRecords.Entry(System.currentTimeMillis(), "boss", player, uuid, character, "戰士", 80, "測試 Boss", "區域 Boss", 1,
                damage, 10, 1, null, null, 0, 0, List.of(), List.of(), Map.of(), 10);
    }

    private DpsRecords filled(final Path file) {
        final DpsRecords records = new DpsRecords(null, file);
        records.add(entry(A, "甲", "甲一", 1000));
        records.add(entry(A, "甲", "甲二", 2000));
        records.add(entry(B, "乙", "乙一", 3000));
        return records;
    }

    private static long lines(final Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).count();
    }

    @Test
    void matchesFilters() {
        final DpsRecords.Entry e = entry(A, "甲", "甲一", 1);
        assertTrue(DpsRecords.matches(e, null, null));
        assertTrue(DpsRecords.matches(e, A.toUpperCase(), null));
        assertTrue(DpsRecords.matches(e, A, "甲一"));
        assertFalse(DpsRecords.matches(e, A, "甲二"));
        assertFalse(DpsRecords.matches(e, B, null));
        assertFalse(DpsRecords.matches(null, null, null));
    }

    @Test
    void clearOnePlayer() throws Exception {
        final Path file = dir.resolve("dps.jsonl");
        final DpsRecords records = filled(file);
        assertEquals(2, records.clear(A, null));
        assertEquals(1, records.size());
        assertEquals(1, records.recent(0).size());
        assertEquals(B, records.recent(0).getFirst().uuid());
        // 排行（管理網頁讀的 json）馬上只剩乙
        assertEquals(1, records.json(0).getAsJsonArray("players").size());
        assertEquals(1, lines(file));
        // 重開（重新讀檔）也不會回來
        assertEquals(1, new DpsRecords(null, file).size());
    }

    @Test
    void clearOneCharacter() throws Exception {
        final Path file = dir.resolve("dps.jsonl");
        final DpsRecords records = filled(file);
        assertEquals(1, records.clear(A, "甲二"));
        assertEquals(2, records.size());
        assertTrue(records.recent(0).stream().noneMatch(e -> "甲二".equals(e.character())));
        assertEquals(2, new DpsRecords(null, file).size());
        // 空白的角色名稱 = 這個人的全部角色
        assertEquals(1, records.clear(A, "  "));
        assertEquals(1, records.size());
    }

    @Test
    void clearAll() throws Exception {
        final Path file = dir.resolve("dps.jsonl");
        final DpsRecords records = filled(file);
        assertEquals(3, records.clear(null, null));
        assertEquals(0, records.size());
        assertEquals(0, records.json(0).getAsJsonArray("rows").size());
        assertEquals(0, lines(file));
        assertEquals(0, new DpsRecords(null, file).size());
        // 清完之後新的紀錄照常記
        records.add(entry(B, "乙", "乙一", 500));
        assertEquals(1, records.size());
        assertEquals(1, new DpsRecords(null, file).size());
    }

    @Test
    void clearUnknownPlayerKeepsEverything() throws Exception {
        final Path file = dir.resolve("dps.jsonl");
        final DpsRecords records = filled(file);
        assertEquals(0, records.clear("33333333-3333-3333-3333-333333333333", null));
        assertEquals(3, records.size());
        assertEquals(3, lines(file));
    }

    @Test
    void clearWithoutFile() {
        final DpsRecords records = new DpsRecords(null, dir.resolve("none.jsonl"));
        assertEquals(0, records.clear(null, null));
        assertFalse(Files.exists(dir.resolve("none.jsonl")));
    }
}
