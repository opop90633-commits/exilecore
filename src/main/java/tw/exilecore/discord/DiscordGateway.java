package tw.exilecore.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Discord Gateway（2026-10）：機器人一直連著 Discord，即時收到訊息（聊天同步）與按鈕、表單（客服單）。
 * 用 JDK 內建的 WebSocket；斷線自動重連（越斷越久再試，最多 60 秒）；Token 錯或沒開 Message Content Intent 就停下來並說明。
 */
final class DiscordGateway {

    private static final String URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    /** GUILDS | GUILD_MESSAGES | MESSAGE_CONTENT。 */
    private static final int INTENTS = 1 | (1 << 9) | (1 << 15);
    /** GUILD_MEMBERS（2026-10，特權：開發者平台要打開 SERVER MEMBERS INTENT）：加成開始、結束即時收到。 */
    private static final int MEMBERS = 1 << 1;
    /** 有沒有要 GUILD_MEMBERS（沒開被 Discord 拒絕就拿掉重連，其他功能照常）。 */
    private volatile boolean members = true;

    private final Logger logger;
    private final String token;
    private final BiConsumer<String, JsonObject> dispatch;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ExileCore-discord-gateway");
        thread.setDaemon(true);
        return thread;
    });

    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile Integer sequence;
    private volatile boolean acked = true;
    private volatile boolean stopped;
    private volatile int failures;
    /** 第幾次連線：舊連線晚到的事件（斷線、錯誤）不理，免得重連兩次。 */
    private volatile int generation;
    private volatile boolean reconnecting;
    /** 機器人自己的使用者 ID（READY 之後才有）。 */
    volatile String selfId;
    volatile String applicationId;

    DiscordGateway(final Logger logger, final String token, final BiConsumer<String, JsonObject> dispatch) {
        this.logger = logger;
        this.token = token;
        this.dispatch = dispatch;
    }

    void start() {
        connect();
    }

    void stop() {
        stopped = true;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        final WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            current.abort();
        }
        timer.shutdownNow();
    }

    boolean connected() {
        return socket != null && selfId != null;
    }

    private void connect() {
        if (stopped) {
            return;
        }
        sequence = null;
        acked = true;
        reconnecting = false;
        final int gen = ++generation;
        try {
            timer.schedule(() -> {
                if (!stopped && gen == generation && selfId == null) {
                    logger.warn("[Discord] 連上 Gateway 30 秒了還沒登入（READY）：檢查 bot-token、機器人的 Intents（Message Content）");
                }
            }, 30, TimeUnit.SECONDS);
        } catch (final java.util.concurrent.RejectedExecutionException ignored) {
            // 關了
        }
        http.newWebSocketBuilder().buildAsync(URI.create(URL), new Listener(gen)).whenComplete((ws, error) -> {
            if (error != null) {
                logger.warn("[Discord] 連不上 Gateway：{}", error.toString());
                retry(gen);
            } else if (gen == generation) {
                socket = ws;
            }
        });
    }

    private void retry(final int gen) {
        if (stopped || gen != generation || reconnecting) {
            return;
        }
        reconnecting = true;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        socket = null;
        selfId = null;
        final long delay = Math.min(60, 2L << Math.min(5, failures++));
        try {
            timer.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (final java.util.concurrent.RejectedExecutionException ignored) {
            // 關了
        }
    }

    /** 2026-10-18：上一筆送出的進度（WebSocket 同一時間只能送一筆：心跳與 IDENTIFY 同時送，第二筆會被拒絕）。 */
    private java.util.concurrent.CompletableFuture<WebSocket> sending = java.util.concurrent.CompletableFuture.completedFuture(null);

    private synchronized void send(final JsonObject payload) {
        final WebSocket current = socket;
        if (current != null) {
            final String text = payload.toString();
            // 2026-10-18：接在上一筆後面依序送（上一筆失敗也照送這一筆）
            sending = sending.exceptionally(error -> null).thenCompose(ignored -> current.sendText(text, true));
        }
    }

    private void identify() {
        final JsonObject properties = new JsonObject();
        properties.addProperty("os", "linux");
        properties.addProperty("browser", "exilecore");
        properties.addProperty("device", "exilecore");
        final JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("intents", INTENTS | (members ? MEMBERS : 0));
        data.add("properties", properties);
        final JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);
        payload.add("d", data);
        send(payload);
    }

    private void beat() {
        if (!acked) {
            logger.warn("[Discord] Gateway 沒有回應心跳，重新連線");
            final WebSocket current = socket;
            if (current != null) {
                current.abort();
            }
            retry(generation);
            return;
        }
        acked = false;
        final JsonObject payload = new JsonObject();
        payload.addProperty("op", 1);
        payload.add("d", sequence == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(sequence));
        send(payload);
    }

    private void handle(final String text) {
        final JsonObject message = JsonParser.parseString(text).getAsJsonObject();
        final int op = message.get("op").getAsInt();
        switch (op) {
            case 10 -> { // HELLO
                final long interval = message.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                heartbeat = timer.scheduleAtFixedRate(this::beat, (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                identify();
            }
            case 11 -> acked = true; // HEARTBEAT ACK
            case 1 -> beat(); // Discord 要求立刻心跳
            case 7, 9 -> { // RECONNECT、INVALID SESSION
                final WebSocket current = socket;
                if (current != null) {
                    current.abort();
                }
                retry(generation);
            }
            case 0 -> {
                if (message.has("s") && !message.get("s").isJsonNull()) {
                    sequence = message.get("s").getAsInt();
                }
                final String type = message.get("t").getAsString();
                final JsonElement data = message.get("d");
                if ("READY".equals(type) && data.isJsonObject()) {
                    selfId = data.getAsJsonObject().getAsJsonObject("user").get("id").getAsString();
                    if (data.getAsJsonObject().has("application")) {
                        applicationId = data.getAsJsonObject().getAsJsonObject("application").get("id").getAsString();
                    }
                    failures = 0;
                    logger.info("[Discord] Gateway 連上了（{}）", data.getAsJsonObject().getAsJsonObject("user").get("username").getAsString());
                }
                if (data != null && data.isJsonObject()) {
                    try {
                        dispatch.accept(type, data.getAsJsonObject());
                    } catch (final RuntimeException e) {
                        logger.warn("[Discord] 處理 {} 出錯", type, e);
                    }
                }
            }
            default -> {
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final int gen;

        Listener(final int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(final WebSocket webSocket) {
            // 2026-10 修正：HELLO 可能比 buildAsync 的 whenComplete 先到；那時 socket 還是 null，IDENTIFY 送不出去，
            // 機器人連著卻一直沒登入（Discord 上顯示離線、也沒有錯誤訊息）。連上就先記住
            if (gen == generation) {
                socket = webSocket;
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
            buffer.append(data);
            if (last) {
                final String text = buffer.toString();
                buffer.setLength(0);
                if (gen != generation) {
                    return null; // 舊連線
                }
                try {
                    handle(text);
                } catch (final RuntimeException e) {
                    logger.warn("[Discord] Gateway 訊息看不懂：{}", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
            if (statusCode == 4004) {
                stopped = true;
                logger.error("[Discord] 機器人 Token 不對（Gateway 4004）：檢查 config.yml 的 discord.bot-token，改好之後 /ec reload");
            } else if (statusCode == 4014 && members) {
                // 2026-10：多半是沒開 SERVER MEMBERS INTENT：拿掉重連（Discord 加成改成登入時才確認），聊天、客服單照常
                members = false;
                logger.warn("[Discord] 機器人沒開 SERVER MEMBERS INTENT（Gateway 4014）：Discord 加成改成玩家登入時才確認；"
                        + "要即時生效到 Discord 開發者平台的 Bot 頁打開，再 /ec reload");
            } else if (statusCode == 4014) {
                stopped = true;
                logger.error("[Discord] 機器人沒開 MESSAGE CONTENT INTENT（Gateway 4014）：到 Discord 開發者平台的 Bot 頁打開，再 /ec reload");
            } else if (!stopped && gen == generation) {
                logger.info("[Discord] Gateway 斷線（{}），等一下重連", statusCode);
            }
            retry(gen);
            return null;
        }

        @Override
        public void onError(final WebSocket webSocket, final Throwable error) {
            if (!stopped && gen == generation) {
                logger.warn("[Discord] Gateway 錯誤：{}", error.toString());
            }
            retry(gen);
        }
    }
}
