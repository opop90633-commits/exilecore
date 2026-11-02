package tw.exilecore.clientmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（模組 1.4 B 組）：地圖資訊框、頭上血條、準心、傷害數字的封包。 */
class WorldHudProtocolTest {

    @Test
    void featuresFromHello() {
        final byte[] hello = "{\"protocol\":2,\"modVersion\":\"1.4.0\",\"hud\":true,\"mapbox\":true,\"dmgnum\":true,\"mobbars\":false}"
                .getBytes(StandardCharsets.UTF_8);
        final WorldHudProtocol.Features features = WorldHudProtocol.decodeFeatures(hello);
        assertTrue(features.mapbox());
        assertTrue(features.dmgnum());
        assertFalse(features.mobbars());
        // 1.3 的模組沒寫：都是 false；舊插件讀到多的欄位照樣讀得到招呼
        final byte[] old = ClientModProtocol.encodeHello(new ClientModProtocol.Hello(2, "1.3.0", true, true, true, true));
        assertFalse(WorldHudProtocol.decodeFeatures(old).any());
        assertNotNull(ClientModProtocol.decodeHello(hello));
        assertFalse(WorldHudProtocol.decodeFeatures("壞掉".getBytes(StandardCharsets.UTF_8)).any());
        assertFalse(WorldHudProtocol.decodeFeatures("{\"mapbox\":\"yes\"}".getBytes(StandardCharsets.UTF_8)).mapbox());
    }

    @Test
    void mapPanelJson() {
        final WorldHudProtocol.MapPanel panel = new WorldHudProtocol.MapPanel("沉船灣 T7", 0x9FD3FF, 7, 70,
                List.of(MiniParts.parse("<#ffff77>怪物生命增加 30%")), 5, "次", 42, 30, WorldHudProtocol.BOSS_FIGHTING, "潮汐統帥", 62,
                List.of(new WorldHudProtocol.EventLine("裂隙", 0xC48BFF, "還有 12 秒", 140, WorldHudProtocol.EVENT_ACTIVE)));
        final JsonObject root = JsonParser.parseString(WorldHudProtocol.encodeMap(panel)).getAsJsonObject();
        assertEquals(1, root.get("v").getAsInt());
        assertEquals("沉船灣 T7", root.get("name").getAsString());
        assertEquals("怪物生命增加 30%", root.getAsJsonArray("mods").get(0).getAsJsonArray().get(0).getAsJsonArray().get(0).getAsString());
        assertEquals(0xFFFF77, root.getAsJsonArray("mods").get(0).getAsJsonArray().get(0).getAsJsonArray().get(1).getAsInt());
        assertEquals(1, root.getAsJsonObject("boss").get("s").getAsInt());
        assertEquals(100, root.getAsJsonArray("events").get(0).getAsJsonObject().get("p").getAsInt(), "夾在 0–100");
        assertTrue(JsonParser.parseString(WorldHudProtocol.encodeMap(null)).getAsJsonObject().get("clear").getAsBoolean());
    }

    @Test
    void mobsRoundTrip() {
        final List<WorldHudProtocol.MobBar> bars = List.of(
                new WorldHudProtocol.MobBar(12, 255, -1, WorldHudProtocol.RARITY_NORMAL, false),
                new WorldHudProtocol.MobBar(300_000, 80, 200, WorldHudProtocol.RARITY_UNIQUE, true, true, 0),
                new WorldHudProtocol.MobBar(7, 128, -1, WorldHudProtocol.RARITY_RARE, false, false,
                        WorldHudProtocol.MARK_CURSED | WorldHudProtocol.MARK_IGNITE | WorldHudProtocol.MARK_SHOCK));
        final byte[] data = WorldHudProtocol.encodeMobs(false, bars, List.of(5, 99));
        final WorldHudProtocol.Mobs decoded = WorldHudProtocol.decodeMobs(data);
        assertNotNull(decoded);
        assertFalse(decoded.full());
        assertEquals(bars, decoded.bars());
        assertEquals(List.of(5, 99), decoded.removed());
        // 表頭 3 個位元組＋一般的怪一筆 3 個（id 12 是 1 個、狀態、生命）＋拿掉的筆數 1 個
        assertEquals(3 + 3 + 1, WorldHudProtocol.encodeMobs(true, bars.subList(0, 1), List.of()).length);
        assertNull(WorldHudProtocol.decodeMobs(new byte[] {9}));
        assertNull(WorldHudProtocol.decodeMobs(new byte[] {1, 0, 3}));
    }

    @Test
    void aim() {
        assertEquals(-1, WorldHudProtocol.decodeAim(WorldHudProtocol.encodeAim(-1)));
        assertEquals(0, WorldHudProtocol.decodeAim(WorldHudProtocol.encodeAim(0)));
        assertEquals(123456, WorldHudProtocol.decodeAim(WorldHudProtocol.encodeAim(123456)));
        assertNull(WorldHudProtocol.decodeAim(new byte[] {2, 1}));
        assertNull(WorldHudProtocol.decodeAim(new byte[0]));
    }

    @Test
    void fraction() {
        assertEquals(0, WorldHudProtocol.fraction255(0));
        assertEquals(1, WorldHudProtocol.fraction255(0.0001), "有剩一點點不是 0");
        assertEquals(254, WorldHudProtocol.fraction255(0.9999), "沒滿不是 255");
        assertEquals(255, WorldHudProtocol.fraction255(1.2));
        assertEquals(0, WorldHudProtocol.fraction255(Double.NaN));
    }

    @Test
    void numbersInFxPacket() {
        final FxProtocol.Writer writer = new FxProtocol.Writer();
        assertTrue(writer.addNumber(new FxProtocol.Number(10.5, 64.2, -3.25, 1234, 0xFF8040, true, false, false, null, 42), 0));
        assertTrue(writer.addNumber(new FxProtocol.Number(10.5, 65, -3, -1, 0xAAAAAA, false, true, false, "閃避", -1), 0));
        assertTrue(writer.addNumber(new FxProtocol.Number(900, 65, -3, 77, 0x7FC8FF, false, false, true, "閃避", 3), 0));
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(writer.toBytes());
        assertNotNull(decoded);
        assertEquals(3, decoded.size());
        final FxProtocol.Number hit = decoded.get(0).number();
        assertEquals(1234, hit.value());
        assertEquals(0xFF8040, hit.rgb());
        assertTrue(hit.emphasis());
        assertEquals(42, hit.target());
        assertEquals(64.2, hit.y(), 0.01);
        final FxProtocol.Number word = decoded.get(1).number();
        assertEquals(-1, word.value());
        assertEquals("閃避", word.text());
        assertTrue(word.self());
        final FxProtocol.Number far = decoded.get(2).number();
        assertEquals(900, far.x(), 0.01, "超過 256 格用 f32");
        assertTrue(far.absorbed());
        // 上限：一筆都放不下就不放（伺服器改回生實體）
        final FxProtocol.Writer small = new FxProtocol.Writer();
        assertFalse(small.addNumber(new FxProtocol.Number(0, 0, 0, 1, 0, false, false, false, null, -1), 10));
    }

    @Test
    void routerNumbersOnlyForCapable() {
        final FxRouter router = new FxRouter();
        final UUID a = UUID.randomUUID();
        final FxProtocol.Number n = new FxProtocol.Number(0, 64, 0, 5, 0xFFFFFF, false, false, false, null, 1);
        assertFalse(router.number(a, n), "沒說 dmgnum：伺服器照舊生實體");
        router.setNumbers(a, true);
        assertTrue(router.number(a, n));
        assertFalse(router.capable(a), "數字跟特效的開關分開");
        assertEquals(1, router.drain().size());
        router.setNumbers(a, false);
        assertFalse(router.number(a, n));
        assertTrue(router.drain().isEmpty());
    }
}
