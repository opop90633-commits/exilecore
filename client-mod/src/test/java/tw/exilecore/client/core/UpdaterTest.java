package tw.exilecore.client.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自動更新：說明檔、下載驗證、換檔、開遊戲時清理。 */
class UpdaterTest {

    @TempDir
    Path game;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String sha(final byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private URI serve(final String manifest, final byte[] jar) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mod/manifest.json", exchange -> {
            final byte[] body = manifest.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/mod/new.jar", exchange -> {
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jar);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mod/manifest.json");
    }

    private Updater updater(final Path mods) throws IOException {
        Files.createDirectories(mods);
        final Path jar = mods.resolve("exilecore_client-1.0.0.jar");
        Files.writeString(jar, "舊的");
        return new Updater(mods, game.resolve("config"), "1.0.0", jar);
    }

    @Test
    void manifestValidation() {
        final String sha = "a".repeat(64);
        final Updater.Manifest m = Updater.parseManifest("{\"version\":\"1.0.1\",\"url\":\"exilecore_client-1.0.1.jar\",\"sha256\":\"" + sha.toUpperCase()
                + "\",\"changelog\":\"修\"}", URI.create("https://a.tw/mod/manifest.json"));
        assertEquals(URI.create("https://a.tw/mod/exilecore_client-1.0.1.jar"), m.url(), "相對網址照說明檔的位置");
        assertEquals(sha, m.sha256());
        assertThrows(IllegalArgumentException.class, () -> Updater.parseManifest(
                "{\"version\":\"1.0.1\",\"url\":\"file:///etc/passwd\",\"sha256\":\"" + sha + "\"}", null), "只准 http(s)");
        assertThrows(IllegalArgumentException.class, () -> Updater.parseManifest(
                "{\"version\":\"../../x\",\"url\":\"https://a.tw/x.jar\",\"sha256\":\"" + sha + "\"}", null), "版本號不能有路徑");
        assertThrows(IllegalArgumentException.class, () -> Updater.parseManifest(
                "{\"version\":\"1.0.1\",\"url\":\"https://a.tw/x.jar\",\"sha256\":\"abc\"}", null));
    }

    @Test
    void downloadsVerifiesAndApplies() throws Exception {
        final byte[] jar = "新版的 jar".getBytes(StandardCharsets.UTF_8);
        final URI url = serve("{\"version\":\"1.0.1\",\"url\":\"new.jar\",\"sha256\":\"" + sha(jar) + "\"}", jar);
        final Path mods = game.resolve("mods");
        final Updater updater = updater(mods);
        assertTrue(updater.supported());
        final Updater.Manifest manifest = updater.fetchManifest(url);
        final Updater.Pending pending = updater.downloadIfNewer(manifest);
        assertEquals(mods.resolve("exilecore_client-1.0.1.jar.pending"), pending.file());
        assertTrue(Files.exists(pending.file()));
        assertFalse(Files.exists(mods.resolve("exilecore_client-1.0.1.jar.part")));
        assertNull(updater.downloadIfNewer(manifest), "已經下載好了");
        Updater.applyInPlace(mods.resolve("exilecore_client-1.0.0.jar"), pending.file(), mods.resolve(Updater.jarName("1.0.1")));
        assertFalse(Files.exists(mods.resolve("exilecore_client-1.0.0.jar")), "舊的要刪掉（Fabric 不准同一個代號兩個 jar）");
        assertEquals("新版的 jar", Files.readString(mods.resolve("exilecore_client-1.0.1.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsWrongHash() throws Exception {
        final byte[] jar = "被改過的".getBytes(StandardCharsets.UTF_8);
        final URI url = serve("{\"version\":\"1.0.1\",\"url\":\"new.jar\",\"sha256\":\"" + "0".repeat(64) + "\"}", jar);
        final Path mods = game.resolve("mods");
        final Updater updater = updater(mods);
        assertThrows(IOException.class, () -> updater.downloadIfNewer(updater.fetchManifest(url)));
        assertNull(updater.pending());
        try (var files = Files.list(mods)) {
            assertEquals(List.of(mods.resolve("exilecore_client-1.0.0.jar")), files.toList(), "雜湊不對的檔案刪掉");
        }
    }

    @Test
    void olderOrSameIsIgnored() throws Exception {
        final Updater updater = updater(game.resolve("mods"));
        assertNull(updater.downloadIfNewer(new Updater.Manifest("1.0.0", URI.create("http://127.0.0.1:1/x.jar"), "0".repeat(64), "")));
        assertNull(updater.downloadIfNewer(new Updater.Manifest("0.9", URI.create("http://127.0.0.1:1/x.jar"), "0".repeat(64), "")));
    }

    @Test
    void notSupportedFromDevFolder() throws IOException {
        final Path mods = Files.createDirectories(game.resolve("mods"));
        assertFalse(new Updater(mods, game, "1.0.0", game.resolve("build/classes")).supported());
        assertFalse(new Updater(mods, game, "1.0.0", null).supported());
    }

    @Test
    void startupCleansLeftovers() throws IOException {
        final Path mods = game.resolve("mods");
        final Updater updater = updater(mods);
        Files.writeString(mods.resolve("exilecore_client-0.9.0.jar.pending"), "舊的");
        Files.writeString(mods.resolve("exilecore_client-1.0.0.jar.pending"), "已經換上了");
        Files.writeString(mods.resolve("exilecore_client-1.0.2.jar.part"), "下載一半");
        final Path keep = mods.resolve("exilecore_client-1.0.1.jar.pending");
        Files.writeString(keep, "上次沒換成功");
        final Path stranger = mods.resolve("exilecore_client-1.0.3.jar.pending");
        Files.writeString(stranger, "不知道哪來的");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config").resolve(Updater.SCRIPT_NAME), "rem");
        updater.startup("1.0.1", Updater.sha256(keep));
        assertTrue(Files.exists(keep), "驗證過的新版留著，關遊戲時再換");
        assertEquals("1.0.1", updater.pending().version());
        assertFalse(Files.exists(stranger), "雜湊對不上的刪掉");
        assertFalse(Files.exists(mods.resolve("exilecore_client-0.9.0.jar.pending")));
        assertFalse(Files.exists(mods.resolve("exilecore_client-1.0.0.jar.pending")));
        assertFalse(Files.exists(mods.resolve("exilecore_client-1.0.2.jar.part")));
        assertFalse(Files.exists(game.resolve("config").resolve(Updater.SCRIPT_NAME)));
        assertTrue(Files.exists(mods.resolve("exilecore_client-1.0.0.jar")), "現在的 jar 不動");
    }

    @Test
    void scripts() {
        final Path old = Path.of("C:/mc/mods/exilecore_client-1.0.0.jar");
        final String bat = Updater.windowsScript(old, Path.of("C:/mc/mods/exilecore_client-1.0.1.jar.pending"),
                Path.of("C:/mc/mods/exilecore_client-1.0.1.jar"));
        assertTrue(bat.contains("del /f /q \"" + old + "\""));
        assertTrue(bat.contains("move /y"));
        assertTrue(bat.contains("ping -n 2 127.0.0.1"), "等遊戲關掉");
        final List<String> sh = Updater.posixCommand(Path.of("/mc/it's/a.jar"), Path.of("/mc/b.pending"), Path.of("/mc/b.jar"));
        assertEquals("sh", sh.get(0));
        assertTrue(sh.get(2).contains("rm -f '/mc/it'\\''s/a.jar'"), sh.get(2));
        assertEquals("1.2.3", Updater.pendingVersion("exilecore_client-1.2.3.jar.pending"));
        assertNull(Updater.pendingVersion("other-1.2.3.jar.pending"));
    }
}
