package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** web/unique_art.txt 的格式。 */
class UniqueArtTest {

    private static String grid(final String fill) {
        final StringBuilder text = new StringBuilder();
        for (int y = 0; y < 16; y++) {
            text.append("  ").append(y == 0 ? ".a.............." : fill).append('\n');
        }
        return text.toString();
    }

    @Test
    void parsesPaletteAndRows() {
        final Map<String, UniqueArt.Sprite> sprites = UniqueArt.parse("# 註解\nitem demo\n  a=ff0000\n  b=00ff0080\n" + grid("b..............."));
        assertEquals(1, sprites.size());
        final UniqueArt.Sprite sprite = sprites.get("demo");
        assertEquals(16, sprite.width());
        assertEquals(0xFFFF0000, sprite.argb()[0][1]);
        assertEquals(0x8000FF00, sprite.argb()[1][0], "有寫 AA 的顏色");
        assertEquals(0, sprite.argb()[0][0], "點是透明");
        final UniqueArt.Sprite big = sprite.scaled(2);
        assertEquals(32, big.width());
        assertEquals(0xFFFF0000, big.argb()[1][3]);
    }

    @Test
    void rejectsBrokenFiles() {
        assertThrows(IllegalArgumentException.class, () -> UniqueArt.parse("item demo\n  a=ff0000\n  ....\n"), "每行要 16 個字");
        assertThrows(IllegalArgumentException.class, () -> UniqueArt.parse("item demo\n" + grid("z...............")), "沒定義的顏色");
        assertThrows(IllegalArgumentException.class, () -> UniqueArt.parse("item demo\n  a=ff0000\n" + grid("................").repeat(1) + "  ................\n"), "多一行");
        assertThrows(IllegalArgumentException.class, () -> UniqueArt.parse("item demo\n  a=ff0000\n" + grid("................") + "item demo\n  a=ff0000\n" + grid("................")), "重複");
        assertThrows(IllegalArgumentException.class, () -> UniqueArt.parse("item Demo-1\n"), "代號");
        assertThrows(IllegalArgumentException.class, () -> UniqueArt.parse("item demo\n  a=red\n"), "顏色格式");
    }

    @Test
    void shippedArtLoads() {
        assertTrue(UniqueArt.sprites().size() >= 20);
        assertTrue(UniqueArt.ids().contains("star_forge"));
        assertTrue(UniqueArt.sprites().containsKey("hawk_eye_pulling_2"));
        assertTrue(UniqueArt.sprites().containsKey("formation_breaker_arrow"));
    }
}
