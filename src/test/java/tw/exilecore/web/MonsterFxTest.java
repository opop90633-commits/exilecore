package tw.exilecore.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「怪物死掉冒出來的粒子換成比較明顯的、用材質包畫的」：怪物詞綴的效果圖。 */
class MonsterFxTest {

    static Map<String, byte[]> files;

    @BeforeAll
    static void build() throws IOException {
        files = new java.util.TreeMap<>();
        UiPack.marks(files);
    }

    private static JsonObject parse(final String path) {
        assertNotNull(files.get(path), "資源包裡沒有 " + path);
        return com.google.gson.JsonParser.parseString(new String(files.get(path), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static int alpha(final BufferedImage image, final int x, final int y) {
        return image.getRGB(x, y) >>> 24;
    }

    @Test
    void groundArtIsAFlatTintedPlaneWhoseRimIsTheRadius() throws IOException {
        for (final String name : MonsterFx.MARKS) {
            assertEquals("exilecore:item/mark/plane", parse("assets/exilecore/models/item/mark/" + name + ".json")
                    .get("parent").getAsString(), name);
            final JsonObject definition = parse("assets/exilecore/items/mark/" + name + ".json").getAsJsonObject("model");
            assertEquals("exilecore:item/mark/" + name, definition.get("model").getAsString());
            assertEquals("minecraft:custom_model_data", definition.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString());
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get("assets/exilecore/textures/item/mark/" + name + ".png")));
            assertEquals(MonsterFx.MARK_PX, image.getWidth(), name);
            assertEquals(MonsterFx.MARK_PX, image.getHeight(), name);
            final int n = MonsterFx.MARK_PX;
            assertEquals(0, alpha(image, 0, 0), name + "：角落透明（圓的）");
            if (!name.equals(MonsterFx.SOUL_RING)) {
                // 外框畫在圖的最邊上：實體放大成 2 × 半徑，外框就是傷害範圍的邊
                for (final int[] edge : new int[][] {{n / 2, 0}, {0, n / 2}, {n - 1, n / 2}, {n / 2, n - 1}}) {
                    assertTrue(alpha(image, edge[0], edge[1]) >= 235, name + "：外框在邊上 " + edge[0] + "," + edge[1]);
                }
                assertTrue(alpha(image, n / 2 + 6, n / 2 + 9) < 200, name + "：裡面是半透明的（看得到地面）");
            }
        }
        // 白色的圖（著色成傷害類型）、有顏色的圖（不著色）
        assertEquals(0xFFFFFF, MonsterFx.image(MonsterFx.BURST).getRGB(16, 0) & 0xFFFFFF);
        assertEquals(0xFFFFFF, MonsterFx.image(MonsterFx.GROUND).getRGB(16, 0) & 0xFFFFFF);
        assertEquals(0xFFD23A, MonsterFx.image(MonsterFx.MAGMA).getRGB(16, 0) & 0xFFFFFF, "熔岩爆：亮黃外框");
    }

    @Test
    void spritesAreSmallPixelArtWithADarkOutline() throws IOException {
        for (final String name : MonsterFx.SPRITES) {
            assertEquals("minecraft:item/generated", parse("assets/exilecore/models/item/fx/" + name + ".json")
                    .get("parent").getAsString());
            assertEquals("exilecore:item/fx/" + name, parse("assets/exilecore/items/fx/" + name + ".json")
                    .getAsJsonObject("model").get("model").getAsString());
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get("assets/exilecore/textures/item/fx/" + name + ".png")));
            assertNotNull(image);
            assertEquals(MonsterFx.SPRITE_PX, image.getWidth());
            assertEquals(MonsterFx.SPRITE_PX, image.getHeight());
            int painted = 0;
            final Set<Integer> colours = new HashSet<>();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    final int a = alpha(image, x, y);
                    assertTrue(a == 0 || a == 255, name + "：像素圖（不是半透明的反鋸齒）" + x + "," + y);
                    if (a > 0) {
                        painted++;
                        colours.add(image.getRGB(x, y));
                    }
                }
            }
            assertTrue(painted >= 50, name + " 幾乎是空的：" + painted);
            assertTrue(colours.size() >= 4 && colours.size() <= 10, name + "：幾個顏色就好 " + colours.size());
            assertEquals(0, alpha(image, 0, 0), name + "：角落透明");
        }
        // 靈魂：芯是白的、往上收尖（頂端比身體窄）
        final BufferedImage soul = MonsterFx.image(MonsterFx.SOUL);
        assertTrue(width(soul, 3) < width(soul, 10), "靈魂：上面是火舌");
        assertFalse(width(soul, 0) > 3, "靈魂：頂端是尖的");
    }

    private static int width(final BufferedImage image, final int y) {
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            count += alpha(image, x, y) > 0 ? 1 : 0;
        }
        return count;
    }

    @Test
    void sameArtEveryBuild() {
        for (final String name : MonsterFx.MARKS) {
            final BufferedImage a = MonsterFx.image(name);
            final BufferedImage b = MonsterFx.image(name);
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    assertEquals(a.getRGB(x, y), b.getRGB(x, y), "資源包的雜湊不能每次變");
                }
            }
        }
    }
}
