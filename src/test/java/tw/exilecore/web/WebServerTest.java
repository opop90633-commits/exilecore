package tw.exilecore.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 內建網頁伺服器：路由、404、只收 GET／HEAD、轉址、處理器出錯不會讓連線卡住。 */
class WebServerTest {

    static WebServer server;
    static HttpClient client;

    @BeforeAll
    static void start() throws IOException {
        server = WebServer.start("127.0.0.1", 0, LoggerFactory.getLogger(WebServerTest.class));
        server.route("/hello", path -> WebServer.Response.text(200, "你好"));
        server.route("/", path -> WebServer.Response.redirect("hello"));
        server.route("/cached", path -> new WebServer.Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("Cache-Control", "no-cache")));
        server.route("/broken", path -> {
            throw new IllegalStateException("故意的");
        });
        server.prefix("/files/", path -> WebServer.Response.text(200, "file:" + path));
        server.prefix("/files/deep/", path -> WebServer.Response.text(200, "deep:" + path));
        // 管理網頁用的：看得到整個請求、可以收 POST
        server.endpoint("/echo", request -> WebServer.Response.text(200, request.method() + "|" + request.text() + "|"
                + request.header("X-Test") + "|" + request.cookie("sid") + "|" + request.param("q") + "|" + request.param("none")), "GET", "POST");
        server.endpoint("/getonly", request -> WebServer.Response.text(200, "ok"), "GET");
        server.endpoint("/postonly", request -> WebServer.Response.text(200, String.valueOf(request.body().length)), "POST");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static HttpResponse<String> send(final String method, final String path) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void servesRoutes() throws Exception {
        final HttpResponse<String> response = send("GET", "/hello");
        assertEquals(200, response.statusCode());
        assertEquals("你好", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("no-cache", send("GET", "/cached").headers().firstValue("Cache-Control").orElse(""));
        // 查詢字串不影響路由
        assertEquals(200, send("GET", "/hello?x=1").statusCode());
    }

    @Test
    void unknownPathsAre404() throws Exception {
        assertEquals(404, send("GET", "/nope").statusCode());
        assertEquals(404, send("GET", "/hello/").statusCode());
    }

    @Test
    void onlyGetAndHead() throws Exception {
        final HttpResponse<String> post = send("POST", "/hello");
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(""));
        final HttpResponse<String> head = send("HEAD", "/hello");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
    }

    @Test
    void endpointsSeeTheWholeRequest() throws Exception {
        final HttpRequest post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/echo?q=%E4%BD%A0%E5%A5%BD&x=1"))
                .timeout(Duration.ofSeconds(5))
                .header("X-Test", "abc")
                .header("Cookie", "other=1; sid=s3cret")
                .POST(HttpRequest.BodyPublishers.ofString("{\"a\":\"星骸\"}", StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response = client.send(post, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        assertEquals("POST|{\"a\":\"星骸\"}|abc|s3cret|你好|null", response.body());
        assertEquals("GET||null|null|null|null", send("GET", "/echo").body());
        final HttpResponse<String> head = send("HEAD", "/echo");
        assertEquals(200, head.statusCode(), "收 GET 就收 HEAD");
        assertEquals("", head.body());
    }

    @Test
    void endpointsRejectOtherMethodsAndHugeBodies() throws Exception {
        final HttpResponse<String> post = send("POST", "/getonly");
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(""));
        final HttpResponse<String> get = send("GET", "/postonly");
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(""));
        final HttpRequest small = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/postonly"))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(new byte[WebServer.MAX_BODY])).build();
        assertEquals(String.valueOf(WebServer.MAX_BODY), client.send(small, HttpResponse.BodyHandlers.ofString()).body());
        final HttpRequest huge = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/postonly"))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(new byte[WebServer.MAX_BODY + 1])).build();
        assertEquals(413, client.send(huge, HttpResponse.BodyHandlers.ofString()).statusCode(), "內容太大不收");
        assertEquals(200, send("GET", "/hello").statusCode(), "之後的請求照常");
    }

    @Test
    void redirects() throws Exception {
        final HttpResponse<String> response = send("GET", "/");
        assertEquals(302, response.statusCode());
        assertEquals("hello", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void longestPrefixWins() throws Exception {
        assertEquals("file:/files/a.zip", send("GET", "/files/a.zip").body());
        assertEquals("deep:/files/deep/b", send("GET", "/files/deep/b").body());
    }

    @Test
    void brokenHandlerAnswers500() throws Exception {
        assertEquals(500, send("GET", "/broken").statusCode());
        // 之後的請求照常
        assertEquals(200, send("GET", "/hello").statusCode());
    }

    @Test
    void slowClientsDoNotBlockOthers() throws Exception {
        final java.util.List<java.net.Socket> hanging = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                final java.net.Socket socket = new java.net.Socket("127.0.0.1", server.port());
                socket.getOutputStream().write("GET /hello HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                hanging.add(socket);
            }
            final long begin = System.nanoTime();
            assertEquals(200, send("GET", "/hello").statusCode());
            assertTrue(System.nanoTime() - begin < 3_000_000_000L, "要馬上回應");
        } finally {
            for (final java.net.Socket socket : hanging) {
                socket.close();
            }
        }
    }

    @Test
    void dotDotIsRefused() throws Exception {
        assertEquals(400, send("GET", "/files/..%2F..%2Fsecret").statusCode());
    }
}
