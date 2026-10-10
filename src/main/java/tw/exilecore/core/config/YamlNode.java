package tw.exilecore.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SnakeYAML 讀進來的樹狀資料的型別安全包裝。不依賴 Bukkit，所以資料檔的載入器可以在單元測試裡直接跑。
 * <p>
 * 所有取值都帶預設值或丟出說明清楚的例外（含路徑），讓設定檔寫錯時一眼看得出哪裡錯。
 */
public final class YamlNode {

    private final String path;
    private final Map<String, Object> map;

    private YamlNode(final String path, final Map<?, ?> raw) {
        this.path = path;
        // YAML 會把 1: 這種鍵解析成整數，統一轉成字串
        final Map<String, Object> normalized = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : raw.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        this.map = normalized;
    }

    /**
     * 讀資料檔時要蓋上去的值（管理網頁的設定覆蓋，見 {@code AdminOverrides}）：檔案 → 點分隔的鍵 → 值。
     * 只影響 {@link #load(Path)}；資料檔本身不動。
     */
    @FunctionalInterface
    public interface Overlay {
        /** 這個檔案要蓋上去的值；沒有回傳空的。 */
        Map<String, Object> values(Path file);
    }

    private static volatile Overlay overlay;

    /** 換掉（null = 拿掉）讀資料檔時的覆蓋。 */
    public static void overlay(final Overlay value) {
        overlay = value;
    }

    /** 讀一個資料檔，再蓋上管理網頁改過的值（{@link #overlay}）。 */
    public static YamlNode load(final Path file) throws IOException {
        final YamlNode node = loadRaw(file);
        final Overlay current = overlay;
        if (current == null) {
            return node;
        }
        final Map<String, Object> values = current.values(file);
        return values == null || values.isEmpty() ? node : node.withValues(values);
    }

    /** 讀檔，不蓋管理網頁的值（網頁上顯示「檔案裡寫的」用）。 */
    public static YamlNode loadRaw(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(file.getFileName().toString(), reader);
        }
    }

    /**
     * 同一份資料，把這些值蓋上去（鍵用點分隔，例如 {@code trade.fee.percent}；中間沒有的層會補上）。
     * 原本的資料不動（路上經過的每一層都複製一份）。
     */
    public YamlNode withValues(final Map<String, Object> values) {
        final Map<Object, Object> root = new LinkedHashMap<>(map);
        for (final Map.Entry<String, Object> entry : values.entrySet()) {
            final String[] parts = entry.getKey().split("\\.");
            Map<Object, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                final Object key = keyFor(current, parts[i]);
                final Map<Object, Object> copy = new LinkedHashMap<>();
                if (current.get(key) instanceof Map<?, ?> existing) {
                    copy.putAll(existing);
                }
                current.put(key, copy);
                current = copy;
            }
            current.put(keyFor(current, parts[parts.length - 1]), entry.getValue());
        }
        return new YamlNode(path, root);
    }

    /**
     * 這份資料蓋上 overlay（overlay 的值優先；兩邊都是鍵值對就往下合併，清單整個換掉），skip 裡的鍵不從這份帶過去。
     * 技能的變體（skills.yml 的 variant_of）用：照原本的技能寫，只改寫到的地方。
     */
    public YamlNode mergedWith(final YamlNode overlay, final java.util.Set<String> skip) {
        final Map<String, Object> base = new LinkedHashMap<>(map);
        for (final String key : skip) {
            base.remove(key);
        }
        return new YamlNode(overlay.path, deepMerge(base, overlay.map));
    }

    private static Map<String, Object> deepMerge(final Map<String, Object> base, final Map<String, Object> over) {
        final Map<String, Object> out = new LinkedHashMap<>(base);
        for (final Map.Entry<String, Object> entry : over.entrySet()) {
            final Object previous = out.get(entry.getKey());
            if (previous instanceof Map<?, ?> left && entry.getValue() instanceof Map<?, ?> right) {
                out.put(entry.getKey(), deepMerge(normalize(left), normalize(right)));
            } else {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> normalize(final Map<?, ?> raw) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** 點分隔的鍵在這份資料裡的值（沒有是 null；中間不是鍵值對也是 null）。 */
    public Object lookup(final String dotted) {
        Object current = map;
        for (final String part : dotted.split("\\.")) {
            if (!(current instanceof Map<?, ?> level)) {
                return null;
            }
            current = level.get(keyFor(level, part));
        }
        return current;
    }

    /** YAML 會把 {@code 1:} 讀成整數鍵：用字串找的時候也找得到。 */
    private static Object keyFor(final Map<?, ?> map, final String key) {
        if (map.containsKey(key)) {
            return key;
        }
        for (final Object existing : map.keySet()) {
            if (existing != null && String.valueOf(existing).equals(key)) {
                return existing;
            }
        }
        return key;
    }

    public static YamlNode load(final String name, final Reader reader) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object root = new Yaml(options).load(reader);
        if (root == null) {
            return new YamlNode(name, new LinkedHashMap<>());
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + "：最外層必須是鍵值對");
        }
        return new YamlNode(name, map);
    }

    public static YamlNode of(final String path, final Map<?, ?> map) {
        return new YamlNode(path, map);
    }

    public String path() {
        return path;
    }

    public Set<String> keys() {
        return map.keySet();
    }

    public boolean has(final String key) {
        return map.containsKey(key) && map.get(key) != null;
    }

    public Object raw(final String key) {
        return map.get(key);
    }

    public YamlNode node(final String key) {
        final Object value = map.get(key);
        if (value == null) {
            return new YamlNode(path + "." + key, new LinkedHashMap<>());
        }
        if (!(value instanceof Map<?, ?> sub)) {
            throw new IllegalArgumentException(path + "." + key + "：應該是鍵值對，卻是 " + value.getClass().getSimpleName());
        }
        return new YamlNode(path + "." + key, sub);
    }

    /** 把這個節點底下的每個子鍵當成一筆資料（例如 bases 檔裡 id → 定義）。 */
    public List<YamlNode> children() {
        final List<YamlNode> list = new ArrayList<>();
        for (final String key : map.keySet()) {
            list.add(node(key));
        }
        return list;
    }

    public String string(final String key, final String def) {
        final Object value = map.get(key);
        return value == null ? def : String.valueOf(value);
    }

    public String requireString(final String key) {
        final Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(path + "：缺少必填欄位 " + key);
        }
        return String.valueOf(value);
    }

    public int integer(final String key, final int def) {
        final Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(path + "." + key + "：應該是整數，卻是「" + value + "」");
        }
    }

    public double decimal(final String key, final double def) {
        final Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(path + "." + key + "：應該是數字，卻是「" + value + "」");
        }
    }

    public boolean bool(final String key, final boolean def) {
        final Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public List<String> strings(final String key) {
        final Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            final List<String> result = new ArrayList<>();
            for (final Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of(String.valueOf(value));
    }

    /** 兩個數字的範圍，例如 {@code [12, 16]}；單一數字視為固定值。 */
    public int[] range(final String key) {
        final Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(path + "：缺少範圍欄位 " + key);
        }
        return toRange(path + "." + key, value);
    }

    public static int[] toRange(final String where, final Object value) {
        if (value instanceof Number number) {
            return new int[] {number.intValue(), number.intValue()};
        }
        if (value instanceof List<?> list && list.size() == 2
                && list.get(0) instanceof Number lo && list.get(1) instanceof Number hi) {
            return new int[] {lo.intValue(), hi.intValue()};
        }
        throw new IllegalArgumentException(where + "：範圍要寫成 [最小, 最大]，卻是「" + value + "」");
    }

    public List<YamlNode> list(final String key) {
        final Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(path + "." + key + "：應該是清單");
        }
        final List<YamlNode> result = new ArrayList<>();
        int index = 0;
        for (final Object item : list) {
            if (!(item instanceof Map<?, ?> sub)) {
                throw new IllegalArgumentException(path + "." + key + "[" + index + "]：應該是鍵值對");
            }
            result.add(new YamlNode(path + "." + key + "[" + index + "]", sub));
            index++;
        }
        return result;
    }
}
