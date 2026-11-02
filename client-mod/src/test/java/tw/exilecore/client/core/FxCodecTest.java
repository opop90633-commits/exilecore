package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 特效封包的解碼：用插件（FxProtocol.Writer）實際產生的位元組，兩邊格式要一樣。 */
class FxCodecTest {

    /**
     * 插件產生的一包：BURST（DUST 橘色、offset、extra 0.05）、重要的 POINT（flame、extra 1）、重要的 AREA START（編號 42、120 tick）、
     * LINE UPDATE（方塊粒子、不知道多久）、很遠的 RING（entity_effect 半透明）、STOP（編號 300）。
     */
    static final String GOLDEN = "01c8018001910305046475737405666c616d6505626c6f636b0f6f616b5f6c6f675b617869733d795d0d656e746974795f6566666563"
            + "7404000100ff6e143fc000000100020303040580112233060103000040002000200c3f0000003e8000003f0000003d4ccccd200401008000800000012b2a0b01000000"
            + "0000800120218078000000003f000000000000003c23d70a14070002000000000080050000000300003000ff420003460b120040c00000460fc60000c0161aac02";

    private static final double NEAR = 1 / 128.0;

    @Test
    void decodesWhatThePluginWrites() {
        final List<FxCodec.Effect> effects = FxCodec.decode(HexFormat.of().parseHex(GOLDEN));
        assertEquals(6, effects.size());

        final FxCodec.Effect burst = effects.get(0);
        assertEquals(FxCodec.SHAPE_BURST, burst.shape());
        assertEquals(FxCodec.PHASE_ONCE, burst.phase());
        assertFalse(burst.important());
        assertEquals("dust", burst.style().particle());
        assertEquals(FxCodec.DATA_DUST, burst.style().kind());
        assertEquals(0xff6e14, burst.style().a());
        assertEquals(1.5f, burst.style().f());
        assertEquals(100.5, burst.x(), NEAR);
        assertEquals(64.25, burst.y(), NEAR);
        assertEquals(-200.75, burst.z(), NEAR);
        assertEquals(12, burst.count());
        assertEquals(0.5, burst.dx(), 1e-6);
        assertEquals(0.25, burst.dy(), 1e-6);
        assertEquals(0.05, burst.extra(), 1e-6);

        final FxCodec.Effect point = effects.get(1);
        assertEquals(FxCodec.SHAPE_POINT, point.shape());
        assertTrue(point.important());
        assertEquals("flame", point.style().particle());
        assertEquals(1.0, point.extra());
        assertEquals(0, point.dx());

        final FxCodec.Effect area = effects.get(2);
        assertEquals(FxCodec.SHAPE_AREA, area.shape());
        assertEquals(FxCodec.PHASE_START, area.phase());
        assertEquals(42, area.id());
        assertTrue(area.important());
        assertEquals(4.5, area.radius(), 1 / 64.0);
        assertEquals(33, area.count());
        assertEquals(0.5, area.density(), 0.01);
        assertEquals(120, area.duration());
        assertEquals(0.5, area.dy(), 1e-6, "AREA 裡面的點多高");
        assertTrue(area.persistent());

        final FxCodec.Effect line = effects.get(3);
        assertEquals(FxCodec.SHAPE_LINE, line.shape());
        assertEquals(FxCodec.PHASE_UPDATE, line.phase());
        assertEquals(7, line.id());
        assertEquals(-1, line.duration());
        assertEquals(110, line.ex(), NEAR);
        assertEquals(-195, line.ez(), NEAR);
        assertEquals(0.75, line.radius(), 1 / 64.0);
        assertEquals(FxCodec.DATA_BLOCK, line.style().kind());
        assertEquals("oak_log[axis=y]", line.style().text());

        final FxCodec.Effect far = effects.get(4);
        assertEquals(9000.5, far.x(), 1e-3);
        assertEquals(9000.5, far.z(), 1e-3);
        assertEquals(22, far.count());
        assertEquals(FxCodec.DATA_COLOR, far.style().kind());
        assertEquals(0x80112233, far.style().a());

        final FxCodec.Effect stop = effects.get(5);
        assertEquals(FxCodec.PHASE_STOP, stop.phase());
        assertEquals(300, stop.id());
        assertNull(stop.style());
    }

    @Test
    void rejectsBrokenPackets() {
        final byte[] full = HexFormat.of().parseHex(GOLDEN);
        for (int cut = 0; cut < full.length; cut++) {
            assertNull(FxCodec.decode(java.util.Arrays.copyOf(full, cut)), "少了位元組（" + cut + "）整包不要");
        }
        final byte[] wrongFormat = full.clone();
        wrongFormat[0] = 2;
        assertNull(FxCodec.decode(wrongFormat));
        assertNull(FxCodec.decode(null));
        assertNull(FxCodec.decode(new byte[] {1, 0, 0, 0, 0, 0, 1, 0}), "特效指到不存在的樣式");
        assertNull(FxCodec.decode(new byte[] {1, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0x03}), "字串數太多");
        // 空的一包（只有 STOP）也可以
        assertEquals(1, FxCodec.decode(new byte[] {1, 0, 0, 0, 0, 0, 1, 0x1a, 5}).size());
    }
}
