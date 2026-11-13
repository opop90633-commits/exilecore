package tw.exilecore.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-12 照站長「減輕伺服器流量」：網頁伺服器的 gzip。 */
class WebGzipTest {

    @Test
    void onlyTextIsCompressed() {
        assertTrue(WebServer.compressible("text/html; charset=utf-8"));
        assertTrue(WebServer.compressible("application/json; charset=utf-8"));
        assertTrue(WebServer.compressible("application/javascript"));
        assertFalse(WebServer.compressible("application/zip"));
        assertFalse(WebServer.compressible("image/png"));
        assertFalse(WebServer.compressible(null));
    }

    @Test
    void readsAcceptEncoding() {
        assertTrue(WebServer.acceptsGzip("gzip, deflate, br"));
        assertTrue(WebServer.acceptsGzip("br;q=1.0, gzip;q=0.8"));
        assertFalse(WebServer.acceptsGzip("gzip;q=0"));
        assertFalse(WebServer.acceptsGzip("br"));
        assertFalse(WebServer.acceptsGzip(null));
    }

    @Test
    void roundTrips() throws Exception {
        final byte[] raw = "{\"a\":1}".repeat(500).getBytes(StandardCharsets.UTF_8);
        final byte[] packed = WebServer.gzip(raw);
        assertTrue(packed.length < raw.length / 5);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            assertArrayEquals(raw, in.readAllBytes());
        }
    }
}
