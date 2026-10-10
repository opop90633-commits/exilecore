package tw.exilecore.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Discord REST（2026-10）：帶機器人 Token 的請求，遇到頻率限制照 Discord 說的等再試。都是阻塞呼叫，要在背景執行緒用。 */
final class DiscordRest {

    static final String API = "https://discord.com/api/v10";
    private static final String AGENT = "DiscordBot (https://github.com/exilecore, 1.0)";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Logger logger;
    private volatile String token = "";

    DiscordRest(final Logger logger) {
        this.logger = logger;
    }

    void token(final String token) {
        this.token = token == null ? "" : token;
    }

    boolean hasToken() {
        return !token.isEmpty();
    }

    /** 回傳 (狀態碼, 內容)；連不上回傳 null。 */
    Result call(final String method, final String path, final JsonElement body) {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : API + path))
                .header("User-Agent", AGENT).timeout(Duration.ofSeconds(15));
        if (!token.isEmpty() && !path.startsWith("http")) {
            request.header("Authorization", "Bot " + token); // 完整網址（Webhook）不帶 Token
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return send(request, method + " " + path);
    }

    /** 上傳一個文字檔（對話紀錄）加一則訊息。 */
    Result upload(final String channelId, final JsonObject payload, final String fileName, final byte[] file) {
        final String boundary = "----exilecore" + UUID.randomUUID().toString().replace("-", "");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"
                + payload + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId + "/messages"))
                .header("User-Agent", AGENT).header("Authorization", "Bot " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        return send(request, "upload " + channelId);
    }

    private Result send(final HttpRequest.Builder request, final String what) {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    sleep(retryAfter(response.body()));
                    continue;
                }
                if (response.statusCode() >= 400) {
                    logger.warn("[Discord] {} 失敗（HTTP {}）：{}", what.replaceAll("/webhooks/\\d+/[^/ ]+", "/webhooks/…")
                            .replaceAll("/interactions/\\d+/[^/ ]+", "/interactions/…"), response.statusCode(), shorten(response.body()));
                }
                return new Result(response.statusCode(), response.body());
            } catch (final java.io.IOException e) {
                if (attempt == 3) {
                    logger.warn("[Discord] 連不上 Discord：{}", e.toString());
                }
                sleep(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    record Result(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        JsonElement json() {
            try {
                return JsonParser.parseString(body);
            } catch (final RuntimeException e) {
                return new JsonObject();
            }
        }
    }

    static long retryAfter(final String body) {
        try {
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return Math.max(200, Math.min(15_000, (long) (json.get("retry_after").getAsDouble() * 1000)));
        } catch (final RuntimeException e) {
            return 2000;
        }
    }

    static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shorten(final String body) {
        final String text = body == null ? "" : body.replaceAll("\\s+", " ");
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
