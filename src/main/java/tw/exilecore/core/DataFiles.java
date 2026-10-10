package tw.exilecore.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * 預設資料檔的同步：jar 裡的 data/ 是預設值，伺服器上的 plugins/ExileCore/data/ 是實際使用的那份。
 * <p>
 * 每次啟動時依 jar 的 data/index.txt 逐檔比對：
 * <ul>
 *   <li>伺服器上沒有 → 複製過去。</li>
 *   <li>伺服器上的檔案就是上次複製的預設值（沒被改過）→ 直接換成新版。</li>
 *   <li>伺服器上的檔案被改過 → 不動它，新版預設值放在旁邊的 {@code .new}，主控台提醒自己合併。</li>
 * </ul>
 * 「上次複製的預設值」用 data/.defaults.properties 記錄每個檔案的 SHA-256。
 * 舊版安裝（還沒有這個紀錄檔）第一次升級時：整個 data/ 先備份到 data-backup-時間/，再全部換成新版。
 */
public final class DataFiles {

    /** 紀錄檔（相對於插件資料夾）。 */
    static final String HASH_FILE = "data/.defaults.properties";

    /** 讀 jar 裡的一個資源；不存在回傳 null。 */
    @FunctionalInterface
    public interface ResourceLoader {
        byte[] load(String path) throws IOException;
    }

    /** 一次同步的結果。 */
    public record Report(List<String> added, List<String> updated, List<String> conflicts, Path backup) {
        public Report {
            added = List.copyOf(added);
            updated = List.copyOf(updated);
            conflicts = List.copyOf(conflicts);
        }
    }

    private DataFiles() {
    }

    /** 插件啟動時呼叫。 */
    public static Report sync(final JavaPlugin plugin) throws IOException {
        final byte[] index = resource(plugin, "data/index.txt");
        if (index == null) {
            return new Report(List.of(), List.of(), List.of(), null);
        }
        final String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return sync(plugin.getDataFolder().toPath(), parseIndex(index), path -> resource(plugin, path), timestamp);
    }

    /** 核心邏輯（不依賴 Bukkit，單元測試直接呼叫）。 */
    public static Report sync(final Path root, final List<String> paths, final ResourceLoader loader,
                              final String timestamp) throws IOException {
        final Path hashFile = root.resolve(HASH_FILE);
        final Properties hashes = new Properties();
        final boolean tracked = Files.exists(hashFile);
        if (tracked) {
            try (Reader reader = Files.newBufferedReader(hashFile, StandardCharsets.UTF_8)) {
                hashes.load(reader);
            }
        }

        // 舊版安裝：沒有紀錄檔但已經有資料檔 → 先整批備份
        boolean legacy = false;
        if (!tracked) {
            for (final String path : paths) {
                if (Files.exists(root.resolve(path))) {
                    legacy = true;
                    break;
                }
            }
        }
        Path backup = null;
        if (legacy) {
            backup = root.resolve("data-backup-" + timestamp);
            for (final String path : paths) {
                final Path file = root.resolve(path);
                if (Files.exists(file)) {
                    final Path target = backup.resolve(path);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        final List<String> added = new ArrayList<>();
        final List<String> updated = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        for (final String path : paths) {
            final byte[] bytes = loader.load(path);
            if (bytes == null) {
                continue; // index 列了但 jar 裡沒有
            }
            final String newHash = sha256(bytes);
            final Path file = root.resolve(path);
            if (!Files.exists(file)) {
                write(file, bytes);
                hashes.setProperty(path, newHash);
                added.add(path);
                continue;
            }
            final String diskHash = sha256(Files.readAllBytes(file));
            if (diskHash.equals(newHash)) {
                hashes.setProperty(path, newHash);
                continue;
            }
            if (legacy || diskHash.equals(hashes.getProperty(path))) {
                write(file, bytes);
                hashes.setProperty(path, newHash);
                updated.add(path);
                continue;
            }
            // 被改過：保留，新版放旁邊
            write(file.resolveSibling(file.getFileName() + ".new"), bytes);
            conflicts.add(path);
        }

        Files.createDirectories(hashFile.getParent());
        try (Writer writer = Files.newBufferedWriter(hashFile, StandardCharsets.UTF_8)) {
            hashes.store(writer, "ExileCore default data file hashes - used to detect edited files, do not edit");
        }
        return new Report(added, updated, conflicts, backup);
    }

    /**
     * 把一個檔案記成「現在的內容就是預設值」（接受 .new 之後用）：之後這個檔案沒再被改過，下次升級就會自動換成新版。
     */
    public static void recordDefault(final Path root, final String path, final byte[] bytes) throws IOException {
        final Path hashFile = root.resolve(HASH_FILE);
        final Properties hashes = new Properties();
        if (Files.exists(hashFile)) {
            try (Reader reader = Files.newBufferedReader(hashFile, StandardCharsets.UTF_8)) {
                hashes.load(reader);
            }
        }
        hashes.setProperty(path, sha256(bytes));
        Files.createDirectories(hashFile.getParent());
        try (Writer writer = Files.newBufferedWriter(hashFile, StandardCharsets.UTF_8)) {
            hashes.store(writer, "ExileCore default data file hashes - used to detect edited files, do not edit");
        }
    }

    /** jar 的 data/index.txt 列的所有資料檔（相對於插件資料夾）；沒有 index 就是空的。 */
    public static List<String> indexedPaths(final JavaPlugin plugin) throws IOException {
        final byte[] index = resource(plugin, "data/index.txt");
        return index == null ? List.of() : parseIndex(index);
    }

    /** 讀 jar 裡預設資料檔的 loader（體檢、指令用）。 */
    public static ResourceLoader loader(final JavaPlugin plugin) {
        return path -> resource(plugin, path);
    }

    static List<String> parseIndex(final byte[] index) {
        final List<String> paths = new ArrayList<>();
        for (final String line : new String(index, StandardCharsets.UTF_8).split("\\R")) {
            final String path = line.trim();
            if (!path.isEmpty() && !path.startsWith("#")) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static byte[] resource(final JavaPlugin plugin, final String path) throws IOException {
        try (InputStream in = plugin.getResource(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void write(final Path file, final byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 沒有 SHA-256", e);
        }
    }
}
