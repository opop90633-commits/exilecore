package tw.exilecore.clientmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 2026-11-02：管理員放在 plugins/ExileCore/clientmod/ 的模組檔（星骸輔助模組的 jar）。純 Java（測試用）。
 * <p>
 * 版本照 jar 裡 fabric.mod.json 的 version（讀不到就從檔名 exilecore_client-版本.jar 猜）；資料夾裡有好幾個 jar 時用最新的那個，
 * 雜湊（SHA-256）照檔案內容算，換了檔案就跟著換。管理員只要把新的 jar 丟進資料夾，不用改設定。
 *
 * @param file    jar 檔
 * @param version 版本
 * @param sha256  檔案的 SHA-256（小寫十六進位）
 * @param size    檔案大小（位元組）
 * @param modified 檔案的修改時間（毫秒；換檔案時重算雜湊用）
 */
public record ModRelease(Path file, String version, String sha256, long size, long modified) {

    /** 模組代號（fabric.mod.json 的 id；不是這個的 jar 不理）。 */
    public static final String MOD_ID = "exilecore_client";
    /** 模組檔最大幾 MB（太大的不給下載）。 */
    static final long MAX_SIZE = 32L * 1024 * 1024;

    public String fileName() {
        return file.getFileName().toString();
    }

    /** 這個資料夾裡最新的模組檔；資料夾不存在、沒有模組檔回傳 null。 */
    public static ModRelease latest(final Path folder) throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            return null;
        }
        ModRelease best = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.jar")) {
            for (final Path jar : stream) {
                if (!Files.isRegularFile(jar) || Files.size(jar) > MAX_SIZE) {
                    continue;
                }
                final String version = version(jar);
                if (version == null) {
                    continue;
                }
                if (best == null || ClientModProtocol.compareVersions(version, best.version()) > 0) {
                    best = new ModRelease(jar, version, null, Files.size(jar), Files.getLastModifiedTime(jar).toMillis());
                }
            }
        }
        return best == null ? null : new ModRelease(best.file(), best.version(), sha256(best.file()), best.size(), best.modified());
    }

    /** jar 的版本：fabric.mod.json 的 version（id 要是 {@value #MOD_ID}）；讀不到 fabric.mod.json 時照檔名；都不是回傳 null。 */
    static String version(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    final JsonObject root = JsonParser.parseString(new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!root.has("id") || !MOD_ID.equals(root.get("id").getAsString()) || !root.has("version")) {
                        return null;
                    }
                    return root.get("version").getAsString();
                }
            }
        } catch (final IOException | RuntimeException e) {
            // 不是 jar 或 fabric.mod.json 壞掉：照檔名
        }
        return versionFromName(jar.getFileName().toString());
    }

    /** 純函式：exilecore_client-1.2.3.jar → 1.2.3；不是這種檔名回傳 null。 */
    static String versionFromName(final String name) {
        final String prefix = MOD_ID + "-";
        if (!name.startsWith(prefix) || !name.endsWith(".jar")) {
            return null;
        }
        final String version = name.substring(prefix.length(), name.length() - 4);
        return version.isEmpty() || !Character.isDigit(version.charAt(0)) ? null : version;
    }

    /** 檔案的 SHA-256（小寫十六進位）。 */
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

    /** 一段位元組的 SHA-256（小寫十六進位）。 */
    public static String sha256Of(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 更新說明檔（manifest.json）：{@code {"version","url","sha256","size","changelog"}}。
     *
     * @param baseUrl   玩家連得到的網址（沒有結尾的 /）
     * @param changelog 更新說明（沒有是空字串）
     */
    public String manifestJson(final String baseUrl, final String changelog) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("url", baseUrl + "/mod/" + java.net.URLEncoder.encode(fileName(), StandardCharsets.UTF_8).replace("+", "%20"));
        root.addProperty("sha256", sha256);
        root.addProperty("size", size);
        root.addProperty("changelog", changelog == null ? "" : changelog);
        return root.toString();
    }
}
