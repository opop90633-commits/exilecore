package tw.exilecore.core;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家看得到的文字不提別的遊戲：資料檔的值（名稱、說明、台詞…）與程式裡的字串都不能有「PoE」「PoE2」「Path of Exile」「流亡黯道」。
 * 註解（# 與 //）是給改設定的人看的設計依據，可以寫。
 */
class PlayerTextTest {

    private static final Pattern BRAND = Pattern.compile("PoE|Path of Exile|流亡黯道");
    /** Java 的字串常值（含轉義）。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    @Test
    void shippedDataValuesNeverNameOtherGames() throws IOException {
        final List<String> offenders = new ArrayList<>();
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(64 * 1024 * 1024);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/data"))) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".yml") && !name.endsWith(".json")) {
                    continue;
                }
                final Object loaded = new Yaml(options).load(Files.readString(file, StandardCharsets.UTF_8));
                walk(file + "", loaded, offenders);
            }
        }
        assertTrue(offenders.isEmpty(), "資料檔的值提到別的遊戲：\n" + String.join("\n", offenders));
    }

    @Test
    void playerFacingJavaStringsNeverNameOtherGames() throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                int lineNumber = 0;
                for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNumber++;
                    final String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue; // javadoc 與區塊註解
                    }
                    final String code = stripLineComment(line);
                    final Matcher matcher = STRING_LITERAL.matcher(code);
                    while (matcher.find()) {
                        if (BRAND.matcher(matcher.group()).find()) {
                            offenders.add(file + ":" + lineNumber + " " + matcher.group());
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "程式裡給玩家看的字串提到別的遊戲：\n" + String.join("\n", offenders));
    }

    /** 去掉 // 之後的註解（字串裡的 // 不算）。 */
    private static String stripLineComment(final String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '\\' && inString) {
                i++;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static void walk(final String where, final Object value, final List<String> offenders) {
        if (value instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                walk(where + "." + entry.getKey(), entry.getValue(), offenders);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(where + "[" + i + "]", list.get(i), offenders);
            }
        } else if (value instanceof String text && BRAND.matcher(text).find()) {
            offenders.add(where + " = " + text);
        }
    }
}
