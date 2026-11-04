package tw.exilecore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;

/** 2026-11-04 照站長「波次引擎＋15 層迷霧」：迷霧回廊的設定讀得起來、每一波的數字照預期往上。 */
class FogTrialConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedSettings() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.FogTrialSettings fog = config.fogTrial();
        assertEquals(15, fog.waves());
        assertEquals(50, fog.cost());
        assertEquals(4, fog.splinterDrop()[0]);
        assertEquals(8, fog.splinterDrop()[1]);
        assertEquals(0, fog.difficulty(1), 1e-9, "第一波不加");
        assertEquals(2.0, fog.difficulty(15), 1e-9, "最後一波 +200%");
        assertEquals(18, fog.count(1, 1));
        assertEquals(18 + 4 * 13, fog.count(14, 1));
        assertEquals(Math.round(18 * 1.5), fog.count(1, 2), "兩個人 ×1.5");
        assertEquals(0, fog.rareChance(3), 1e-9, "第 4 波前沒有稀有怪");
        assertEquals(0.03, fog.rareChance(4), 1e-9);
        assertEquals(0.15, fog.rareChance(15), 1e-9);
        assertTrue(fog.eliteWaves().contains(5) && fog.eliteWaves().contains(10));
        assertTrue(fog.bossWave(15));
        assertFalse(fog.bossWave(14));
        assertEquals(1, fog.rewards(1));
        assertEquals(3, fog.rewards(15));
        assertFalse(fog.rewardWeights().containsKey(MapEventConfig.Reward.UNIQUE), "專屬獎勵之後再加：先不給傳奇");
        assertTrue(fog.groupMin() <= fog.groupMax());
    }

    @Test
    void splinterExists() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertNotNull(currencies.get(config.fogTrial().splinter()), "currencies.yml 有迷霧碎片");
    }
}
