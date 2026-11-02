package tw.exilecore.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02：生命球、魔力球（資源包）：段數、外環、數字的字、字型檔。 */
class OrbFontTest {

    @Test
    void segments() {
        assertEquals(0, OrbArt.segment(0, 20));
        assertEquals(0, OrbArt.segment(Double.NaN, 20));
        assertEquals(1, OrbArt.segment(0.001, 20), "有一點就至少一段");
        assertEquals(10, OrbArt.segment(0.5, 20));
        assertEquals(19, OrbArt.segment(0.999, 20), "沒滿就不畫成滿的");
        assertEquals(20, OrbArt.segment(1, 20));
        assertEquals(20, OrbArt.segment(1.7, 20));
    }

    @Test
    void surfaceRisesWithLevel() {
        final double bottom = OrbArt.surface(0, 19, 0);
        final double half = OrbArt.surface(0.5, 19, 0);
        final double top = OrbArt.surface(1, 19, 0);
        assertEquals(OrbArt.BOX / 2.0 + OrbArt.LIQUID, bottom, 1e-9, "空的：液面在球底（沒有波紋）");
        assertEquals(OrbArt.BOX / 2.0 - OrbArt.LIQUID, top, 1e-9, "滿的：液面在球頂");
        assertTrue(Math.abs(half - OrbArt.BOX / 2.0) <= 0.8, "一半：在球心附近（波紋最多 0.75）");
    }

    @Test
    void ringFillsFromTheBottom() {
        assertFalse(OrbArt.ringFilled(0, 17, 0), "沒有護盾");
        assertTrue(OrbArt.ringFilled(0, 17, 0.01), "從正下方開始");
        assertFalse(OrbArt.ringFilled(0, -17, 0.99), "正上方要滿了才有");
        assertTrue(OrbArt.ringFilled(0, -17, 1));
        assertTrue(OrbArt.ringFilled(17, 0, 0.5), "一半：到左右兩邊");
        assertTrue(OrbArt.ringFilled(-17, 0, 0.5));
        assertFalse(OrbArt.ringFilled(12, -12, 0.5));
    }

    @Test
    void approachIsSmooth() {
        assertEquals(0.5, OrbArt.approach(0.5, 1, 0, 8), 1e-9);
        final double step = OrbArt.approach(0, 1, 0.05, 8);
        assertTrue(step > 0.2 && step < 0.5, "一幀只走一部分：" + step);
        assertEquals(1, OrbArt.approach(0.99999, 1, 0.05, 8), 1e-9, "很近就直接到");
    }

    @Test
    void compactNumbersFitOnTheOrb() {
        assertEquals("0", OrbFont.compact(0));
        assertEquals("999", OrbFont.compact(999));
        assertEquals("1,000", OrbFont.compact(1000));
        assertEquals("9,999", OrbFont.compact(9999));
        assertEquals("12.3k", OrbFont.compact(12_345));
        assertEquals("123k", OrbFont.compact(123_456));
        assertEquals("1.2m", OrbFont.compact(1_234_567));
        for (final long v : new long[] {0, 7, 1234, 9999, 12_345, 99_999, 123_456, 9_999_999}) {
            final String text = OrbFont.compact(v);
            assertTrue(text.length() <= 5, text);
            assertTrue(OrbFont.width("/" + text) <= 23, "放得進球裡：" + text);
            for (final char c : text.toCharArray()) {
                assertTrue(OrbFont.digit(0, c) != 0, "字型裡有：" + c);
            }
        }
    }

    @Test
    void glyphCodesDoNotCollide() {
        final Set<Character> seen = new HashSet<>();
        for (int k = 0; k <= OrbFont.LEVELS; k++) {
            assertTrue(seen.add(OrbFont.life(k)));
            assertTrue(seen.add(OrbFont.mana(k)));
            assertTrue(seen.add(OrbFont.shieldLiquid(k)));
            if (k > 0) {
                assertTrue(seen.add(OrbFont.reserved(k)));
                assertTrue(seen.add(OrbFont.shield(k)));
                assertTrue(seen.add(OrbFont.mom(k)));
            }
        }
        assertTrue(seen.add(OrbFont.frame()));
        for (int line = 0; line < OrbFont.LINES.length; line++) {
            for (final char c : OrbFont.DIGIT_SET.toCharArray()) {
                assertTrue(seen.add(OrbFont.digit(line, c)), "第 " + line + " 行的 " + c);
            }
        }
        assertEquals(0, OrbFont.digit(0, 'x'));
    }

    @Test
    void packFiles() throws Exception {
        final Map<String, byte[]> files = new TreeMap<>();
        OrbFont.build(files);
        assertTrue(OrbFont.ready());
        final JsonObject font = JsonParser.parseString(new String(files.get("assets/exilecore/font/orb.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        final Set<Character> defined = new HashSet<>();
        for (final JsonElement element : font.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            assertTrue(provider.get("ascent").getAsInt() <= provider.get("height").getAsInt(), "ascent 不能比 height 大");
            final String[] file = provider.get("file").getAsString().split(":", 2);
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get("assets/" + file[0] + "/textures/" + file[1])));
            assertNotNull(image);
            final int rows = provider.getAsJsonArray("chars").size();
            final int columns = provider.getAsJsonArray("chars").get(0).getAsString().length();
            assertEquals(0, image.getWidth() % columns);
            assertEquals(0, image.getHeight() % rows);
            for (final JsonElement row : provider.getAsJsonArray("chars")) {
                assertEquals(columns, row.getAsString().length());
                for (final char c : row.getAsString().toCharArray()) {
                    defined.add(c);
                }
            }
            // 每一格最右邊一欄都有點（字寬固定）
            final int cw = image.getWidth() / columns;
            final int ch = image.getHeight() / rows;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    boolean any = false;
                    for (int y = 0; y < ch; y++) {
                        any |= image.getRGB(c * cw + cw - 1, r * ch + y) >>> 24 != 0;
                    }
                    assertTrue(any, provider.get("file") + " 第 " + r + " 排第 " + c + " 格");
                }
            }
        }
        assertTrue(defined.contains(OrbFont.frame()));
        assertTrue(defined.contains(OrbFont.life(OrbFont.LEVELS)));
        assertTrue(defined.contains(OrbFont.reserved(1)));
        assertTrue(defined.contains(OrbFont.digit(OrbFont.LINES.length - 1, '%')));
        assertTrue(defined.contains(OrbFont.shield(20)));
        assertTrue(defined.contains(OrbFont.shieldLiquid(0)));
        assertTrue(defined.contains(OrbFont.mom(1)));
        // 球頂在畫面高 − 39（聊天欄最下面那行的下面）、球底在快捷欄底
        assertEquals(7 - 33, OrbFont.ascent(0));
        // 原版愛心（坐騎的不算）與飢餓條是透明的
        assertTrue(files.containsKey("assets/minecraft/textures/gui/sprites/hud/heart/full.png"));
        assertTrue(files.containsKey("assets/minecraft/textures/gui/sprites/hud/heart/absorbing_hardcore_half_blinking.png"));
        assertTrue(files.containsKey("assets/minecraft/textures/gui/sprites/hud/food_full.png"));
        assertFalse(files.containsKey("assets/minecraft/textures/gui/sprites/hud/heart/vehicle_full.png"), "坐騎的血照原版");
        assertFalse(files.containsKey("assets/minecraft/textures/gui/sprites/hud/armor_full.png"), "盔甲照原版");
        assertFalse(files.containsKey("assets/minecraft/textures/gui/sprites/hud/air.png"), "氧氣照原版");
        final BufferedImage heart = ImageIO.read(new ByteArrayInputStream(files.get("assets/minecraft/textures/gui/sprites/hud/heart/full.png")));
        assertEquals(9, heart.getWidth());
        assertEquals(0, heart.getRGB(4, 4) >>> 24);
        assertEquals(4 + 5 * 2 * 2 * 2 + 6, OrbFont.HIDDEN_SPRITES.size()); // 4 個空心＋5 種 × 2 × 2 × 2＋6 個飢餓
    }

    @Test
    void orbLayersLookRight() {
        final int size = OrbArt.BOX * 2;
        final int[] full = OrbArt.liquid(size, OrbArt.Liquid.LIFE, 1, 0, 0);
        final int[] empty = OrbArt.liquid(size, OrbArt.Liquid.LIFE, 0, 0, 0);
        final int center = size / 2 * size + size / 2;
        assertTrue((full[center] >> 16 & 0xff) > (full[center] & 0xff) + 60, "滿的生命球是紅的");
        assertTrue((empty[center] >> 16 & 0xff) < 60, "空的是暗的");
        assertEquals(0, full[0] >>> 24, "角落是透明的");
        final int[] mana = OrbArt.liquid(size, OrbArt.Liquid.MANA, 1, 0, 0);
        assertTrue((mana[center] & 0xff) > (mana[center] >> 16 & 0xff) + 60, "魔力球是藍的");
        final int[] half = OrbArt.liquid(size, OrbArt.Liquid.LIFE, 0.5, 0, 0);
        final int low = (size * 3 / 4) * size + size / 2;
        final int high = (size / 4 + 2) * size + size / 2;
        assertTrue((half[low] >> 16 & 0xff) > (half[high] >> 16 & 0xff) + 40, "一半：下面紅、上面暗");
        final int[] ring = OrbArt.ring(size, 0.5);
        final int ringBottom = (int) ((OrbArt.BOX / 2.0 + (OrbArt.RING_IN + OrbArt.RING_OUT) / 2) * 2) * size + size / 2;
        final int ringTop = (int) ((OrbArt.BOX / 2.0 - (OrbArt.RING_IN + OrbArt.RING_OUT) / 2) * 2) * size + size / 2;
        assertTrue((ring[ringBottom] & 0xff) > 200, "外環下面填了（藍白）");
        assertTrue((ring[ringTop] & 0xff) < 80, "外環上面還沒填");
        assertEquals(0, OrbArt.ring(size, -1)[ringBottom], "沒有能量護盾不畫外環");
    }

    @Test
    void shieldOverlayIsTranslucentBlueUpToItsLevel() {
        final int size = OrbArt.BOX * 2;
        final int[] shield = OrbArt.shield(size, 0.5, 0);
        final int low = (size * 3 / 4) * size + size / 2 + 6;
        final int high = (size / 4) * size + size / 2 + 6;
        final int a = shield[low] >>> 24;
        assertTrue(a > 40 && a < 200, "半透明：" + a);
        assertTrue((shield[low] & 0xff) >= (shield[low] >> 16 & 0xff), "偏藍白");
        assertEquals(0, shield[high] >>> 24, "能量護盾一半：上半沒有");
        assertEquals(0, OrbArt.shield(size, 0, 0)[low], "沒有能量護盾就沒有罩層");
        // 疊在紅色上面：還看得出紅色
        final int[] life = OrbArt.liquid(size, OrbArt.Liquid.LIFE, 1, 0, 0);
        OrbArt.overAll(life, shield);
        assertTrue((life[low] >> 16 & 0xff) > (life[low] >> 8 & 0xff), "罩層下面還是紅的");
        // 混沌免疫的液體是藍白的
        final int[] ci = OrbArt.liquid(size, OrbArt.Liquid.SHIELD, 1, 0, 0);
        final int center = size / 2 * size + size / 2;
        assertTrue((ci[center] & 0xff) > (ci[center] >> 16 & 0xff) + 40);
    }

    @Test
    void momMarkerGrowsFromTheBottomOfTheFrame() {
        final int size = OrbArt.BOX * 2;
        final double mid = (OrbArt.LIQUID + 15.6) / 2;
        final int bottom = (int) ((OrbArt.BOX / 2.0 + mid) * 2) * size + size / 2;
        final int top = (int) ((OrbArt.BOX / 2.0 - mid) * 2) * size + size / 2;
        final int[] mom = OrbArt.mom(size, 0.4);
        assertTrue(mom[bottom] >>> 24 > 150, "正下方有");
        assertTrue((mom[bottom] >> 16 & 0xff) > (mom[bottom] >> 8 & 0xff) + 40, "紫紅色");
        assertEquals(0, mom[top] >>> 24, "40% 到不了正上方");
        assertTrue(OrbArt.mom(size, 1)[top] >>> 24 > 150, "100% 一整圈");
        assertEquals(0, OrbArt.mom(size, 0)[bottom]);
    }
}
