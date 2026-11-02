package tw.exilecore.clientmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tw.exilecore.map.MapLayout;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（C 組）：預警、小地圖、死亡回顧的封包（模組的 FeedCodecTest 讀的是同樣的格式）。 */
class FeedProtocolTest {

    private static JsonObject json(final byte[] data) {
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void helloFlags() {
        final FeedProtocol.Flags flags = FeedProtocol.flags(("{\"protocol\":2,\"modVersion\":\"1.4.0\",\"hud\":true,\"hearts\":true,"
                + "\"alerts\":true,\"minimap\":true,\"death\":false,\"tooltip\":true}").getBytes(StandardCharsets.UTF_8));
        assertTrue(flags.alerts());
        assertTrue(flags.minimap());
        assertFalse(flags.death());
        assertTrue(flags.tooltip());
        // 舊模組（1.3）沒寫：全部 false，照舊送大標題、印聊天欄
        assertEquals(FeedProtocol.Flags.NONE, FeedProtocol.flags("{\"protocol\":2,\"orbs\":true}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(FeedProtocol.Flags.NONE, FeedProtocol.flags("壞掉".getBytes(StandardCharsets.UTF_8)));
        assertEquals(FeedProtocol.Flags.NONE, FeedProtocol.flags(null));
        assertEquals(FeedProtocol.Flags.NONE, FeedProtocol.flags("{\"alerts\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FeedProtocol.Flags.NONE.any());
    }

    @Test
    void alertJson() {
        final JsonObject root = json(FeedProtocol.alert(FeedProtocol.Alert.top("inside:a", 2, "<red><bold>⚠ 隕石", "<white>離開紅色區域", 11)
                .countdown(40).at(1.25, 64, -3.04, false)));
        assertEquals("show", root.get("op").getAsString());
        assertEquals("top", root.get("lane").getAsString());
        assertEquals(2, root.get("p").getAsInt());
        assertEquals("⚠ 隕石", root.getAsJsonArray("t").get(0).getAsJsonObject().get("t").getAsString());
        assertEquals(0xFF5555, root.getAsJsonArray("t").get(0).getAsJsonObject().get("c").getAsInt());
        assertEquals(40, root.get("cd").getAsInt());
        assertEquals(11, root.get("d").getAsInt());
        assertEquals(1.3, root.getAsJsonArray("at").get(0).getAsDouble(), 1e-9);
        assertEquals(-3.0, root.getAsJsonArray("at").get(2).getAsDouble(), 1e-9);
        assertEquals("danger", root.get("k").getAsString());

        final JsonObject side = json(FeedProtocol.alert(FeedProtocol.Alert.side("phase:3", "<red>第 2 階段", List.of("<white>站滿光圈", "叫出小怪"), 400)));
        assertEquals("side", side.get("lane").getAsString());
        assertEquals(2, side.getAsJsonArray("l").size());
        assertFalse(side.has("s"), "沒有說明不寫");
        assertFalse(side.has("at"));
        assertEquals("*", json(FeedProtocol.clearAlert(null)).get("id").getAsString());
        assertEquals(3, json(FeedProtocol.alert(FeedProtocol.Alert.top("x", 9, "a", "", 1))).get("p").getAsInt(), "優先度夾在 0～3");
    }

    @Test
    void deathDropsCommandLine() {
        final JsonObject root = json(FeedProtocol.death(List.of("<red>☠ 死亡回顧", "<gray>最後 3 下", "<dark_gray>/deaths 可以再看一次")));
        assertEquals(2, root.getAsJsonArray("lines").size());
    }

    @Test
    void tilesAndPack() {
        assertArrayEquals(new byte[] {(byte) 0b11_10_01_00, 0b01}, FeedProtocol.pack(new int[] {0, 1, 2, 3, 1}));
        final MapLayout layout = MapLayout.generate(12345L, 8);
        final int[] tiles = FeedProtocol.tiles(layout, FeedProtocol.RES);
        final int size = MapLayout.SIZE / FeedProtocol.RES;
        assertEquals(size * size, tiles.length);
        // 起點房的中心一定是地板
        final MapLayout.Room start = layout.start();
        assertEquals(FeedProtocol.TILE_FLOOR, tiles[(start.cz() / FeedProtocol.RES) * size + start.cx() / FeedProtocol.RES]);
        int floors = 0;
        int walls = 0;
        for (final int tile : tiles) {
            floors += tile == FeedProtocol.TILE_FLOOR ? 1 : 0;
            walls += tile == FeedProtocol.TILE_WALL ? 1 : 0;
        }
        assertTrue(floors > 100 && walls > 100);

        final JsonObject init = json(FeedProtocol.minimapInit(7, 1000, -2000, layout, new FeedProtocol.Boss(1100, -1900, "wait"),
                List.of(new FeedProtocol.Marker(0, 1050, -1950, "shrine", "神龕", 0x9FE8C8, 0))));
        assertEquals("init", init.get("op").getAsString());
        assertEquals(size, init.get("w").getAsInt());
        assertEquals((size * size + 3) / 4, Base64.getDecoder().decode(init.get("tiles").getAsString()).length);
        assertEquals(layout.rooms().size(), init.getAsJsonArray("rooms").size());
        assertEquals(1000 + start.cx(), init.getAsJsonArray("rooms").get(layout.rooms().indexOf(start)).getAsJsonObject().get("x").getAsInt());
        assertEquals("wait", init.getAsJsonObject("boss").get("st").getAsString());
        assertEquals("神龕", init.getAsJsonArray("events").get(0).getAsJsonObject().get("n").getAsString());
    }

    @Test
    void changedMarkersAndBoss() {
        final FeedProtocol.Marker a = new FeedProtocol.Marker(0, 1, 2, "shrine", "神龕", 1, 0);
        final FeedProtocol.Marker b = new FeedProtocol.Marker(1, 5, 6, "breach", "裂隙", 2, 0);
        final FeedProtocol.Marker bDone = new FeedProtocol.Marker(1, 5, 6, "breach", "裂隙", 2, 2);
        assertEquals(List.of(bDone), FeedProtocol.changed(List.of(a, b), List.of(a, bDone)));
        assertEquals(List.of(a), FeedProtocol.changed(null, List.of(a)));
        assertTrue(FeedProtocol.changed(List.of(a), List.of(a)).isEmpty());

        assertTrue(MinimapFeed.bossChanged(null, new FeedProtocol.Boss(0, 0, "wait")));
        assertFalse(MinimapFeed.bossChanged(new FeedProtocol.Boss(0, 0, "alive"), new FeedProtocol.Boss(1, 1, "alive")));
        assertTrue(MinimapFeed.bossChanged(new FeedProtocol.Boss(0, 0, "alive"), new FeedProtocol.Boss(3, 0, "alive")));
        assertTrue(MinimapFeed.bossChanged(new FeedProtocol.Boss(0, 0, "alive"), new FeedProtocol.Boss(0, 0, "dead")));
        assertFalse(MinimapFeed.bossChanged(new FeedProtocol.Boss(0, 0, "alive"), null));
    }

    @Test
    void nearestSpot() {
        assertNull(AlertFeed.nearest(0, 0, List.of()));
        assertArrayEquals(new double[] {2, 2}, AlertFeed.nearest(1, 1, List.of(new double[] {10, 10}, new double[] {2, 2}, new double[] {-5, 0})));
    }
}
