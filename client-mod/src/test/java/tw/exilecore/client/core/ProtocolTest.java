package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 封包格式要跟插件（ClientModProtocol）一樣。 */
class ProtocolTest {

    @Test
    void helloAndKey() {
        assertEquals("{\"protocol\":1,\"modVersion\":\"1.0.0\",\"hud\":true,\"hearts\":false}",
                new String(Protocol.hello("1.0.0", true, false), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[] {1, 9, 1}, Protocol.key(9, true));
        assertArrayEquals(new byte[] {1, 0, 0}, Protocol.key(0, false));
        // 1.3（2026-11-02）：有球時多寫 orbs（插件 ClientModProtocolTest.orbsFlag 讀的是同樣的格式）；沒有球跟 1.2 一樣
        assertEquals("{\"protocol\":2,\"modVersion\":\"1.3.0\",\"hud\":true,\"hearts\":true,\"effects\":true,\"orbs\":true}",
                new String(Protocol.hello(2, "1.3.0", true, true, true, true), StandardCharsets.UTF_8));
        assertEquals("{\"protocol\":2,\"modVersion\":\"1.3.0\",\"hud\":true,\"hearts\":true,\"effects\":true}",
                new String(Protocol.hello(2, "1.3.0", true, true, true, false), StandardCharsets.UTF_8));
    }

    @Test
    void serverHello() {
        final Protocol.ServerHello hello = Protocol.serverHello(
                "{\"protocol\":1,\"latestModVersion\":\"1.0.1\",\"manifestUrl\":\"https://a.tw/mod/manifest.json\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(new Protocol.ServerHello(1, "1.0.1", "https://a.tw/mod/manifest.json"), hello);
        assertEquals(new Protocol.ServerHello(2, "", ""), Protocol.serverHello("{\"protocol\":2}".getBytes(StandardCharsets.UTF_8)));
        assertNull(Protocol.serverHello("??".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void protocolTwo() {
        assertEquals("{\"protocol\":2,\"modVersion\":\"1.1.0\",\"hud\":true,\"hearts\":true,\"effects\":true}",
                new String(Protocol.hello(2, "1.1.0", true, true, true), StandardCharsets.UTF_8));
        assertEquals("{\"protocol\":1,\"modVersion\":\"1.1.0\",\"hud\":true,\"hearts\":true}",
                new String(Protocol.hello(1, "1.1.0", true, true, true), StandardCharsets.UTF_8), "舊伺服器：照 1.0 的格式，不寫 effects");
        final Protocol.ServerHello modern = Protocol.serverHello(
                "{\"protocol\":2,\"latestModVersion\":\"1.1.0\",\"manifestUrl\":\"u\",\"effects\":true}".getBytes(StandardCharsets.UTF_8));
        assertTrue(modern.effects());
        assertTrue(!Protocol.serverHello("{\"protocol\":1,\"effects\":true}".getBytes(StandardCharsets.UTF_8)).effects(), "協定 1 沒有特效");
        assertTrue(Protocol.supported(1) && Protocol.supported(2));
        assertTrue(!Protocol.supported(0) && !Protocol.supported(3));
        // 舊伺服器（只會 1）回 1：改用 1 重打招呼；新伺服器回 2：不用
        assertEquals(1, Protocol.fallback(2, 1));
        assertEquals(0, Protocol.fallback(2, 2));
        assertEquals(0, Protocol.fallback(1, 1), "已經用 1 了不要一直重打");
        assertEquals(0, Protocol.fallback(2, 3), "比我們新的伺服器：不支援");
    }

    @Test
    void versions() {
        assertTrue(Protocol.compareVersions("1.0.10", "1.0.9") > 0);
        assertEquals(0, Protocol.compareVersions("1.0", "1.0.0"));
        assertTrue(Protocol.compareVersions("1.1.0-rc1", "1.1.0") < 0);
        assertTrue(Protocol.compareVersions("1.0.0", "1.0.0+26.2") == 0);
    }
}
