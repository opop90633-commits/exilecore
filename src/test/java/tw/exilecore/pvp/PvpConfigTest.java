package tw.exilecore.pvp;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pvp.yml：出貨的值、夾範圍、沒有檔案時的預設。 */
class PvpConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedConfig() throws IOException {
        final PvpConfig config = new PvpConfig();
        config.load(DATA);
        assertTrue(config.problems().isEmpty(), config.problems().toString());
        assertTrue(config.enabled());
        assertEquals(70, config.reduction(), 1e-9, "專屬的 PvP 減傷 70%");
        assertEquals(0.3, config.multiplier(), 1e-9);
        assertEquals(0, config.minLevel());
        assertEquals(5, config.flaskCharges());
        assertTrue(config.killFeed());
        assertTrue(config.respawnInArena());
        assertTrue(Files.readString(DATA.resolve("index.txt")).contains("data/pvp.yml"), "新資料檔要列在 index.txt 才會同步到伺服器");
    }

    @Test
    void reductionIsClamped() {
        final PvpConfig high = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader("damage_reduction: 120\n")));
        assertEquals(PvpConfig.MAX_REDUCTION, high.reduction(), 1e-9);
        assertEquals(0.05, high.multiplier(), 1e-9, "最少還是吃 5%");
        assertFalse(high.problems().isEmpty());
        final PvpConfig low = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader("damage_reduction: -10\nmin_level: -3\n")));
        assertEquals(0, low.reduction(), 1e-9);
        assertEquals(1, low.multiplier(), 1e-9);
        assertEquals(0, low.minLevel());
        final PvpConfig off = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader("enabled: false\nrespawn_in_arena: false\n")));
        assertFalse(off.enabled());
        assertFalse(off.respawnInArena());
        assertEquals(PvpConfig.DEFAULT_REDUCTION, off.reduction(), 1e-9);
    }

    @Test
    void missingFileUsesDefaults() throws IOException {
        final PvpConfig config = new PvpConfig();
        config.load(Files.createTempDirectory("pvp"));
        assertTrue(config.enabled());
        assertEquals(PvpConfig.DEFAULT_REDUCTION, config.reduction(), 1e-9);
        assertTrue(config.problems().isEmpty());
    }
}
