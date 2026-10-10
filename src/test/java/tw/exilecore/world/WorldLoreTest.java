package tw.exilecore.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/world.yml：伺服器名稱、MOTD、登場旁白。 */
class WorldLoreTest {

    @Test
    void shippedWorldLoads() throws IOException {
        final WorldLore lore = new WorldLore();
        lore.load(Path.of("src/main/resources/data"));
        assertEquals("星骸", lore.name());
        assertEquals(2, lore.motd().size(), "伺服器列表只有兩行");
        assertTrue(lore.motd().get(0).contains("星骸"));
        assertFalse(lore.arrivalTitle().isBlank());
        assertTrue(lore.arrivalLines().size() >= 3);
    }

    @Test
    void missingFileKeepsDefaults(@TempDir final Path dir) throws IOException {
        final WorldLore lore = new WorldLore();
        lore.load(dir);
        assertEquals("星骸", lore.name());
        assertTrue(lore.motd().isEmpty(), "沒有 world.yml 就不改原版的 motd");
        assertTrue(lore.arrivalLines().isEmpty());
    }
}
