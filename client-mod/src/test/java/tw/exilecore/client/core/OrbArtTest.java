package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02：生命球、魔力球：液面高度、能量護盾外環、平滑、位置。 */
class OrbArtTest {

    @Test
    void surfaceHeight() {
        final double c = OrbArt.BOX / 2.0;
        assertEquals(c + OrbArt.LIQUID, OrbArt.surface(0, 10, 1.3), 1e-9, "空：球底，沒有波紋");
        assertEquals(c - OrbArt.LIQUID, OrbArt.surface(1, 10, 1.3), 1e-9, "滿：球頂");
        assertEquals(c, OrbArt.surface(0.5, 10, 0) - waveAt(0.5, 10, 0), 1e-9, "一半：球心（扣掉波紋）");
        assertTrue(OrbArt.surface(0.3, 19, 0) > OrbArt.surface(0.6, 19, 0), "越多液面越高（y 越小）");
        // 波紋會動
        assertTrue(Math.abs(OrbArt.surface(0.5, 19, 0) - OrbArt.surface(0.5, 19, 1.5)) > 0.05);
    }

    private static double waveAt(final double level, final double x, final double phase) {
        return OrbArt.surface(level, x, phase) - (OrbArt.BOX / 2.0 + OrbArt.LIQUID - 2 * OrbArt.LIQUID * level);
    }

    @Test
    void ringFraction() {
        assertFalse(OrbArt.ringFilled(0, 17, 0));
        assertTrue(OrbArt.ringFilled(0, 17, 0.05), "從正下方開始長");
        assertTrue(OrbArt.ringFilled(17, 0, 0.5), "一半到左右兩邊");
        assertTrue(OrbArt.ringFilled(-17, 0, 0.5));
        assertFalse(OrbArt.ringFilled(0, -17, 0.9), "正上方要滿才有");
        assertTrue(OrbArt.ringFilled(0, -17, 1));
    }

    @Test
    void smoothApproach() {
        double v = 0;
        for (int i = 0; i < 60; i++) {
            final double next = OrbArt.approach(v, 1, 1 / 60.0, 9);
            assertTrue(next >= v && next <= 1, "一直往目標靠、不會衝過頭");
            v = next;
        }
        assertTrue(v > 0.99, "一秒內差不多到了：" + v);
        assertEquals(0.3, OrbArt.approach(0.3, 0.8, 0, 9), 1e-9, "沒過時間不動");
    }

    @Test
    void segmentsAndPixels() {
        assertEquals(0, OrbArt.segment(0, 20));
        assertEquals(1, OrbArt.segment(0.0001, 20));
        assertEquals(19, OrbArt.segment(0.9999, 20));
        assertEquals(20, OrbArt.segment(1, 20));
        final int size = 76;
        final int[] orb = OrbArt.compose(size, OrbArt.Liquid.LIFE, 0.5, 0.8, 0, 0.1, 0, 0);
        assertEquals(size * size, orb.length);
        assertEquals(0, orb[0] >>> 24, "角落透明");
        final int bottom = (size * 3 / 4) * size + size / 2;
        assertTrue((orb[bottom] >> 16 & 0xff) > (orb[bottom] & 0xff) + 60, "下半是紅的");
        final int[] trail = OrbArt.liquid(size, OrbArt.Liquid.LIFE, 0.5, 0.8, 0);
        final int[] none = OrbArt.liquid(size, OrbArt.Liquid.LIFE, 0.5, 0.5, 0);
        final int between = (int) ((OrbArt.BOX / 2.0 - 4) * 2) * size + size / 2; // 液面上面一點：剛被打掉的那段
        assertTrue(brightness(trail[between]) > brightness(none[between]) + 30, "剛被打掉的那段比空的亮");
    }

    private static int brightness(final int argb) {
        return (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
    }

    @Test
    void shieldLayerAndMindOverMatter() {
        final int size = 76;
        final int low = (size * 3 / 4) * size + size / 2 + 6;
        final int high = (size / 4) * size + size / 2 + 6;
        final int[] shield = OrbArt.shield(size, 0.5, 0);
        assertTrue((shield[low] >>> 24) > 40 && (shield[low] >>> 24) < 200, "能量護盾罩層是半透明的");
        assertEquals(0, shield[high] >>> 24, "高度照能量護盾比例（一半：上半沒有）");
        final int[] withShield = OrbArt.compose(size, OrbArt.Liquid.LIFE, 1, 1, 1, 0, 0, 0);
        final int[] plain = OrbArt.compose(size, OrbArt.Liquid.LIFE, 1, 1, 0, 0, 0, 0);
        assertTrue((withShield[low] & 0xff) > (plain[low] & 0xff) + 30, "疊上去偏藍白");
        assertTrue((withShield[low] >> 16 & 0xff) > (withShield[low] >> 8 & 0xff), "下面的紅色還看得出來");
        final double mid = (OrbArt.LIQUID + 15.6) / 2;
        final int frameBottom = (int) ((OrbArt.BOX / 2.0 + mid) * 2) * size + size / 2;
        final int frameTop = (int) ((OrbArt.BOX / 2.0 - mid) * 2) * size + size / 2;
        assertTrue(OrbArt.mom(size, 0.4)[frameBottom] >>> 24 > 150, "心靈昇華：框的正下方有紫紅色");
        assertEquals(0, OrbArt.mom(size, 0.4)[frameTop] >>> 24, "40% 到不了正上方");
        assertTrue(HudData.parse("{\"vit\":{\"mhp\":1,\"hp\":1,\"mes\":500,\"es\":200}}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .vitals().chaosInoculation(), "混沌免疫：最大生命 1、有能量護盾");
        assertEquals(0, HudData.parse("{\"vit\":{\"mhp\":10}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).vitals().reservedMana(),
                "舊插件沒有 rmp：0");
    }

    @Test
    void layoutAvoidsHotbarNeighbours() {
        final int size = OrbLayout.size(1);
        assertEquals(OrbArt.BOX, size);
        assertTrue(OrbLayout.size(3) <= OrbLayout.CHAT_LINE - 2, "縮放再大也不超過聊天欄最下面那行");
        final int w = 640;
        assertTrue(OrbLayout.leftX(w, size) + size <= w / 2 - 91 - 29, "左球在副手格外側");
        assertTrue(OrbLayout.rightX(w) >= w / 2 + 91 + 6 + 18, "右球在攻擊冷卻指示外側");
        assertTrue(OrbLayout.top(360, size) >= 360 - OrbLayout.CHAT_LINE, "不壓到聊天欄");
        assertEquals(91 + 29 + 2 + OrbArt.BOX + 4, OrbLayout.outer(size), "跟資源包的 HotbarSides.ORB_EDGE 一樣（164）");
        // 慣用手是左手：副手格在右邊（91～120）、攻擊冷卻指示在左邊（−115～−97）：兩邊都在球的內側
        assertTrue(OrbLayout.rightX(w) >= w / 2 + 91 + 29, "右球在（左手慣用時的）副手格外側");
        assertTrue(OrbLayout.leftX(w, size) + size <= w / 2 - 91 - 6 - 18, "左球在（左手慣用時的）攻擊冷卻指示外側");
    }

    @org.junit.jupiter.api.Test
    void orbScaleMakesOrbsBigger() {
        // 2026-11-02 照站長：球可以再放大（設定的 orbScale），最大 MAX
        org.junit.jupiter.api.Assertions.assertEquals(OrbLayout.size(1f), OrbLayout.size(1f, 1f));
        org.junit.jupiter.api.Assertions.assertTrue(OrbLayout.size(1f, 1.3f) > OrbLayout.size(1f));
        org.junit.jupiter.api.Assertions.assertTrue(OrbLayout.size(3f, 1.9f) <= OrbLayout.MAX);
    }
}
