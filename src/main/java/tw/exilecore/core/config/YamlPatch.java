package tw.exilecore.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 改資料檔裡某一個項目的幾個值（2026-10-15，給 /ec dummy move、/ec npc move 存位置用）：只動那幾行，
 * 註解、排版、其他設定都不動。純文字處理，不依賴 Bukkit。
 */
public final class YamlPatch {

    private YamlPatch() {
    }

    /** 數字寫成檔案裡的樣子：整數不帶小數點，其他留一位小數。 */
    public static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.rint(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** 找頂層的 section（例如 dummies:）底下、縮兩格的 id: 那一段：回傳 [開始行, 結束行（不含）]；找不到 null。 */
    static int[] block(final List<String> lines, final String section, final String id) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches(Pattern.quote(section) + ":\\s*(#.*)?")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        final Pattern head = Pattern.compile("  " + Pattern.quote(id) + ":\\s*(#.*)?");
        for (int i = start + 1; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                return null; // 到下一個頂層了
            }
            if (head.matcher(line).matches()) {
                int end = i + 1;
                while (end < lines.size()) {
                    final String next = lines.get(end);
                    if (!next.isBlank() && indent(next) <= 2 && !next.trim().startsWith("#")) {
                        break;
                    }
                    end++;
                }
                // 結尾的空行、註解屬於下一段
                while (end > i + 1 && (lines.get(end - 1).isBlank() || lines.get(end - 1).trim().startsWith("#"))) {
                    end--;
                }
                return new int[] {i, end};
            }
        }
        return null;
    }

    private static int indent(final String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static List<String> split(final String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    /** 這一段裡設一個「縮四格的 key: 值」：有就換掉值（行尾註解留著），沒有就加在 id 那一行下面。 */
    private static void put(final List<String> lines, final int[] block, final String key, final String value) {
        final Pattern pattern = Pattern.compile("(    " + Pattern.quote(key) + ":\\s*)([^#]*?)(\\s*#.*)?");
        for (int i = block[0] + 1; i < block[1]; i++) {
            final Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.matches()) {
                lines.set(i, matcher.group(1) + value + (matcher.group(3) == null ? "" : matcher.group(3)));
                return;
            }
        }
        lines.add(block[0] + 1, "    " + key + ": " + value);
        block[1]++;
    }

    /**
     * 訓練假人（dummies.yml）的位置：dummies.&lt;id&gt;.offset 與 yaw。
     *
     * @return 改好的檔案內容；找不到這隻假人 null
     */
    public static String dummyPlacement(final String text, final String id, final double x, final double z, final float yaw) {
        final List<String> lines = split(text);
        final int[] block = block(lines, "dummies", id);
        if (block == null) {
            return null;
        }
        put(lines, block, "offset", "[" + number(x) + ", " + number(z) + "]");
        put(lines, block, "yaw", number(yaw));
        return String.join("\n", lines);
    }

    /**
     * 劇情人物（quests.yml）的位置：npcs.&lt;id&gt;.place 的 at、y、yaw（place 寫成一行的 { … } 或分好幾行都可以）。
     *
     * @return 改好的檔案內容；找不到這個人物、或他沒有 place null
     */
    public static String storyPlacement(final String text, final String id, final double x, final double z, final double y, final float yaw) {
        final List<String> lines = split(text);
        final int[] block = block(lines, "npcs", id);
        if (block == null) {
            return null;
        }
        final Pattern inline = Pattern.compile("(    place:\\s*\\{)(.*)(\\}\\s*(#.*)?)");
        for (int i = block[0] + 1; i < block[1]; i++) {
            final Matcher matcher = inline.matcher(lines.get(i));
            if (matcher.matches()) {
                String body = matcher.group(2);
                body = setInline(body, "at", "[" + number(x) + ", " + number(z) + "]");
                body = setInline(body, "y", y == 0 ? null : number(y));
                body = setInline(body, "yaw", number(yaw));
                lines.set(i, matcher.group(1) + " " + body.trim() + " " + matcher.group(3));
                return String.join("\n", lines);
            }
            if (lines.get(i).matches("    place:\\s*(#.*)?")) {
                // 分好幾行：縮六格的 at、y、yaw
                int end = i + 1;
                while (end < block[1] && (lines.get(end).isBlank() || indent(lines.get(end)) >= 6)) {
                    end++;
                }
                final int[] place = {i, end};
                putDeep(lines, place, "at", "[" + number(x) + ", " + number(z) + "]");
                if (y != 0) {
                    putDeep(lines, place, "y", number(y));
                } else {
                    removeDeep(lines, place, "y");
                }
                putDeep(lines, place, "yaw", number(yaw));
                return String.join("\n", lines);
            }
        }
        return null;
    }

    /** { a: 1, b: 2 } 裡面設一個值（value 為 null = 拿掉）。 */
    static String setInline(final String body, final String key, final String value) {
        final List<String> parts = new ArrayList<>();
        // 照逗號切，但 [ ] 裡的逗號不算
        int depth = 0;
        int from = 0;
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(from, i).trim());
                from = i + 1;
            }
        }
        if (!body.substring(from).isBlank()) {
            parts.add(body.substring(from).trim());
        }
        boolean found = false;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).startsWith(key + ":")) {
                found = true;
                if (value == null) {
                    parts.remove(i);
                } else {
                    parts.set(i, key + ": " + value);
                }
                break;
            }
        }
        if (!found && value != null) {
            parts.add(key + ": " + value);
        }
        return String.join(", ", parts);
    }

    private static void putDeep(final List<String> lines, final int[] place, final String key, final String value) {
        final Pattern pattern = Pattern.compile("(      " + Pattern.quote(key) + ":\\s*)([^#]*?)(\\s*#.*)?");
        for (int i = place[0] + 1; i < place[1]; i++) {
            final Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.matches()) {
                lines.set(i, matcher.group(1) + value + (matcher.group(3) == null ? "" : matcher.group(3)));
                return;
            }
        }
        lines.add(place[0] + 1, "      " + key + ": " + value);
        place[1]++;
    }

    private static void removeDeep(final List<String> lines, final int[] place, final String key) {
        for (int i = place[0] + 1; i < place[1]; i++) {
            if (lines.get(i).matches("      " + Pattern.quote(key) + ":.*")) {
                lines.remove(i);
                place[1]--;
                return;
            }
        }
    }
}
