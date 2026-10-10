package tw.exilecore.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 很小的網頁伺服器（JDK 內建的 HttpServer）。一般的路由（{@link #route}、{@link #prefix}）只回應 GET／HEAD，
 * 內容都是插件準備好的（資源包、天賦規劃器）；管理網頁用 {@link #endpoint} 註冊，可以收 POST（內容最多 {@value #MAX_BODY} 位元組），
 * 處理器拿得到標頭、查詢字串、Cookie。
 * 處理請求在自己的執行緒上（最多 {@value #MAX_THREADS} 條），不碰遊戲的主執行緒；路由的處理器要自己保證執行緒安全
 * （通常只是回傳準備好的位元組；要動遊戲裡的東西時自己排回主執行緒）。
 * 純 Java，不依賴 Bukkit，可以在單元測試裡啟動。
 */
public final class WebServer {

    /** 一個回應。 */
    public record Response(int status, String contentType, byte[] body, Map<String, String> headers) {

        public static Response of(final String contentType, final byte[] body) {
            return new Response(200, contentType, body, Map.of());
        }

        public static Response text(final int status, final String text) {
            return new Response(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static Response redirect(final String location) {
            return new Response(302, "text/plain; charset=utf-8", new byte[0], Map.of("Location", location));
        }

        /** 同一個回應，多加（或換掉）幾個標頭。 */
        public Response with(final Map<String, String> more) {
            final Map<String, String> merged = new java.util.LinkedHashMap<>(headers);
            merged.putAll(more);
            return new Response(status, contentType, body, merged);
        }
    }

    /**
     * 一個請求（{@link #endpoint} 的處理器拿到的）。
     *
     * @param method  GET、HEAD、POST…（大寫）
     * @param query   原始的查詢字串（沒有是 null）
     * @param headers 標頭（鍵是小寫，同名的只留第一個）
     * @param body    內容（GET 是空的）
     * @param remote  對方的 IP
     */
    public record Request(String method, String path, String query, Map<String, String> headers, byte[] body, String remote) {

        public String header(final String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** 查詢字串裡的一個參數（解碼過；沒有是 null）。 */
        public String param(final String name) {
            if (query == null || query.isEmpty()) {
                return null;
            }
            for (final String pair : query.split("&")) {
                final int eq = pair.indexOf('=');
                final String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                if (key.equals(name)) {
                    return eq < 0 ? "" : decode(pair.substring(eq + 1));
                }
            }
            return null;
        }

        /** 一個 Cookie 的值（沒有是 null）。 */
        public String cookie(final String name) {
            final String header = header("cookie");
            if (header == null) {
                return null;
            }
            for (final String part : header.split(";")) {
                final int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                    return part.substring(eq + 1).trim();
                }
            }
            return null;
        }

        /** 內容當成 UTF-8 文字。 */
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        private static String decode(final String text) {
            try {
                return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                return text; // 寫壞的 %xx：照原樣
            }
        }
    }

    /** 處理一個路徑（在網頁伺服器的執行緒上呼叫）。 */
    @FunctionalInterface
    public interface Handler {
        /** @return null = 404 */
        Response handle(String path);
    }

    /** 處理一個請求（{@link #endpoint}；在網頁伺服器的執行緒上呼叫）。 */
    @FunctionalInterface
    public interface RequestHandler {
        /** @return null = 404 */
        Response handle(Request request);
    }

    /** 同時處理的連線上限。 */
    static final int MAX_THREADS = 32;
    /** POST 的內容上限（管理網頁送的 JSON）。 */
    static final int MAX_BODY = 64 * 1024;

    private record Endpoint(Set<String> methods, RequestHandler handler) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Logger logger;
    private final Map<String, Handler> exact = new ConcurrentHashMap<>();
    private final Map<String, Handler> prefixes = new ConcurrentHashMap<>();
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();

    private WebServer(final HttpServer server, final ExecutorService executor, final Logger logger) {
        this.server = server;
        this.executor = executor;
        this.logger = logger;
    }

    /** 在 bind:port 開始聽（port 0＝隨便挑一個空的，測試用）。 */
    public static WebServer start(final String bind, final int port, final Logger logger) throws IOException {
        // JDK 的 HttpServer 預設讀請求不限時間：故意連上來不送完的連線會一直占著。
        // 限制讀請求 15 秒、送回應 120 秒（要在第一次建立 HttpServer 之前設；別人已經設了就照別人的）
        System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "15");
        System.getProperties().putIfAbsent("sun.net.httpserver.maxRspTime", "120");
        final InetSocketAddress address = bind == null || bind.isBlank() ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
        final HttpServer http = HttpServer.create(address, 64);
        // 最多 32 條執行緒同時處理（慢的連線各占一條，不會卡住別人）；全滿時新的連線直接關掉，不會無限開執行緒
        final AtomicInteger count = new AtomicInteger();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, MAX_THREADS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            final Thread thread = new Thread(runnable, "ExileCore-web-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        final WebServer web = new WebServer(http, executor, logger);
        http.createContext("/", web::serve);
        http.setExecutor(executor);
        http.start();
        return web;
    }

    /** 完全符合這個路徑。 */
    public void route(final String path, final Handler handler) {
        exact.put(path, handler);
    }

    /** 以這段開頭的路徑（例如 "/pack/"）。 */
    public void prefix(final String prefix, final Handler handler) {
        prefixes.put(prefix, handler);
    }

    /**
     * 完全符合這個路徑、看得到整個請求的處理器（管理網頁）。methods 是收哪些方法（"GET"、"POST"）；收 GET 就也收 HEAD。
     * 同一個路徑先找這裡，再找 {@link #route}。
     */
    public void endpoint(final String path, final RequestHandler handler, final String... methods) {
        final Set<String> allowed = new java.util.LinkedHashSet<>();
        for (final String method : methods) {
            allowed.add(method.toUpperCase(Locale.ROOT));
        }
        if (allowed.isEmpty()) {
            allowed.add("GET");
        }
        endpoints.put(path, new Endpoint(Set.copyOf(allowed), handler));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            final boolean head = method.equals("HEAD");
            final URI uri = exchange.getRequestURI();
            final String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            final Endpoint endpoint = path.contains("..") ? null : endpoints.get(path);
            Response response;
            if (endpoint != null) {
                try {
                    response = handle(exchange, method, path, uri, endpoint);
                } catch (final RuntimeException e) {
                    logger.warn("網頁伺服器處理 {} {} 時出錯", method, path, e);
                    response = Response.text(500, "伺服器出錯了");
                }
            } else if (!head && !method.equals("GET")) {
                response = Response.text(405, "只支援 GET");
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            } else {
                try {
                    response = dispatch(uri);
                } catch (final RuntimeException e) {
                    logger.warn("網頁伺服器處理 {} 時出錯", uri, e);
                    response = Response.text(500, "伺服器出錯了");
                }
            }
            if (response == null) {
                response = Response.text(404, "找不到這個頁面");
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            for (final Map.Entry<String, String> header : response.headers().entrySet()) {
                exchange.getResponseHeaders().set(header.getKey(), header.getValue());
            }
            if (head) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            if (response.body().length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body());
                }
            }
        }
    }

    /** {@link #endpoint} 的請求：檢查方法、讀內容（太大回 413）、交給處理器。 */
    private Response handle(final HttpExchange exchange, final String method, final String path, final URI uri, final Endpoint endpoint)
            throws IOException {
        final boolean allowed = endpoint.methods().contains(method) || method.equals("HEAD") && endpoint.methods().contains("GET");
        if (!allowed) {
            final List<String> methods = new ArrayList<>(endpoint.methods());
            if (methods.contains("GET")) {
                methods.add("HEAD");
            }
            return Response.text(405, "不支援 " + method).with(Map.of("Allow", String.join(", ", methods)));
        }
        byte[] body = new byte[0];
        if (!method.equals("GET") && !method.equals("HEAD")) {
            final String length = exchange.getRequestHeaders().getFirst("Content-Length");
            if (length != null) {
                try {
                    if (Long.parseLong(length.trim()) > MAX_BODY) {
                        return Response.text(413, "內容太大");
                    }
                } catch (final NumberFormatException e) {
                    return Response.text(400, "Content-Length 不對");
                }
            }
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                return Response.text(413, "內容太大");
            }
        }
        final Map<String, String> headers = new HashMap<>();
        for (final Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                headers.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getFirst());
            }
        }
        final InetSocketAddress remote = exchange.getRemoteAddress();
        final String ip = remote == null || remote.getAddress() == null ? "" : remote.getAddress().getHostAddress();
        return endpoint.handler().handle(new Request(method, path, uri.getRawQuery(), Map.copyOf(headers), body, ip));
    }

    private Response dispatch(final URI uri) {
        final String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        if (path.contains("..")) {
            return Response.text(400, "路徑不合法");
        }
        final Handler handler = exact.get(path);
        if (handler != null) {
            return handler.handle(path);
        }
        String best = null;
        for (final String prefix : prefixes.keySet()) {
            if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? null : prefixes.get(best).handle(path);
    }
}
