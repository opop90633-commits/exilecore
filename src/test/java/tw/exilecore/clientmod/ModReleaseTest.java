package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** plugins/ExileCore/clientmod/ 的模組檔：挑最新的、算雜湊、更新說明檔。 */
class ModReleaseTest {

    @TempDir
    Path folder;

    private Path jar(final String name, final String id, final String version) throws IOException {
        final Path file = folder.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void picksNewestOfOurMod() throws IOException {
        assertNull(ModRelease.latest(folder), "空資料夾");
        assertNull(ModRelease.latest(folder.resolve("missing")), "沒有資料夾");
        jar("a.jar", "exilecore_client", "1.0.9");
        final Path newest = jar("b.jar", "exilecore_client", "1.0.10");
        jar("c.jar", "someone_else", "9.9.9");
        Files.writeString(folder.resolve("readme.txt"), "x");
        final ModRelease release = ModRelease.latest(folder);
        assertEquals("1.0.10", release.version());
        assertEquals(newest, release.file());
        assertEquals(ModRelease.sha256(newest), release.sha256());
        assertEquals(64, release.sha256().length());
        assertEquals(ModRelease.sha256Of(Files.readAllBytes(newest)), release.sha256());
        assertEquals(Files.size(newest), release.size());
        assertEquals(release, ModRelease.latest(folder), "沒換檔案時一樣（不會一直重送）");
    }

    @Test
    void sha256KnownValue() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ModRelease.sha256Of("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void nameFallback() throws IOException {
        assertEquals("1.2.3", ModRelease.versionFromName("exilecore_client-1.2.3.jar"));
        assertNull(ModRelease.versionFromName("exilecore_client-.jar"));
        assertNull(ModRelease.versionFromName("other-1.2.3.jar"));
        Files.writeString(folder.resolve("exilecore_client-2.0.0.jar"), "不是 zip");
        assertEquals("2.0.0", ModRelease.latest(folder).version(), "壞掉的 jar 照檔名");
    }

    @Test
    void manifest() throws IOException {
        jar("exilecore_client 1.0.0.jar", "exilecore_client", "1.0.0");
        final ModRelease release = ModRelease.latest(folder);
        final String json = release.manifestJson("https://play.example.tw", "修正");
        assertTrue(json.contains("\"version\":\"1.0.0\""), json);
        assertTrue(json.contains("\"url\":\"https://play.example.tw/mod/exilecore_client%201.0.0.jar\""), json);
        assertTrue(json.contains("\"sha256\":\"" + release.sha256() + "\""), json);
        assertTrue(json.contains("\"changelog\":\"修正\""), json);
    }
}
