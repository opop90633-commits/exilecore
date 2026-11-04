package tw.exilecore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長「軍團」：軍團的設定讀得起來、數字合理。 */
class LegionConfigTest {

    @Test
    void shippedSettings() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(Path.of("src/main/resources/data"));
        final MapEventConfig.LegionSettings legion = config.legion();
        assertEquals(0.2, legion.chance(), 1e-9);
        assertEquals(25, legion.freezeSeconds(), 1e-9);
        assertEquals(24, legion.soldiers());
        assertTrue(legion.rewardCarriers() + legion.sergeants() + legion.generals() <= legion.soldiers(), "帶獎勵的不能比兵多");
        assertEquals(2, legion.generals(), "一邊一隻將領");
        assertEquals(3, legion.generalShares());
        assertTrue(legion.rewardWeights().containsKey(MapEventConfig.Reward.CURRENCY));
        assertEquals(5, legion.domainCost());
        assertEquals(1.0, legion.generalEmblem(), 1e-9);
    }
}
