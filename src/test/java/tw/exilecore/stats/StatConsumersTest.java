package tw.exilecore.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27：天賦樹（tree.json）與昇華（ascendancy.yml）用到的每個屬性鍵都要有程式在讀（{@link StatConsumers}）。
 * 站長：「天賦點好像還有蠻多沒有生效的東西」——說明行沒標「尚未生效」、卻沒人讀那個鍵，就是假生效；新鍵沒接消費端這裡會失敗。
 * <p>
 * 掃 src/main/java（拿掉註解）：字串直接寫在程式裡（宣告常數那一行不算）、常數在宣告以外的地方用到、或是由前綴組出來
 * （前綴的常數或字串有人用）。說明文字的檔案（*Text.java、StatLines、SpecialEffectLines）與登記表本身不算讀。
 */
class StatConsumersTest {

    static final Path SOURCES = Path.of("src/main/java");
    /** 鍵 → 用到的種類（FLAT／INCREASED／MORE）與出處。 */
    static final Map<String, Set<String>> USED = new TreeMap<>();
    static String blob;
    static final Map<String, Integer> IDS = new HashMap<>();
    static final Map<String, Integer> LITERALS = new HashMap<>();
    /** 常數宣告：字串 → 常數名稱；名稱 → 宣告幾次。 */
    static final Map<String, List<String>> DECLARED = new HashMap<>();
    static final Map<String, Integer> DECLARED_NAMES = new HashMap<>();
    static final Map<String, Integer> DECLARED_LITERALS = new HashMap<>();

    static final Pattern DECLARATION = Pattern.compile("String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]+)\"\\s*;");
    static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");
    static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\\\n]{1,100})\"");

    @BeforeAll
    static void scan() throws IOException {
        final String tree = Files.readString(Path.of("src/main/resources/data/passives/tree.json"), StandardCharsets.UTF_8);
        final Matcher t = Pattern.compile("\"stat\":\\s*\"([^\"]+)\",\\s*\"type\":\\s*\"([A-Z]+)\"").matcher(tree);
        while (t.find()) {
            USED.computeIfAbsent(t.group(1), k -> new TreeSet<>()).add(t.group(2) + " tree.json");
        }
        final String asc = Files.readString(Path.of("src/main/resources/data/ascendancy.yml"), StandardCharsets.UTF_8);
        final Matcher a = Pattern.compile("stat:\\s*([A-Za-z0-9_.%+\\-]+)\\s*,\\s*type:\\s*([A-Z]+)").matcher(asc);
        while (a.find()) {
            USED.computeIfAbsent(a.group(1), k -> new TreeSet<>()).add(a.group(2) + " ascendancy.yml");
        }
        // 2026-10-27：血脈輔助寶石（supports_lineage.yml）用到的屬性鍵也要有程式在讀
        final String lineage = Files.readString(Path.of("src/main/resources/data/supports_lineage.yml"), StandardCharsets.UTF_8);
        final Matcher l = Pattern.compile("stat:\\s*([A-Za-z0-9_.%+\\-]+)\\s*,\\s*type:\\s*([A-Z]+)").matcher(lineage);
        while (l.find()) {
            USED.computeIfAbsent(l.group(1), k -> new TreeSet<>()).add(l.group(2) + " supports_lineage.yml");
        }
        final StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                final String name = file.getFileName().toString();
                final Matcher d = DECLARATION.matcher(code);
                while (d.find()) {
                    DECLARED.computeIfAbsent(d.group(2), k -> new ArrayList<>()).add(d.group(1));
                    DECLARED_NAMES.merge(d.group(1), 1, Integer::sum);
                    DECLARED_LITERALS.merge(d.group(2), 1, Integer::sum);
                }
                if (displayOnly(name)) {
                    // 說明文字、登記表：常數的宣告留著（別的地方要找得到名字），用到的地方不算
                    final StringBuilder kept = new StringBuilder();
                    final Matcher keep = DECLARATION.matcher(code);
                    while (keep.find()) {
                        kept.append(keep.group()).append('\n');
                    }
                    code = kept.toString();
                }
                all.append(code).append('\n');
            }
        }
        blob = all.toString();
        final Matcher ids = IDENTIFIER.matcher(blob);
        while (ids.find()) {
            IDS.merge(ids.group(), 1, Integer::sum);
        }
        final Matcher literals = LITERAL.matcher(blob);
        while (literals.find()) {
            LITERALS.merge(literals.group(1), 1, Integer::sum);
        }
    }

    /** 只是說明文字（或這張登記表）的檔案。 */
    static boolean displayOnly(final String file) {
        return file.endsWith("Text.java") || file.equals("StatLines.java") || file.equals("SpecialEffectLines.java")
                || file.equals("StatConsumers.java");
    }

    /** 拿掉 // 與 /* *&#47; 註解（字串、字元裡的不動）。 */
    static String stripComments(final String code) {
        final StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            final char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                if (c == '"' && code.startsWith("\"\"\"", i)) {
                    final int end = code.indexOf("\"\"\"", i + 3);
                    final int stop = end < 0 ? code.length() : end + 3;
                    out.append(code, i, stop);
                    i = stop;
                    continue;
                }
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != c && code.charAt(j) != '\n') {
                    j += code.charAt(j) == '\\' ? 2 : 1;
                }
                final int stop = Math.min(code.length(), j + 1);
                out.append(code, i, stop);
                i = stop;
            } else if (code.startsWith("//", i)) {
                final int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", i)) {
                final int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 這個常數名稱在宣告以外的地方有沒有用到。 */
    static boolean referenced(final String name) {
        return IDS.getOrDefault(name, 0) > DECLARED_NAMES.getOrDefault(name, 0);
    }

    /** 這個字串在宣告常數以外的地方有沒有直接寫。 */
    static boolean written(final String literal) {
        return LITERALS.getOrDefault(literal, 0) > DECLARED_LITERALS.getOrDefault(literal, 0);
    }

    /**
     * 夠具體的前綴（damage.extra.、flag.immune.、damage.vs_）：「flag.」「exposure.」這種只有一段的太籠統（什麼都配得上），不算。
     */
    static boolean specificPrefix(final String prefix) {
        final long dots = prefix.chars().filter(c -> c == '.').count();
        return prefix.length() > 3 && (prefix.endsWith(".") && dots >= 2 || prefix.endsWith("_") && dots >= 1);
    }

    static boolean consumed(final String key) {
        if (written(key)) {
            return true;
        }
        for (final String name : DECLARED.getOrDefault(key, List.of())) {
            if (referenced(name)) {
                return true;
            }
        }
        // 由前綴組出來的（damage.extra. ＋ 類型、flag.immune. ＋ 異常、resist.max. ＋ 元素…）
        for (final Map.Entry<String, List<String>> entry : DECLARED.entrySet()) {
            final String prefix = entry.getKey();
            if (specificPrefix(prefix) && key.startsWith(prefix)
                    && (written(prefix) || entry.getValue().stream().anyMatch(StatConsumersTest::referenced))) {
                return true;
            }
        }
        for (final String literal : LITERALS.keySet()) {
            if (specificPrefix(literal) && key.startsWith(literal) && written(literal)) {
                return true;
            }
        }
        // 登記的一段前綴（grant_skill.、supgem.、surge.）
        for (final String prefix : StatConsumers.PREFIXES.keySet()) {
            if (key.startsWith(prefix) && (written(prefix) || DECLARED.getOrDefault(prefix, List.of()).stream().anyMatch(StatConsumersTest::referenced))) {
                return true;
            }
        }
        // 「常數 ＋ "." ＋ …」（StatKeys.RESIST_MAX + "." + 元素、SELF_AILMENT_DURATION + ".ignite"）
        final int dot = key.lastIndexOf('.');
        if (dot > 0) {
            final String head = key.substring(0, dot);
            for (final String name : DECLARED.getOrDefault(head, List.of())) {
                if (Pattern.compile("\\b" + name + "\\s*\\+\\s*\"\\.").matcher(blob).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void everyTreeAndAscendancyStatHasAConsumer() {
        assertTrue(USED.size() > 500, "讀到的鍵太少：" + USED.size());
        final List<String> missing = new ArrayList<>();
        for (final Map.Entry<String, Set<String>> entry : USED.entrySet()) {
            final String key = entry.getKey();
            if (StatConsumers.INHERENT.containsKey(key) || StatConsumers.BY_NODE.containsKey(key)) {
                continue;
            }
            if (!consumed(key)) {
                missing.add(key + " " + entry.getValue());
            }
        }
        assertTrue(missing.isEmpty(), "這些屬性鍵沒有程式在讀（接上消費端，或寫進 StatConsumers 並說明原因）：" + missing);
    }

    @Test
    void registryEntriesAreReallyUsedAndNotConsumed() {
        // 登記表的鍵要真的在樹上或昇華用到；INHERENT 的鍵不該有人讀（有人讀就從表裡拿掉）
        for (final String key : StatConsumers.INHERENT.keySet()) {
            assertTrue(USED.containsKey(key), "沒有用到的登記：" + key);
            assertTrue(!consumed(key), "已經有程式在讀，從 INHERENT 拿掉：" + key);
        }
        for (final String key : StatConsumers.BY_NODE.keySet()) {
            assertTrue(USED.containsKey(key), "沒有用到的登記：" + key);
        }
    }

    /** 讀的方法：flat（含 flag）、increased、more、get／apply（全部種類都算）。 */
    static final Pattern READ = Pattern.compile("\\b(flat|flag|increased|more|get|apply|modifiersOf|scale|has)\\((?=([^;{}]{0,160}))");

    @Test
    void treeTypesMatchHowTheKeyIsRead() {
        // 2026-10-27：鍵只被 flat(...) 讀、樹上卻是 INCREASED（或反過來）＝假生效（「增加 30% 對低血敵人的擊中傷害」就是這樣）
        final Map<String, Set<String>> reads = new HashMap<>();
        final Matcher m = READ.matcher(blob);
        while (m.find()) {
            final String args = firstArgs(m.group(2));
            final Matcher ids = Pattern.compile("\"([^\"]+)\"|\\b([A-Z][A-Z0-9_]*)\\b").matcher(args);
            while (ids.find()) {
                reads.computeIfAbsent(ids.group(1) != null ? ids.group(1) : ids.group(2), k -> new HashSet<>()).add(m.group(1));
            }
        }
        final List<String> wrong = new ArrayList<>();
        for (final Map.Entry<String, Set<String>> entry : USED.entrySet()) {
            final String key = entry.getKey();
            if (StatConsumers.ANY_TYPE.containsKey(key) || StatConsumers.INHERENT.containsKey(key) || StatConsumers.BY_NODE.containsKey(key)) {
                continue;
            }
            final Set<String> how = new HashSet<>(reads.getOrDefault(key, Set.of()));
            for (final String name : DECLARED.getOrDefault(key, List.of())) {
                how.addAll(reads.getOrDefault(name, Set.of()));
            }
            if (how.isEmpty() || how.contains("get") || how.contains("apply") || how.contains("modifiersOf") || how.contains("scale")) {
                continue; // 沒找到直接讀的地方（前綴組出來的）或是全部種類都算
            }
            for (final String use : entry.getValue()) {
                final String type = use.substring(0, use.indexOf(' '));
                final boolean ok = switch (type) {
                    case "FLAT" -> how.contains("flat") || how.contains("flag") || how.contains("has");
                    case "INCREASED" -> how.contains("increased");
                    case "MORE" -> how.contains("more");
                    default -> true;
                };
                if (!ok) {
                    wrong.add(key + " " + use + " 只被 " + how + " 讀");
                }
            }
        }
        assertTrue(wrong.isEmpty(), "種類對不上（樹上寫的種類沒人讀）：" + wrong);
    }

    /** 呼叫的前兩個參數（到第二個逗號或右括號為止）。 */
    static String firstArgs(final String rest) {
        int depth = 0;
        int commas = 0;
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return rest.substring(0, i);
                }
                depth--;
            } else if (c == ',' && depth == 0 && ++commas == 2) {
                return rest.substring(0, i);
            }
        }
        return rest;
    }
}
