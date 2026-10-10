package tw.exilecore.core;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 資料檔裡的物品名稱（icon、material、head、helmet、weapon）都要是原版物品：昇華模組啟動時會檢查，
 * 寫錯一個（例如 26.x 把 CHAIN 改名成 IRON_CHAIN）整個模組就不啟用，所以在這裡先抓。
 * 原版物品清單在 src/test/resources/materials.txt（tools/gen_materials.py 從 Paper 的 Material.java 產生）。
 */
class IconTest {

    private static final Set<String> KEYS = Set.of("icon", "material", "head", "helmet", "weapon");

    @Test
    void everyIconInShippedDataIsAVanillaMaterial() throws IOException {
        final Set<String> materials = materials();
        assertTrue(materials.size() > 1000, "materials.txt 讀到 " + materials.size() + " 個");
        assertTrue(materials.contains("IRON_CHAIN") && !materials.contains("CHAIN"), "26.x 的鏈條叫 IRON_CHAIN");
        final List<String> offenders = new ArrayList<>();
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/data"))) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".yml") && !name.endsWith(".json")) {
                    continue;
                }
                walk(file + "", null, new Yaml(options).load(Files.readString(file, StandardCharsets.UTF_8)), materials, offenders);
            }
        }
        assertTrue(offenders.isEmpty(), "不是原版物品：\n" + String.join("\n", offenders));
    }

    private static Set<String> materials() throws IOException {
        final Set<String> names = new HashSet<>();
        try (InputStream in = IconTest.class.getResourceAsStream("/materials.txt")) {
            assertFalse(in == null, "找不到 src/test/resources/materials.txt");
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }

    private static void walk(final String where, final String key, final Object value, final Set<String> materials, final List<String> offenders) {
        if (value instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                walk(where + "." + entry.getKey(), String.valueOf(entry.getKey()), entry.getValue(), materials, offenders);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(where + "[" + i + "]", key, list.get(i), materials, offenders);
            }
        } else if (value instanceof String text && key != null && KEYS.contains(key)) {
            // 只看寫成物品名稱的值（全大寫）；exilecore:... 這種模型路徑、空字串不看
            if (text.matches("[A-Z][A-Z0-9_]*") && !materials.contains(text)) {
                offenders.add(where + " = " + text);
            }
        }
    }
}
