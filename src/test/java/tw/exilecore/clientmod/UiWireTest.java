package tw.exilecore.clientmod;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（D 組）原生畫面的封包：切段、壓縮、雜湊、動作、招呼的旗標、選單文字轉成顏色文字。 */
class UiWireTest {

    @Test
    void chunksRoundTrip() {
        final StringBuilder json = new StringBuilder("{\"nodes\":[");
        final Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"i\":\"p").append(random.nextInt(99999)).append("\",\"x\":").append(random.nextInt()).append('}');
        }
        json.append("]}");
        final byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
        final List<byte[]> frames = UiWire.encode("passive.static", raw, 4096);
        assertTrue(frames.size() > 3, "大的資料要切成好幾段");
        final ByteArrayOutputStream packed = new ByteArrayOutputStream();
        int index = 0;
        long hash = 0;
        for (final byte[] frame : frames) {
            assertTrue(frame.length <= 4096 + 64, "每段不超過設定的大小（加表頭）");
            final UiWire.Frame decoded = UiWire.decode(frame);
            assertEquals("passive.static", decoded.name());
            assertEquals(index++, decoded.index());
            assertEquals(frames.size(), decoded.count());
            hash = decoded.hash();
            packed.writeBytes(decoded.data());
            assertEquals(decoded.total(), UiWire.decode(frames.getFirst()).total());
        }
        final byte[] back = UiWire.inflate(packed.toByteArray(), 10_000_000);
        assertArrayEquals(raw, back);
        assertEquals(UiWire.hash(raw), hash, "雜湊是沒壓縮的 JSON 的");
        assertNull(UiWire.inflate(packed.toByteArray(), 1000), "解出來太大的不要");
    }

    @Test
    void smallDataIsOneFrameAndHashChanges() {
        final byte[] a = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        final byte[] b = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, UiWire.encode("x", a, UiWire.DEFAULT_CHUNK).size());
        assertNotEquals(UiWire.hash(a), UiWire.hash(b));
        assertEquals(16, UiWire.hex(UiWire.hash(a)).length());
        assertEquals(1, UiWire.encode("x", new byte[0], 1).size(), "空的也是一段");
    }

    @Test
    void badFrames() {
        assertNull(UiWire.decode(null));
        assertNull(UiWire.decode(new byte[5]));
        final byte[] frame = UiWire.encode("x", "{}".getBytes(StandardCharsets.UTF_8), 2048).getFirst();
        frame[0] = 9;
        assertNull(UiWire.decode(frame), "版本不對");
        assertNull(UiWire.inflate(new byte[] {1, 2, 3}, 100), "不是壓縮的資料");
    }

    @Test
    void actions() {
        final JsonObject action = UiWire.decodeAction("{\"t\":\"passive\",\"a\":\"alloc\",\"path\":[\"p1\"]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("alloc", action.get("a").getAsString());
        assertNull(UiWire.decodeAction("{\"t\":\"passive\"}".getBytes(StandardCharsets.UTF_8)), "沒有 a");
        assertNull(UiWire.decodeAction("[1]".getBytes(StandardCharsets.UTF_8)));
        assertNull(UiWire.decodeAction("{{".getBytes(StandardCharsets.UTF_8)));
        assertNull(UiWire.decodeAction(new byte[UiWire.MAX_ACTION + 1]), "太長");
    }

    @Test
    void helloFlags() {
        final byte[] hello = "{\"protocol\":2,\"modVersion\":\"1.4.0\",\"treeui\":true,\"atlasui\":false}".getBytes(StandardCharsets.UTF_8);
        assertTrue(UiWire.helloFlag(hello, "treeui"));
        assertFalse(UiWire.helloFlag(hello, "atlasui"));
        assertFalse(UiWire.helloFlag(hello, "orbs"), "沒寫是 false（舊模組）");
        assertFalse(UiWire.helloFlag("{\"treeui\":\"yes\"}".getBytes(StandardCharsets.UTF_8), "treeui"));
        assertFalse(UiWire.helloFlag("nope".getBytes(StandardCharsets.UTF_8), "treeui"));
        // 舊插件的招呼解析不受影響
        assertEquals(2, ClientModProtocol.decodeHello(hello).protocol());
    }

    @Test
    void componentText() {
        final Component line = Component.text("塔：", NamedTextColor.GRAY).append(Component.text("<爬到頂>", TextColor.color(0xF5B942)))
                .append(Component.text("，完成"));
        assertEquals("<#aaaaaa>塔：<#f5b942>\\<爬到頂><#aaaaaa>，完成", UiText.of(line, 0xFFFFFF));
        assertEquals("<#ffffff>白", UiText.of(Component.text("白"), 0xFFFFFF), "沒有顏色用預設的");
        final List<MiniParts.Part> parts = MiniParts.parse(UiText.of(line, 0xFFFFFF));
        assertEquals("塔：<爬到頂>，完成", parts.stream().map(MiniParts.Part::text).reduce("", String::concat), "跟模組一樣的解法讀得回來");
    }
}
