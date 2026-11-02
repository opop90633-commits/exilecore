package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 星骸輔助模組的封包：招呼、按鍵、版本比較。 */
class ClientModProtocolTest {

    @Test
    void helloRoundTrip() {
        final ClientModProtocol.Hello hello = new ClientModProtocol.Hello(1, "1.2.3", true, false);
        assertEquals(hello, ClientModProtocol.decodeHello(ClientModProtocol.encodeHello(hello)));
        // 舊版模組沒寫 hud、hearts：當成都有畫
        final ClientModProtocol.Hello old = ClientModProtocol.decodeHello("{\"protocol\":1,\"modVersion\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(old.hud());
        assertTrue(old.hearts());
        assertNull(ClientModProtocol.decodeHello("不是 JSON".getBytes(StandardCharsets.UTF_8)));
        assertNull(ClientModProtocol.decodeHello("[1,2]".getBytes(StandardCharsets.UTF_8)));
        assertNull(ClientModProtocol.decodeHello("{\"modVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8)), "沒有 protocol");
        assertNull(ClientModProtocol.decodeHello(new byte[ClientModProtocol.MAX_HELLO + 1]), "太長");
        assertNull(ClientModProtocol.decodeHello(new byte[0]));
    }

    @Test
    void orbsFlag() {
        // 2026-11-02：模組 1.3 招呼多了 orbs（只多欄位，協定不升）
        final ClientModProtocol.Hello hello = new ClientModProtocol.Hello(2, "1.3.0", true, true, true, true);
        assertEquals(hello, ClientModProtocol.decodeHello(ClientModProtocol.encodeHello(hello)));
        final ClientModProtocol.Hello older = ClientModProtocol.decodeHello(
                "{\"protocol\":2,\"modVersion\":\"1.2.0\",\"hud\":true,\"hearts\":true,\"effects\":true}".getBytes(StandardCharsets.UTF_8));
        assertFalse(older.orbs(), "1.2 以前的模組沒寫 = 沒有（伺服器照舊）");
        final ClientModProtocol.Hello noHearts = ClientModProtocol.decodeHello(
                "{\"protocol\":2,\"modVersion\":\"1.3.0\",\"hearts\":false,\"orbs\":true}".getBytes(StandardCharsets.UTF_8));
        assertFalse(noHearts.orbs(), "沒畫生命就沒有球");
        assertFalse(new String(ClientModProtocol.encodeHello(new ClientModProtocol.Hello(2, "1.2.0", true, true, true)), StandardCharsets.UTF_8)
                .contains("orbs"), "沒有球不寫（跟 1.2 送的一樣）");
    }

    @Test
    void serverHello() {
        // 1.0 的模組（協定 1）：回 1，跟以前一模一樣（它只認 protocol 1，不寫 effects）
        final String v1 = new String(ClientModProtocol.encodeServerHello(ClientModProtocol.negotiate(1), "1.0.1", "https://a.tw/mod/manifest.json",
                true), StandardCharsets.UTF_8);
        assertEquals("{\"protocol\":1,\"latestModVersion\":\"1.0.1\",\"manifestUrl\":\"https://a.tw/mod/manifest.json\"}", v1);
        final String v2 = new String(ClientModProtocol.encodeServerHello(ClientModProtocol.negotiate(2), "1.1.0", "u", true), StandardCharsets.UTF_8);
        assertEquals("{\"protocol\":2,\"latestModVersion\":\"1.1.0\",\"manifestUrl\":\"u\",\"effects\":true}", v2);
        assertTrue(new String(ClientModProtocol.encodeServerHello(null, null), StandardCharsets.UTF_8).contains("\"latestModVersion\":\"\""));
    }

    @Test
    void negotiation() {
        assertEquals(1, ClientModProtocol.negotiate(1), "1.0 的模組");
        assertEquals(2, ClientModProtocol.negotiate(2));
        assertEquals(ClientModProtocol.PROTOCOL, ClientModProtocol.negotiate(9), "比較新的模組：用我們的版本");
        assertEquals(0, ClientModProtocol.negotiate(0));
        final ClientModProtocol.Hello modern = new ClientModProtocol.Hello(2, "1.1.0", true, true, true);
        assertEquals(modern, ClientModProtocol.decodeHello(ClientModProtocol.encodeHello(modern)));
        assertFalse(ClientModProtocol.decodeHello("{\"protocol\":2,\"modVersion\":\"1.1.0\"}".getBytes(StandardCharsets.UTF_8)).effects(),
                "沒寫 effects = 不要");
        assertFalse(ClientModProtocol.decodeHello("{\"protocol\":1,\"effects\":true}".getBytes(StandardCharsets.UTF_8)).effects(),
                "協定 1 的 effects 不算數");
        assertFalse(new String(ClientModProtocol.encodeHello(new ClientModProtocol.Hello(1, "1.0.0", true, true)), StandardCharsets.UTF_8)
                .contains("effects"));
    }

    @Test
    void keys() {
        final ClientModProtocol.Key key = new ClientModProtocol.Key(7, true);
        assertEquals(key, ClientModProtocol.decodeKey(ClientModProtocol.encodeKey(key), 10));
        assertEquals(new ClientModProtocol.Key(0, false), ClientModProtocol.decodeKey(new byte[] {1, 0, 0}, 10));
        assertNull(ClientModProtocol.decodeKey(new byte[] {1, 10, 1}, 10), "超出按鍵格");
        assertNull(ClientModProtocol.decodeKey(new byte[] {2, 0, 1}, 10), "版本不對");
        assertNull(ClientModProtocol.decodeKey(new byte[] {1, 0}, 10), "太短");
        assertNull(ClientModProtocol.decodeKey(null, 10));
    }

    @Test
    void versions() {
        assertTrue(ClientModProtocol.compareVersions("1.2.10", "1.2.9") > 0);
        assertEquals(0, ClientModProtocol.compareVersions("1.0", "1.0.0"));
        assertEquals(0, ClientModProtocol.compareVersions("v1.0.0+build.5", "1.0.0"));
        assertTrue(ClientModProtocol.compareVersions("1.0.0-beta", "1.0.0") < 0);
        assertTrue(ClientModProtocol.compareVersions("1.0.0-beta.2", "1.0.0-beta.1") > 0);
        assertTrue(ClientModProtocol.compareVersions("2", "1.9.9") > 0);
        assertTrue(ClientModProtocol.compareVersions("", "0.0.1") < 0);
        assertFalse(ClientModProtocol.compareVersions("1.0.0", "1.0.1") > 0);
    }
}
