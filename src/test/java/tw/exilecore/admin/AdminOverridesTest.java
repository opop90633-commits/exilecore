package tw.exilecore.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理網頁改的設定：存在 admin-overrides.yml，讀資料檔時蓋上去，資料檔本身不動。 */
class AdminOverridesTest {

    @TempDir
    Path folder;

    @AfterEach
    void removeOverlay() {
        YamlNode.overlay(null);
    }

    private Path data(final String name, final String text) throws IOException {
        final Path file = folder.resolve("data").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void noFileMeansNothingOverridden() {
        final AdminOverrides overrides = new AdminOverrides(folder);
        assertNull(overrides.reload());
        assertTrue(overrides.data().isEmpty());
        assertTrue(overrides.config().isEmpty());
        assertFalse(overrides.maintenance());
        assertEquals(AdminOverrides.DEFAULT_MAINTENANCE_MESSAGE, overrides.maintenanceMessage());
        assertTrue(overrides.forFile(folder.resolve("data/character.yml")).isEmpty());
    }

    @Test
    void valuesOverlayDataFilesWithoutTouchingThem() throws IOException {
        final Path character = data("character.yml", "level_cap: 100\nexperience_bonus: 2\n");
        final Path economy = data("economy.yml", "trade:\n  range: 16\n  fee: { percent: 3, min: 10 }\n");
        final Path affixMaps = data("affixes/maps.yml", "a: 1\n");
        final Path baseMaps = data("bases/maps.yml", "a: 1\n");
        final String before = Files.readString(character);
        final AdminOverrides overrides = new AdminOverrides(folder);
        overrides.reload();
        YamlNode.overlay(overrides::forFile);
        overrides.setData("character.yml", "experience_bonus", 3.0);
        overrides.setData("economy.yml", "trade.fee.percent", 5.0);
        overrides.setData("drops.yml", "server_bonus.quantity", 50);
        overrides.setData("affixes/maps.yml", "a", 9);

        assertEquals(3.0, YamlNode.load(character).decimal("experience_bonus", 0), 1e-9);
        assertEquals(100, YamlNode.load(character).integer("level_cap", 0), "其他鍵照檔案");
        assertEquals(2.0, YamlNode.loadRaw(character).decimal("experience_bonus", 0), 1e-9, "loadRaw 是檔案裡寫的");
        final YamlNode trade = YamlNode.load(economy).node("trade");
        assertEquals(5.0, trade.node("fee").decimal("percent", 0), 1e-9);
        assertEquals(10, trade.node("fee").integer("min", 0), "同一層的其他值留著");
        assertEquals(16, trade.integer("range", 0));
        assertEquals(9, YamlNode.load(affixMaps).integer("a", 0), "照相對於 data/ 的路徑分辨同名的檔案");
        assertEquals(1, YamlNode.load(baseMaps).integer("a", 0));
        assertEquals(before, Files.readString(character), "資料檔本身不動");

        // 檔案裡沒有的那一層會補上
        final Path drops = data("drops.yml", "unidentified: true\n");
        assertEquals(50, YamlNode.load(drops).node("server_bonus").integer("quantity", 0));
        // 不在 data/ 底下的檔案不蓋
        final Path outside = folder.resolve("character.yml");
        Files.writeString(outside, "experience_bonus: 2\n");
        assertEquals(2.0, YamlNode.load(outside).decimal("experience_bonus", 0), 1e-9);
    }

    @Test
    void savedFileReadsBackTheSame() throws IOException {
        final AdminOverrides overrides = new AdminOverrides(folder);
        overrides.reload();
        overrides.setData("character.yml", "experience_bonus", 2.5);
        overrides.setData("events.yml", "settings.force", "ember_month");
        overrides.setConfig("items.allow-drop", true);
        overrides.setMaintenance(true, "今晚 10 點維修");
        final String text = Files.readString(overrides.file());
        assertTrue(text.startsWith("# 管理網頁改的設定"), "開頭有說明");
        assertTrue(text.contains("今晚 10 點維修"), "中文不轉成跳脫字元：" + text);

        final AdminOverrides again = new AdminOverrides(folder);
        assertNull(again.reload());
        assertEquals(2.5, again.data("character.yml", "experience_bonus"));
        assertEquals("ember_month", again.data("events.yml", "settings.force"));
        assertEquals(true, again.config().get("items.allow-drop"));
        assertTrue(again.maintenance());
        assertEquals("今晚 10 點維修", again.maintenanceMessage());

        // 拿掉：檔案裡也沒了；一個檔的值都拿掉，那個檔也拿掉
        again.setData("character.yml", "experience_bonus", null);
        again.setConfig("items.allow-drop", null);
        again.setMaintenance(false, null);
        final AdminOverrides third = new AdminOverrides(folder);
        third.reload();
        assertNull(third.data("character.yml", "experience_bonus"));
        assertFalse(third.data().containsKey("character.yml"));
        assertTrue(third.config().isEmpty());
        assertFalse(third.maintenance());
        assertEquals("今晚 10 點維修", third.maintenanceMessage(), "關掉維護模式時說明留著，下次開沿用");
    }

    @Test
    void handWrittenNestedValuesAreAccepted() throws IOException {
        Files.writeString(folder.resolve(AdminOverrides.FILE), """
                data:
                  economy.yml:
                    trade:
                      fee: { percent: 7 }
                  ./data/drops.yml:
                    server_bonus.gold: 2
                config:
                  items: { allow-drop: true }
                maintenance: { enabled: true }
                """);
        final AdminOverrides overrides = new AdminOverrides(folder);
        assertNull(overrides.reload());
        assertEquals(7, overrides.data("economy.yml", "trade.fee.percent"));
        assertEquals(2, overrides.data("drops.yml", "server_bonus.gold"), "檔名的 ./ 與 data/ 都拿掉");
        assertEquals(true, overrides.config().get("items.allow-drop"));
        assertTrue(overrides.maintenance());
        assertEquals(Map.of("trade.fee.percent", 7), overrides.forFile(folder.resolve("data").resolve("economy.yml")));
    }

    @Test
    void brokenFileIsNeverOverwritten() throws IOException {
        final Path file = folder.resolve(AdminOverrides.FILE);
        Files.writeString(file, "data: [this is: not: valid\n");
        final AdminOverrides overrides = new AdminOverrides(folder);
        final String problem = overrides.reload();
        assertNotNull(problem);
        assertEquals(problem, overrides.problem());
        assertThrows(IOException.class, () -> overrides.setData("character.yml", "experience_bonus", 2.0), "壞掉的檔不能蓋掉");
        assertEquals("data: [this is: not: valid\n", Files.readString(file));
        Files.writeString(file, "data: {}\n");
        assertNull(overrides.reload(), "修好之後恢復");
        overrides.setData("character.yml", "experience_bonus", 2.0);
        assertEquals(2.0, overrides.data("character.yml", "experience_bonus"));
    }

    @Test
    void lookupFindsDottedAndIntegerKeys() throws IOException {
        final Path drops = data("drops.yml", "gold:\n  amount:\n    1: [2, 6]\n    60: [90, 200]\n  chance: { normal: 0.6 }\n");
        final YamlNode node = YamlNode.loadRaw(drops);
        assertEquals(0.6, ((Number) node.lookup("gold.chance.normal")).doubleValue(), 1e-9);
        assertEquals(java.util.List.of(90, 200), node.lookup("gold.amount.60"), "YAML 的整數鍵用字串也找得到");
        assertNull(node.lookup("gold.nope.x"));
        assertNull(node.lookup("gold.chance.normal.deeper"));
        // 蓋整數鍵：不會多出一個字串的 "60"
        final YamlNode changed = node.withValues(Map.of("gold.amount.60", java.util.List.of(1, 1)));
        assertEquals(java.util.List.of(1, 1), changed.lookup("gold.amount.60"));
        assertEquals(2, ((Map<?, ?>) changed.lookup("gold.amount")).size());
        assertEquals(java.util.List.of(90, 200), node.lookup("gold.amount.60"), "原本的不動");
    }
}
