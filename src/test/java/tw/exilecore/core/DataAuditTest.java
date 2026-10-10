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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAuditTest {

    private static final List<String> PATHS = List.of("data/economy.yml", "data/passives/tree.json");

    private final Map<String, String> jar = new HashMap<>();

    private DataFiles.ResourceLoader loader() {
        return path -> {
            final String content = jar.get(path);
            return content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        };
    }

    private DataFiles.Report sync(final Path root) throws IOException {
        return DataFiles.sync(root, PATHS, loader(), "20261004-120000");
    }

    private List<DataAudit.FileStatus> audit(final Path root) throws IOException {
        return DataAudit.audit(root, PATHS, loader());
    }

    @Test
    void missingKeysComparesKeysNotValuesUpToDepthThree() {
        final String defaults = """
                reference:
                  days: 14
                  min_pairs: 2
                values:
                  chaos: 100
                  distilled_ire: 30
                  nested:
                    deep:
                      deeper: 1
                list:
                  - a
                  - b
                """;
        final String mine = """
                reference:
                  days: 7
                values:
                  chaos: 50
                  nested:
                    deep: {}
                list:
                  - a
                """;
        assertEquals(List.of("reference.min_pairs", "values.distilled_ire"), DataAudit.missingKeys(mine, defaults),
                "只比鍵不比值；第 4 層（values.nested.deep.deeper）跟清單裡面不看");
    }

    @Test
    void missingKeysTreatsWholeMissingSectionsAsOneKey() {
        final String defaults = """
                events:
                  enabled: true
                  broadcast: true
                fees:
                  listing: 1
                """;
        assertEquals(List.of("events", "fees.listing"), DataAudit.missingKeys("fees: {other: 1}", defaults),
                "整段不見只報段落名，不展開裡面的鍵");
        assertEquals(List.of("events", "fees"), DataAudit.missingKeys("", defaults), "空檔案");
        assertEquals(List.of(), DataAudit.missingKeys(defaults, defaults));
    }

    @Test
    void statesFollowTheSyncRules(@TempDir final Path root) throws IOException {
        jar.put("data/economy.yml", "reference:\n  days: 7\n");
        jar.put("data/passives/tree.json", "{\"version\": 1}");
        sync(root);
        for (final DataAudit.FileStatus status : audit(root)) {
            assertEquals(DataAudit.State.SAME, status.state(), status.path());
            assertFalse(status.needsAttention());
        }

        // 使用者改了 economy，新版多了一段；tree.json 不是 YAML，改了只說「改過」
        Files.writeString(root.resolve("data/economy.yml"), "reference:\n  days: 3\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/passives/tree.json"), "{\"version\": 2}", StandardCharsets.UTF_8);
        jar.put("data/economy.yml", "reference:\n  days: 7\n  min_pairs: 2\nvalues:\n  chaos: 100\n");
        sync(root);

        final List<DataAudit.FileStatus> statuses = audit(root);
        final DataAudit.FileStatus economy = statuses.get(0);
        assertEquals(DataAudit.State.PENDING, economy.state());
        assertEquals(List.of("reference.min_pairs", "values"), economy.missingKeys());
        assertTrue(economy.needsAttention());
        assertNull(economy.problem());
        assertEquals("data/economy.yml：改過，有 .new 還沒合併；缺 2 個新版的鍵：reference.min_pairs、values", DataAudit.summary(economy));

        final DataAudit.FileStatus tree = statuses.get(1);
        assertEquals(DataAudit.State.PENDING, tree.state(), "改過就有 .new（同步時寫的）");
        assertTrue(tree.missingKeys().isEmpty(), "不是 YAML 不比鍵");
        Files.delete(root.resolve("data/passives/tree.json.new"));
        final DataAudit.FileStatus treeLater = audit(root).get(1);
        assertEquals(DataAudit.State.MODIFIED, treeLater.state(), "把 .new 刪了就只是改過");
        assertFalse(treeLater.needsAttention(), "改過但沒有東西要合併就不提醒");

        Files.delete(root.resolve("data/passives/tree.json"));
        assertEquals(DataAudit.State.MISSING, audit(root).get(1).state());
    }

    @Test
    void brokenYamlIsReportedNotThrown(@TempDir final Path root) throws IOException {
        jar.put("data/economy.yml", "reference:\n  days: 7\n");
        jar.put("data/passives/tree.json", "{}");
        sync(root);
        Files.writeString(root.resolve("data/economy.yml"), "reference: [\n", StandardCharsets.UTF_8);
        final DataAudit.FileStatus status = audit(root).get(0);
        assertEquals(DataAudit.State.MODIFIED, status.state());
        assertTrue(status.problem() != null && status.problem().startsWith("讀不出來"), status.problem());
        assertTrue(DataAudit.summary(status).contains("讀不出來"));
    }

    @Test
    void acceptNewKeepsABackupAndMakesTheFileFollowDefaultsAgain(@TempDir final Path root) throws IOException {
        jar.put("data/economy.yml", "v: 1\n");
        jar.put("data/passives/tree.json", "{}");
        sync(root);
        Files.writeString(root.resolve("data/economy.yml"), "v: mine\n", StandardCharsets.UTF_8);
        jar.put("data/economy.yml", "v: 2\n");
        sync(root);
        assertTrue(Files.exists(root.resolve("data/economy.yml.new")));

        final Path backup = DataAudit.acceptNew(root, "data/economy.yml", "20261004-130000");
        assertEquals(root.resolve("data/economy.yml.bak-20261004-130000"), backup);
        assertEquals("v: mine\n", Files.readString(backup, StandardCharsets.UTF_8), "你的那份留著");
        assertEquals("v: 2\n", Files.readString(root.resolve("data/economy.yml"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("data/economy.yml.new")));
        assertEquals(DataAudit.State.SAME, audit(root).get(0).state());

        // 記成預設值了：下一版直接換，不會再變 .new
        jar.put("data/economy.yml", "v: 3\n");
        final DataFiles.Report report = sync(root);
        assertEquals(List.of("data/economy.yml"), report.updated());
        assertTrue(report.conflicts().isEmpty());

        assertThrows(IOException.class, () -> DataAudit.acceptNew(root, "data/economy.yml", "20261004-140000"), "沒有 .new 就不能接受");
    }

    @Test
    void summaryCapsTheListedKeys() {
        final StringBuilder defaults = new StringBuilder();
        for (int i = 0; i < DataAudit.MAX_LISTED + 5; i++) {
            defaults.append("k").append(i).append(": 1\n");
        }
        final List<String> missing = DataAudit.missingKeys("", defaults.toString());
        final String summary = DataAudit.summary(new DataAudit.FileStatus("data/x.yml", DataAudit.State.MODIFIED, missing, null));
        assertTrue(summary.contains("缺 " + (DataAudit.MAX_LISTED + 5) + " 個"), summary);
        assertTrue(summary.endsWith("…（還有 5 個）"), summary);
    }
}
