package tw.exilecore.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長「稀有度的部分做成 maps.yml 裡的開關」：flat_boss_loot 讀得起來，出貨的全部不吃。 */
class FlatBossLootTest {

    @Test
    void shippedFileTurnsEveryKindFlat() throws IOException {
        final MapConfig config = new MapConfig();
        config.load(Path.of("src/main/resources/data"));
        for (final String kind : MapConfig.FLAT_LOOT_KINDS) {
            assertTrue(config.flatBossLoot(kind), kind);
        }
        assertFalse(config.flatBossLoot("map_boss"), "不認得的種類照吃");
    }
}
