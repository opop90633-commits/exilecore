package tw.exilecore.monster;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 2026-10-19：玩家自己的物品稀有度遞減（drops.yml 的 rarity_curve）。 */
class RarityCurveTest {

    @Test
    void shippedCurveDiminishesAbove75() throws IOException {
        final DropTable drops = new DropTable();
        drops.load(Path.of("src/main/resources/data"));
        assertEquals(0.5, drops.diminishRarity(0.5), 1e-9, "75% 以內照算");
        assertEquals(0.75, drops.diminishRarity(0.75), 1e-9);
        assertEquals(0.875, drops.diminishRarity(1.0), 1e-9, "100% → 87.5%");
        assertEquals(1.125, drops.diminishRarity(1.5), 1e-9, "150% → 112.5%");
        assertEquals(1.3125, drops.diminishRarity(2.0), 1e-9, "200% → 131.25%");
        assertEquals(1.5375, drops.diminishRarity(2.9), 1e-9, "290% → 153.75%");
        assertEquals(-0.3, drops.diminishRarity(-0.3), 1e-9, "負的不變");
    }

    @Test
    void emptyCurveMeansNoDiminishing() {
        assertEquals(5.0, DropTable.diminish(5.0, List.of()), 1e-9);
        assertEquals(DropTable.DEFAULT_RARITY_CURVE, new DropTable().rarityCurve(), "沒寫 = 預設");
    }

    @Test
    void parsingChecksOrderAndRate() {
        assertThrows(IllegalArgumentException.class, () -> DropTable.parseCurve(
                tw.exilecore.core.config.YamlNode.load("drops.yml", new StringReader("c: [{ from: 200, rate: 0.5 }, { from: 100, rate: 0.5 }]")).list("c")));
        assertThrows(IllegalArgumentException.class, () -> DropTable.parseCurve(
                tw.exilecore.core.config.YamlNode.load("drops.yml", new StringReader("c: [{ from: 100, rate: 1.5 }]")).list("c")));
    }
}
