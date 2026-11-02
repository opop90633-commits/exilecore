package tw.exilecore.web;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02：資源包把吸收（金色）愛心換成藍色（能量護盾）。 */
class ShieldHeartTest {

    @Test
    void allEightSpritesAreBlueHearts() throws Exception {
        final Map<String, byte[]> files = new TreeMap<>();
        UiPack.shieldHearts(files);
        assertEquals(8, files.size());
        for (final String sprite : UiPack.SHIELD_HEART_SPRITES) {
            final byte[] png = files.get("assets/minecraft/textures/gui/sprites/hud/heart/" + sprite + ".png");
            assertTrue(png != null && png.length > 8 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', sprite);
            final BufferedImage image = UiPack.shieldHeart(sprite.contains("half"), sprite.contains("blinking"), sprite.contains("hardcore"));
            assertEquals(9, image.getWidth());
            assertEquals(9, image.getHeight());
            int painted = 0;
            int rightHalf = 0;
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 9; x++) {
                    final int argb = image.getRGB(x, y);
                    if (argb >>> 24 == 0) {
                        continue;
                    }
                    painted++;
                    if (x > 4) {
                        rightHalf++;
                    }
                    final int r = argb >> 16 & 0xff;
                    final int b = argb & 0xff;
                    assertTrue(b >= r, sprite + " 是藍色的（" + Integer.toHexString(argb) + "）");
                }
            }
            assertTrue(painted > (sprite.contains("half") ? 12 : 25), sprite);
            assertEquals(sprite.contains("half"), rightHalf == 0, sprite + "：半顆只有左半");
        }
    }
}
