package tw.exilecore.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 資料檔的體檢（{@code /ec data status}、啟動時的提醒）：每個資料檔跟 jar 裡的新版預設值比，
 * 告訴你哪些檔案是你改過的、哪些有還沒合併的 {@code .new}、你的檔案裡<b>缺了新版有的哪些段落或鍵</b>
 * （新功能的設定通常長這樣：你改過的檔案不會自動換新，新段落就沒讀到，照預設值跑）。
 * 只比鍵不比值（值是你故意改的）。純邏輯，不依賴 Bukkit。
 */
public final class DataAudit {

    /** 鍵的差異最多列幾個（主控台不洗版）。 */
    public static final int MAX_LISTED = 12;
    /** 鍵的路徑最多看幾層（經濟的 values.通貨 是第 2 層；清單裡面不看）。 */
    static final int MAX_DEPTH = 3;

    /** 一個檔案的狀態。 */
    public enum State {
        /** 跟新版預設值一樣。 */
        SAME("跟預設一樣"),
        /** 你改過（新版跟你的不同），而且旁邊有還沒合併的 .new。 */
        PENDING("改過，有 .new 還沒合併"),
        /** 你改過，但新版預設值跟上次一樣（沒有東西要合併）。 */
        MODIFIED("改過"),
        /** 伺服器上沒有這個檔案（下次啟動會複製）。 */
        MISSING("不存在");

        private final String displayName;

        State(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * 一個檔案的體檢結果。
     *
     * @param path        相對於插件資料夾（data/economy.yml）
     * @param state       狀態
     * @param missingKeys 新版有、你的檔案沒有的鍵（a.b.c）；非 YAML 檔或讀不出來是空的
     * @param problem     讀不出來的原因（null = 正常）
     */
    public record FileStatus(String path, State state, List<String> missingKeys, String problem) {
        public FileStatus {
            missingKeys = List.copyOf(missingKeys);
        }

        /** 要提醒的：有 .new 或缺鍵。 */
        public boolean needsAttention() {
            return state == State.PENDING || !missingKeys.isEmpty();
        }
    }

    private DataAudit() {
    }

    /** 全部檔案的體檢。 */
    public static List<FileStatus> audit(final Path root, final List<String> paths, final DataFiles.ResourceLoader loader) throws IOException {
        final List<FileStatus> list = new ArrayList<>();
        for (final String path : paths) {
            final byte[] bytes = loader.load(path);
            if (bytes == null) {
                continue; // index 列了但 jar 裡沒有
            }
            list.add(auditOne(root, path, bytes));
        }
        return list;
    }

    static FileStatus auditOne(final Path root, final String path, final byte[] defaults) throws IOException {
        final Path file = root.resolve(path);
        if (!Files.exists(file)) {
            return new FileStatus(path, State.MISSING, List.of(), null);
        }
        final byte[] disk = Files.readAllBytes(file);
        if (DataFiles.sha256(disk).equals(DataFiles.sha256(defaults))) {
            return new FileStatus(path, State.SAME, List.of(), null);
        }
        final boolean pending = Files.exists(file.resolveSibling(file.getFileName() + ".new"));
        if (!path.endsWith(".yml") && !path.endsWith(".yaml")) {
            return new FileStatus(path, pending ? State.PENDING : State.MODIFIED, List.of(), null);
        }
        try {
            final List<String> missing = missingKeys(new String(disk, StandardCharsets.UTF_8), new String(defaults, StandardCharsets.UTF_8));
            return new FileStatus(path, pending ? State.PENDING : State.MODIFIED, missing, null);
        } catch (final RuntimeException e) {
            return new FileStatus(path, pending ? State.PENDING : State.MODIFIED, List.of(), "讀不出來：" + e.getMessage());
        }
    }

    /** 新版有、你的檔案沒有的鍵（a.b.c，最多看 {@value #MAX_DEPTH} 層）。 */
    public static List<String> missingKeys(final String yours, final String defaults) {
        final Object mine = load(yours);
        final Object theirs = load(defaults);
        final List<String> missing = new ArrayList<>();
        collect("", mine, theirs, missing, 0);
        return missing;
    }

    private static Object load(final String text) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded = new Yaml(options).load(text);
        return loaded == null ? Map.of() : loaded;
    }

    private static void collect(final String prefix, final Object mine, final Object theirs, final List<String> missing, final int depth) {
        if (!(theirs instanceof Map<?, ?> defaults) || depth >= MAX_DEPTH) {
            return;
        }
        final Map<?, ?> own = mine instanceof Map<?, ?> map ? map : Map.of();
        for (final Map.Entry<?, ?> entry : defaults.entrySet()) {
            final String key = String.valueOf(entry.getKey());
            final String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (!own.containsKey(entry.getKey())) {
                missing.add(full);
                continue;
            }
            collect(full, own.get(entry.getKey()), entry.getValue(), missing, depth + 1);
        }
    }

    /**
     * 接受新版：你的檔案改名成 {@code 檔名.bak-時間} 留著，.new 換上來、記成預設值（之後沒改過就會自動跟新版）。
     *
     * @return 備份的路徑
     */
    public static Path acceptNew(final Path root, final String path, final String timestamp) throws IOException {
        final Path file = root.resolve(path);
        final Path fresh = file.resolveSibling(file.getFileName() + ".new");
        if (!Files.exists(fresh)) {
            throw new IOException(path + " 沒有 .new 可以接受");
        }
        final Path backup = file.resolveSibling(file.getFileName() + ".bak-" + timestamp);
        if (Files.exists(file)) {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(fresh, file, StandardCopyOption.REPLACE_EXISTING);
        DataFiles.recordDefault(root, path, Files.readAllBytes(file));
        return backup;
    }

    /** 一行摘要（主控台、指令用）：「data/economy.yml：改過，有 .new 還沒合併；缺 5 個鍵：values.distilled_ire、…」。 */
    public static String summary(final FileStatus status) {
        final StringBuilder text = new StringBuilder(status.path()).append("：").append(status.state().displayName());
        if (status.problem() != null) {
            text.append("；").append(status.problem());
        }
        if (!status.missingKeys().isEmpty()) {
            text.append("；缺 ").append(status.missingKeys().size()).append(" 個新版的鍵：");
            final List<String> shown = status.missingKeys().subList(0, Math.min(MAX_LISTED, status.missingKeys().size()));
            text.append(String.join("、", shown));
            if (status.missingKeys().size() > shown.size()) {
                text.append("…（還有 ").append(status.missingKeys().size() - shown.size()).append(" 個）");
            }
        }
        return text.toString();
    }
}
