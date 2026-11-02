package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 自動更新（純 Java，不碰 Minecraft；測試用）。
 * <ol>
 *   <li>讀更新說明檔（manifest.json：{version, url, sha256, changelog}），比現在的版本新才下載。</li>
 *   <li>下載到 mods/exilecore_client-版本.jar.pending（Fabric 只載入 .jar，.pending 不會被載入），算 SHA-256，
 *       跟說明檔對不上就刪掉、不用。</li>
 *   <li>關遊戲時（JVM 的 shutdown hook）換檔：刪掉舊的 jar、.pending 改名成 .jar（Fabric 不准同一個模組代號有兩個 jar，舊的一定要先刪）。
 *       Windows 的 jar 在遊戲開著時鎖住、刪不掉，所以另外開一個小程序（cmd）等遊戲關掉再換；其他系統直接換（開著的檔案可以刪），
 *       換不了才用 sh 在背景等一下再換。</li>
 *   <li>下次開遊戲時清掉剩下的（已經套用過的 .pending、換檔的批次檔）。</li>
 * </ol>
 * 下載的檔案除了「下次開遊戲時被 Fabric 當模組載入」以外不會被執行。
 */
public final class Updater {

    public static final String MOD_ID = "exilecore_client";
    public static final String PENDING_SUFFIX = ".jar.pending";
    /** 換檔用的批次檔（Windows），放在 mods 旁邊的 config 資料夾。 */
    public static final String SCRIPT_NAME = "exilecore_client-update.cmd";
    /** 模組檔最大幾 MB。 */
    static final long MAX_SIZE = 32L * 1024 * 1024;
    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 更新說明檔。url 已經換成絕對網址。 */
    public record Manifest(String version, URI url, String sha256, String changelog) {
    }

    /** 下載好、驗證過、等著關遊戲時換上去的新版。 */
    public record Pending(String version, Path file, String sha256) {
    }

    private final Path modsDir;
    private final Path configDir;
    private final String currentVersion;
    /** 現在載入的 jar（開發環境是資料夾：不更新）。 */
    private final Path currentJar;
    private final HttpClient http;
    private volatile Pending pending;
    private volatile boolean hookInstalled;

    public Updater(final Path modsDir, final Path configDir, final String currentVersion, final Path currentJar) {
        this.modsDir = modsDir;
        this.configDir = configDir;
        this.currentVersion = currentVersion;
        this.currentJar = currentJar;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 這個環境能不能自動更新（模組是從 mods 資料夾裡的一個 jar 載入的）。 */
    public boolean supported() {
        return currentJar != null && Files.isRegularFile(currentJar) && currentJar.getFileName().toString().endsWith(".jar")
                && currentJar.getParent() != null && sameFile(currentJar.getParent(), modsDir);
    }

    public Pending pending() {
        return pending;
    }

    public String currentVersion() {
        return currentVersion;
    }

    // ---- 純函式 ----

    /** 說明檔：格式不對、網址不是 http(s)、雜湊不是 64 個十六進位、版本有怪字元都丟例外。 */
    public static Manifest parseManifest(final String json, final URI base) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final String version = root.get("version").getAsString().trim();
        if (!SAFE_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("版本號有不能用的字：" + version);
        }
        final String sha = root.get("sha256").getAsString().trim().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha).matches()) {
            throw new IllegalArgumentException("SHA-256 格式不對");
        }
        final URI url = base == null ? URI.create(root.get("url").getAsString().trim()) : base.resolve(root.get("url").getAsString().trim());
        if (!httpUrl(url)) {
            throw new IllegalArgumentException("下載網址不是 http(s)：" + url);
        }
        final String changelog = root.has("changelog") && !root.get("changelog").isJsonNull() ? root.get("changelog").getAsString() : "";
        return new Manifest(version, url, sha, changelog);
    }

    public static boolean httpUrl(final URI url) {
        final String scheme = url == null || url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("http") || scheme.equals("https")) && url.getHost() != null;
    }

    public static String pendingName(final String version) {
        return MOD_ID + "-" + version + PENDING_SUFFIX;
    }

    public static String jarName(final String version) {
        return MOD_ID + "-" + version + ".jar";
    }

    /** exilecore_client-1.2.3.jar.pending → 1.2.3；不是這種檔名回傳 null。 */
    public static String pendingVersion(final String fileName) {
        final String prefix = MOD_ID + "-";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(PENDING_SUFFIX)) {
            return null;
        }
        final String version = fileName.substring(prefix.length(), fileName.length() - PENDING_SUFFIX.length());
        return SAFE_VERSION.matcher(version).matches() ? version : null;
    }

    public static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Windows 換檔的批次檔：每秒試一次刪掉舊的 jar（遊戲還開著時鎖住），刪掉了就把 .pending 改名，最多等 60 秒；最後刪掉自己。
     */
    public static String windowsScript(final Path oldJar, final Path pendingFile, final Path newJar) {
        return "@echo off\r\n"
                + "chcp 65001 >nul\r\n" // 路徑可能有中文（使用者名稱）：批次檔用 UTF-8 寫
                + "set n=0\r\n"
                + ":wait\r\n"
                + "ping -n 2 127.0.0.1 >nul\r\n"
                + "del /f /q \"" + oldJar + "\" >nul 2>&1\r\n"
                + "if not exist \"" + oldJar + "\" goto apply\r\n"
                + "set /a n+=1\r\n"
                + "if %n% lss 60 goto wait\r\n"
                + "goto end\r\n"
                + ":apply\r\n"
                + "move /y \"" + pendingFile + "\" \"" + newJar + "\" >nul\r\n"
                + ":end\r\n"
                + "(goto) 2>nul & del \"%~f0\"\r\n";
    }

    /** 其他系統在背景換檔的指令（直接換不了時才用）：等 2 秒、刪舊的、改名。路徑用單引號包起來。 */
    public static List<String> posixCommand(final Path oldJar, final Path pendingFile, final Path newJar) {
        final String script = "sleep 2; rm -f " + quote(oldJar) + " && mv -f " + quote(pendingFile) + " " + quote(newJar);
        return List.of("sh", "-c", "(" + script + ") >/dev/null 2>&1 &");
    }

    static String quote(final Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    /**
     * 開遊戲時要清掉的檔案：版本沒比現在新的 .pending（已經換上去了、或是更舊的）、留下來的批次檔。
     * 比現在新的 .pending 留著（上次沒換成功：這次關遊戲再換）。
     */
    public static List<Path> leftovers(final List<Path> modsFiles, final List<Path> configFiles, final String running) {
        final List<Path> out = new ArrayList<>();
        for (final Path file : modsFiles) {
            final String version = pendingVersion(file.getFileName().toString());
            if (version != null && Protocol.compareVersions(version, running) <= 0) {
                out.add(file);
            } else if (file.getFileName().toString().startsWith(MOD_ID + "-") && file.getFileName().toString().endsWith(".jar.part")) {
                out.add(file); // 下載到一半的
            }
        }
        for (final Path file : configFiles) {
            if (file.getFileName().toString().equals(SCRIPT_NAME)) {
                out.add(file);
            }
        }
        return out;
    }

    // ---- 會碰檔案、網路的 ----

    /**
     * 開遊戲時呼叫：清掉剩下的檔案，找出上次下載好但還沒換上去的新版（重新驗證雜湊；這次關遊戲時換）。
     *
     * @param expectedSha 上次下載時記下的雜湊（設定檔裡的；沒有是 null：那就不知道對不對，刪掉重下載）
     */
    public void startup(final String expectedVersion, final String expectedSha) {
        final List<Path> mods = list(modsDir);
        for (final Path file : leftovers(mods, list(configDir), currentVersion)) {
            try {
                Files.deleteIfExists(file);
            } catch (final IOException ignored) {
                // 刪不掉下次再試
            }
        }
        for (final Path file : list(modsDir)) {
            final String version = pendingVersion(file.getFileName().toString());
            if (version == null) {
                continue;
            }
            try {
                if (version.equals(expectedVersion) && expectedSha != null && expectedSha.equals(sha256(file))) {
                    setPending(new Pending(version, file, expectedSha));
                } else {
                    Files.deleteIfExists(file); // 不知道是不是我們下載的、或雜湊不對：不用
                }
            } catch (final IOException ignored) {
                // 讀不到：不用
            }
        }
    }

    /** 讀更新說明檔（在呼叫的執行緒上；不要在遊戲主執行緒叫）。 */
    public Manifest fetchManifest(final URI url) throws IOException, InterruptedException {
        if (!httpUrl(url)) {
            throw new IOException("更新說明檔的網址不是 http(s)：" + url);
        }
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15))
                .header("User-Agent", MOD_ID + "/" + currentVersion).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("更新說明檔 HTTP " + response.statusCode());
        }
        return parseManifest(response.body(), url);
    }

    /**
     * 有比現在（以及已經下載好的）新的版本就下載、驗證，成功回傳下載好的新版；不用更新回傳 null。
     * 雜湊對不上丟例外（檔案已經刪掉）。
     */
    public Pending downloadIfNewer(final Manifest manifest) throws IOException, InterruptedException {
        if (Protocol.compareVersions(manifest.version(), currentVersion) <= 0) {
            return null;
        }
        final Pending already = pending;
        if (already != null && Protocol.compareVersions(manifest.version(), already.version()) <= 0) {
            return null;
        }
        if (!supported()) {
            throw new IOException("這個安裝方式不能自動更新（模組不是 mods 資料夾裡的 jar）");
        }
        final Path part = modsDir.resolve(jarName(manifest.version()) + ".part");
        final Path target = modsDir.resolve(pendingName(manifest.version()));
        final HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(manifest.url()).timeout(Duration.ofMinutes(2))
                .header("User-Agent", MOD_ID + "/" + currentVersion).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("下載新版 HTTP " + response.statusCode());
        }
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            final byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_SIZE) {
                    throw new IOException("新版的檔案太大");
                }
                out.write(buffer, 0, read);
            }
        } catch (final IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        final String actual = sha256(part);
        if (!actual.equals(manifest.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException("下載的檔案 SHA-256 跟說明檔對不上，不使用（" + actual + "）");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        if (already != null && !already.file().equals(target)) {
            Files.deleteIfExists(already.file()); // 更舊的那個待更新的不用了
        }
        final Pending result = new Pending(manifest.version(), target, actual);
        setPending(result);
        return result;
    }

    private void setPending(final Pending value) {
        pending = value;
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::applyOnExit, "exilecore_client-update"));
        }
    }

    /** 關遊戲時：換上新版（只換驗證過的那個）。 */
    void applyOnExit() {
        final Pending value = pending;
        if (value == null || currentJar == null) {
            return;
        }
        try {
            if (!Files.isRegularFile(value.file()) || !value.sha256().equals(sha256(value.file()))) {
                return; // 檔案不見了或被改過：不換
            }
            final Path newJar = modsDir.resolve(jarName(value.version()));
            if (windows()) {
                Files.createDirectories(configDir);
                final Path script = configDir.resolve(SCRIPT_NAME);
                Files.writeString(script, windowsScript(currentJar, value.file(), newJar), StandardCharsets.UTF_8);
                new ProcessBuilder("cmd", "/c", "start", "/min", "cmd", "/c", script.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                return;
            }
            try {
                applyInPlace(currentJar, value.file(), newJar);
            } catch (final IOException e) {
                new ProcessBuilder(posixCommand(currentJar, value.file(), newJar))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            }
        } catch (final IOException | RuntimeException ignored) {
            // 換不了：下次開遊戲時 .pending 還在，關遊戲時再試
        }
    }

    /** 直接換檔（不是 Windows 時，開著的 jar 也刪得掉）：刪舊的、.pending 改名。 */
    static void applyInPlace(final Path oldJar, final Path pendingFile, final Path newJar) throws IOException {
        if (!oldJar.equals(newJar)) {
            Files.deleteIfExists(oldJar);
        }
        Files.move(pendingFile, newJar, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 背景下載（回呼在下載的執行緒上）。 */
    public void checkAsync(final URI manifestUrl, final boolean download, final Consumer<String> message, final Consumer<Pending> done) {
        final Thread thread = new Thread(() -> {
            try {
                final Manifest manifest = fetchManifest(manifestUrl);
                if (Protocol.compareVersions(manifest.version(), currentVersion) <= 0) {
                    return;
                }
                if (!download || !supported()) {
                    message.accept("星骸輔助模組有新版本 " + manifest.version() + "（你的是 " + currentVersion + "）"
                            + (download ? "，這個安裝方式不能自動更新，請手動下載" : "，自動更新關著，請手動下載") + "：" + manifest.url());
                    return;
                }
                final Pending result = downloadIfNewer(manifest);
                if (result != null) {
                    done.accept(result);
                }
            } catch (final IOException | RuntimeException e) {
                message.accept("星骸輔助模組檢查更新失敗：" + e.getMessage());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "exilecore_client-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<Path> list(final Path dir) {
        final List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (final Path file : stream) {
                out.add(file);
            }
        } catch (final IOException ignored) {
            // 讀不到資料夾：當成空的
        }
        return out;
    }

    private static boolean sameFile(final Path a, final Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (final IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }
}
