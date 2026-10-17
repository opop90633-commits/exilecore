package tw.exilecore.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.core.db.Database;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.event.EventConfig;
import tw.exilecore.flask.FlaskBase;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapInstance;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.web.WebServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 管理網頁的網址（在網頁伺服器的執行緒上跑；要動遊戲裡的東西時排回主執行緒等結果，資料庫的排進資料庫執行緒）：
 * <pre>
 * GET  /admin                  頁面（沒登入時頁面自己說怎麼登入）
 * GET  /admin/login?t=代碼      一次性連結換成登入（Cookie），轉回 /admin
 * POST /admin/password-login   密碼登入（2026-10）：{ password }，成功給 Cookie；要帶 X-EC-Login 標頭，猜錯有次數限制
 * POST /admin/logout           登出
 * GET  /admin/api/state        登入的人、線上玩家、可以發的東西、設定、維護模式
 * GET  /admin/api/deliveries   發放紀錄（status=pending|claimed|cancelled|all，q=玩家或批次）
 * GET  /admin/api/search?q=    找玩家（帳號名稱或角色名稱，含離線的）
 * GET  /admin/api/log          操作紀錄
 * POST /admin/api/deliver      發放
 * POST /admin/api/cancel       取消還沒領的（ids 或 batch）
 * POST /admin/api/setting      改設定、恢復
 * POST /admin/api/maintenance  維護模式
 * POST /admin/api/restart      重新啟動：{ action: schedule, minutes, reason }、{ action: shutdown, minutes, reason }（倒數關服）、
 *                              { action: cancel }、{ action: daily, times: ["05:30"] }
 * POST /admin/api/broadcast    全服公告
 * </pre>
 * 每個 API 都要登入；POST 另外要帶 X-EC-CSRF 標頭（登入時給的代碼）。回應一律不快取、不能被別的網站嵌入。
 */
final class AdminWeb {

    static final String COOKIE = "ec_admin";
    static final String CSRF_HEADER = "X-EC-CSRF";
    /** 等主執行緒、資料庫最久幾秒。 */
    private static final long WAIT_SECONDS = 15;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Map<String, String> SECURITY = Map.of(
            "Cache-Control", "no-store",
            "X-Frame-Options", "DENY",
            "Referrer-Policy", "no-referrer",
            "Cross-Origin-Resource-Policy", "same-origin",
            "Cross-Origin-Opener-Policy", "same-origin");

    private final ExileCore plugin;
    private final AdminModule module;
    private final SecureRandom random = new SecureRandom();
    private final byte[] page;
    /** 資料檔的內容（網頁顯示「檔案裡寫的」）：檔案 → 修改時間與內容。 */
    private final Map<Path, Cached> files = new ConcurrentHashMap<>();

    private record Cached(long modified, YamlNode node) {
    }

    /** API 的一段工作。 */
    @FunctionalInterface
    private interface Call {
        Object run(AdminAuth.Session session) throws Exception;
    }

    AdminWeb(final ExileCore plugin, final AdminModule module) throws IOException {
        this.plugin = plugin;
        this.module = module;
        try (InputStream in = plugin.getResource("web/admin.html")) {
            if (in == null) {
                throw new IOException("插件裡沒有 web/admin.html");
            }
            this.page = in.readAllBytes();
        }
    }

    /** 裝到網頁伺服器上（每次啟動都裝）。 */
    void install(final WebServer server) {
        server.endpoint("/admin", this::page, "GET");
        server.endpoint("/admin/", request -> module.pageEnabled() ? WebServer.Response.redirect("../admin") : null, "GET");
        server.endpoint("/admin/login", this::login, "GET");
        server.endpoint("/admin/password-login", this::passwordLogin, "POST");
        server.endpoint("/admin/logout", this::logout, "POST");
        server.endpoint("/admin/api/state", request -> api(request, false, this::state), "GET");
        server.endpoint("/admin/api/deliveries", request -> api(request, false, session -> deliveries(request)), "GET");
        server.endpoint("/admin/api/search", request -> api(request, false, session -> search(request)), "GET");
        server.endpoint("/admin/api/log", request -> api(request, false, session -> log()), "GET");
        server.endpoint("/admin/api/orders", request -> api(request, false, session -> orders(request)), "GET");
        server.endpoint("/admin/api/affixes", request -> api(request, false, session -> affixes(request)), "GET");
        // 2026-10：網頁角色頁的管理員檢視（不公開的角色也看得到；/builds 頁面登入管理網頁時自動用這個）
        server.endpoint("/admin/api/builds/list", request -> api(request, false, session -> builds(null)), "GET");
        server.endpoint("/admin/api/builds/character", request -> api(request, false, session -> builds(request.param("name"))), "GET");
        server.endpoint("/admin/api/builds/meta", request -> api(request, false, session -> buildsMeta()), "GET");
        // 2026-10：輸出紀錄（每場 Boss、每段假人：每個人打了多少、幾秒）
        server.endpoint("/admin/api/dps", request -> api(request, false, session -> tw.exilecore.combat.DpsRecords.get(plugin)
                .json(parseDays(request.param("days")))), "GET");
        server.endpoint("/admin/api/deliver", request -> api(request, true, session -> deliver(session, body(request))), "POST");
        server.endpoint("/admin/api/cancel", request -> api(request, true, session -> cancel(session, body(request))), "POST");
        server.endpoint("/admin/api/setting", request -> api(request, true, session -> setting(session, body(request))), "POST");
        server.endpoint("/admin/api/maintenance", request -> api(request, true, session -> maintenance(session, body(request))), "POST");
        server.endpoint("/admin/api/broadcast", request -> api(request, true, session -> broadcast(session, body(request))), "POST");
        server.endpoint("/admin/api/restart", request -> api(request, true, session -> restart(session, body(request))), "POST");
        // 2026-10：公會管理、玩家處置（禁言、踢出）、物品追查
        server.endpoint("/admin/api/guilds", request -> api(request, false, session -> guilds()), "GET");
        server.endpoint("/admin/api/guild", request -> api(request, false, session -> guildDetail(request)), "GET");
        server.endpoint("/admin/api/guild/action", request -> api(request, true, session -> guildAction(session, body(request))), "POST");
        server.endpoint("/admin/api/player", request -> api(request, true, session -> playerAction(session, body(request))), "POST");
        server.endpoint("/admin/api/trace", request -> api(request, false, session -> trace(session, request)), "GET");
        // 2026-10-17：帳號轉移（A 的整個帳號覆蓋到 B；排進下次開機時做，見 AccountTransfer）
        server.endpoint("/admin/api/transfer", request -> api(request, false, session -> transferList()), "GET");
        server.endpoint("/admin/api/transfer/preview", request -> api(request, false, session -> transferPreview(session, request)), "GET");
        server.endpoint("/admin/api/transfer/queue", request -> api(request, true, session -> transferQueue(session, body(request))), "POST");
        server.endpoint("/admin/api/transfer/cancel", request -> api(request, true, session -> transferCancel(session, body(request))), "POST");
    }

    // ---- 頁面與登入 ----

    private WebServer.Response page(final WebServer.Request request) {
        if (!module.pageEnabled()) {
            return null;
        }
        final byte[] nonceBytes = new byte[18];
        random.nextBytes(nonceBytes);
        final String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        final byte[] html = new String(page, StandardCharsets.UTF_8).replace("__NONCE__", nonce).getBytes(StandardCharsets.UTF_8);
        final Map<String, String> headers = new LinkedHashMap<>(SECURITY);
        headers.put("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce
                + "'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        return new WebServer.Response(200, "text/html; charset=utf-8", html, headers);
    }

    private WebServer.Response login(final WebServer.Request request) {
        if (!module.pageEnabled()) {
            return null;
        }
        if ("HEAD".equals(request.method())) {
            return html(200, "星骸 管理網頁", "用瀏覽器打開這個連結登入。"); // 預覽、預先抓取不算登入（連結只能用一次）
        }
        final AdminAuth.Session session = module.auth().redeem(request.param("t"));
        if (session == null) {
            return html(403, "連結已失效", "這個登入連結過期了、用過了，或是打錯了。請在遊戲裡打 /ec web 拿一個新的（10 分鐘內有效、只能用一次）。");
        }
        plugin.getSLF4JLogger().info("[管理網頁] {} 登入（{}）", session.admin(), request.remote());
        record(session.admin(), "登入", "IP " + request.remote());
        return WebServer.Response.redirect("../admin").with(merge(SECURITY, Map.of("Set-Cookie",
                cookie(session.id(), (int) AdminAuth.SESSION_LIFETIME.toSeconds()))));
    }

    /** 密碼登入（2026-10）：密碼要先在主控台設（ec web password set）。 */
    private WebServer.Response passwordLogin(final WebServer.Request request) {
        if (!module.pageEnabled()) {
            return null;
        }
        // 自訂標頭：別的網站的表單、跨站的 fetch 帶不出來（會先被瀏覽器的預檢擋下）
        if (!"1".equals(request.header("x-ec-login")) || "cross-site".equalsIgnoreCase(request.header("sec-fetch-site"))) {
            return json(403, Map.of("error", "不接受從別的網站送來的請求"));
        }
        final AdminPassword password = module.password();
        if (password == null || !password.enabled()) {
            return json(403, Map.of("error", "沒有設密碼：請在伺服器主控台打 ec web password set <密碼>，或在遊戲裡打 /ec web 拿登入連結"));
        }
        final String text;
        try {
            text = optionalString(body(request), "password");
        } catch (final IllegalArgumentException e) {
            return json(400, Map.of("error", e.getMessage()));
        }
        switch (password.attempt(request.remote(), text)) {
            case OK -> {
                final AdminAuth.Session session = module.auth().open("密碼登入", null);
                plugin.getSLF4JLogger().info("[管理網頁] 密碼登入（{}）", request.remote());
                record(session.admin(), "登入", "IP " + request.remote());
                return json(200, Map.of("ok", true)).with(Map.of("Set-Cookie", cookie(session.id(), (int) AdminAuth.SESSION_LIFETIME.toSeconds())));
            }
            case LOCKED -> {
                return json(429, Map.of("error", "密碼錯太多次，先鎖 " + AdminPassword.LOCK.toMinutes() + " 分鐘；稍後再試，或在遊戲裡打 /ec web 拿登入連結"));
            }
            case NOT_SET -> {
                return json(403, Map.of("error", "沒有設密碼"));
            }
            default -> {
                plugin.getSLF4JLogger().warn("[管理網頁] 密碼登入失敗（{}）", request.remote());
                return json(401, Map.of("error", "密碼不對"));
            }
        }
    }

    private WebServer.Response logout(final WebServer.Request request) {
        final WebServer.Response response = api(request, true, false, session -> {
            module.auth().logout(request.cookie(COOKIE));
            plugin.getSLF4JLogger().info("[管理網頁] {} 登出", session.admin());
            return Map.of("ok", true);
        });
        return response == null ? null : response.with(Map.of("Set-Cookie", cookie("", 0)));
    }

    private String cookie(final String value, final int maxAge) {
        return COOKIE + "=" + value + "; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (module.secureCookie() ? "; Secure" : "");
    }

    private static WebServer.Response html(final int status, final String title, final String message) {
        final String body = "<!doctype html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1\"><title>星骸 管理網頁</title></head><body style=\"font-family:"
                + "'Noto Sans TC','Microsoft JhengHei','PingFang TC',system-ui,sans-serif;padding:32px 16px;max-width:640px;margin:0 auto;"
                + "line-height:1.6\"><h1 style=\"font-size:20px\">" + escape(title) + "</h1><p>" + escape(message) + "</p></body></html>";
        final Map<String, String> headers = new LinkedHashMap<>(SECURITY);
        headers.put("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'");
        return new WebServer.Response(status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), headers);
    }

    // ---- API 的共同做法 ----

    private WebServer.Response api(final WebServer.Request request, final boolean modify, final Call call) {
        return api(request, modify, true, call);
    }

    /**
     * @param modify          改東西的請求（要帶防偽造代碼）
     * @param checkPermission 先確認登入的人還有管理權限（登出不用：主執行緒卡住時也要能登出）
     */
    private WebServer.Response api(final WebServer.Request request, final boolean modify, final boolean checkPermission, final Call call) {
        if (!module.pageEnabled()) {
            return null;
        }
        final AdminAuth.Session session = module.auth().session(request.cookie(COOKIE));
        if (session == null) {
            final boolean passwordOn = module.password() != null && module.password().enabled();
            return json(401, Map.of("error", passwordOn ? "沒有登入或登入過期了：打密碼登入，或在遊戲裡打 /ec web 拿登入連結"
                    : "沒有登入或登入過期了：請在遊戲裡打 /ec web 拿一個新的登入連結", "login", true, "password", passwordOn));
        }
        if (modify) {
            if (!AdminAuth.csrfMatches(session, request.header(CSRF_HEADER))) {
                return json(403, Map.of("error", "請重新整理頁面再試一次（防偽造的代碼不對）"));
            }
            if ("cross-site".equalsIgnoreCase(request.header("sec-fetch-site"))) {
                return json(403, Map.of("error", "不接受從別的網站送來的請求"));
            }
        }
        try {
            if (checkPermission && !stillAdmin(session)) {
                final int revoked = module.auth().revokeAdmin(session.adminId());
                plugin.getSLF4JLogger().info("[管理網頁] {} 已經沒有管理權限：登出 {} 個工作階段", session.admin(), revoked);
                return json(401, Map.of("error", "你已經沒有管理權限了（登入已取消）", "login", true));
            }
            return json(200, call.run(session));
        } catch (final IllegalArgumentException e) {
            return json(400, Map.of("error", e.getMessage() == null ? "資料不對" : e.getMessage()));
        } catch (final TimeoutException e) {
            return json(503, Map.of("error", e.getMessage() != null ? e.getMessage() : "伺服器忙碌中（主執行緒或資料庫沒有回應），稍後再試"));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return json(503, Map.of("error", "伺服器正在關閉"));
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("管理網頁處理 {} 時出錯", request.path(), e);
            return json(500, Map.of("error", "伺服器出錯了（主控台有錯誤訊息）：" + e));
        }
    }

    private static int parseDays(final String value) {
        try {
            return value == null ? 7 : Math.max(0, Math.min(3650, Integer.parseInt(value.trim())));
        } catch (final NumberFormatException e) {
            return 7;
        }
    }

    private Object builds(final String name) throws Exception {
        final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
        if (profiles == null) {
            throw new IllegalArgumentException("網頁角色頁沒有開");
        }
        final byte[] bytes = name == null ? profiles.listJson(true) : profiles.characterJson(name, null, true);
        if (bytes == null) {
            throw new IllegalArgumentException("沒有這個角色（還沒有快照，或已經刪掉）");
        }
        return com.google.gson.JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    }

    private Object buildsMeta() throws Exception {
        final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
        if (profiles == null) {
            throw new IllegalArgumentException("網頁角色頁沒有開");
        }
        return com.google.gson.JsonParser.parseString(new String(profiles.metaJson(), StandardCharsets.UTF_8));
    }

    private static WebServer.Response json(final int status, final Object body) {
        return new WebServer.Response(status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8), SECURITY);
    }

    private static Map<String, String> merge(final Map<String, String> a, final Map<String, String> b) {
        final Map<String, String> merged = new LinkedHashMap<>(a);
        merged.putAll(b);
        return merged;
    }

    private static JsonObject body(final WebServer.Request request) {
        try {
            final JsonElement element = JsonParser.parseString(request.text());
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("要送 JSON 物件");
            }
            return element.getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("JSON 寫錯了");
        }
    }

    /** 排回主執行緒做，等結果。等太久就不做了（還沒開始的取消掉，免得管理員重按之後做兩次）。 */
    private <T> T onMain(final Callable<T> task) throws Exception {
        final java.util.concurrent.Future<T> future = plugin.getServer().getScheduler().callSyncMethod(plugin, task);
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            throw unwrap(e);
        } catch (final TimeoutException | InterruptedException e) {
            future.cancel(false);
            throw e;
        }
    }

    /** 每個請求先確認：登入的人在線的話，還要有管理權限（離線的查不到權限外掛給的權限，照登入時的算；/ec web logout 全部登出）。 */
    private boolean stillAdmin(final AdminAuth.Session session) throws Exception {
        if (session.adminId() == null) {
            return true; // 主控台
        }
        return onMain(() -> {
            final Player player = plugin.getServer().getPlayer(session.adminId());
            return player == null || player.hasPermission(ExileCore.PERMISSION_ADMIN);
        });
    }

    /** 排進資料庫執行緒做，等結果。 */
    private <T> T onDatabase(final Database.SqlWork<T> work) throws Exception {
        return wait(plugin.database().async(work));
    }

    private static <T> T wait(final CompletableFuture<T> future) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(final ExecutionException e) {
        final Throwable cause = e.getCause();
        return cause instanceof Exception exception ? exception : e;
    }

    /** 記一筆操作（不等）。 */
    private void record(final String admin, final String action, final String detail) {
        plugin.database().async(connection -> {
            module.repository().log(connection, admin, action, detail);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("管理網頁的操作紀錄寫不進去：{}", error.toString());
            }
        });
    }

    // ---- 狀態 ----

    private Object state(final AdminAuth.Session session) throws Exception {
        final Map<String, Object> state = onMain(this::mainState);
        state.put("me", session.admin());
        state.put("csrf", session.csrf());
        state.put("expires", session.expires().toEpochMilli());
        state.put("accounts", onDatabase(connection -> module.repository().countAccounts(connection)));
        state.put("settings", settings(castChoices(state.remove("themes"))));
        state.put("overridesProblem", plugin.overrides().problem());
        state.put("overridesFile", plugin.getDataFolder().getPath().replace('\\', '/') + "/" + AdminOverrides.FILE);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castChoices(final Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 主執行緒：線上玩家、可以發的東西、活動主題、伺服器狀態。 */
    private Map<String, Object> mainState() {
        final Map<String, Object> state = new LinkedHashMap<>();
        final List<Map<String, Object>> players = new ArrayList<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState ps = plugin.players().get(player);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", player.getUniqueId().toString());
            row.put("name", player.getName());
            row.put("character", ps.hasCharacter() ? ps.character().name() : null);
            row.put("class", ps.hasCharacter() ? ps.character().characterClass().displayName() : null);
            row.put("level", ps.hasCharacter() ? ps.level() : 0);
            row.put("where", where(player));
            row.put("admin", player.hasPermission(ExileCore.PERMISSION_ADMIN));
            row.put("muted", plugin.chat() != null && plugin.chat().moderation().muted(player.getUniqueId())); // 2026-10：網頁上可以禁言、解除
            players.add(row);
        }
        state.put("players", players);
        final Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", players.size());
        server.put("max", plugin.getServer().getMaxPlayers());
        server.put("tps", Math.round(plugin.getServer().getTPS()[0] * 10) / 10.0);
        server.put("version", plugin.getPluginMeta().getVersion());
        final Map<String, Object> maintenance = new LinkedHashMap<>();
        maintenance.put("enabled", plugin.overrides().maintenance());
        maintenance.put("message", plugin.overrides().maintenanceMessage());
        server.put("maintenance", maintenance);
        final Map<String, Object> restart = new LinkedHashMap<>();
        final RestartScheduler restarts = module.restarts();
        final RestartScheduler.Pending pending = restarts == null ? null : restarts.pending();
        restart.put("at", pending == null ? null : pending.at());
        restart.put("reason", pending == null ? "" : pending.reason());
        restart.put("by", pending == null ? "" : pending.by());
        restart.put("shutdown", pending != null && pending.shutdown());
        restart.put("panel", restarts != null && restarts.panelConfigured());
        restart.put("daily", restarts == null ? List.of() : restarts.dailyTimes());
        restart.put("warning", RestartScheduler.DAILY_WARNING_MINUTES);
        server.put("restart", restart);
        server.put("currentTheme", plugin.events() == null || plugin.events().theme() == null ? null : plugin.events().theme().name());
        state.put("server", server);
        state.put("catalog", catalog());
        final List<Map<String, Object>> themes = new ArrayList<>();
        themes.add(choice("", "照月份輪替"));
        if (plugin.events() != null) {
            for (final EventConfig.Theme theme : plugin.events().config().themes()) {
                themes.add(choice(theme.id(), theme.name()));
            }
        }
        state.put("themes", themes);
        return state;
    }

    private String where(final Player player) {
        final Location location = player.getLocation();
        if (plugin.pvp() != null && plugin.pvp().inArena(player)) {
            return "競技場";
        }
        if (plugin.maps() != null) {
            final MapInstance instance = plugin.maps().instanceAt(location);
            if (instance != null && instance.map() != null) {
                return "地圖 " + instance.map().name() + " T" + instance.stats().tier();
            }
        }
        if (plugin.zones() != null) {
            final String zone = plugin.zones().zoneAt(location).map(tw.exilecore.zone.Zone::name).orElse(null);
            if (zone != null) {
                return zone;
            }
        }
        return location.getWorld() == null ? "" : location.getWorld().getName();
    }

    private static Map<String, Object> choice(final String id, final String name) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        return map;
    }

    /** 指定詞綴用（2026-10）：?base=基底 → 這個基底能出的詞綴；?unique=傳奇 → 這件傳奇的詞綴與範圍。 */
    private Object affixes(final WebServer.Request request) throws Exception {
        final String baseId = request.param("base");
        final String uniqueId = request.param("unique");
        return onMain(() -> {
            final Map<String, Object> out = new LinkedHashMap<>();
            if (uniqueId != null && !uniqueId.isBlank()) {
                final UniqueItem unique = plugin.items().repository().unique(uniqueId.trim());
                if (unique == null) {
                    throw new IllegalArgumentException("沒有這件傳奇：" + uniqueId);
                }
                out.put("affixes", AffixSpec.catalogFor(plugin.items().repository(), unique));
                return out;
            }
            final ItemBase base = baseId == null ? null : plugin.items().repository().base(baseId.trim());
            if (base == null || base.slot() == ItemSlot.MAP) {
                throw new IllegalArgumentException("沒有這種裝備基底：" + baseId);
            }
            out.put("affixes", AffixSpec.catalogFor(plugin.items().repository(), base));
            out.put("jewel", base.isJewel());
            return out;
        });
    }

    /** 可以發的東西（主執行緒：資料檔的內容）。 */
    private Map<String, Object> catalog() {
        final Map<String, Object> catalog = new LinkedHashMap<>();
        final List<Map<String, Object>> currencies = new ArrayList<>();
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            final Map<String, Object> row = choice(type.id(), type.name());
            row.put("stack", type.stack());
            currencies.add(row);
        }
        catalog.put("currencies", currencies);
        final List<Map<String, Object>> bases = new ArrayList<>();
        for (final ItemBase base : plugin.items().repository().bases()) {
            if (base.slot() == ItemSlot.MAP) {
                continue;
            }
            final Map<String, Object> row = choice(base.id(), base.name());
            row.put("slot", base.slot().displayName());
            row.put("level", base.dropLevel());
            bases.add(row);
        }
        catalog.put("bases", bases);
        final List<Map<String, Object>> uniques = new ArrayList<>();
        for (final UniqueItem unique : plugin.items().repository().uniques()) {
            final ItemBase base = plugin.items().repository().base(unique.base());
            if (base == null) {
                continue;
            }
            final Map<String, Object> row = choice(unique.id(), unique.name());
            row.put("base", base.name());
            row.put("slot", base.slot().displayName());
            row.put("level", unique.dropLevel());
            row.put("chase", unique.chase());
            uniques.add(row);
        }
        catalog.put("uniques", uniques);
        final List<Map<String, Object>> skills = new ArrayList<>();
        final List<Map<String, Object>> supports = new ArrayList<>();
        if (plugin.skills() != null) {
            for (final SkillDefinition skill : plugin.skills().config().all()) {
                if (!skill.hidden()) {
                    skills.add(choice(skill.id(), skill.name()));
                }
            }
            for (final SupportDefinition support : plugin.skills().config().supports()) {
                final Map<String, Object> row = choice(support.id(), support.name());
                row.put("tier", support.tier());
                supports.add(row);
            }
        }
        catalog.put("skills", skills);
        catalog.put("supports", supports);
        catalog.put("maxGemLevel", SkillLoadout.MAX_LEVEL);
        catalog.put("maxSupportTier", SupportDefinition.MAX_TIER);
        final List<Map<String, Object>> flasks = new ArrayList<>();
        if (plugin.flasks() != null) {
            for (final FlaskBase base : plugin.flasks().config().bases()) {
                final Map<String, Object> row = choice(base.id(), base.name());
                row.put("level", base.level());
                flasks.add(row);
            }
        }
        catalog.put("flasks", flasks);
        final List<Map<String, Object>> maps = new ArrayList<>();
        int maxTier = 0;
        if (plugin.maps() != null) {
            final MapConfig config = plugin.maps().config();
            maxTier = config.tiers().max();
            for (final MapConfig.MapDef map : config.maps()) {
                maps.add(choice(map.id(), map.name()));
            }
        }
        catalog.put("maps", maps);
        catalog.put("maxMapTier", maxTier);
        final List<Map<String, Object>> rarities = new ArrayList<>();
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
            rarities.add(choice(rarity.name().toLowerCase(Locale.ROOT), rarity.displayName()));
        }
        catalog.put("rarities", rarities);
        final Map<String, Object> limits = new LinkedHashMap<>();
        for (final String kind : List.of(DeliveryService.CURRENCY, DeliveryService.GOLD, DeliveryService.MERITS, DeliveryService.SPONSOR, DeliveryService.COSMETIC, DeliveryService.REFUND,
                DeliveryService.ITEM, DeliveryService.UNIQUE, DeliveryService.GEM, DeliveryService.FLASK, DeliveryService.MAP,
                DeliveryService.TAKE_SPONSOR, DeliveryService.TAKE_CURRENCY, DeliveryService.TAKE_GOLD, DeliveryService.TAKE_ITEM)) {
            limits.put(kind, DeliveryService.maxAmount(kind));
        }
        catalog.put("limits", limits);
        catalog.put("hasMerits", plugin.bounties() != null);
        catalog.put("hasSponsor", plugin.sponsors() != null);
        // 2026-10：時裝（整組的排前面；限定的標出來源）
        final List<Map<String, Object>> cosmetics = new ArrayList<>();
        if (plugin.sponsors() != null) {
            for (final tw.exilecore.sponsor.SponsorConfig.Bundle bundle : plugin.sponsors().config().bundles().values()) {
                cosmetics.add(choice("bundle:" + bundle.id(), "【整組】" + bundle.name() + "（" + bundle.cosmetics().size() + " 件）"));
            }
            for (final tw.exilecore.sponsor.SponsorConfig.LimitedTitle title : plugin.sponsors().config().limitedTitles().values()) {
                final Map<String, Object> row = choice("title:" + title.id(), "★ 稱號「" + title.name() + "」（" + title.limitedTag() + "）");
                row.put("limited", true);
                cosmetics.add(row);
            }
            for (final tw.exilecore.sponsor.SponsorConfig.Cosmetic cosmetic : plugin.sponsors().config().cosmetics().values()) {
                final Map<String, Object> row = choice(cosmetic.id(), (cosmetic.isLimited() ? "★ " : "") + cosmetic.name()
                        + "（" + cosmetic.rarity().displayName() + (cosmetic.isLimited() ? "・" + cosmetic.limitedTag() : "") + "）");
                row.put("limited", cosmetic.isLimited());
                cosmetics.add(row);
            }
        }
        catalog.put("cosmetics", cosmetics);
        return catalog;
    }

    /** 設定（網頁伺服器的執行緒：讀資料檔、覆蓋的快照都不用主執行緒）。 */
    private List<Map<String, Object>> settings(final List<Map<String, Object>> themes) {
        final List<Map<String, Object>> list = new ArrayList<>();
        final List<String> themeIds = new ArrayList<>();
        for (final Map<String, Object> theme : themes) {
            themeIds.add(String.valueOf(theme.get("id")));
        }
        for (final AdminSettings.Setting setting : AdminSettings.ALL) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", setting.id());
            row.put("group", setting.group().name());
            row.put("groupLabel", setting.group().label());
            row.put("label", setting.label());
            row.put("help", setting.help());
            row.put("kind", setting.kind().name());
            row.put("min", setting.min());
            row.put("max", setting.max());
            row.put("step", setting.step());
            row.put("unit", setting.unit());
            row.put("file", setting.config() ? AdminSettings.CONFIG_FILE : "data/" + setting.file());
            row.put("key", setting.key());
            final Object fileValue = fileValue(setting);
            final Object override = setting.config() ? plugin.overrides().config().get(setting.key())
                    : plugin.overrides().data(setting.file(), setting.key());
            row.put("fileValue", setting.display(fileValue));
            row.put("override", setting.display(override));
            row.put("default", setting.display(setting.fallback()));
            row.put("value", setting.display(override != null ? override : fileValue != null ? fileValue : setting.fallback()));
            if (setting.kind() == AdminSettings.Kind.CHOICE) {
                row.put("choices", themes);
                if (!themeIds.contains(String.valueOf(row.get("value")))) {
                    row.put("value", "");
                }
            }
            list.add(row);
        }
        return list;
    }

    /** 檔案裡寫的值（沒寫、讀不到是 null）。 */
    private Object fileValue(final AdminSettings.Setting setting) {
        final Path path = setting.config() ? plugin.dataFolder().resolve(AdminSettings.CONFIG_FILE)
                : plugin.dataFolder().resolve("data").resolve(setting.file());
        try {
            if (!Files.exists(path)) {
                return null;
            }
            final long modified = Files.getLastModifiedTime(path).toMillis();
            Cached cached = files.get(path);
            if (cached == null || cached.modified() != modified) {
                cached = new Cached(modified, YamlNode.loadRaw(path));
                files.put(path, cached);
            }
            return cached.node().lookup(setting.key());
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- 發放 ----

    private Object deliver(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final JsonObject target = object(body, "target");
        final String mode = string(target, "mode");
        final List<UUID> uuids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        switch (mode) {
            case "players" -> {
                for (final JsonElement element : array(target, "uuids")) {
                    try {
                        uuids.add(UUID.fromString(element.getAsString()));
                    } catch (final IllegalArgumentException | UnsupportedOperationException | IllegalStateException e) {
                        throw new IllegalArgumentException("玩家的 UUID 不對");
                    }
                }
                if (uuids.isEmpty()) {
                    throw new IllegalArgumentException("還沒選要發給誰");
                }
            }
            case "names" -> {
                for (final JsonElement element : array(target, "names")) {
                    final String name = element.isJsonPrimitive() ? element.getAsString().trim() : "";
                    if (!name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                }
                if (names.isEmpty()) {
                    throw new IllegalArgumentException("還沒填要發給誰");
                }
            }
            case "online", "all" -> {
            }
            default -> throw new IllegalArgumentException("不認得的對象：" + mode);
        }
        if (uuids.size() + names.size() > 500) {
            throw new IllegalArgumentException("一次最多指定 500 個人（要發給更多人請選「所有帳號」）");
        }
        final List<DeliveryService.Spec> specs = new ArrayList<>();
        for (final JsonElement element : array(body, "items")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("要發的東西寫錯了");
            }
            final JsonObject item = element.getAsJsonObject();
            specs.add(new DeliveryService.Spec(string(item, "kind"), optionalString(item, "ref"), optionalString(item, "rarity"),
                    (int) number(item, "level", 0), number(item, "amount", 1), optionalString(item, "affixes")));
        }
        final String note = optionalString(body, "note") == null ? "" : optionalString(body, "note").trim();
        if (note.length() > 100) {
            throw new IllegalArgumentException("備註最多 100 個字");
        }
        // 這次發放的代號（網頁按「發放」時產生）：逾時後重送同一個請求不會發兩次
        final String requestId = optionalString(body, "requestId");
        final String batchId;
        if (requestId == null || requestId.isBlank()) {
            batchId = UUID.randomUUID().toString();
        } else {
            try {
                batchId = UUID.fromString(requestId.trim()).toString();
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("requestId 不對");
            }
        }
        final DeliveryService deliveries = module.deliveries();
        // 重送（上一次逾時）：先看這一批寫過了沒有——寫過的話不管現在的檢查（例如那時候在線的人都下線了）都算成功，再推給在線的人一次
        final List<UUID> already = onDatabase(connection -> module.repository().batchAccounts(connection, batchId));
        if (already != null) {
            final int[] onlineAgain = {0};
            onMain(() -> {
                for (final UUID uuid : already) {
                    if (plugin.getServer().getPlayer(uuid) != null) {
                        onlineAgain[0]++;
                    }
                }
                deliveries.push(already);
                return null;
            });
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("repeated", true);
            result.put("batch", batchId);
            result.put("accounts", already.size());
            result.put("rows", 0);
            result.put("online", onlineAgain[0]);
            result.put("labels", List.of());
            return result;
        }
        final Object[] planned = onMain(() -> {
            final List<DeliveryService.Planned> plan = deliveries.plan(specs);
            final List<UUID> online = new ArrayList<>();
            if (mode.equals("online")) {
                for (final Player player : plugin.getServer().getOnlinePlayers()) {
                    online.add(player.getUniqueId());
                }
            }
            return new Object[] {plan, online};
        });
        @SuppressWarnings("unchecked") final List<DeliveryService.Planned> plan = (List<DeliveryService.Planned>) planned[0];
        @SuppressWarnings("unchecked") final List<UUID> online = (List<UUID>) planned[1];
        if (mode.equals("online") && online.isEmpty()) {
            throw new IllegalArgumentException("現在沒有人在線");
        }
        final DeliveryService.Targets targets = new DeliveryService.Targets(mode, uuids, names);
        final DeliveryService.Created created;
        try {
            created = onDatabase(connection -> deliveries.create(connection, batchId, targets, online, plan, note, session.admin()));
        } catch (final TimeoutException e) {
            throw new TimeoutException("資料庫還在寫：先到「發放紀錄」看有沒有發出去。直接再按一次「發放」也不會重複發（同一個請求）");
        }
        if (!created.unknown().isEmpty()) {
            final Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "找不到這些玩家（帳號名稱或角色名稱）：" + String.join("、", created.unknown()) + "——什麼都沒發");
            error.put("unknown", created.unknown());
            throw new IllegalArgumentException(String.valueOf(error.get("error")));
        }
        final int[] onlineNow = {0};
        onMain(() -> {
            for (final UUID uuid : created.accountIds()) {
                if (plugin.getServer().getPlayer(uuid) != null) {
                    onlineNow[0]++;
                }
            }
            deliveries.push(created.accountIds());
            return null;
        });
        final List<String> labels = new ArrayList<>();
        for (final DeliveryService.Planned item : plan) {
            labels.add(item.label());
        }
        if (!created.repeated()) {
            plugin.getSLF4JLogger().info("[管理網頁] {} 發放給 {} 個帳號：{}{}", session.admin(), created.accounts(), String.join("、", labels),
                    note.isEmpty() ? "" : "（備註：" + note + "）");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("repeated", created.repeated());
        result.put("batch", created.batchId());
        result.put("accounts", created.accounts());
        result.put("rows", created.rows());
        result.put("online", onlineNow[0]);
        result.put("labels", labels);
        return result;
    }

    private Object cancel(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final List<Long> ids = new ArrayList<>();
        final String batch = optionalString(body, "batch");
        if (batch != null && !batch.isBlank()) {
            ids.addAll(onDatabase(connection -> module.repository().pendingInBatch(connection, batch.trim())));
        } else {
            for (final JsonElement element : array(body, "ids")) {
                try {
                    ids.add(element.getAsLong());
                } catch (final RuntimeException e) {
                    throw new IllegalArgumentException("編號不對");
                }
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("沒有可以取消的（都領完或取消過了）");
        }
        if (ids.size() > 50_000) {
            throw new IllegalArgumentException("一次取消太多了");
        }
        final CompletableFuture<DeliveryService.Cancelled> future = onMain(() -> module.deliveries().cancel(ids, session.admin()));
        final DeliveryService.Cancelled cancelled = wait(future);
        plugin.getSLF4JLogger().info("[管理網頁] {} 取消了 {} 筆還沒領的物資", session.admin(), cancelled.done().size());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("cancelled", cancelled.done().size());
        result.put("busy", cancelled.busy().size());
        result.put("skipped", ids.size() - cancelled.done().size() - cancelled.busy().size());
        return result;
    }

    private Object deliveries(final WebServer.Request request) throws Exception {
        final String status = request.param("status") == null ? "pending" : request.param("status");
        final String query = request.param("q");
        final List<AdminRepository.Delivery> rows = onDatabase(connection -> module.repository().list(connection, status, query, 300));
        final List<Map<String, Object>> list = new ArrayList<>();
        for (final AdminRepository.Delivery row : rows) {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.id());
            map.put("batch", row.batchId());
            map.put("player", row.playerName());
            map.put("label", row.label());
            map.put("amount", row.amount());
            map.put("original", row.original());
            map.put("note", row.note());
            map.put("by", row.createdBy());
            map.put("at", millis(row.createdAt()));
            map.put("claimedAt", millis(row.claimedAt()));
            map.put("claimedBy", row.claimedBy());
            map.put("cancelledAt", millis(row.cancelledAt()));
            map.put("cancelledBy", row.cancelledBy());
            map.put("status", row.cancelledAt() != null ? "cancelled" : row.claimedAt() != null ? "claimed" : "pending");
            list.add(map);
        }
        return Map.of("rows", list);
    }

    /**
     * 贊助商店的訂單（2026-10，PAYUNi）：最新的 300 筆，可以照狀態、玩家／訂單編號篩選；加上已付款的合計（今天、這個月、全部，台灣時間）。
     * 每筆附發放批次代號：在「發放紀錄」看實際發了什麼、領了沒。
     */
    private Object orders(final WebServer.Request request) throws Exception {
        final String status = request.param("status") == null ? "all" : request.param("status");
        final String query = request.param("q") == null ? "" : request.param("q").trim().toLowerCase(java.util.Locale.ROOT);
        final java.time.ZoneId zone = java.time.ZoneId.of("Asia/Taipei");
        final java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        final Instant today = now.toLocalDate().atStartOfDay(zone).toInstant();
        final Instant month = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant();
        return onDatabase(connection -> {
            final StringBuilder sql = new StringBuilder("SELECT order_no, player_name, stars, bonus, amount, email, status, trade_no, payment_type, "
                    + "created_at, paid_at, note FROM shop_orders WHERE 1 = 1");
            final List<String> args = new ArrayList<>();
            if (!"all".equals(status)) {
                sql.append(" AND status = ?");
                args.add(status);
            }
            if (!query.isEmpty()) {
                sql.append(" AND (LOWER(player_name) LIKE ? OR LOWER(order_no) LIKE ? OR LOWER(COALESCE(trade_no, '')) LIKE ?)");
                final String like = "%" + query.replace("%", "") + "%";
                args.add(like);
                args.add(like);
                args.add(like);
            }
            sql.append(" ORDER BY created_at DESC LIMIT 300");
            final List<Map<String, Object>> rows = new ArrayList<>();
            try (java.sql.PreparedStatement select = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    select.setString(i + 1, args.get(i));
                }
                try (java.sql.ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        final Map<String, Object> row = new LinkedHashMap<>();
                        final String orderNo = result.getString(1);
                        row.put("order", orderNo);
                        row.put("player", result.getString(2));
                        row.put("stars", result.getLong(3));
                        row.put("bonus", result.getLong(4));
                        row.put("amount", result.getInt(5));
                        row.put("email", result.getString(6));
                        row.put("status", result.getString(7));
                        row.put("trade", result.getString(8));
                        final int type = result.getInt(9);
                        row.put("payment", result.wasNull() ? "" : tw.exilecore.web.ShopCheckout.paymentName(String.valueOf(type)));
                        final java.sql.Timestamp created = result.getTimestamp(10);
                        final java.sql.Timestamp paid = result.getTimestamp(11);
                        row.put("at", created == null ? null : created.getTime());
                        row.put("paidAt", paid == null ? null : paid.getTime());
                        row.put("note", result.getString(12));
                        row.put("batch", tw.exilecore.web.ShopCheckout.batchId(orderNo));
                        rows.add(row);
                    }
                }
            }
            final Map<String, Object> totals = new LinkedHashMap<>();
            try (java.sql.PreparedStatement sum = connection.prepareStatement("SELECT "
                    + "COALESCE(SUM(CASE WHEN paid_at >= ? THEN amount ELSE 0 END), 0), SUM(CASE WHEN paid_at >= ? THEN 1 ELSE 0 END), "
                    + "COALESCE(SUM(CASE WHEN paid_at >= ? THEN amount ELSE 0 END), 0), SUM(CASE WHEN paid_at >= ? THEN 1 ELSE 0 END), "
                    + "COALESCE(SUM(amount), 0), COUNT(*) FROM shop_orders WHERE status = 'paid'")) {
                sum.setTimestamp(1, java.sql.Timestamp.from(today));
                sum.setTimestamp(2, java.sql.Timestamp.from(today));
                sum.setTimestamp(3, java.sql.Timestamp.from(month));
                sum.setTimestamp(4, java.sql.Timestamp.from(month));
                try (java.sql.ResultSet result = sum.executeQuery()) {
                    if (result.next()) {
                        totals.put("today", result.getLong(1));
                        totals.put("todayCount", result.getLong(2));
                        totals.put("month", result.getLong(3));
                        totals.put("monthCount", result.getLong(4));
                        totals.put("all", result.getLong(5));
                        totals.put("allCount", result.getLong(6));
                    }
                }
            }
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("totals", totals);
            return out;
        });
    }

    private Object search(final WebServer.Request request) throws Exception {
        final String query = request.param("q") == null ? "" : request.param("q").trim();
        if (query.isEmpty()) {
            return Map.of("rows", List.of());
        }
        if (query.length() > 32) {
            throw new IllegalArgumentException("名字太長了");
        }
        final List<AdminRepository.Found> found = onDatabase(connection -> module.repository().search(connection, query, 20));
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final AdminRepository.Found account : found) {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("uuid", account.uuid().toString());
            map.put("name", account.name());
            map.put("lastSeen", millis(account.lastSeen()));
            map.put("characters", account.characters());
            rows.add(map);
        }
        return Map.of("rows", rows);
    }

    private Object log() throws Exception {
        final List<AdminRepository.LogEntry> entries = onDatabase(connection -> module.repository().log(connection, 200));
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final AdminRepository.LogEntry entry : entries) {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("at", millis(entry.at()));
            map.put("admin", entry.admin());
            map.put("action", entry.action());
            map.put("detail", entry.detail());
            rows.add(map);
        }
        return Map.of("rows", rows);
    }

    // ---- 設定 ----

    private Object setting(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final AdminSettings.Setting setting = AdminSettings.find(string(body, "id"));
        if (setting == null) {
            throw new IllegalArgumentException("沒有這項設定");
        }
        final boolean reset = bool(body, "reset", false);
        final Object input = reset ? null : value(body.get("value"));
        final String[] change = onMain(() -> apply(setting, reset, input));
        plugin.getSLF4JLogger().info("[管理網頁] {} 把「{}」{}", session.admin(), setting.label(), change[0]);
        record(session.admin(), "改設定", setting.label() + "：" + change[0]);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("change", change[0]);
        result.put("warning", change[1]);
        return result;
    }

    /** 主執行緒：檢查、存進覆蓋檔、重新載入相關的模組；載入失敗就改回去。回傳 [說明, 警告]。 */
    private String[] apply(final AdminSettings.Setting setting, final boolean reset, final Object input) throws Exception {
        final List<String> choices = new ArrayList<>();
        if (setting.kind() == AdminSettings.Kind.CHOICE) {
            choices.add("");
            if (plugin.events() != null) {
                for (final EventConfig.Theme theme : plugin.events().config().themes()) {
                    choices.add(theme.id());
                }
            }
        }
        final Object stored = reset ? null : setting.parse(input, choices);
        final AdminOverrides overrides = plugin.overrides();
        final Object previous = setting.config() ? overrides.config().get(setting.key()) : overrides.data(setting.file(), setting.key());
        write(setting, stored);
        try {
            applyNow(setting, stored);
        } catch (final Exception e) {
            write(setting, previous);
            try {
                applyNow(setting, previous);
            } catch (final Exception again) {
                plugin.getSLF4JLogger().error("改回原本的設定時又失敗了（{}）", setting.label(), again);
            }
            plugin.getSLF4JLogger().warn("管理網頁改「{}」套用失敗，已改回原本的值", setting.label(), e);
            throw new IllegalArgumentException("套用失敗，已改回原本的值：" + e.getMessage());
        }
        final String text = reset ? "恢復成檔案裡的值" : "改成 " + describe(setting, stored);
        String warning = null;
        if (setting.id().equals("pvp") && Boolean.FALSE.equals(stored) && plugin.pvp() != null) {
            warning = "競技場裡的人還在場地裡（只是不能互打），可以叫他們用 /pvp leave 或 /town 離開";
        }
        return new String[] {text, warning};
    }

    private void write(final AdminSettings.Setting setting, final Object value) throws IOException {
        if (setting.config()) {
            plugin.overrides().setConfig(setting.key(), value);
        } else {
            plugin.overrides().setData(setting.file(), setting.key(), value);
        }
    }

    /** 馬上生效：config.yml 的鍵直接改記憶體；資料檔的重新載入那幾個模組。 */
    private void applyNow(final AdminSettings.Setting setting, final Object value) throws Exception {
        if (setting.config()) {
            plugin.applyConfigValue(setting.key(), value);
            return;
        }
        for (final String id : setting.reload()) {
            plugin.modules().reload(id);
        }
    }

    private static String describe(final AdminSettings.Setting setting, final Object stored) {
        final Object display = setting.display(stored);
        return switch (setting.kind()) {
            case BOOLEAN -> Boolean.TRUE.equals(display) ? "開" : "關";
            case CHOICE -> String.valueOf(display).isEmpty() ? "照月份輪替" : String.valueOf(display);
            default -> AdminSettings.plain(((Number) display).doubleValue()) + (setting.unit().isEmpty() ? "" : " " + setting.unit());
        };
    }

    private Object maintenance(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final boolean enabled = bool(body, "enabled", false);
        final String message = optionalString(body, "message");
        if (message != null && message.length() > 200) {
            throw new IllegalArgumentException("說明最多 200 個字");
        }
        final boolean kick = bool(body, "kick", false);
        final int kicked = onMain(() -> {
            plugin.overrides().setMaintenance(enabled, message);
            return enabled && kick ? module.kickNonAdmins() : 0;
        });
        final String text = (enabled ? "開啟維護模式" : "關閉維護模式") + (kicked > 0 ? "，請出 " + kicked + " 個玩家" : "");
        plugin.getSLF4JLogger().info("[管理網頁] {} {}", session.admin(), text);
        record(session.admin(), "維護模式", text + (enabled && message != null && !message.isBlank() ? "（" + message.trim() + "）" : ""));
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("enabled", enabled);
        result.put("kicked", kicked);
        return result;
    }

    private Object restart(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final RestartScheduler restarts = module.restarts();
        if (restarts == null) {
            throw new IllegalArgumentException("重新啟動的功能沒有啟用");
        }
        final String action = optionalString(body, "action") == null ? "" : optionalString(body, "action");
        final String text;
        switch (action) {
            case "schedule", "shutdown" -> {
                final JsonElement element = body.get("minutes");
                if (element == null || !element.isJsonPrimitive()) {
                    throw new IllegalArgumentException("要寫幾分鐘後");
                }
                final int minutes = element.getAsInt();
                if (minutes < 0 || minutes > RestartScheduler.MAX_MINUTES) {
                    throw new IllegalArgumentException("分鐘要在 0–" + RestartScheduler.MAX_MINUTES + " 之間");
                }
                final String reason = optionalString(body, "reason") == null ? "" : optionalString(body, "reason").trim();
                if (reason.length() > 100) {
                    throw new IllegalArgumentException("原因最多 100 個字");
                }
                final boolean shutdown = action.equals("shutdown");
                onMain(() -> {
                    if (shutdown) {
                        restarts.scheduleShutdown(minutes, reason, session.admin());
                    } else {
                        restarts.schedule(minutes, reason, session.admin());
                    }
                    return null;
                });
                text = minutes + " 分鐘後" + (shutdown ? "關服" : "重新啟動") + (reason.isEmpty() ? "" : "（" + reason + "）");
            }
            case "cancel" -> {
                final boolean had = onMain(() -> restarts.cancel("管理員取消"));
                text = had ? "取消重新啟動／關服" : "（本來就沒有排）";
            }
            case "daily" -> {
                final java.util.Set<java.time.LocalTime> times = new java.util.TreeSet<>();
                for (final JsonElement element : array(body, "times")) {
                    final java.time.LocalTime time = element.isJsonPrimitive() ? RestartScheduler.parseTime(element.getAsString()) : null;
                    if (time == null) {
                        throw new IllegalArgumentException("時間看不懂：" + element + "（要像 05:30）");
                    }
                    times.add(time.withSecond(0).withNano(0));
                }
                if (times.size() > 6) {
                    throw new IllegalArgumentException("一天最多 6 個時間");
                }
                onMain(() -> {
                    restarts.setDaily(times);
                    return null;
                });
                text = times.isEmpty() ? "關掉每天定時重新啟動" : "每天定時重新啟動：" + String.join("、", restarts.dailyTimes());
            }
            default -> throw new IllegalArgumentException("action 要是 schedule、shutdown、cancel、daily");
        }
        plugin.getSLF4JLogger().info("[管理網頁] {} {}", session.admin(), text);
        record(session.admin(), "重新啟動", text);
        return Map.of("ok", true, "change", text);
    }

    private Object broadcast(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final String message = optionalString(body, "message") == null ? "" : optionalString(body, "message").trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("公告不能是空的");
        }
        if (message.length() > 200) {
            throw new IllegalArgumentException("公告最多 200 個字");
        }
        if (message.contains("\n") || message.contains("\r")) {
            throw new IllegalArgumentException("公告只能一行");
        }
        final boolean chat = bool(body, "chat", true);
        final boolean title = bool(body, "title", false);
        if (!chat && !title) {
            throw new IllegalArgumentException("聊天欄、畫面中間至少選一個");
        }
        final int count = onMain(() -> module.broadcast(message, chat, title));
        plugin.getSLF4JLogger().info("[管理網頁] {} 公告（{}人）：{}", session.admin(), count, message);
        record(session.admin(), "公告", (chat && title ? "聊天欄＋畫面中間" : chat ? "聊天欄" : "畫面中間") + "：" + message);
        return Map.of("ok", true, "sent", count);
    }

    // ---- 公會、玩家處置、物品追查（2026-10） ----

    /**
     * 照遊戲裡管理員指令的做法跑一段、把它寫出來的訊息收回來（公會管理、物品追查都沿用指令那一套）。
     *
     * @param settle 有些指令的結果要查資料庫、過一下才寫出來：跑完之後最多再等這麼久（毫秒），連續 300 毫秒沒有新訊息就當寫完了；
     *               0 = 當場就有結果，不等
     */
    private List<String> capture(final AdminAuth.Session session, final java.util.function.Consumer<org.bukkit.command.CommandSender> action,
                                 final long settle) throws Exception {
        final tw.exilecore.core.command.CapturedOutput output = new tw.exilecore.core.command.CapturedOutput(plugin.getServer(),
                "網頁:" + session.admin());
        onMain(() -> {
            action.accept(output.sender());
            return Boolean.TRUE;
        });
        if (settle > 0) {
            final long deadline = System.currentTimeMillis() + settle;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(60);
                if (!output.lines().isEmpty() && System.currentTimeMillis() - output.lastAt() >= 300) {
                    break;
                }
            }
        }
        return output.lines();
    }

    private tw.exilecore.guild.GuildService guildService() {
        final tw.exilecore.guild.GuildService guilds = plugin.guilds();
        if (guilds == null) {
            throw new IllegalArgumentException("公會模組沒有啟用");
        }
        return guilds;
    }

    private Object guilds() throws Exception {
        final tw.exilecore.guild.GuildService service = guildService();
        return Map.of("guilds", onMain(service::adminGuildList));
    }

    private Object guildDetail(final WebServer.Request request) throws Exception {
        final tw.exilecore.guild.GuildService service = guildService();
        final long id;
        try {
            id = Long.parseLong(String.valueOf(request.param("id")));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("少了公會的編號");
        }
        final Map<String, Object> detail = onMain(() -> service.adminGuildDetail(id));
        if (detail == null) {
            throw new IllegalArgumentException("沒有這個公會（可能剛被解散）");
        }
        final List<Map<String, Object>> log = new ArrayList<>();
        for (final tw.exilecore.guild.GuildRepository.LogRow row : onDatabase(connection -> service.adminLogRows(connection, id, 60))) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("at", row.at());
            entry.put("actor", row.actor());
            entry.put("action", row.action());
            entry.put("detail", row.detail());
            log.add(entry);
        }
        detail.put("log", log);
        return detail;
    }

    /** 公會名稱、成員名字都不能有空白（指令是用空白切的）；有的話就是資料不對。 */
    private static String token(final JsonObject body, final String key, final String what) {
        final String value = optionalString(body, key) == null ? "" : optionalString(body, key).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("少了" + what);
        }
        return value;
    }

    private Object guildAction(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final tw.exilecore.guild.GuildService service = guildService();
        final String action = string(body, "action");
        final String guild = token(body, "guild", "公會");
        if (guild.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("公會名稱不對");
        }
        final List<String> lines;
        final String what;
        switch (action) {
            case "leader", "promote", "demote", "kick" -> {
                final String member = token(body, "member", "成員");
                what = switch (action) {
                    case "leader" -> "換會長";
                    case "promote" -> "升幹部";
                    case "demote" -> "降成員";
                    default -> "請出";
                } + "：" + member;
                lines = capture(session, sender -> {
                    switch (action) {
                        case "leader" -> service.adminLeader(sender, member);
                        case "promote" -> service.adminRank(sender, member, tw.exilecore.guild.GuildRank.OFFICER);
                        case "demote" -> service.adminRank(sender, member, tw.exilecore.guild.GuildRank.MEMBER);
                        default -> service.adminKick(sender, member);
                    }
                }, 0);
            }
            case "rename" -> {
                final String tag = token(body, "tag", "新標籤");
                final String name = token(body, "name", "新名稱");
                if (tag.chars().anyMatch(Character::isWhitespace) || name.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException("標籤與名稱不能有空白");
                }
                what = "改名：[" + tag + "] " + name;
                lines = capture(session, sender -> service.adminRename(sender, guild + " " + tag + " " + name), 0);
            }
            case "motd" -> {
                final String text = optionalString(body, "text") == null ? "" : optionalString(body, "text").replace('\n', ' ').strip();
                what = text.isEmpty() ? "清掉公告" : "改公告：" + text;
                lines = capture(session, sender -> service.adminMotd(sender, text.isEmpty() ? guild : guild + " " + text), 0);
            }
            case "xp" -> {
                final long amount = number(body, "amount", 0);
                if (amount <= 0) {
                    throw new IllegalArgumentException("經驗要是正的數字");
                }
                what = "公會經驗 +" + amount;
                lines = capture(session, sender -> service.adminExperience(sender, amount, guild), 0);
            }
            case "level" -> {
                final int level = (int) number(body, "amount", 0);
                what = "設等級 " + level;
                lines = capture(session, sender -> service.adminLevel(sender, level, guild), 0);
            }
            case "pages" -> {
                final int pages = (int) number(body, "amount", -1);
                what = "星幣買的倉庫頁設成 " + pages;
                lines = capture(session, sender -> service.adminPages(sender, pages, guild), 0);
            }
            case "disband", "forcedisband" -> {
                final boolean force = action.equals("forcedisband");
                what = force ? "解散（倉庫的東西一起刪）" : "解散";
                lines = capture(session, sender -> service.adminDisband(sender, guild, force), 4000);
            }
            default -> throw new IllegalArgumentException("不認得的動作：" + action);
        }
        plugin.getSLF4JLogger().info("[管理網頁] {} 公會「{}」{}", session.admin(), guild, what);
        record(session.admin(), "公會", "「" + guild + "」" + what + (lines.isEmpty() ? "" : " → " + String.join("／", lines)));
        return Map.of("ok", true, "lines", lines);
    }

    /** 線上玩家：禁言、解除禁言、踢出。 */
    private Object playerAction(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final String action = string(body, "action");
        final UUID uuid;
        try {
            uuid = UUID.fromString(string(body, "uuid"));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("玩家的代號不對");
        }
        final String reason = optionalString(body, "reason") == null ? "" : optionalString(body, "reason").replace('\n', ' ').strip();
        if (reason.length() > 100) {
            throw new IllegalArgumentException("原因最多 100 個字");
        }
        final long minutes = Math.max(0, number(body, "minutes", 0));
        final String by = "網頁:" + session.admin();
        final String result = onMain(() -> {
            final Player target = plugin.getServer().getPlayer(uuid);
            final String name = target != null ? target.getName() : String.valueOf(plugin.getServer().getOfflinePlayer(uuid).getName());
            switch (action) {
                case "mute" -> {
                    if (plugin.chat() == null) {
                        throw new IllegalArgumentException("聊天模組沒有啟用");
                    }
                    plugin.chat().moderation().mute(uuid, minutes, reason, by);
                    final String length = minutes <= 0 ? "永久" : minutes + " 分鐘";
                    if (target != null) {
                        target.sendMessage(plugin.text().prefixed("<red>你被禁言了（" + length + "）"
                                + (reason.isBlank() ? "" : "：" + tw.exilecore.item.ItemRenderer.escape(reason))));
                    }
                    return "禁言 " + name + "（" + length + "）" + (reason.isBlank() ? "" : "：" + reason);
                }
                case "unmute" -> {
                    if (plugin.chat() == null) {
                        throw new IllegalArgumentException("聊天模組沒有啟用");
                    }
                    final boolean had = plugin.chat().moderation().unmute(uuid);
                    if (had && target != null) {
                        target.sendMessage(plugin.text().prefixed("<green>你的禁言解除了"));
                    }
                    return had ? "解除禁言 " + name : name + " 本來就沒有被禁言";
                }
                case "kick" -> {
                    if (target == null) {
                        throw new IllegalArgumentException("這個玩家已經不在線上了");
                    }
                    target.kick(plugin.text().parse("<red>你被管理員請出伺服器" + (reason.isBlank() ? "" : "<newline><gray>原因：<white>"
                            + tw.exilecore.item.ItemRenderer.escape(reason))));
                    return "踢出 " + name + (reason.isBlank() ? "" : "：" + reason);
                }
                default -> throw new IllegalArgumentException("不認得的動作：" + action);
            }
        });
        plugin.getSLF4JLogger().info("[管理網頁] {} {}", session.admin(), result);
        record(session.admin(), "玩家", result);
        return Map.of("ok", true, "message", result);
    }

    // ---- 帳號轉移（2026-10-17）----

    private tw.exilecore.ops.AccountTransfer transfers() {
        if (plugin.ops() == null || plugin.ops().transfer() == null) {
            throw new IllegalArgumentException("維運模組沒有啟用（看主控台的錯誤訊息）");
        }
        return plugin.ops().transfer();
    }

    private static String account(final String value) {
        final String text = value == null ? "" : value.strip();
        if (!text.matches("[A-Za-z0-9_]{1,16}|[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("帳號要填 Minecraft ID（英數字）或 UUID：「" + text + "」");
        }
        return text;
    }

    /** 排著的轉移。 */
    private Object transferList() {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final tw.exilecore.ops.AccountTransfer.Pending entry : transfers().pending()) {
            rows.add(Map.of("from", entry.fromName(), "fromUuid", entry.from(), "to", entry.toName(), "toUuid", entry.to(), "admin", entry.admin()));
        }
        return Map.of("rows", rows);
    }

    /** 預覽：兩邊各有什麼、會清掉什麼（不動任何東西）。 */
    private Object transferPreview(final AdminAuth.Session session, final WebServer.Request request) throws Exception {
        final tw.exilecore.ops.AccountTransfer.Preview preview = wait(transfers().inspect(
                account(request.param("from")), account(request.param("to")), "網頁:" + session.admin()));
        return Map.of("ok", preview.pending() != null, "lines", preview.lines(), "warning", preview.warning());
    }

    /** 排進下次開機：送出前在伺服器上再預覽一次（不能轉就不排）。 */
    private Object transferQueue(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final tw.exilecore.ops.AccountTransfer service = transfers();
        final tw.exilecore.ops.AccountTransfer.Preview preview = wait(service.inspect(
                account(string(body, "from")), account(string(body, "to")), "網頁:" + session.admin()));
        if (preview.pending() == null) {
            throw new IllegalArgumentException(preview.lines().get(0));
        }
        final tw.exilecore.ops.AccountTransfer.Pending pending = preview.pending();
        onMain(() -> {
            service.enqueue(pending);
            return null;
        });
        final String what = "轉移帳號 " + pending.fromName() + " → " + pending.toName() + "（下次開機時做）";
        plugin.getSLF4JLogger().info("[管理網頁] {} 排了{}", session.admin(), what);
        record(session.admin(), "轉移帳號", "排了 " + pending.fromName() + "（" + pending.from() + "）→ " + pending.toName() + "（" + pending.to() + "），下次開機時做");
        return Map.of("ok", true, "message", "排好了：" + pending.fromName() + " → " + pending.toName() + "。重新啟動伺服器時才會轉移（開機先自動備份）");
    }

    /** 取消一筆排著的轉移。 */
    private Object transferCancel(final AdminAuth.Session session, final JsonObject body) throws Exception {
        final int index = (int) number(body, "index", -1);
        final tw.exilecore.ops.AccountTransfer.Pending removed = transfers().cancel(index);
        if (removed == null) {
            throw new IllegalArgumentException("沒有這一筆（可能已經被取消了，重新整理看看）");
        }
        record(session.admin(), "轉移帳號", "取消 " + removed.fromName() + " → " + removed.toName());
        return Map.of("ok", true, "message", "取消了：" + removed.fromName() + " → " + removed.toName());
    }

    /** 物品追查：一件物品（UUID）的交易與通貨紀錄，或一個玩家最近的交易。 */
    private Object trace(final AdminAuth.Session session, final WebServer.Request request) throws Exception {
        if (plugin.economy() == null) {
            throw new IllegalArgumentException("經濟模組沒有啟用");
        }
        final String item = request.param("item") == null ? "" : request.param("item").strip();
        final String player = request.param("player") == null ? "" : request.param("player").strip();
        if (item.isEmpty() == player.isEmpty()) {
            throw new IllegalArgumentException("物品的 UUID 或玩家的 ID 填一個");
        }
        if (!item.isEmpty() && !item.matches("[0-9a-fA-F-]{8,40}")) {
            throw new IllegalArgumentException("物品的 UUID 格式不對（在物品上按 F3+H 看得到，或用遊戲裡的 /ec inspect）");
        }
        if (!player.isEmpty() && !player.matches("[A-Za-z0-9_.*]{1,32}")) {
            throw new IllegalArgumentException("玩家要填 ID（英數字），不是角色名稱");
        }
        final List<String> lines = capture(session, sender -> {
            if (!item.isEmpty()) {
                tw.exilecore.ops.OpsModule.traceItem(plugin, sender, item);
            } else {
                tw.exilecore.ops.OpsModule.tracePlayer(plugin, sender, player);
            }
        }, 8000);
        if (lines.isEmpty()) {
            throw new TimeoutException("查詢還沒回來（資料庫忙碌中），稍後再試");
        }
        return Map.of("lines", lines);
    }

    // ---- JSON 小工具 ----

    private static JsonObject object(final JsonObject body, final String key) {
        final JsonElement element = body.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("少了 " + key);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(final JsonObject body, final String key) {
        final JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(key + " 要是清單");
        }
        return element.getAsJsonArray();
    }

    private static String string(final JsonObject body, final String key) {
        final String value = optionalString(body, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("少了 " + key);
        }
        return value.trim();
    }

    private static String optionalString(final JsonObject body, final String key) {
        final JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " 要是文字");
        }
        return element.getAsString();
    }

    private static boolean bool(final JsonObject body, final String key, final boolean fallback) {
        final JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " 要是 true 或 false");
        }
        return element.getAsBoolean();
    }

    private static long number(final JsonObject body, final String key, final long fallback) {
        final JsonElement element = body.get(key);
        if (element == null || element.isJsonNull() || element.isJsonPrimitive() && element.getAsString().isBlank()) {
            return fallback;
        }
        try {
            final double value = element.getAsDouble();
            if (!Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > 1e15) {
                throw new IllegalArgumentException(key + " 要是整數");
            }
            return (long) value;
        } catch (final NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            throw new IllegalArgumentException(key + " 要是數字");
        }
    }

    /** JSON 的值 → Java（數字、開關、文字）。 */
    private static Object value(final JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("沒有填值");
        }
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        return primitive.getAsString();
    }

    private static Long millis(final Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static String escape(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
