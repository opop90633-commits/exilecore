package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 設定檔：預設值、存讀、寫壞了。 */
class ModConfigTest {

    @TempDir
    Path dir;

    @Test
    void roundTripAndDefaults() throws IOException {
        final Path file = dir.resolve("exilecore_client.json");
        final ModConfig fresh = ModConfig.load(file);
        assertTrue(fresh.hudEnabled);
        assertTrue(fresh.orbs, "2026-11-02：預設畫成球");
        assertEquals("", fresh.effectiveManifestUrl());
        assertNull(fresh.pendingVersion());
        fresh.showTarget = false;
        fresh.orbs = false;
        fresh.lastManifestUrl = "http://a.tw/mod/manifest.json";
        fresh.setPending("1.0.1", "ab");
        fresh.save(file);
        final ModConfig loaded = ModConfig.load(file);
        assertFalse(loaded.showTarget);
        assertFalse(loaded.orbs, "切回原本的生命條會記住");
        assertEquals("http://a.tw/mod/manifest.json", loaded.effectiveManifestUrl());
        assertEquals("1.0.1", loaded.pendingVersion());
        loaded.manifestUrl = " https://mine.tw/m.json ";
        assertEquals("https://mine.tw/m.json", loaded.effectiveManifestUrl(), "自己指定的優先");
    }

    @Test
    void brokenFile() throws IOException {
        final Path file = dir.resolve("exilecore_client.json");
        Files.writeString(file, "{壞掉");
        assertTrue(ModConfig.load(file).hudEnabled);
        assertTrue(Files.exists(dir.resolve("exilecore_client.json.broken")));
        Files.writeString(file, "{\"scale\":99}");
        assertEquals(1f, ModConfig.load(file).scale, "不合理的縮放");
    }
}
