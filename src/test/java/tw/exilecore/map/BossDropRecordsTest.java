package tw.exilecore.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11：Boss 掉落紀錄——寫檔、重讀、每隻 Boss 的統計。 */
class BossDropRecordsTest {
    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void summarisesPerBossAndSurvivesReload() {
        final Path file = dir.resolve("boss_drops.jsonl");
        final BossDropRecords records = new BossDropRecords(null, file);
        final long now = System.currentTimeMillis();
        records.add(new BossDropRecords.Entry(now, "u1", "A", "災厄級", "迷霧之主・無面", "fog_trial", 0,
                List.of("虛望之盔"), List.of("無面霧（技能）"), Map.of("霧之碎片", 10)));
        records.add(new BossDropRecords.Entry(now, "u2", "B", "災厄級", "迷霧之主・無面", "fog_trial", 0,
                List.of(), List.of(), Map.of("霧之碎片", 10)));
        records.add(new BossDropRecords.Entry(now, "u1", "A", "巔峰", "淵骸巫妖", "abyss_lich", 16,
                List.of("淵髓晶", "淵髓晶"), List.of(), Map.of()));

        final BossDropRecords reloaded = new BossDropRecords(null, file);
        final Map<String, Object> json = reloaded.json(7);
        assertEquals(3, json.get("total"));
        final List<Map<String, Object>> summary = (List<Map<String, Object>>) json.get("summary");
        final Map<String, Object> fog = summary.stream().filter(s -> "迷霧之主・無面".equals(s.get("boss"))).findFirst().orElseThrow();
        assertEquals(2, fog.get("kills"));
        assertEquals(2, fog.get("players"));
        assertEquals(1, fog.get("uniques"));
        assertEquals(1, fog.get("gems"));
        final Map<String, Object> lich = summary.stream().filter(s -> "淵骸巫妖".equals(s.get("boss"))).findFirst().orElseThrow();
        assertEquals(2, ((Map<String, Integer>) lich.get("items")).get("淵髓晶"));
        final List<Map<String, Object>> rows = (List<Map<String, Object>>) json.get("rows");
        assertEquals("淵骸巫妖", rows.get(0).get("boss"), "新的在前");
        assertTrue(reloaded.json(0).get("total").equals(3));
    }
}
