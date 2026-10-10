package tw.exilecore.admin;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 管理網頁改的設定：存在 plugins/ExileCore/{@value #FILE}，蓋過 data/ 資料檔與 config.yml 的同一個鍵（那些檔案本身不動）。
 * <ul>
 *   <li>data：資料檔（相對於 data/ 的路徑，例如 {@code character.yml}、{@code affixes/maps.yml}）→ 點分隔的鍵 → 值。
 *       讀資料檔時由 {@link tw.exilecore.core.config.YamlNode#load(Path)} 蓋上去（{@link #forFile}）。</li>
 *   <li>config：config.yml 的鍵 → 值（插件載入 config.yml 之後蓋在記憶體裡那份上）。</li>
 *   <li>maintenance：維護模式（只讓管理員進）與給玩家看的說明。</li>
 * </ul>
 * 讀的時候不用鎖（不可變的快照）；改的時候寫檔成功才換上新的快照。檔案寫壞了讀不進來時不會蓋掉它（先修好或刪掉）。
 * 純 Java，不依賴 Bukkit。
 */
public final class AdminOverrides {

    public static final String FILE = "admin-overrides.yml";
    /** 維護模式沒寫說明時給玩家看的。 */
    public static final String DEFAULT_MAINTENANCE_MESSAGE = "伺服器維護中，請稍後再來";

    /** 一份不會再變的內容。 */
    private record Snapshot(Map<String, Map<String, Object>> data, Map<String, Object> config, boolean maintenance, String message) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), false, "");
    }

    private final Path file;
    private final Path dataRoot;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    /** 檔案讀不進來（寫壞了）：不能存，免得把它蓋掉。 */
    private volatile String problem;

    /**
     * @param pluginFolder plugins/ExileCore（覆蓋檔在這裡，資料檔在底下的 data/）
     */
    public AdminOverrides(final Path pluginFolder) {
        this.file = pluginFolder.resolve(FILE);
        this.dataRoot = pluginFolder.resolve("data").toAbsolutePath().normalize();
    }

    /** 從檔案讀（沒有檔案 = 什麼都沒蓋）。讀不進來時保留原本的內容，回傳原因（之後也不能存）；成功回傳 null。 */
    public synchronized String reload() {
        if (!Files.exists(file)) {
            snapshot = Snapshot.EMPTY;
            problem = null;
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            final Object root = new Yaml(options).load(reader);
            snapshot = parse(root);
            problem = null;
            return null;
        } catch (final IOException | RuntimeException e) {
            problem = FILE + " 讀不進來（" + e.getMessage() + "）：先修好或刪掉這個檔，再 /ec reload";
            return problem;
        }
    }

    /** 檔案讀不進來的原因（沒問題是 null）。 */
    public String problem() {
        return problem;
    }

    public Path file() {
        return file;
    }

    // ---- 讀 ----

    /** 這個檔案要蓋上去的值（不在 data/ 底下、沒有覆蓋回傳空的）。給 YamlNode 的 overlay 用，任何執行緒都可以呼叫。 */
    public Map<String, Object> forFile(final Path path) {
        final Snapshot current = snapshot;
        if (current.data().isEmpty() || path == null) {
            return Map.of();
        }
        final String relative = relative(path);
        if (relative == null) {
            return Map.of();
        }
        final Map<String, Object> values = current.data().get(relative);
        return values == null ? Map.of() : values;
    }

    /** 某個資料檔的某個鍵被改成什麼（沒改是 null）。 */
    public Object data(final String dataFile, final String key) {
        final Map<String, Object> values = snapshot.data().get(normalize(dataFile));
        return values == null ? null : values.get(key);
    }

    /** 所有資料檔的覆蓋（檔案 → 鍵 → 值）。 */
    public Map<String, Map<String, Object>> data() {
        return snapshot.data();
    }

    /** config.yml 的覆蓋（鍵 → 值）。 */
    public Map<String, Object> config() {
        return snapshot.config();
    }

    public boolean maintenance() {
        return snapshot.maintenance();
    }

    /** 維護模式時給玩家看的說明（沒寫用預設的）。 */
    public String maintenanceMessage() {
        final String message = snapshot.message();
        return message == null || message.isBlank() ? DEFAULT_MAINTENANCE_MESSAGE : message;
    }

    // ---- 改（寫檔成功才生效） ----

    /** 改一個資料檔的鍵（value = null 拿掉，回到檔案裡的值）。 */
    public synchronized void setData(final String dataFile, final String key, final Object value) throws IOException {
        final Snapshot current = snapshot;
        final Map<String, Map<String, Object>> data = new TreeMap<>();
        for (final Map.Entry<String, Map<String, Object>> entry : current.data().entrySet()) {
            data.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
        final String name = normalize(dataFile);
        final Map<String, Object> values = data.computeIfAbsent(name, ignored -> new TreeMap<>());
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        if (values.isEmpty()) {
            data.remove(name);
        }
        save(new Snapshot(freeze(data), current.config(), current.maintenance(), current.message()));
    }

    /** 改 config.yml 的一個鍵（value = null 拿掉）。 */
    public synchronized void setConfig(final String key, final Object value) throws IOException {
        final Snapshot current = snapshot;
        final Map<String, Object> config = new TreeMap<>(current.config());
        if (value == null) {
            config.remove(key);
        } else {
            config.put(key, value);
        }
        save(new Snapshot(current.data(), Collections.unmodifiableMap(config), current.maintenance(), current.message()));
    }

    /** 開關維護模式（message 可以是 null：沿用原本的）。 */
    public synchronized void setMaintenance(final boolean enabled, final String message) throws IOException {
        final Snapshot current = snapshot;
        save(new Snapshot(current.data(), current.config(), enabled, message == null ? current.message() : message.trim()));
    }

    private void save(final Snapshot next) throws IOException {
        if (problem != null) {
            throw new IOException(problem);
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        final Map<String, Object> data = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Object>> entry : next.data().entrySet()) {
            data.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        root.put("data", data);
        root.put("config", new LinkedHashMap<>(next.config()));
        final Map<String, Object> maintenance = new LinkedHashMap<>();
        maintenance.put("enabled", next.maintenance());
        maintenance.put("message", next.message() == null ? "" : next.message());
        root.put("maintenance", maintenance);
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setIndent(2);
        options.setWidth(200);
        final String body = new Yaml(options).dump(root);
        final String text = """
                # 管理網頁改的設定：蓋過 data/ 資料檔與 config.yml 的同一個鍵（那些檔案本身不動）。
                # 在管理網頁按「恢復」會拿掉那一行；也可以直接改這個檔，改完 /ec reload（刪掉這個檔 = 全部回到檔案裡的值）。
                # data：資料檔（相對於 data/ 的路徑）→ 鍵（點分隔）→ 值；config：config.yml 的鍵 → 值；maintenance：維護模式（只讓管理員進）。
                """ + body;
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(FILE + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true); // 先寫到磁碟再換上去：停電也不會留下空的檔
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        snapshot = next;
    }

    // ---- 解析 ----

    private static Snapshot parse(final Object root) {
        if (root == null) {
            return Snapshot.EMPTY;
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("最外層要是鍵值對");
        }
        final Map<String, Map<String, Object>> data = new TreeMap<>();
        if (map.get("data") instanceof Map<?, ?> files) {
            for (final Map.Entry<?, ?> entry : files.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> values) {
                    final Map<String, Object> flat = new TreeMap<>();
                    flatten("", values, flat);
                    if (!flat.isEmpty()) {
                        data.put(normalize(String.valueOf(entry.getKey())), flat);
                    }
                }
            }
        } else if (map.get("data") != null) {
            throw new IllegalArgumentException("data 要是鍵值對");
        }
        final Map<String, Object> config = new TreeMap<>();
        if (map.get("config") instanceof Map<?, ?> values) {
            flatten("", values, config);
        } else if (map.get("config") != null) {
            throw new IllegalArgumentException("config 要是鍵值對");
        }
        boolean maintenance = false;
        String message = "";
        if (map.get("maintenance") instanceof Map<?, ?> section) {
            maintenance = Boolean.TRUE.equals(section.get("enabled")) || "true".equalsIgnoreCase(String.valueOf(section.get("enabled")));
            message = section.get("message") == null ? "" : String.valueOf(section.get("message")).trim();
        }
        return new Snapshot(freeze(data), Collections.unmodifiableMap(config), maintenance, message);
    }

    /** 巢狀的寫法也收（{@code trade: {fee: {percent: 5}}} = {@code trade.fee.percent: 5}）；清單照原樣當成一個值。 */
    private static void flatten(final String prefix, final Map<?, ?> values, final Map<String, Object> out) {
        for (final Map.Entry<?, ?> entry : values.entrySet()) {
            final String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(key + ".", nested, out);
            } else if (entry.getValue() != null) {
                out.put(key, entry.getValue());
            }
        }
    }

    private static Map<String, Map<String, Object>> freeze(final Map<String, Map<String, Object>> data) {
        final Map<String, Map<String, Object>> frozen = new TreeMap<>();
        for (final Map.Entry<String, Map<String, Object>> entry : data.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableMap(new TreeMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /** 檔名統一用正斜線、不帶開頭的 ./ 或 data/。 */
    static String normalize(final String dataFile) {
        String name = dataFile.trim().replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("data/")) {
            name = name.substring(5);
        }
        return name;
    }

    /** 資料檔相對於 data/ 的路徑；不在 data/ 底下回傳 null。 */
    private String relative(final Path path) {
        final Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(dataRoot)) {
            return null;
        }
        return dataRoot.relativize(absolute).toString().replace('\\', '/');
    }
}
