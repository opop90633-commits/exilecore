package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 範圍的顏色、閃爍、撒幾顆。 */
class FxLookTest {

    private static FxCodec.Style style(final String particle, final int kind, final int a) {
        return new FxCodec.Style(particle, kind, a, 0, 1, 0, null, 0, 0, 0);
    }

    @Test
    void colours() {
        assertEquals(0x123456, FxLook.baseColor(style("dust", FxCodec.DATA_DUST, 0x123456)));
        assertEquals(0x112233, FxLook.baseColor(style("entity_effect", FxCodec.DATA_COLOR, 0x80112233)), "不要 alpha");
        assertEquals(0xFF8A2A, FxLook.baseColor(style("flame", FxCodec.DATA_NONE, 0)));
        assertEquals(0xFF8A2A, FxLook.baseColor(style("minecraft:flame", FxCodec.DATA_NONE, 0)));
        assertEquals(0x4FD8E6, FxLook.baseColor(style("soul_fire_flame", FxCodec.DATA_NONE, 0)));
        assertEquals(0xE0E0E0, FxLook.baseColor(style("something_new", FxCodec.DATA_NONE, 0)));
        assertEquals(0xE0E0E0, FxLook.baseColor(null));
        // 重要的往紅色偏
        final int blue = 0x0000FF;
        final int tinted = FxLook.tint(blue, true);
        assertTrue((tinted >> 16 & 0xff) > 100, "紅色多了");
        assertEquals(blue, FxLook.tint(blue, false));
    }

    @Test
    void importantPulses() {
        final FxCodec.Style dust = style("dust", FxCodec.DATA_DUST, 0x00FF00);
        assertEquals(FxLook.edgeColor(dust, false, 0), FxLook.edgeColor(dust, false, 7), "一般的不閃");
        assertNotEquals(FxLook.edgeColor(dust, true, 5), FxLook.edgeColor(dust, true, 15), "重要的閃");
        assertTrue(FxLook.edgeWidth(true, 5) > FxLook.edgeWidth(false, 5));
        assertTrue(FxLook.edgeWidth(true, 5) > FxLook.edgeWidth(true, 15), "閃的時候變粗");
        final int fill = FxLook.fillColor(dust, false, 0);
        assertTrue((fill >>> 24) < 80, "裡面半透明");
        assertTrue(FxLook.pulse(5) > 0.99 && FxLook.pulse(15) < 0.01);
    }

    @Test
    void ambientRespectsParticleSetting() {
        final FxCodec.Effect area = FxZonesTest.zone(1, FxCodec.PHASE_START, 4, -1, false);
        final int all = FxLook.ambientCount(area, FxLook.PARTICLES_ALL);
        final int decreased = FxLook.ambientCount(area, FxLook.PARTICLES_DECREASED);
        assertTrue(all > decreased && decreased >= 1);
        assertEquals(0, FxLook.ambientCount(area, FxLook.PARTICLES_MINIMAL), "最少：不撒（地上的圈照畫）");
        assertTrue(all <= 30, "比伺服器的少：" + all);
        final FxCodec.Effect sparse = new FxCodec.Effect(FxCodec.SHAPE_AREA, FxCodec.PHASE_START, 2, false, area.style(), 0, 64, 0, 0, 64, 0, 4,
                20, 0, -1, 0, 0, 0, 0);
        assertEquals(0, FxLook.ambientCount(sparse, FxLook.PARTICLES_ALL), "密度 0");
    }

    @Test
    void segments() {
        assertEquals(16, FxLook.ringSegments(0.5));
        assertEquals(128, FxLook.ringSegments(50));
        assertEquals(1, FxLook.discRings(0.3));
        assertEquals(8, FxLook.discRings(30));
        assertEquals(20, FxLook.lineSegments(10));
        assertTrue(FxLook.drawsGround(FxCodec.SHAPE_RING));
        assertTrue(FxLook.drawsGround(FxCodec.SHAPE_LINE));
        assertFalse(FxLook.drawsGround(FxCodec.SHAPE_BEAM));
        assertFalse(FxLook.drawsGround(FxCodec.SHAPE_POINT));
    }
}
