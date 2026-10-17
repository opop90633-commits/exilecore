package tw.exilecore.web;

import org.bukkit.configuration.ConfigurationSection;
import tw.exilecore.ExileCore;
import tw.exilecore.admin.DeliveryService;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 贊助商店的付款（2026-10，統一金流 PAYUNi 整合式支付頁 UPP，API 2.0）。
 * <ol>
 *   <li>商店頁送出（方案、遊戲名稱、信箱、同意條款）→ {@code POST /shop/checkout}：找帳號、寫一筆訂單（shop_orders），
 *       回一頁自動送出的表單到 PAYUNi 的付款頁。</li>
 *   <li>PAYUNi 付款完成 → 背景通知 {@code POST /shop/payuni/notify}（只能是 80、443 埠：要用 public-url 的 https 網址）：
 *       驗 HashInfo、解密、商店代號與金額都對才發星幣。ATM、超商代碼取號時先記「等待繳費」，繳完會再通知一次。</li>
 *   <li>付款完的瀏覽器 → {@code POST /shop/payuni/return}：同樣驗證、同樣處理（背景通知沒到也發得出去），顯示結果。</li>
 * </ol>
 * 發星幣走管理員發放（DeliveryService），批次代號固定由訂單編號算出來：通知重送、通知與返回同時到，都只會發一次；
 * 在線的馬上領，離線的下次登入領。整個處理在網頁伺服器的執行緒上（資料庫用 sync），只有「在線的人馬上領」切回主執行緒。
 */
public final class ShopCheckout {

    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,100}\\.[^@\\s]{1,20}");
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneId.of("Asia/Taipei"));
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 同一個 IP 10 分鐘內最多建幾筆訂單。 */
    private static final int ORDERS_PER_WINDOW = 10;
    /** 同一個遊戲名稱 10 分鐘內最多幾筆。 */
    private static final int ORDERS_PER_NAME = 5;
    /** 全部加起來 10 分鐘內最多幾筆（被灌單時至少不會把資料庫塞滿）。 */
    private static final int ORDERS_GLOBAL = 200;
    private static final long WINDOW_MILLIS = 10 * 60 * 1000L;

    /** 一個儲值方案（config.yml web.shop.packages 的第幾個）。 */
    record Package(long stars, long price, long bonus, String name) {
    }

    /** 一筆訂單。 */
    record Order(String orderNo, UUID account, String playerName, long stars, long bonus, int amount, String status) {
    }

    /** 處理一次 PAYUNi 回傳的結果（給返回頁顯示）。 */
    record Outcome(boolean valid, String status, Order order, Map<String, String> info, String message) {
    }

    private final ExileCore plugin;
    private final Supplier<String> publicUrl;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    ShopCheckout(final ExileCore plugin, final Supplier<String> publicUrl) {
        this.plugin = plugin;
        this.publicUrl = publicUrl;
    }

    // ---- 設定 ----

    /**
     * 設定的快照（主執行緒讀好，網頁執行緒只讀這個：重讀設定時不會讀到改到一半的）。
     *
     * @param keysProblem 商店代號或金鑰的問題（null = 收得到 PAYUNi 的回傳）
     * @param testPlayers 測試區只有這些遊戲名稱可以下單（測試卡付的款不是真的錢）
     */
    record Settings(boolean enabled, boolean sandbox, String merId, PayUniCrypto crypto, String keysProblem, List<Package> packages,
                    java.util.Set<String> testPlayers, boolean trustProxy, boolean cloudflare) {
        static final Settings OFF = new Settings(false, true, "", null, "線上付款還沒設定", List.of(), java.util.Set.of(), false, false);
    }

    private volatile Settings settings = Settings.OFF;

    /** 重讀設定（主執行緒：啟動、/ec reload）。 */
    void refresh() {
        final ConfigurationSection shop = plugin.getConfig().getConfigurationSection("web.shop");
        final ConfigurationSection payuni = shop == null ? null : shop.getConfigurationSection("payuni");
        final List<Package> packages = new ArrayList<>();
        if (shop != null) {
            for (final Map<?, ?> entry : shop.getMapList("packages")) {
                final long stars = number(entry.get("stars"));
                final long price = number(entry.get("price"));
                if (stars <= 0 || price <= 0) {
                    continue;
                }
                final Object name = entry.get("name");
                packages.add(new Package(stars, price, Math.max(0, number(entry.get("bonus"))), name == null ? "" : name.toString()));
            }
        }
        if (payuni == null) {
            // 2026-10-18：沒設金流時代理設定也照讀（/tree/calc 的次數限制共用 clientIp）
            settings = new Settings(false, true, "", null, "線上付款還沒設定", List.copyOf(packages), java.util.Set.of(),
                    shop != null && shop.getBoolean("trust-proxy", true), shop != null && cloudflare(shop));
            return;
        }
        final String merId = payuni.getString("mer-id", "").trim();
        PayUniCrypto crypto = null;
        String keysProblem = merId.isEmpty() ? "商店代號還沒設定" : null;
        if (keysProblem == null) {
            try {
                crypto = new PayUniCrypto(payuni.getString("hash-key", ""), payuni.getString("hash-iv", ""));
            } catch (final IllegalArgumentException e) {
                keysProblem = e.getMessage();
            }
        }
        final java.util.Set<String> testers = new java.util.HashSet<>();
        for (final String name : payuni.getStringList("test-players")) {
            testers.add(name.trim().toLowerCase(Locale.ROOT));
        }
        settings = new Settings(payuni.getBoolean("enabled", false), payuni.getBoolean("sandbox", true), merId, crypto, keysProblem,
                List.copyOf(packages), java.util.Set.copyOf(testers), shop.getBoolean("trust-proxy", true), cloudflare(shop));
    }

    /**
     * 2026-10-18：web.shop.proxy 決定信不信 CF-Connecting-IP。沒寫（或 cloudflare）照舊相信（走 Cloudflare 的伺服器不受影響）；
     * 前面只有 nginx／Caddy、沒有 Cloudflare 的請寫 nginx 或 none——這類代理會原樣轉送用戶端偽造的這個標頭，可以拿來繞過次數限制。
     */
    private static boolean cloudflare(final ConfigurationSection shop) {
        final String proxy = shop.getString("proxy", "").trim();
        return proxy.isEmpty() || "cloudflare".equalsIgnoreCase(proxy);
    }

    /** 付款有沒有開、設定齊不齊（商店頁照這個決定要不要顯示購買表單）。 */
    boolean ready() {
        return problem() == null;
    }

    /** 不能「建立新訂單」的原因（null = 可以）。 */
    String problem() {
        final Settings current = settings;
        if (!current.enabled()) {
            return "線上付款還沒開放";
        }
        if (current.keysProblem() != null) {
            return current.keysProblem();
        }
        if (plugin.database() == null || plugin.admin() == null || plugin.admin().deliveries() == null || plugin.sponsors() == null) {
            return "伺服器的資料庫、發放或贊助功能沒有啟用";
        }
        return null;
    }

    boolean sandbox() {
        return settings.sandbox();
    }

    private static String endpoint(final Settings current) {
        return current.sandbox() ? "https://sandbox-api.payuni.com.tw/api/upp" : "https://api.payuni.com.tw/api/upp";
    }

    List<Package> packages() {
        return settings.packages();
    }

    /** 這個方案發不發得出去（星幣數量超過發放上限之類）；null = 可以。 */
    private String packageProblem(final Package pkg) {
        if (pkg.price() > Integer.MAX_VALUE) {
            return "這個方案的金額設定錯誤";
        }
        try {
            plugin.admin().deliveries().plan(specs(pkg.stars(), pkg.bonus()));
            return null;
        } catch (final RuntimeException e) {
            return "這個方案的設定發不出星幣（" + e.getMessage() + "）";
        }
    }

    private static List<DeliveryService.Spec> specs(final long stars, final long bonus) {
        final List<DeliveryService.Spec> specs = new ArrayList<>();
        specs.add(new DeliveryService.Spec(DeliveryService.SPONSOR, "paid", null, 0, stars));
        if (bonus > 0) {
            specs.add(new DeliveryService.Spec(DeliveryService.SPONSOR, "bonus", null, 0, bonus));
        }
        return specs;
    }

    private static long number(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : new java.math.BigDecimal(value.toString().trim()).longValueExact();
        } catch (final NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    /** 對方的 IP（2026-10-18：/tree/calc 的次數限制也用這個）。 */
    String clientIp(final WebServer.Request request) {
        return clientIp(request, settings);
    }

    /** 對方的 IP：前面有反向代理（本機或內網連進來）時看代理給的標頭。 */
    private String clientIp(final WebServer.Request request, final Settings current) {
        final String remote = request.remote() == null ? "" : request.remote();
        if (current.trustProxy() && privateAddress(remote)) {
            // 2026-10-18：web.shop.proxy 寫 nginx／none 時不信 CF-Connecting-IP（沒有 Cloudflare 時可以每次偽造一個來繞過次數限制）
            final String cf = current.cloudflare() ? request.header("cf-connecting-ip") : null;
            if (cf != null && !cf.isBlank()) {
                return cut(cf.trim(), 64);
            }
            final String forwarded = request.header("x-forwarded-for");
            if (forwarded != null && !forwarded.isBlank()) {
                final String[] parts = forwarded.split(",");
                return cut(parts[parts.length - 1].trim(), 64); // 最後一個是最近一層代理看到的（前面的可以被偽造）
            }
            final String real = request.header("x-real-ip");
            if (real != null && !real.isBlank()) {
                return cut(real.trim(), 64);
            }
        }
        return remote;
    }

    private static boolean privateAddress(final String ip) {
        return ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1") || ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*") || ip.startsWith("fd") || ip.startsWith("fc");
    }

    // ---- 建立訂單 ----

    WebServer.Response checkout(final WebServer.Request request) {
        final Settings current = settings;
        final String problem = problem();
        if (problem != null) {
            return page(503, "暫時不能付款", "<p>" + esc(problem) + "，請稍後再試或聯絡客服。</p>");
        }
        final Map<String, String> form = PayUniCrypto.parse(request.text());
        final List<Package> packages = current.packages();
        final long index = number(form.get("pkg"));
        if (index < 0 || index >= packages.size()) {
            return back("請選一個儲值方案");
        }
        final Package pkg = packages.get((int) index);
        if (form.containsKey("price") && number(form.get("price")) != pkg.price()) {
            return back("儲值方案剛剛更新過，請重新整理商店頁再選一次");
        }
        final String name = form.getOrDefault("name", "").trim();
        if (!MC_NAME.matcher(name).matches()) {
            return back("遊戲名稱要是 3–16 個英文字母、數字或底線");
        }
        final String email = form.getOrDefault("email", "").trim();
        if (!email.isEmpty() && (email.length() > 120 || !EMAIL.matcher(email).matches())) {
            return back("信箱格式不對（可以留空）");
        }
        if (!"1".equals(form.get("agree"))) {
            return back("請先勾選同意服務條款與退款政策");
        }
        if (current.sandbox() && !current.testPlayers().contains(name.toLowerCase(Locale.ROOT))) {
            return back("現在是測試模式：只有設定裡 test-players 的遊戲名稱可以下單");
        }
        final String packageProblem = packageProblem(pkg);
        if (packageProblem != null) {
            plugin.getSLF4JLogger().error("贊助商店：{}", packageProblem);
            return page(503, "暫時不能付款", "<p>" + esc(packageProblem) + "，請聯絡客服。</p>");
        }
        final String ip = clientIp(request, current);
        if (!allow("ip:" + ip, ORDERS_PER_WINDOW) || !allow("name:" + name.toLowerCase(Locale.ROOT), ORDERS_PER_NAME)
                || !allow("all", ORDERS_GLOBAL)) {
            return back("訂單建立太頻繁，請 10 分鐘後再試");
        }
        final String orderNo = newOrderNo();
        final String[] found = new String[2];
        try {
            plugin.database().sync(connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT uuid, name FROM accounts WHERE LOWER(name) = ? ORDER BY last_seen DESC LIMIT 1")) {
                    select.setString(1, name.toLowerCase(Locale.ROOT));
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            return null;
                        }
                        found[0] = result.getString(1);
                        found[1] = result.getString(2);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO shop_orders (order_no, account_uuid, player_name, stars, bonus, "
                        + "amount, email, status, remote, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'created', ?, ?)")) {
                    insert.setString(1, orderNo);
                    insert.setString(2, found[0]);
                    insert.setString(3, found[1]);
                    insert.setLong(4, pkg.stars());
                    insert.setLong(5, pkg.bonus());
                    insert.setInt(6, (int) pkg.price());
                    insert.setString(7, email.isEmpty() ? null : email);
                    insert.setString(8, cut(ip, 64));
                    insert.setTimestamp(9, Timestamp.from(Instant.now()));
                    insert.executeUpdate();
                }
                return null;
            });
        } catch (final SQLException e) {
            plugin.getSLF4JLogger().error("贊助商店：建立訂單失敗", e);
            return page(503, "暫時不能付款", "<p>資料庫忙碌中，請稍後再試。</p>");
        }
        if (found[0] == null) {
            return back("找不到遊戲名稱「" + name + "」：要先進過伺服器一次（大小寫不用管）");
        }
        final String base = publicUrl.get();
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("MerID", current.merId());
        params.put("MerTradeNo", orderNo);
        params.put("TradeAmt", String.valueOf(pkg.price()));
        params.put("Timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("ProdDesc", cut("星骸 星幣 " + pkg.stars() + (pkg.bonus() > 0 ? "（加送 " + pkg.bonus() + "）" : "") + "；遊戲名稱 " + found[1], 500));
        params.put("ReturnURL", base + "/shop/payuni/return");
        params.put("NotifyURL", base + "/shop/payuni/notify");
        params.put("BackURL", base + "/shop");
        if (!email.isEmpty()) {
            params.put("UsrMail", email);
        }
        final PayUniCrypto crypto = current.crypto();
        final String encrypted = crypto.encrypt(params);
        plugin.getSLF4JLogger().info("贊助商店：建立訂單 {}（{}，{} 星幣，NT${}）{}", orderNo, found[1], pkg.stars(), pkg.price(), current.sandbox() ? "［測試區］" : "");
        final String html = "<!doctype html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>前往付款</title></head><body onload=\"document.getElementById('upp').submit()\" style=\"font-family:system-ui,sans-serif;padding:24px\">"
                + "<p>正在前往統一金流的付款頁…</p>"
                + "<form id=\"upp\" method=\"post\" action=\"" + esc(endpoint(current)) + "\">"
                + hidden("MerID", current.merId()) + hidden("Version", "2.0") + hidden("EncryptInfo", encrypted) + hidden("HashInfo", crypto.hash(encrypted))
                + "<noscript><button type=\"submit\">前往付款</button></noscript></form></body></html>";
        return new WebServer.Response(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), Map.of("Cache-Control", "no-store"));
    }

    /** 10 分鐘內同一個 key（IP、遊戲名稱、全部）最多 limit 次。 */
    private boolean allow(final String key, final int limit) {
        final long now = System.currentTimeMillis();
        if (recent.size() > 5_000) {
            recent.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || now - entry.getValue().peekLast() > WINDOW_MILLIS;
                }
            });
        }
        final Deque<Long> times = recent.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
        }
        return true;
    }

    /** 訂單編號：SR + 台灣時間 yyMMddHHmmss + 6 碼亂數（20 碼，PAYUNi 限 25 碼、[A-Za-z0-9_-]）。 */
    String newOrderNo() {
        final StringBuilder out = new StringBuilder("SR").append(ORDER_TIME.format(Instant.now()));
        for (int i = 0; i < 6; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    // ---- PAYUNi 回傳 ----

    /** 背景通知：處理完回 SUCCESS；驗證不過回 400（PAYUNi 會再送）。 */
    WebServer.Response notify(final WebServer.Request request) {
        final Outcome outcome = process(PayUniCrypto.parse(request.text()), "通知");
        if (!outcome.valid()) {
            return WebServer.Response.text(400, "FAIL");
        }
        return WebServer.Response.text(200, "SUCCESS");
    }

    /** 付款完返回（瀏覽器 Form Post）：同樣處理，顯示結果。 */
    WebServer.Response result(final WebServer.Request request) {
        final Map<String, String> form = "POST".equals(request.method()) ? PayUniCrypto.parse(request.text()) : Map.of();
        if (form.isEmpty()) {
            return page(200, "付款結果", "<p>付款結果會用背景通知送到伺服器；星幣到了在遊戲裡會跳通知。</p>");
        }
        final Outcome outcome = process(form, "返回");
        if (!outcome.valid()) {
            return page(400, "看不懂付款結果", "<p>" + esc(outcome.message()) + "</p><p>如果已經扣款，星幣會在背景通知到的時候發出；超過 24 小時沒收到請聯絡客服。</p>");
        }
        final Order order = outcome.order();
        final Map<String, String> info = outcome.info();
        final String who = order == null ? "" : esc(order.playerName());
        final String no = "<p class=\"m\">訂單編號 " + esc(info.getOrDefault("MerTradeNo", "")) + "</p>";
        return switch (outcome.status()) {
            case "paid" -> page(200, "付款成功", "<p><b>" + (order == null ? "" : String.format(Locale.ROOT, "%,d", order.stars() + order.bonus()))
                    + " 星幣</b>已經發給 <b>" + who + "</b>。在線上的話馬上到，不在線的話下次登入時發。</p>" + no);
            case "waiting" -> page(200, "取號成功，請在期限內繳費", waitingText(info) + no);
            case "pending" -> page(200, "付款確認中", "<p>銀行還在確認這筆付款，確認後星幣會自動發到 <b>" + who + "</b>。</p>" + no);
            case "mismatch" -> page(200, "金額不符", "<p>付款金額跟訂單不一樣，這筆訂單先不發星幣，請聯絡客服處理。</p>" + no);
            default -> page(200, "付款沒有完成", "<p>" + esc(info.getOrDefault("Message", "付款失敗或取消")) + "</p><p>沒有扣款；可以回商店重新下單。</p>" + no);
        };
    }

    private static String waitingText(final Map<String, String> info) {
        final String type = info.getOrDefault("PaymentType", "");
        final String expire = info.getOrDefault("ExpireDate", "");
        final StringBuilder out = new StringBuilder();
        if ("2".equals(type)) {
            out.append("<p>ATM 轉帳：銀行代碼 <b>").append(esc(info.getOrDefault("BankType", ""))).append("</b>，帳號 <b>")
                    .append(esc(info.getOrDefault("PayNo", ""))).append("</b></p>");
        } else if ("3".equals(type)) {
            out.append("<p>超商代碼（7-ELEVEN ibon）：<b>").append(esc(info.getOrDefault("PayNo", ""))).append("</b></p>");
        }
        if (!expire.isEmpty()) {
            out.append("<p>繳費期限：").append(esc(expire)).append("</p>");
        }
        out.append("<p>繳費完成後星幣會自動發到帳號。</p>");
        return out.toString();
    }

    /**
     * 驗證並處理一次 PAYUNi 回傳（通知或返回）。發星幣、改訂單狀態都在這裡；同一時間只處理一筆（通知與返回同時到也不會發兩次）。
     */
    Outcome process(final Map<String, String> form, final String source) {
        final Settings current = settings;
        // 收回傳只要金鑰對（關掉 enabled 只是不再接新訂單：已經取號的 ATM、超商代碼繳完還是要發）
        if (current.crypto() == null || plugin.database() == null || plugin.admin() == null || plugin.admin().deliveries() == null) {
            return new Outcome(false, "", null, Map.of(), "線上付款沒有設定");
        }
        final PayUniCrypto crypto = current.crypto();
        final String encrypted = form.get("EncryptInfo");
        if (encrypted == null || !crypto.verify(encrypted, form.get("HashInfo"))) {
            plugin.getSLF4JLogger().warn("贊助商店：收到驗證不過的 PAYUNi {}（HashInfo 不對）", source);
            return new Outcome(false, "", null, Map.of(), "驗證碼不對");
        }
        final Map<String, String> info;
        try {
            info = crypto.decrypt(encrypted);
        } catch (final IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("贊助商店：PAYUNi {} 解不開：{}", source, e.getMessage());
            return new Outcome(false, "", null, Map.of(), "解不開付款結果");
        }
        if (!current.merId().equals(info.get("MerID"))) {
            plugin.getSLF4JLogger().warn("贊助商店：PAYUNi {} 的商店代號不對（{}）", source, info.get("MerID"));
            return new Outcome(false, "", null, info, "商店代號不對");
        }
        final String orderNo = info.getOrDefault("MerTradeNo", "");
        lock.lock();
        try {
            final Order order = load(orderNo);
            if (order == null) {
                plugin.getSLF4JLogger().warn("贊助商店：PAYUNi {} 的訂單 {} 不存在", source, orderNo);
                return new Outcome(false, "", null, info, "找不到這筆訂單");
            }
            if ("paid".equals(order.status())) {
                return new Outcome(true, "paid", order, info, ""); // 重送：已經發過了
            }
            if ("mismatch".equals(order.status())) {
                return new Outcome(true, "mismatch", order, info, ""); // 金額不對的：只能人工處理，之後的回傳都不動它
            }
            final String decision = decide(info.getOrDefault("Status", ""), info.getOrDefault("TradeStatus", ""),
                    number(info.get("TradeAmt")), order.amount());
            switch (decision) {
                case "paid" -> deliver(order, info);
                case "mismatch" -> {
                    plugin.getSLF4JLogger().error("贊助商店：訂單 {} 付款金額 {} 跟訂單 {} 不一樣，不發星幣（請人工處理）", orderNo, info.get("TradeAmt"), order.amount());
                    update(orderNo, "mismatch", info, "付款金額 " + info.get("TradeAmt"));
                }
                case "waiting" -> {
                    if ("created".equals(order.status())) { // 只往前走：失敗了的不會變回等待繳費
                        update(orderNo, "waiting", info,
                                cut("繳費代碼 " + info.getOrDefault("PayNo", "") + " 期限 " + info.getOrDefault("ExpireDate", ""), 255));
                    }
                }
                case "failed" -> update(orderNo, "failed", info, cut(info.getOrDefault("Message", info.getOrDefault("Status", "")), 255));
                default -> {
                    // pending：還沒有結果（授權逾時、待確認）：之後會再通知
                }
            }
            return new Outcome(true, decision, order, info, "");
        } catch (final SQLException e) {
            plugin.getSLF4JLogger().error("贊助商店：處理訂單 {} 時資料庫出錯（PAYUNi 會再通知）", orderNo, e);
            return new Outcome(false, "", null, info, "資料庫忙碌中");
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("贊助商店：處理訂單 {} 時出錯（請人工確認這筆有沒有發；PAYUNi 會再通知）", orderNo, e);
            return new Outcome(false, "", null, info, "伺服器處理失敗，請聯絡客服並提供訂單編號");
        } finally {
            lock.unlock();
        }
    }

    /**
     * PAYUNi 回傳的結果要怎麼處理：paid = 發星幣、mismatch = 金額不對（不發）、waiting = ATM／超商代碼取號了等繳費、
     * pending = 還沒有結果（之後會再通知）、failed = 失敗或取消。
     */
    static String decide(final String status, final String tradeStatus, final long paidAmount, final long orderAmount) {
        if ("SUCCESS".equals(status) && "1".equals(tradeStatus)) {
            return paidAmount == orderAmount ? "paid" : "mismatch";
        }
        if ("SUCCESS".equals(status) && "0".equals(tradeStatus)) {
            return "waiting";
        }
        if ("UNKNOWN".equalsIgnoreCase(status) || "UNAPPROVED".equalsIgnoreCase(status) || "8".equals(tradeStatus)) {
            return "pending";
        }
        return "failed";
    }

    private Order load(final String orderNo) throws SQLException {
        return plugin.database().sync(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT order_no, account_uuid, player_name, stars, bonus, amount, status FROM shop_orders WHERE order_no = ?")) {
                select.setString(1, orderNo);
                try (ResultSet result = select.executeQuery()) {
                    return result.next() ? new Order(result.getString(1), UUID.fromString(result.getString(2)), result.getString(3),
                            result.getLong(4), result.getLong(5), result.getInt(6), result.getString(7)) : null;
                }
            }
        });
    }

    private void update(final String orderNo, final String status, final Map<String, String> info, final String note) throws SQLException {
        plugin.database().sync(connection -> {
            try (PreparedStatement update = connection.prepareStatement("UPDATE shop_orders SET status = ?, trade_no = ?, payment_type = ?, note = ?"
                    + (("paid".equals(status)) ? ", paid_at = ?" : "") + " WHERE order_no = ? AND status NOT IN ('paid', 'mismatch')")) {
                int i = 1;
                update.setString(i++, status);
                update.setString(i++, cut(info.get("TradeNo"), 40));
                final long type = number(info.get("PaymentType"));
                if (type > 0) {
                    update.setInt(i++, (int) type);
                } else {
                    update.setNull(i++, java.sql.Types.INTEGER);
                }
                update.setString(i++, note);
                if ("paid".equals(status)) {
                    update.setTimestamp(i++, Timestamp.from(Instant.now()));
                }
                update.setString(i, orderNo);
                update.executeUpdate();
            }
            return null;
        });
    }

    /** 發星幣（批次代號由訂單編號算出來：重送不會發兩次）＋訂單改成已付款；在線的馬上領。 */
    private void deliver(final Order order, final Map<String, String> info) throws SQLException {
        final DeliveryService deliveries = plugin.admin().deliveries();
        final List<DeliveryService.Planned> plan = deliveries.plan(specs(order.stars(), order.bonus()));
        final String batchId = batchId(order.orderNo());
        final String note = "贊助商店 訂單 " + order.orderNo() + "（PAYUNi " + info.getOrDefault("TradeNo", "") + "，NT$" + order.amount() + "）";
        final DeliveryService.Created created = plugin.database().sync(connection ->
                deliveries.create(connection, batchId, new DeliveryService.Targets("players", List.of(order.account()), List.of()), List.of(),
                        plan, note, "PAYUNi"));
        if (!created.unknown().isEmpty()) {
            throw new SQLException("訂單 " + order.orderNo() + " 的帳號 " + order.account() + " 不存在");
        }
        update(order.orderNo(), "paid", info, null);
        try {
            plugin.database().sync(connection -> {
                plugin.admin().log(connection, "PAYUNi", "贊助商店付款", "訂單 " + order.orderNo() + "：" + order.playerName() + " 付 NT$" + order.amount()
                        + "（" + paymentName(info.get("PaymentType")) + "），發 " + order.stars() + " 星幣"
                        + (order.bonus() > 0 ? "＋加送 " + order.bonus() : "") + "；PAYUNi 交易 " + info.getOrDefault("TradeNo", ""));
                return null;
            });
        } catch (final SQLException e) {
            plugin.getSLF4JLogger().warn("贊助商店：訂單 {} 的操作紀錄寫不進去：{}", order.orderNo(), e.toString()); // 星幣已經發了，紀錄少一筆而已
        }
        plugin.getSLF4JLogger().info("贊助商店：訂單 {} 付款成功，發 {} 星幣{}給 {}", order.orderNo(), order.stars(),
                order.bonus() > 0 ? "（加送 " + order.bonus() + "）" : "", order.playerName());
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> deliveries.push(created.accountIds()));
        } catch (final RuntimeException e) {
            // 插件正在關：發放已經寫進資料庫，下次登入領
        }
    }

    /** PAYUNi 的付款方式代碼 → 中文（管理網頁、紀錄用）。 */
    public static String paymentName(final String code) {
        return switch (code == null ? "" : code.trim()) {
            case "1" -> "信用卡";
            case "2" -> "ATM 轉帳";
            case "3" -> "超商代碼";
            case "" -> "未知";
            default -> "付款方式 " + code;
        };
    }

    /** 這筆訂單的發放批次代號（管理網頁「發放紀錄」用這個找）。 */
    public static String batchId(final String orderNo) {
        return UUID.nameUUIDFromBytes(("payuni:" + orderNo).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---- 頁面 ----

    private WebServer.Response back(final String message) {
        return page(400, "沒辦法下單", "<p>" + esc(message) + "</p>");
    }

    private static WebServer.Response page(final int status, final String title, final String body) {
        final String html = "<!doctype html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "｜星骸 贊助商店</title><style>"
                + ":root{color-scheme:light dark;--page:#f9f9f7;--card:#fcfcfb;--text:#0b0b0b;--muted:#52514e;--border:rgba(11,11,11,.1)}"
                + "@media (prefers-color-scheme:dark){:root{--page:#0d0d0d;--card:#1a1a19;--text:#fff;--muted:#c3c2b7;--border:rgba(255,255,255,.1)}}"
                + "body{margin:0;background:var(--page);color:var(--text);font-family:\"Noto Sans TC\",\"Microsoft JhengHei\",system-ui,sans-serif;"
                + "font-size:15px;line-height:1.65;padding:24px 16px}.w{max-width:560px;margin:0 auto}"
                + ".c{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:16px 20px}h1{font-size:22px;margin:8px 0 12px}"
                + ".m{color:var(--muted);font-size:13px}a{color:inherit}</style></head><body><div class=\"w\">"
                + "<a href=\"" + "/shop" + "\" class=\"m\" style=\"text-decoration:none\">← 回贊助商店</a><h1>" + esc(title) + "</h1><div class=\"c\">" + body
                + "</div></div></body></html>";
        return new WebServer.Response(status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), Map.of("Cache-Control", "no-store"));
    }

    private static String hidden(final String name, final String value) {
        return "<input type=\"hidden\" name=\"" + esc(name) + "\" value=\"" + esc(value) + "\">";
    }

    static String esc(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (final char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String cut(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
