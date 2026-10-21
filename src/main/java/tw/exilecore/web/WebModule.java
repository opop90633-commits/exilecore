package tw.exilecore.web;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.character.PlayerState;
import tw.exilecore.module.Module;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * web 模組：插件內建的小型網頁伺服器（config.yml 的 web），提供
 * <ul>
 *   <li>介面資源包（/pack/雜湊.zip）：登入時詢問玩家要不要套用；套用了的人看到星盤式天賦樹（{@link UiPack}）。</li>
 *   <li>網頁天賦規劃器（/tree）：整棵樹拖曳縮放、算總能力、產生代碼，遊戲內 /tree import 匯入。</li>
 *   <li>市場報告（/economy）：全服通貨與金幣每天的產出、消耗、存量、參考價值（config.yml 的 web.economy-page）。</li>
 * </ul>
 * 在自己電腦測試不用設定（網址是 http://localhost:埠）；正式開服要開放這個埠、在 public-url 填玩家連得到的網址。
 */
public final class WebModule implements Module, Listener {

    /** 角色頁同時最多幾個資料庫查詢（2026-10）。 */
    private final java.util.concurrent.Semaphore buildQueries = new java.util.concurrent.Semaphore(4);

    /** 資源包的代號（固定：重送時取代同一個，不會疊好幾份）。 */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("exilecore-ui-pack".getBytes(StandardCharsets.UTF_8));
    /** 背景音樂那包的代號（2026-10-21：音樂拆成另一包，更新插件時不用重新下載）。 */
    private static final UUID MUSIC_ID = UUID.nameUUIDFromBytes("exilecore-music-pack".getBytes(StandardCharsets.UTF_8));
    /** 開服後最多等 BetterModel 的資源包多久（tick）才送資源包（2026-10-21）。 */
    private static final long PACK_WAIT_TICKS = 20L * 60;

    private final ExileCore plugin;
    private WebServer server;
    private String bind = "";
    private int port;
    private String configuredUrl = "";
    private boolean send;
    private boolean required;
    /** 資源包放在外部空間的網址（2026-10：主機拿不到第二個埠時用）；空白 = 用內建的網頁伺服器。 */
    private String externalUrl = "";
    /** 上次寫到 plugins/ExileCore/exilecore-ui.zip 的雜湊。 */
    private String writtenSha1;
    private boolean warnedStale;
    private UiPack.Built pack;
    /** 我們自己的資源包（還沒併別的插件的；拆掉音樂的）。 */
    private UiPack.Built ownPack;
    /** 我們自己的資源包，含音樂（2026-10-21：外部空間只給一個網址時用這包，跟以前一樣）。 */
    private UiPack.Built ownFull;
    /** 背景音樂那包（2026-10-21）；沒有音樂時是 null。 */
    private UiPack.Built musicPack;
    /** 音樂那包放在外部空間的網址（2026-10-21）。外部空間開著、這個沒填時音樂併回介面那包（跟以前一樣一包）。 */
    private String externalMusicUrl = "";
    /** 現在的 pack 有沒有把音樂拆出去。 */
    private boolean packSplit;
    private String writtenMusicSha1;
    /** 2026-10-21：開服後等 BetterModel 的資源包併進來才送（以前這段時間登入的人會先下載一次、併完又重下載一次）。 */
    private boolean packReady = true;
    /** 等資源包的時候登入的人（主執行緒）。 */
    private final Set<UUID> waitingPack = ConcurrentHashMap.newKeySet();
    /** 別的插件產生、要併進來的資源包檔案（2026-10：BetterModel）。 */
    private final Map<String, Map<String, byte[]>> externalPacks = new java.util.TreeMap<>();
    /** 這次啟動的代號（網頁與資料對版本用）。 */
    private static final String BUILD = Long.toString(System.currentTimeMillis(), 36);
    private volatile byte[] plannerPage = new byte[0];
    private volatile byte[] buildsPage = new byte[0];
    /** 網頁角色頁（2026-10）。 */
    private Profiles profiles;
    private volatile byte[] plannerData = new byte[0];
    /** 規劃器的裝備資料（2026-10）。 */
    private volatile byte[] plannerItems = "{}".getBytes(StandardCharsets.UTF_8);
    /** 網頁計算器的技能與輔助清單（2026-10）。 */
    private volatile byte[] plannerSkills = "{\"skills\":[],\"supports\":[]}".getBytes(StandardCharsets.UTF_8);
    /** 首頁、圖鑑（2026-10）。 */
    private volatile byte[] homePage = new byte[0];
    private volatile byte[] shopPage = new byte[0];
    /** 贊助商店的付款（網頁伺服器開著才有）。 */
    private volatile ShopCheckout checkout; // 2026-10-18：網頁執行緒（/tree/calc 的 clientIp）也會讀
    /** 2026-10-18：商店開關與商店資料的快照（主執行緒算好；網頁執行緒只讀這個，不碰重讀中的 live config）。 */
    private volatile boolean shopOn = true;
    private volatile byte[] shopJson = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] codexPage = new byte[0];
    private volatile byte[] codexData = "{}".getBytes(StandardCharsets.UTF_8);
    /** 2026-10-21：星圖天賦的網頁規劃器（web/atlas.html）與它的資料（atlas_tree.yml 轉成 JSON）。 */
    private volatile byte[] atlasPage = new byte[0];
    private volatile byte[] atlasData = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] economyPage = new byte[0];
    private volatile byte[] ladderPage = new byte[0];
    private boolean economyEnabled = false;
    /** 已經套用介面資源包的玩家（主執行緒）。 */
    private final Set<UUID> packLoaded = ConcurrentHashMap.newKeySet();
    /** 別的模組加的路由（管理網頁）：網頁伺服器每次啟動（含換埠重開）都裝一次。 */
    private final List<java.util.function.Consumer<WebServer>> extensions = new java.util.concurrent.CopyOnWriteArrayList<>();

    public WebModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "web";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.skills() != null) {
            // 快捷欄兩側的小字要用到的字：所有技能的名稱（光環、召喚物都在裡面）
            final java.util.List<String> names = new java.util.ArrayList<>();
            for (final tw.exilecore.skill.SkillDefinition definition : plugin.skills().config().all()) {
                names.add(definition.name());
            }
            // 2026-10-20：快捷欄左邊的增益倒數（護符、神龕、擊殺後、疾速……）
            names.addAll(tw.exilecore.skill.SkillService.HUD_BUFF_TEXTS);
            if (plugin.flasks() != null && plugin.flasks().charms() != null) {
                names.addAll(plugin.flasks().charms().names());
            }
            if (plugin.maps() != null) {
                for (final tw.exilecore.map.MapEventConfig.Shrine shrine : plugin.maps().eventConfig().shrines()) {
                    names.add(shrine.name());
                }
            }
            HudFont.useTexts(names);
            final java.util.List<String> summons = new java.util.ArrayList<>();
            for (final tw.exilecore.skill.SkillDefinition definition : plugin.skills().config().all()) {
                if (definition.summon() != null) {
                    summons.add(definition.name());
                }
            }
            HudFont.useRowTexts(summons); // 召喚物直排（2026-10）
        }
        // 2026-10：撿到東西的角落提示要用到的字：資料檔裡出現過的所有字（物品、通貨、傳奇、詞綴的名稱）
        final java.util.List<String> lootTexts = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(plugin.dataFolder().resolve("data"))) {
            for (final java.nio.file.Path file : (Iterable<java.nio.file.Path>) walk.filter(p -> p.toString().endsWith(".yml"))::iterator) {
                lootTexts.add(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (final java.io.IOException | java.io.UncheckedIOException e) {
            plugin.getSLF4JLogger().warn("撿到東西的角落提示：讀不到資料檔的字（{}）", e.getMessage());
        }
        LootHud.useTexts(lootTexts);
        ownFull = UiPack.build(plugin.items() == null ? List.of() : UiPack.skins(plugin.items().repository()),
                gemSkins(plugin.currencies() == null ? List.of() : UiPack.currencySkins(plugin.currencies().repository()), skillSkins()),
                plugin.items() == null ? java.util.Set.of() : UiPack.gearItems(plugin.items().repository()),
                plugin.items() == null ? List.of() : UiPack.baseSkins(plugin.items().repository())); // 2026-10-21：一般裝備、地圖、藥劑、護符、珠寶的圖
        final UiPack.Split split = UiPack.splitMusic(ownFull);
        ownPack = split.ui();
        musicPack = split.music();
        // 2026-10-21：有裝 BetterModel：等它的資源包併進來（最多 PACK_WAIT_TICKS）才送資源包
        final org.bukkit.plugin.Plugin betterModel = plugin.getServer().getPluginManager().getPlugin("BetterModel");
        packReady = betterModel == null || !betterModel.isEnabled();
        if (!packReady) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!packReady) {
                    plugin.getSLF4JLogger().info("介面資源包：等了 {} 秒沒等到 BetterModel 的資源包，先送現在的", PACK_WAIT_TICKS / 20);
                    markPackReady();
                }
            }, PACK_WAIT_TICKS);
        }
        // 2026-10：頁面上寫這次啟動的代號，跟資料的代號對不上（分頁在更新前就開著）頁面會自己提醒重新整理
        plannerPage = new String(loadResource("web/planner.html"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("__BUILD__", BUILD).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        economyPage = loadResource("web/economy.html");
        ladderPage = loadResource("web/ladder.html");
        buildsPage = loadResource("web/builds.html");
        homePage = loadResource("web/home.html");
        shopPage = loadResource("web/shop.html");
        codexPage = loadResource("web/codex.html");
        atlasPage = loadResource("web/atlas.html"); // 2026-10-21
        if (profiles == null) {
            profiles = new Profiles(plugin);
            profiles.start();
        }
        readConfig();
        mergePacks(); // 2026-10-21：要先讀設定（外部空間有沒有給音樂的網址）才知道音樂要不要拆
        if (!externalUrl.isEmpty() && pack != null) {
            plugin.getSLF4JLogger().info("介面資源包改從外部空間下載：{}（要上傳的檔案是 plugins/ExileCore/exilecore-ui.zip，SHA1 {}）{}",
                    externalUrl, pack.sha1(), packSplit ? "；背景音樂：" + externalMusicUrl + "（plugins/ExileCore/exilecore-music.zip）"
                            : "；背景音樂併在同一包（要拆開就填 resource-pack.external-music-url）");
        }
        refreshPlannerData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startIfEnabled();
    }

    @Override
    public void reload() {
        final String oldBind = bind;
        final int oldPort = port;
        final boolean wasRunning = server != null;
        readConfig();
        refreshPlannerData();
        final boolean enabled = plugin.getConfig().getBoolean("web.enabled", true);
        if (!enabled || !oldBind.equals(bind) || oldPort != port || !wasRunning) {
            stopServer();
            startIfEnabled();
        }
    }

    @Override
    public void disable() {
        if (profiles != null) {
            profiles.stop(); // 關服時玩家先被踢出（下線事件做最後一次快照），這裡不用再做
            profiles = null;
        }
        stopServer();
        packLoaded.clear();
        waitingPack.clear();
    }

    private void readConfig() {
        if (checkout != null) {
            checkout.refresh(); // 付款設定的快照（網頁執行緒只讀快照）
        }
        refreshShopSnapshot();
        // 2026-10-18：贊助模組可能排在後面才重讀：下一個 tick 再算一次商店資料
        plugin.getServer().getScheduler().runTask(plugin, this::refreshShopSnapshot);
        final ConfigurationSection web = plugin.getConfig().getConfigurationSection("web");
        bind = web == null ? "" : web.getString("bind", "");
        port = web == null ? 25580 : web.getInt("port", 25580);
        configuredUrl = web == null ? "" : web.getString("public-url", "");
        economyEnabled = web != null && web.getBoolean("economy-page", false); // 2026-10：預設不公開
        send = plugin.getConfig().getBoolean("resource-pack.send", true);
        required = plugin.getConfig().getBoolean("resource-pack.required", false);
        externalUrl = plugin.getConfig().getString("resource-pack.external-url", "").trim();
        externalMusicUrl = plugin.getConfig().getString("resource-pack.external-music-url", "").trim();
        if (pack != null && splitMusic() != packSplit) {
            mergePacks(); // 外部空間的設定改了：音樂改拆開或併回去
        }
        if (!externalUrl.isEmpty() && pack != null) {
            plugin.getSLF4JLogger().info("介面資源包改從外部空間下載：{}（要上傳的檔案是 plugins/ExileCore/exilecore-ui.zip，SHA1 {}）",
                    externalUrl, pack.sha1());
        }
    }

    private void startIfEnabled() {
        if (!plugin.getConfig().getBoolean("web.enabled", true)) {
            plugin.getSLF4JLogger().info("網頁伺服器沒有開（web.enabled: false）：沒有介面資源包與網頁天賦規劃器");
            return;
        }
        try {
            server = WebServer.start(bind, port, plugin.getSLF4JLogger());
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("網頁伺服器開不起來（埠 {} 被占用？改 config.yml 的 web.port）：{}", port, e.getMessage());
            server = null;
            return;
        }
        // 2026-10：首頁（在線人數、各頁的入口、排行前幾名）與圖鑑（技能、輔助寶石、通貨、傳奇）；以前 / 直接跳到天賦規劃器
        server.route("/", path -> WebServer.Response.of("text/html; charset=utf-8", homePage));
        server.route("/home/status.json", path -> new WebServer.Response(200, "application/json; charset=utf-8", statusJson(),
                java.util.Map.of("Cache-Control", "no-cache")));
        // 2026-10-21：星圖天賦的網頁規劃器
        server.route("/atlas", path -> WebServer.Response.of("text/html; charset=utf-8", atlasPage));
        server.route("/atlas/", path -> WebServer.Response.redirect("../atlas"));
        server.route("/atlas/data.json", path -> new WebServer.Response(200, "application/json; charset=utf-8", atlasData,
                java.util.Map.of("Cache-Control", "no-cache")));
        server.route("/codex", path -> WebServer.Response.of("text/html; charset=utf-8", codexPage));
        server.route("/codex/", path -> WebServer.Response.redirect("../codex"));
        server.route("/codex/data.json", path -> new WebServer.Response(200, "application/json; charset=utf-8", codexData,
                java.util.Map.of("Cache-Control", "no-cache")));
        server.route("/tree", path -> WebServer.Response.of("text/html; charset=utf-8", plannerPage));
        server.route("/tree/", path -> WebServer.Response.redirect("../tree"));
        server.route("/tree/items.json", path -> new WebServer.Response(200, "application/json; charset=utf-8", plannerItems,
                java.util.Map.of("Cache-Control", "no-cache")));
        server.route("/tree/data.json", path -> new WebServer.Response(200, "application/json; charset=utf-8", plannerData,
                java.util.Map.of("Cache-Control", "no-cache", "X-Build", BUILD)));
        // 2026-10：網頁計算器：技能與輔助清單、送一個角色過來算屬性與每秒傷害
        server.route("/tree/skills.json", path -> new WebServer.Response(200, "application/json; charset=utf-8", plannerSkills,
                java.util.Map.of("Cache-Control", "no-cache")));
        // 2026-10-18：次數限制用跟贊助商店同一套 clientIp（代理後面不會全服共用一個桶）
        final BuildCalc calc = new BuildCalc(plugin, request -> {
            final ShopCheckout current = checkout;
            return current == null ? request.remote() : current.clientIp(request);
        });
        server.endpoint("/tree/calc", calc::handle, "POST");
        // 市場報告：全服通貨與金幣的產出、消耗、存量、參考價值（只有加總，沒有個人資料）；關掉時整頁 404
        server.route("/economy", path -> economyEnabled ? WebServer.Response.of("text/html; charset=utf-8", economyPage) : null);
        server.route("/economy/", path -> economyEnabled ? WebServer.Response.redirect("../economy") : null);
        server.route("/economy/data.json", path -> economyEnabled && plugin.economy() != null ? new WebServer.Response(200, "application/json; charset=utf-8",
                plugin.economy().reports().webJson(), java.util.Map.of("Cache-Control", "no-cache")) : null);
        // 排行榜：角色名稱、職業、等級、地圖、死亡（沒有帳號以外的個人資料）
        server.route("/ladder", path -> plugin.ladder() == null ? null : WebServer.Response.of("text/html; charset=utf-8", ladderPage));
        server.route("/ladder/", path -> plugin.ladder() == null ? null : WebServer.Response.redirect("../ladder"));
        server.route("/ladder/data.json", path -> plugin.ladder() == null ? null : new WebServer.Response(200, "application/json; charset=utf-8",
                plugin.ladder().webJson().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.util.Map.of("Cache-Control", "no-cache")));
        // 贊助商店（2026-10，金流審核要的商店網址）：儲值方案、星幣能換的東西、機率、商家資料與條款（config.yml 的 web.shop）
        server.route("/shop", path -> shopEnabled() ? WebServer.Response.of("text/html; charset=utf-8", shopPage) : null);
        server.route("/shop/", path -> shopEnabled() ? WebServer.Response.redirect("../shop") : null);
        final ShopCheckout checkout = new ShopCheckout(plugin, this::publicUrl);
        checkout.refresh();
        this.checkout = checkout;
        refreshShopSnapshot();
        server.route("/shop/data.json", path -> shopEnabled() ? new WebServer.Response(200, "application/json; charset=utf-8",
                shopJson, java.util.Map.of("Cache-Control", "no-cache")) : null); // 2026-10-18：只讀主執行緒算好的快照
        // 付款（統一金流 PAYUNi）：建立訂單 → PAYUNi 付款頁；背景通知、付款完返回都會驗證後發星幣（同一筆只發一次）
        server.endpoint("/shop/checkout", request -> shopEnabled() ? checkout.checkout(request) : null, "POST");
        server.endpoint("/shop/payuni/notify", checkout::notify, "POST");
        server.endpoint("/shop/payuni/return", checkout::result, "POST", "GET");
        if (plugin.getConfig().getBoolean("web.shop.payuni.enabled", false)) {
            final String problem = checkout.problem();
            if (problem != null) {
                plugin.getSLF4JLogger().warn("贊助商店：線上付款開著但不能用：{}（config.yml 的 web.shop.payuni）", problem);
            } else if (!publicUrl().startsWith("https://")) {
                plugin.getSLF4JLogger().warn("贊助商店：public-url（{}）不是 https：PAYUNi 的背景通知只送 80、443 埠，星幣會發不出去", publicUrl());
            } else {
                plugin.getSLF4JLogger().info("贊助商店：線上付款開著（{}），通知網址 {}/shop/payuni/notify", checkout.sandbox() ? "測試區" : "正式區", publicUrl());
            }
        }
        // 角色頁（2026-10，像 poe.ninja）：公開的角色（玩家可以 /char profile off 關掉）
        server.route("/builds", path -> profiles == null ? null : WebServer.Response.of("text/html; charset=utf-8", buildsPage));
        server.route("/builds/", path -> profiles == null ? null : WebServer.Response.redirect("../builds"));
        server.route("/builds/list.json", path -> {
            if (profiles == null) {
                return null;
            }
            // 2026-10：同時最多幾個查資料庫（角色頁沒有登入、誰都能打：以前一直打會把遊戲存檔的資料庫排隊塞爆）
            if (!buildQueries.tryAcquire()) {
                return WebServer.Response.text(503, "查詢的人太多了，稍後再試");
            }
            try {
                return new WebServer.Response(200, "application/json; charset=utf-8", profiles.listJson(),
                        java.util.Map.of("Cache-Control", "no-cache"));
            } catch (final Exception e) {
                return WebServer.Response.text(503, "資料庫忙碌中，稍後再試");
            } finally {
                buildQueries.release();
            }
        });
        server.endpoint("/builds/character.json", request -> {
            if (!buildQueries.tryAcquire()) {
                return WebServer.Response.text(503, "查詢的人太多了，稍後再試");
            }
            try {
                final byte[] body = profiles == null ? null : profiles.characterJson(request.param("name"), request.param("key"));
                return body == null ? WebServer.Response.text(404, "沒有這個角色（或沒有公開）")
                        : new WebServer.Response(200, "application/json; charset=utf-8", body, java.util.Map.of("Cache-Control", "no-cache"));
            } catch (final Exception e) {
                return WebServer.Response.text(503, "資料庫忙碌中，稍後再試");
            } finally {
                buildQueries.release();
            }
        }, "GET");
        server.endpoint("/builds/icon.png", request -> {
            final byte[] png = Profiles.uniqueIcon(request.param("id"));
            return png == null ? WebServer.Response.text(404, "沒有這個圖示")
                    : new WebServer.Response(200, "image/png", png, java.util.Map.of("Cache-Control", "public, max-age=3600"));
        }, "GET");
        // 帶雜湊的網址：照現在的資源包（併了 BetterModel 之後雜湊會變，所以用前綴，什麼雜湊都回現在的）
        server.prefix("/pack/", path -> new WebServer.Response(200, "application/zip", pack.zip(),
                java.util.Map.of("Cache-Control", "no-cache")));
        // 2026-10-21：背景音樂那包（帶雜湊的網址給玩家，固定的給管理員）
        server.prefix("/music/", path -> musicPack == null ? null : new WebServer.Response(200, "application/zip", musicPack.zip(),
                java.util.Map.of("Cache-Control", "no-cache")));
        server.route("/music.zip", path -> musicPack == null ? null : new WebServer.Response(200, "application/zip", musicPack.zip(),
                java.util.Map.of("Cache-Control", "no-cache", "Content-Disposition", "attachment; filename=\"exilecore-music.zip\"")));
        // 固定的下載網址（管理員自己看、放進資源包資料夾用；玩家登入時收到的是上面那個帶雜湊的）
        server.route("/pack.zip", path -> new WebServer.Response(200, "application/zip", pack.zip(),
                java.util.Map.of("Cache-Control", "no-cache", "Content-Disposition", "attachment; filename=\"exilecore-ui.zip\"")));
        for (final java.util.function.Consumer<WebServer> extension : extensions) {
            extension.accept(server);
        }
        plugin.getSLF4JLogger().info("網頁伺服器：{}（天賦規劃器 {}/tree{}，介面資源包 {}/pack.zip，{} KB{}）", publicUrl(), publicUrl(),
                economyEnabled ? "，市場報告 " + publicUrl() + "/economy" : "", publicUrl(), (pack.zip().length + 1023) / 1024,
                packSplit ? "；背景音樂 " + publicUrl() + "/music.zip，" + (musicPack.zip().length + 1023) / 1024 + " KB" : "");
        if (localOnly()) {
            plugin.getSLF4JLogger().warn("config.yml 的 web.public-url 沒填：介面資源包與網頁規劃器只給在這台電腦上連線的玩家。"
                    + "開放給別人玩之前，填上玩家連得到的網址（例如 http://你的IP或網域:{}），並開放這個埠", port);
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private byte[] loadResource(final String name) throws IOException {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                throw new IOException("插件裡沒有 " + name);
            }
            return in.readAllBytes();
        }
    }

    /** 天賦樹改了（/ec reload）就重新產生規劃器的資料。 */
    public void refreshPlannerData() {
        if (plugin.items() != null) {
            try {
                plannerItems = PlannerItems.json(plugin.items().repository());
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("規劃器的裝備資料做不出來：{}", e.toString());
            }
        }
        if (plugin.skills() != null) {
            try {
                plannerSkills = BuildCalc.skillsJson(plugin.skills().config());
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("網頁計算器的技能資料做不出來：{}", e.toString());
            }
        }
        try {
            codexData = CodexData.json(plugin); // 2026-10：圖鑑（技能、輔助寶石、通貨）
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("圖鑑的資料做不出來：{}", e.toString());
        }
        try {
            if (plugin.maps() != null) {
                atlasData = AtlasPlannerData.json(plugin.maps().tree()); // 2026-10-21：星圖天賦樹
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("星圖天賦的網頁資料做不出來：{}", e.toString());
        }
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        if (tree == null) {
            plannerData = "{\"error\":\"天賦樹沒有載入\"}".getBytes(StandardCharsets.UTF_8);
            return;
        }
        final List<PlannerData.ClassInfo> classes = new ArrayList<>();
        for (final CharacterClass type : CharacterClass.values()) {
            classes.add(new PlannerData.ClassInfo(type.id(), type.displayName(), hexColor(type.color())));
        }
        // 2026-10：昇華職業（規劃器的昇華預覽）
        final List<PlannerData.AscendancyInfo> ascendancies = new ArrayList<>();
        int ascendancyPoints = 0;
        if (plugin.ascendancy() != null) {
            final tw.exilecore.ascendancy.AscendancyConfig config = plugin.ascendancy().config();
            ascendancyPoints = config.totalPoints();
            for (final tw.exilecore.ascendancy.AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
                final List<List<PlannerData.AscendancyNode>> branches = new ArrayList<>();
                for (final List<tw.exilecore.ascendancy.AscendancyConfig.Node> branch : ascendancy.branches()) {
                    final List<PlannerData.AscendancyNode> nodes = new ArrayList<>();
                    for (final tw.exilecore.ascendancy.AscendancyConfig.Node node : branch) {
                        nodes.add(new PlannerData.AscendancyNode(node.name(), node.cost(),
                                tw.exilecore.ascendancy.StatLines.describeAll(node.stats()), node.note(), node.id()));
                    }
                    branches.add(nodes);
                }
                final String color = ascendancy.color() != null && ascendancy.color().startsWith("#") ? ascendancy.color() : "#f5c04a";
                ascendancies.add(new PlannerData.AscendancyInfo(ascendancy.id(), ascendancy.classId(), ascendancy.name(), color,
                        ascendancy.summary(), branches));
            }
        }
        plannerData = PlannerData.json(tree, classes, plugin.players().config().levelCap(),
                plugin.players().config().pointsPerLevel(), bonusPoints(), ascendancies, ascendancyPoints);
    }

    /** 區域 Boss、地圖 Boss 首殺、任務最多送幾點。 */
    private int bonusPoints() {
        int total = 0;
        try {
            if (plugin.quests() != null) {
                total += plugin.quests().config().totalPassivePoints();
            }
            if (plugin.zones() != null) {
                final int perBoss = plugin.zones().config().boss().firstKillPoints();
                for (final tw.exilecore.zone.Zone zone : plugin.zones().zones()) {
                    if (zone.boss() != null) {
                        total += perBoss;
                    }
                }
            }
            if (plugin.maps() != null) {
                final tw.exilecore.map.MapConfig config = plugin.maps().config();
                final tw.exilecore.map.MapConfig.FirstKill firstKill = config.firstKill();
                for (final tw.exilecore.map.MapConfig.MapDef map : config.all()) { // 含所有終局
                    total += map.isFinal() ? config.finalPoints(map.id()) : map.boss() != null && map.boss().named()
                            ? firstKill.namedPoints() : firstKill.mapPoints();
                }
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().debug("算首殺天賦點時出錯", e);
        }
        return total;
    }

    private static String hexColor(final String miniMessage) {
        final String text = miniMessage == null ? "" : miniMessage.replace("<", "").replace(">", "").trim();
        return text.matches("#[0-9a-fA-F]{6}") ? text.toLowerCase(Locale.ROOT) : "#f5b942";
    }

    // ---- 給其他模組用 ----

    /** 網頁角色頁；網頁模組沒啟用時 null。 */
    public Profiles profiles() {
        return profiles;
    }

    /** 加一組路由（管理網頁）：現在在跑就馬上裝，之後重開網頁伺服器時也會再裝。 */
    public void extend(final java.util.function.Consumer<WebServer> installer) {
        extensions.add(installer);
        if (server != null) {
            installer.accept(server);
        }
    }

    /** 玩家看到的網址是 https（Cookie 要加 Secure）。 */
    public boolean secure() {
        return publicUrl().toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** 網頁伺服器有沒有在跑。 */
    public boolean available() {
        return server != null;
    }

    /** 沒填 public-url、伺服器也沒有對外的位址：網址只有在這台電腦上打得開。 */
    public boolean localOnlyUrl() {
        return localOnly();
    }

    /** 這個玩家是不是從這台電腦連線的。 */
    public static boolean connectedLocally(final Player player) {
        return isLocal(player);
    }

    /** 網頁伺服器在跑、而且這個玩家連得到（沒填 public-url 時只有從本機連線的玩家連得到）。 */
    public boolean available(final Player player) {
        return server != null && (!localOnly() || isLocal(player));
    }

    /** 玩家看到的網址（沒有結尾的 /）。 */
    /** 首頁用的狀態：在線人數、有開哪些頁。任何執行緒都可以叫（只讀人數）。 */
    /** 贊助商店頁開著沒（web.shop.enabled，預設開）。 */
    private boolean shopEnabled() {
        return shopOn; // 2026-10-18：網頁執行緒只讀快照（refreshShopSnapshot）
    }

    /** 2026-10-18：主執行緒算商店開關與 /shop/data.json（啟動、/ec reload 時）。 */
    private void refreshShopSnapshot() {
        shopOn = plugin.getConfig().getBoolean("web.shop.enabled", true);
        final ShopCheckout current = checkout;
        try {
            shopJson = ShopData.json(plugin.getConfig().getConfigurationSection("web.shop"), plugin.sponsors() == null ? null : plugin.sponsors().config(),
                    current != null && current.ready(), current != null && current.sandbox());
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("贊助商店：商店資料產生失敗（沿用上一份）：{}", e.toString());
        }
    }

    private byte[] statusJson() {
        final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("online", plugin.getServer().getOnlinePlayers().size());
        root.addProperty("max", plugin.getServer().getMaxPlayers());
        final com.google.gson.JsonObject pages = new com.google.gson.JsonObject();
        pages.addProperty("ladder", plugin.ladder() != null);
        pages.addProperty("builds", profiles != null);
        pages.addProperty("economy", economyEnabled && plugin.economy() != null);
        pages.addProperty("shop", shopEnabled());
        root.add("pages", pages);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String publicUrl() {
        String url = configuredUrl == null ? "" : configuredUrl.trim();
        if (url.isEmpty()) {
            url = "http://" + (localHost() ? "localhost" : serverIp()) + ":" + (server == null ? port : server.port());
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 沒填 public-url、server.properties 也沒有指定對外的位址：網址只能是 localhost。 */
    private boolean localOnly() {
        return (configuredUrl == null || configuredUrl.isBlank()) && localHost();
    }

    private String serverIp() {
        final String ip = plugin.getServer().getIp();
        return ip == null ? "" : ip.trim();
    }

    private boolean localHost() {
        final String ip = serverIp();
        if (ip.isEmpty() || ip.equals("0.0.0.0") || ip.equals("::") || ip.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            return java.net.InetAddress.getByName(ip).isLoopbackAddress();
        } catch (final java.net.UnknownHostException e) {
            return false;
        }
    }

    private static boolean isLocal(final Player player) {
        final java.net.InetSocketAddress address = player.getAddress();
        return address != null && address.getAddress() != null && address.getAddress().isLoopbackAddress();
    }

    /** 併進別的插件的資源包（例如 BetterModel 重讀完）：重做一份，已經套用的人重送。 */
    public void setExternalPack(final String source, final Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) {
            externalPacks.remove(source);
        } else {
            externalPacks.put(source, Map.copyOf(files));
        }
        final String before = pack == null ? null : pack.sha1();
        mergePacks();
        if (!packReady) {
            markPackReady(); // 2026-10-21：開服後第一次併完：送給等著的人（他們還沒下載過，不用重送）
            return;
        }
        if (pack != null && !pack.sha1().equals(before)) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                if (packLoaded.contains(player.getUniqueId()) || required) {
                    sendPack(player);
                }
            }
        }
    }

    /** 音樂要不要拆成另一包：有音樂，而且不是「外部空間只給了一個網址」。 */
    private boolean splitMusic() {
        return musicPack != null && (externalUrl == null || externalUrl.isEmpty() || !externalMusicUrl.isEmpty());
    }

    /** 開服後可以送資源包了：送給等著的人（2026-10-21）。 */
    private void markPackReady() {
        if (packReady) {
            return;
        }
        packReady = true;
        for (final UUID id : List.copyOf(waitingPack)) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isConnected()) {
                sendPack(player);
            }
        }
        waitingPack.clear();
    }

    private void mergePacks() {
        if (ownPack == null) {
            return;
        }
        packSplit = splitMusic();
        final UiPack.Built base = packSplit ? ownPack : ownFull;
        final List<String> conflicts = new ArrayList<>();
        try {
            pack = UiPack.merge(base, externalPacks, conflicts);
        } catch (final IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("介面資源包：併不進 {}：{}", externalPacks.keySet(), e.toString());
            pack = base;
            writePackFile();
            return;
        }
        if (!conflicts.isEmpty()) {
            plugin.getSLF4JLogger().warn("介面資源包：{} 個檔案兩邊都有（留我們的）：{}", conflicts.size(),
                    String.join("、", conflicts.subList(0, Math.min(10, conflicts.size()))));
        }
        if (!externalPacks.isEmpty()) {
            plugin.getSLF4JLogger().info("介面資源包：併進 {}，共 {} KB", externalPacks.keySet(), (pack.zip().length + 1023) / 1024);
        }
        writePackFile();
    }

    /** 把現在的資源包寫到 plugins/ExileCore/exilecore-ui.zip（放外部空間的人上傳這個檔案）。 */
    private void writePackFile() {
        // 等 15 秒再寫（BetterModel 這類插件的資源包會晚一點併進來，只寫最後那份）
        final long token = ++writeToken;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (token == writeToken) {
                writePackFileNow();
            }
        }, 300);
    }

    private long writeToken;

    private void writePackFileNow() {
        writeMusicFileNow();
        if (pack == null || pack.sha1().equals(writtenSha1)) {
            return;
        }
        try {
            final java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("exilecore-ui.zip");
            writtenSha1 = pack.sha1();
            if (java.nio.file.Files.isRegularFile(file) && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file), pack.zip())) {
                return; // 跟上次一樣（重開伺服器不用重傳）
            }
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, pack.zip());
            warnedStale = false;
            if (externalUrl != null && !externalUrl.isEmpty()) {
                plugin.getSLF4JLogger().warn("介面資源包更新了（SHA1 {}）：請把 plugins/ExileCore/exilecore-ui.zip 重新上傳到 {}，不然玩家會下載失敗",
                        pack.sha1(), externalUrl);
            }
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("介面資源包寫不出 exilecore-ui.zip：{}", e.toString());
        }
    }

    /** 背景音樂那包寫到 plugins/ExileCore/exilecore-music.zip（2026-10-21；放外部空間的人上傳這個檔案到 external-music-url）。 */
    private void writeMusicFileNow() {
        if (musicPack == null || musicPack.sha1().equals(writtenMusicSha1)) {
            return;
        }
        try {
            final java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("exilecore-music.zip");
            writtenMusicSha1 = musicPack.sha1();
            if (java.nio.file.Files.isRegularFile(file) && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file), musicPack.zip())) {
                return;
            }
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, musicPack.zip());
            if (!externalMusicUrl.isEmpty()) {
                plugin.getSLF4JLogger().warn("背景音樂資源包更新了（SHA1 {}）：請把 plugins/ExileCore/exilecore-music.zip 重新上傳到 {}",
                        musicPack.sha1(), externalMusicUrl);
            }
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("背景音樂資源包寫不出 exilecore-music.zip：{}", e.toString());
        }
    }

    /** 這個玩家有沒有套用介面資源包（有的話天賦樹畫成星盤）。 */
    public boolean hasPack(final Player player) {
        return packLoaded.contains(player.getUniqueId());
    }

    /** 送出介面資源包（設定裡打開時、登入時）。玩家連不到網頁伺服器時不送（不然每次登入都跳出下載失敗）。 */
    public void sendPack(final Player player) {
        final boolean external = externalUrl != null && !externalUrl.isEmpty();
        if (pack == null || !external && !available(player)) {
            return;
        }
        final URI uri;
        try {
            uri = external ? URI.create(externalUrl) : URI.create(publicUrl() + "/pack/" + pack.sha1() + ".zip");
        } catch (final IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("config.yml 的 resource-pack.external-url 不是正確的網址：{}", externalUrl);
            return;
        }
        final ResourcePackInfo ui = ResourcePackInfo.resourcePackInfo(PACK_ID, uri, pack.sha1());
        // 2026-10-21：背景音樂另外一包（各自的雜湊，客戶端各自快取：更新插件時只重新下載介面那包；一次問、一起套用）
        ResourcePackInfo music = null;
        if (packSplit && musicPack != null) {
            try {
                music = ResourcePackInfo.resourcePackInfo(MUSIC_ID,
                        URI.create(external ? externalMusicUrl : publicUrl() + "/music/" + musicPack.sha1() + ".zip"), musicPack.sha1());
            } catch (final IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("config.yml 的 resource-pack.external-music-url 不是正確的網址：{}", externalMusicUrl);
            }
        }
        final long size = pack.zip().length + (music == null ? 0 : musicPack.zip().length);
        final ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest();
        if (music == null) {
            request.packs(ui);
        } else {
            request.packs(ui, music);
        }
        player.sendResourcePacks(request
                .required(required)
                .replace(false)
                .prompt(plugin.text().parse("<#f5b942>《星骸》介面資源包<gray>：星盤式的天賦樹、介面圖示、Boss 招式的地面警告（比較不卡）、背景音樂、按住左鍵施放時消掉敲方塊的聲音（約 "
                        + (size + 1023) / 1024 + " KB，第一次下載後會記住，之後更新只重新下載有改的部分）。不套用也能玩，下次登入會再問一次。"))
                .build());
    }

    /** 拿掉介面資源包（設定裡關掉時）。 */
    public void removePack(final Player player) {
        player.removeResourcePacks(PACK_ID, MUSIC_ID);
        packLoaded.remove(player.getUniqueId());
    }

    // ---- 事件 ----

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (!send) {
            return;
        }
        if (!packReady) {
            waitingPack.add(player.getUniqueId()); // 2026-10-21：開服後還在等 BetterModel 的資源包：併完一起送
            return;
        }
        // 2026-10：設定裡的開關拿掉了，登入一律送（以前關掉的人也會再被問一次；不要的在客戶端拒絕就好）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isConnected()) {
                sendPack(player);
            }
        }, 40);
    }

    @EventHandler
    public void onStatus(final PlayerResourcePackStatusEvent event) {
        if (MUSIC_ID.equals(event.getID())) {
            return; // 2026-10-21：背景音樂那包：套不套用都不影響介面
        }
        // 2026-10：走 Velocity（LimboAPI）時代理可能把資源包的代號換掉；代號對不上的當成介面那包
        //   （2026-10-21 起音樂另外一包、一起送，代號被換掉時音樂那包的狀態也會算到這裡）
        if (!PACK_ID.equals(event.getID())) {
            plugin.getSLF4JLogger().info("玩家 {} 的資源包狀態 {} 代號不是介面資源包的（{}），當成介面資源包處理",
                    event.getPlayer().getName(), event.getStatus(), event.getID());
        }
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                packLoaded.add(event.getPlayer().getUniqueId());
                plugin.getSLF4JLogger().info("玩家 {} 套用了介面資源包", event.getPlayer().getName());
            }
            case FAILED_DOWNLOAD, INVALID_URL -> {
                packLoaded.remove(event.getPlayer().getUniqueId());
                if (externalUrl != null && !externalUrl.isEmpty() && !warnedStale) {
                    warnedStale = true;
                    plugin.getSLF4JLogger().warn("玩家 {} 下載介面資源包失敗：外部空間的檔案可能不是最新的（要跟 plugins/ExileCore/exilecore-ui.zip 一樣，SHA1 {}），"
                            + "或網址不是直接下載的連結", event.getPlayer().getName(), pack == null ? "?" : pack.sha1());
                }
            }
            case DECLINED, FAILED_RELOAD, DISCARDED -> packLoaded.remove(event.getPlayer().getUniqueId());
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        packLoaded.remove(event.getPlayer().getUniqueId());
        waitingPack.remove(event.getPlayer().getUniqueId());
    }

    /** 技能圖示（2026-10）：掛在技能寶石、選單裡技能的原版物品（skills.yml 的 icon）上。 */
    private java.util.Set<UiPack.CurrencySkin> skillSkins() {
        final java.util.Set<UiPack.CurrencySkin> skins = new java.util.LinkedHashSet<>();
        if (plugin.skills() == null) {
            return skins;
        }
        for (final tw.exilecore.skill.SkillDefinition definition : plugin.skills().config().all()) {
            final String art = tw.exilecore.skill.SkillArt.artOf(definition);
            if (art == null) {
                continue;
            }
            final java.util.List<String> items = new java.util.ArrayList<>(tw.exilecore.skill.SkillArt.FALLBACK_ITEMS);
            items.add(tw.exilecore.skill.SkillArt.vanillaItem(definition.icon()));
            for (final String item : items) {
                // 只掛在有附原版物品定義（web/vanilla_items/）的物品上：沒有的話改寫後的定義會用錯模型，
                // 那個物品在所有地方都會壞掉（例如方塊類的鐵砧、標靶）
                if (item != null && WebModule.class.getResource("/web/vanilla_items/" + item + ".json") != null) {
                    skins.add(new UiPack.CurrencySkin(art, item, !definition.engravable())); // 專屬的：傳奇外框
                }
            }
        }
        // 2026-10：輔助寶石的圖示（寶石本身是惡魂之淚；選單裡的是 supports.yml 的 icon）
        for (final tw.exilecore.skill.SupportDefinition support : plugin.skills().config().supports()) {
            final String art = tw.exilecore.skill.SkillArt.supportArtOf(support.id());
            if (art == null) {
                continue;
            }
            for (final String item : java.util.Arrays.asList("ghast_tear", tw.exilecore.skill.SkillArt.vanillaItem(support.icon()))) {
                if (item != null && WebModule.class.getResource("/web/vanilla_items/" + item + ".json") != null) {
                    skins.add(new UiPack.CurrencySkin(art, item, !support.cuttable())); // 專屬的：傳奇外框
                }
            }
        }
        return skins;
    }

    /** 技能寶石、輔助寶石的貼圖（2026-10）：跟通貨一樣用 custom_model_data 字串掛在原版物品上。 */
    private static List<UiPack.CurrencySkin> gemSkins(final List<UiPack.CurrencySkin> skins, final java.util.Set<UiPack.CurrencySkin> skillSkins) {
        final List<UiPack.CurrencySkin> all = new java.util.ArrayList<>(skins);
        all.addAll(skillSkins);
        all.add(new UiPack.CurrencySkin("gem_skill", "heart_of_the_sea"));
        all.add(new UiPack.CurrencySkin("gem_support", "ghast_tear"));
        all.add(new UiPack.CurrencySkin("star_voucher", "paper")); // 星幣券（2026-10）
        all.sort(java.util.Comparator.comparing(UiPack.CurrencySkin::vanillaItem).thenComparing(UiPack.CurrencySkin::artId));
        return all;
    }
}
