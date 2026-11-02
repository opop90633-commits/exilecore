package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 特效封包（exilecore:fx）：寫了讀回來一樣、樣式只寫一次、遠的座標、大小上限、壞掉的封包。 */
class FxProtocolTest {

    private static final double NEAR = 1 / FxProtocol.NEAR_SCALE;

    @Test
    void roundTripAllShapesAndPhases() {
        final FxProtocol.Writer writer = new FxProtocol.Writer();
        final FxProtocol.Style dust = FxProtocol.Style.dust("dust", 0xff6e14, 1.35f);
        final FxProtocol.Style flame = FxProtocol.Style.none("flame");
        final FxProtocol.Entry point = new FxProtocol.Entry(FxProtocol.SHAPE_POINT, FxProtocol.PHASE_ONCE, 0, false, 1000.25, 64.5, -2000.75,
                1000.25, 64.5, -2000.75, 0, 1, 1, 0, 0, 0, 0, 1.0);
        final FxProtocol.Entry burst = new FxProtocol.Entry(FxProtocol.SHAPE_BURST, FxProtocol.PHASE_ONCE, 0, true, 1003, 65, -2001, 1003, 65,
                -2001, 0.5, 12, 1, 0, 0.5, 0.2, 0.5, 0.05);
        final FxProtocol.Entry ring = new FxProtocol.Entry(FxProtocol.SHAPE_RING, FxProtocol.PHASE_START, 77, true, 1001, 64, -2000, 1001, 64,
                -2000, 4.5, 33, 0.5, 120, 0, 0, 0, 0);
        final FxProtocol.Entry line = new FxProtocol.Entry(FxProtocol.SHAPE_LINE, FxProtocol.PHASE_UPDATE, 300, false, 1000, 64, -2000, 1010,
                64, -1995, 0.75, 0, 1, -1, 0, 0, 0, 0);
        final FxProtocol.Entry stop = FxProtocol.Entry.stop(FxProtocol.SHAPE_AREA, 1L << 40, false);
        assertTrue(writer.add(point, dust, 0));
        assertTrue(writer.add(burst, flame, 0));
        assertTrue(writer.add(ring, dust, 0));
        assertTrue(writer.add(line, flame, 0));
        assertTrue(writer.add(stop, null, 0));
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(writer.toBytes());
        assertEquals(5, decoded.size());

        assertSame(point, decoded.get(0).entry());
        assertEquals(dust.particle(), decoded.get(0).style().particle());
        assertEquals(0xff6e14, decoded.get(0).style().a());
        assertEquals(1.35f, decoded.get(0).style().f());
        assertSame(burst, decoded.get(1).entry());
        assertEquals(flame, decoded.get(1).style());
        assertSame(ring, decoded.get(2).entry());
        assertEquals(120, decoded.get(2).entry().duration());
        assertEquals(0.5, decoded.get(2).entry().density(), 0.01);
        assertSame(line, decoded.get(3).entry());
        assertEquals(-1, decoded.get(3).entry().duration(), "不知道多久");
        assertEquals(FxProtocol.PHASE_STOP, decoded.get(4).entry().phase());
        assertEquals(1L << 40, decoded.get(4).entry().id());
        assertNull(decoded.get(4).style());
    }

    private static void assertSame(final FxProtocol.Entry expected, final FxProtocol.Entry actual) {
        assertEquals(expected.shape(), actual.shape());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.important(), actual.important());
        assertEquals(expected.x(), actual.x(), NEAR);
        assertEquals(expected.y(), actual.y(), NEAR);
        assertEquals(expected.z(), actual.z(), NEAR);
        if (FxProtocol.hasEnd(expected.shape())) {
            assertEquals(expected.ex(), actual.ex(), NEAR);
            assertEquals(expected.ey(), actual.ey(), NEAR);
            assertEquals(expected.ez(), actual.ez(), NEAR);
        }
        if (FxProtocol.hasRadius(expected.shape())) {
            assertEquals(expected.radius(), actual.radius(), 1 / FxProtocol.RADIUS_SCALE);
        }
        assertEquals(expected.count(), actual.count());
        assertEquals(expected.dx(), actual.dx(), 1e-6);
        assertEquals(expected.dy(), actual.dy(), 1e-6);
        assertEquals(expected.dz(), actual.dz(), 1e-6);
        assertEquals(expected.extra(), actual.extra(), 1e-6);
    }

    @Test
    void stylesAndStringsWrittenOnce() {
        final FxProtocol.Writer writer = new FxProtocol.Writer();
        final FxProtocol.Style dust = FxProtocol.Style.dust("dust", 0x112233, 1f);
        int before = 0;
        int firstCost = 0;
        int laterCost = 0;
        for (int i = 0; i < 100; i++) {
            assertTrue(writer.add(new FxProtocol.Entry(0, 0, 0, false, i * 0.1, 64, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0), dust, 0));
            final int now = writer.toBytes().length;
            if (i == 0) {
                firstCost = now;
            } else if (i == 1) {
                laterCost = now - before;
            }
            before = now;
        }
        assertTrue(laterCost <= 10, "一顆點（同樣式、近的座標）≤ 10 位元組，實際 " + laterCost);
        assertTrue(firstCost < 40);
        assertTrue(before < 1100, "100 顆點約 1 KB，實際 " + before);
        // 每種資料都讀得回來
        final FxProtocol.Style[] all = {FxProtocol.Style.none("flame"), FxProtocol.Style.transition("dust_color_transition", 1, 2, 0.5f),
                FxProtocol.Style.block("block", "oak_log[axis=y]"), FxProtocol.Style.item("item", "diamond"),
                FxProtocol.Style.color("entity_effect", 0x80ff0000), FxProtocol.Style.spell("effect", 0x00ff00, 2f),
                FxProtocol.Style.floating("sculk_charge", 0.5f), FxProtocol.Style.integer("shriek", -3),
                FxProtocol.Style.trail("trail", 5.5, 70, -3, 0xabcdef, 40)};
        final FxProtocol.Writer each = new FxProtocol.Writer();
        for (final FxProtocol.Style style : all) {
            assertTrue(each.add(new FxProtocol.Entry(0, 0, 0, false, 5, 64, -3, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0), style, 0));
        }
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(each.toBytes());
        for (int i = 0; i < all.length; i++) {
            final FxProtocol.Style want = all[i];
            final FxProtocol.Style got = decoded.get(i).style();
            assertEquals(want.particle(), got.particle());
            assertEquals(want.kind(), got.kind());
            assertEquals(want.a(), got.a());
            assertEquals(want.b(), got.b());
            assertEquals(want.f(), got.f());
            assertEquals(want.n(), got.n());
            assertEquals(want.text(), got.text());
            assertEquals(want.tx(), got.tx(), 1e-4);
            assertEquals(want.ty(), got.ty(), 1e-4);
            assertEquals(want.tz(), got.tz(), 1e-4);
        }
    }

    @Test
    void farCoordinatesUseFloats() {
        final FxProtocol.Writer writer = new FxProtocol.Writer();
        final FxProtocol.Style style = FxProtocol.Style.none("crit");
        writer.add(new FxProtocol.Entry(0, 0, 0, false, 0.5, 64, 0.5, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0), style, 0);
        writer.add(new FxProtocol.Entry(0, 0, 0, false, 5000.5, 64, -9000.25, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0), style, 0);
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(writer.toBytes());
        assertEquals(5000.5, decoded.get(1).entry().x(), 1e-3);
        assertEquals(-9000.25, decoded.get(1).entry().z(), 1e-3);
    }

    @Test
    void limitRefusesAndKeepsBatchValid() {
        final FxProtocol.Writer writer = new FxProtocol.Writer();
        final FxProtocol.Style style = FxProtocol.Style.none("crit");
        int added = 0;
        for (int i = 0; i < 1000; i++) {
            if (writer.add(new FxProtocol.Entry(0, 0, 0, false, i * 0.01, 64, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0), style, 512)) {
                added++;
            }
        }
        assertTrue(added > 10 && added < 1000);
        assertTrue(writer.toBytes().length <= 512);
        assertEquals(added, FxProtocol.decode(writer.toBytes()).size());
    }

    @Test
    void rejectsGarbage() {
        assertNull(FxProtocol.decode(new byte[0]));
        assertNull(FxProtocol.decode(new byte[] {2, 0, 0, 0, 0, 0, 0}), "格式版本不對");
        assertNull(FxProtocol.decode(new byte[] {1, 0, 0, 0, 0, 0, 1, 0}), "特效指到不存在的樣式");
        final FxProtocol.Writer writer = new FxProtocol.Writer();
        writer.add(new FxProtocol.Entry(2, 1, 5, false, 0, 64, 0, 0, 0, 0, 3, 20, 1, 40, 0, 0, 0, 0), FxProtocol.Style.none("flame"), 0);
        final byte[] full = writer.toBytes();
        assertFalse(FxProtocol.decode(full).isEmpty());
        assertNull(FxProtocol.decode(java.util.Arrays.copyOf(full, full.length - 1)), "少一個位元組");
    }

    @Test
    void shortIds() {
        assertEquals("dust", FxProtocol.shortId("minecraft:dust"));
        assertEquals("other:thing", FxProtocol.shortId("other:thing"));
        assertEquals("", FxProtocol.shortId(null));
    }
}
