package tw.exilecore.web;

import org.junit.jupiter.api.Test;
import tw.exilecore.map.MapConfig;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobHatsTest {

    @Test
    void everyThemeHasAHat() {
        for (final String theme : MapConfig.THEMES) {
            assertNotNull(MobHats.model(theme, false), theme);
            assertTrue(MobHats.NAMES.containsKey(MobHats.THEME_STYLES.get(theme)), theme);
        }
        assertNull(MobHats.model(null, true));
        assertEquals("exilecore:hat/ember_elite", MobHats.model("inferno", true));
    }

    @Test
    void modelsAreValidVanillaGeometry() {
        final Set<Double> angles = Set.of(-45.0, -22.5, 0.0, 22.5, 45.0);
        for (final String style : MobHats.styles()) {
            for (final boolean elite : new boolean[] {false, true}) {
                final MobHats.Shape shape = MobHats.shape(style, elite);
                assertTrue(shape.boxes.size() >= 8, style);
                for (final double[] b : shape.boxes) {
                    for (int i = 0; i < 6; i++) {
                        assertTrue(b[i] >= -16 && b[i] <= 32, style + " 超出模型範圍 " + b[i]);
                    }
                    assertTrue(b[0] <= b[3] && b[1] <= b[4] && b[2] <= b[5], style + " from > to");
                    assertTrue(angles.contains(b[8]), style + " 角度 " + b[8]);
                    assertTrue(b[6] < MobHats.palette(style).length, style + " 顏色");
                }
                assertNotNull(MobHats.modelJson(style, elite).get("elements"));
            }
            assertTrue(MobHats.shape(style, true).boxes.size() > MobHats.shape(style, false).boxes.size(), style + " 加大版要比較多");
        }
        assertEquals(MobHats.styles().size() * 5, MobHats.files().size());
    }
}
