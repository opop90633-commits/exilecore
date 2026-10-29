package tw.exilecore.core.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /ec dummy move、/ec npc move 存位置：只改那幾行（用出貨的 dummies.yml、quests.yml）。 */
class YamlPatchTest {

    static final Path DATA = Path.of("src/main/resources/data");

    private static int diffLines(final String a, final String b) {
        final String[] x = a.split("\n", -1);
        final String[] y = b.split("\n", -1);
        int diff = Math.abs(x.length - y.length);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                diff++;
            }
        }
        return diff;
    }

    @Test
    void dummyOffsetAndYaw() throws IOException {
        // 2026-10-29：假人只剩首領，改用首領測（這次之前用「一般」）
        final String text = Files.readString(DATA.resolve("dummies.yml"));
        final String changed = YamlPatch.dummyPlacement(text, "boss", 5.25, -3, -90f);
        final YamlNode root = YamlNode.load("test", new java.io.StringReader(changed));
        final YamlNode boss = root.node("dummies").node("boss");
        assertEquals(java.util.List.of(5.3, -3), boss.raw("offset"));
        assertEquals(-90, boss.decimal("yaw", 0), 1e-9);
        assertEquals("diamond", boss.string("look", null), "別的欄位不動");
        assertEquals(text.split("\n", -1).length + 1, changed.split("\n", -1).length, "只多一行 yaw");
        assertEquals(text.replace("    offset: [28, 20]", "    offset: [5.3, -3]"), changed.replace("    yaw: -90\n", ""), "其他都沒動");
        assertTrue(changed.contains("# 營地的訓練假人"), "註解留著");
        assertNull(YamlPatch.dummyPlacement(text, "nobody", 0, 0, 0));
        // 再改一次：yaw 那行換掉，不會多一行
        final String again = YamlPatch.dummyPlacement(changed, "boss", 1, 2, 180f);
        assertEquals(changed.split("\n", -1).length, again.split("\n", -1).length);
        assertEquals(180, YamlNode.load("test", new java.io.StringReader(again)).node("dummies").node("boss").decimal("yaw", 0), 1e-9);
    }

    @Test
    void storyPlaceInline() throws IOException {
        final String text = Files.readString(DATA.resolve("quests.yml"));
        final String changed = YamlPatch.storyPlacement(text, "shen_du", 6.5, -2, 1, 90f);
        assertEquals(1, diffLines(text, changed));
        final YamlNode place = YamlNode.load("test", new java.io.StringReader(changed)).node("npcs").node("shen_du").node("place");
        assertEquals("camp", place.string("zone", null));
        assertEquals(1, place.decimal("y", 0), 1e-9);
        assertEquals(90, place.decimal("yaw", 0), 1e-9);
        assertEquals(java.util.List.of(6.5, -2), place.raw("at"));
        final String flat = YamlPatch.storyPlacement(changed, "shen_du", 6.5, -2, 0, 90f);
        assertTrue(!YamlNode.load("test", new java.io.StringReader(flat)).node("npcs").node("shen_du").node("place").has("y"), "回到地面：y 拿掉");
        assertNull(YamlPatch.storyPlacement(text, "nobody", 0, 0, 0, 0));
    }

    @Test
    void storyPlaceMultiLine() {
        final String text = "npcs:\n  a:\n    name: A\n    place:\n      zone: camp\n      at: [1, 2]   # 舊的\n    idle: []\nquests: {}\n";
        final String changed = YamlPatch.storyPlacement(text, "a", 3, 4, 0, 45f);
        assertTrue(changed.contains("      at: [3, 4]   # 舊的"), changed);
        assertTrue(changed.contains("      yaw: 45"), changed);
        assertTrue(changed.contains("    idle: []"));
    }

    @Test
    void numbers() {
        assertEquals("3", YamlPatch.number(3.0));
        assertEquals("-2.5", YamlPatch.number(-2.5));
        assertEquals("0.3", YamlPatch.number(0.26));
    }
}
