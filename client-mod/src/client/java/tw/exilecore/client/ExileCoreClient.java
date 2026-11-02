package tw.exilecore.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.ModConfig;
import tw.exilecore.client.core.Protocol;
import tw.exilecore.client.core.Updater;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * 星骸輔助模組（exilecore_client）：連上星骸伺服器時才有作用（伺服器回了 exilecore:hello），別的伺服器什麼都不做。
 * <ul>
 *   <li>10 個技能鍵（分類「星骸」，預設沒綁）：按下／放開送 exilecore:key，伺服器照按那一格處理（跟滑鼠、數字鍵一樣）。</li>
 *   <li>技能列、增益列、目標框、生命與能量護盾（取代原版的愛心）：照伺服器送的 exilecore:hud 畫。
 *       1.2（2026-11-02）：預設畫成快捷欄兩側的生命球、魔力球（{@link OrbRenderer}），按 F9 切回原本的生命條。</li>
 *   <li>自動更新：開遊戲時與伺服器說有新版時，下載、驗證，關遊戲時換上（{@link Updater}）。</li>
 *   <li>1.1：特效改由模組繪製（exilecore:fx，{@link FxRenderer}）：招呼說 effects:true，伺服器就不送那些粒子給我們，改送特效的描述；
 *       設定「drawEffects」、按鍵「開關特效改由模組繪製」（預設 F8）關掉時重打招呼，伺服器改回送粒子。</li>
 *   <li>1.4（2026-11-02 照站長）：招呼多寫功能旗標（notice、panels、res、status、sides），伺服器照這個停掉原版那一條：
 *       提示列（exilecore:notice，{@link NoticeRenderer}）、側邊面板（exilecore:panels，{@link PanelRenderer}；F10 收起）、
 *       生命球旁的異常與守護（{@link StatusRenderer}）、魔力球旁的戰鬥資源（{@link ResourceRenderer}）、經驗條上的字（{@link XpRenderer}）、
 *       光環併進增益列，目標框避開 Boss 血條。</li>
 * </ul>
 */
public final class ExileCoreClient implements ClientModInitializer {

    public static final String MOD_ID = "exilecore_client";
    static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 按鍵格的名字（翻譯鍵的後半）：左鍵、右鍵、潛行＋左鍵、潛行＋右鍵、數字鍵格 1～6。 */
    static final String[] SLOT_KEYS = {"left", "right", "sneak_left", "sneak_right", "hotbar_1", "hotbar_2", "hotbar_3", "hotbar_4",
            "hotbar_5", "hotbar_6"};
    /** 技能列上沒綁按鍵時寫的格子名稱。 */
    static final String[] SLOT_LABELS = {"左", "右", "蹲左", "蹲右", "1", "2", "3", "4", "5", "6"};

    static ModConfig config = new ModConfig();
    static Path configFile;
    static final KeyMapping[] SKILL_KEYS = new KeyMapping[Protocol.SLOTS];
    static KeyMapping toggleHud;
    static KeyMapping toggleEffects;
    /** 2026-11-02：生命球、魔力球 ↔ 原本的生命條（設定 orbs）。 */
    static KeyMapping toggleOrbs;
    /** 2026-11-02（1.4）：收起／展開側邊面板（設定 panelsCollapsed）。 */
    static KeyMapping togglePanels;
    /** 2026-11-02（1.4）：開遊戲後過了幾 tick（提示列淡出用）。 */
    private static long clientTicks;

    /** 這次連線伺服器有沒有回招呼（協定對得上）：沒有就什麼都不畫、按鍵也不送。 */
    static volatile boolean connected;
    private static boolean helloSent;
    /** 2026-11-02 照站長：送出招呼時的 clientTicks（伺服器沒回就重送一次、再沒回在聊天提示）；這次之前沒回就默默不畫。 */
    private static long helloSentAt;
    private static boolean helloRetried;
    private static boolean warnedNoAnswer;
    /** 這次招呼用的協定版本（舊伺服器回 1 時改用 1）。 */
    private static int helloProtocol = Protocol.PROTOCOL;
    /** 伺服器說它會送 exilecore:fx（我們要了、它也支援）。 */
    static volatile boolean serverEffects;
    /** 這次連線已經提示過伺服器不支援模組繪製特效。 */
    private static boolean warnedNoEffects;
    /** 剛打開「特效改由模組繪製」：伺服器回招呼時告訴玩家開了沒。 */
    private static boolean effectsNotice;

    private static final boolean[] lastDown = new boolean[Protocol.SLOTS];
    /** 最新的畫面資料與收到後過了幾 tick。 */
    static volatile HudData hud;
    static volatile long hudAge;

    static Updater updater;
    private static String modVersion = "0.0.0";
    /** 這次開遊戲已經為伺服器說的哪個版本查過更新。 */
    private static String attemptedVersion;

    @Override
    public void onInitializeClient() {
        final FabricLoader loader = FabricLoader.getInstance();
        configFile = loader.getConfigDir().resolve(MOD_ID + ".json");
        config = ModConfig.load(configFile);
        final ModContainer self = loader.getModContainer(MOD_ID).orElseThrow();
        modVersion = self.getMetadata().getVersion().getFriendlyString();

        Payloads.register();
        registerKeys();
        registerNetworking();
        registerHud();
        WorldHudClient.init(); // 2026-11-02（1.4 B 組）：地圖資訊框、傷害數字、頭上血條、準心
        FeedsClient.init(); // 2026-11-02（C 組）：預警、小地圖、死亡回顧、說明欄、設定畫面
        UiNet.init(); // 2026-11-02（1.4 D 組）：天賦樹、星圖、星圖天賦、昇華天賦的原生畫面
        LevelRenderEvents.COLLECT_SUBMITS.register(FxRenderer::render);
        ClientTickEvents.END_CLIENT_TICK.register(ExileCoreClient::tick);
        startUpdater(loader, self);
    }

    private static void registerKeys() {
        final KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        for (int slot = 0; slot < Protocol.SLOTS; slot++) {
            SKILL_KEYS[slot] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + "." + SLOT_KEYS[slot],
                    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        }
        toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".toggle_hud", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7, category));
        toggleEffects = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".toggle_effects", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8, category));
        toggleOrbs = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".toggle_orbs", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9, category));
        togglePanels = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".toggle_panels", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10, category)); // F6 是地圖資訊框（B 組）
        WorldHudClient.registerKeys(category); // 2026-11-02（1.4 B 組）
        FeedsClient.registerKeys(category); // 2026-11-02（C 組）：設定 O、大地圖 M、死亡回顧 J
        UiNet.registerKeys(category); // 2026-11-02（1.4 D 組）：開天賦樹（K）、開星圖（N）
    }

    private static void registerNetworking() {
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Hello.TYPE, (payload, context) -> onServerHello(payload.data()));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Hud.TYPE, (payload, context) -> {
            if (!connected) {
                return;
            }
            final HudData parsed = HudData.parse(payload.data());
            if (parsed != null) {
                hud = parsed;
                hudAge = 0;
                XpRenderer.observe(parsed);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Notice.TYPE, (payload, context) -> {
            if (connected) {
                NoticeRenderer.onPacket(payload.data());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.PanelData.TYPE, (payload, context) -> {
            if (connected) {
                PanelRenderer.onPacket(payload.data());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Fx.TYPE, (payload, context) -> {
            if (connected) {
                FxRenderer.onPacket(payload.data()); // 關掉以後還在路上的、收過的範圍的 UPDATE／STOP 也照畫完
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            reset();
            if (ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
                sendHello();
            }
        });
        // 伺服器的頻道清單有時比 JOIN 晚到：收到 exilecore:hello 的時候再打一次招呼
        ServerboundPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (!helloSent && channels.contains(Payloads.HELLO_ID)) {
                client.execute(ExileCoreClient::sendHello);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void registerHud() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), HudRenderer::extract);
        // 生命、護盾取代原版的愛心：只在連上星骸伺服器、而且收到資料時；不然照原版畫
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, vanilla -> (graphics, delta) -> {
            HudDragBinding.begin(graphics, HudDragBinding.ORBS); // 2026-11-02（C 組）：設定畫面拖過的位置
            final boolean drawn = HudRenderer.extractHearts(graphics);
            HudDragBinding.end(graphics);
            if (!drawn) {
                vanilla.extractRenderState(graphics, delta);
            }
        });
        // 2026-11-02 照站長：畫生命球、魔力球時原版的飢餓條也藏起來（伺服器的飢餓一直是滿的，沒有用）；
        //   切回原本的生命條（F9）、沒連上星骸時照原版。盔甲、氧氣、坐騎血量是別的元素，照原位
        HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, vanilla -> (graphics, delta) -> {
            if (!HudRenderer.orbsShown()) {
                vanilla.extractRenderState(graphics, delta);
            }
        });
        // 1.3：有球時技能列放在快捷欄正上方（原本愛心、盔甲那兩排的高度），盔甲、氧氣、坐騎血量整排往上移到技能列上面
        //   （動作列下面那一排）；沒有球照原位
        for (final Identifier bar : List.of(VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH)) {
            HudElementRegistry.replaceElement(bar, vanilla -> (graphics, delta) -> {
                final int lift = HudRenderer.statusBarLift(bar);
                if (lift == 0) {
                    vanilla.extractRenderState(graphics, delta);
                    return;
                }
                graphics.pose().pushMatrix();
                graphics.pose().translate(0, -lift);
                vanilla.extractRenderState(graphics, delta);
                graphics.pose().popMatrix();
            });
        }
    }

    private static void reset() {
        connected = false;
        helloSent = false;
        helloRetried = false;
        warnedNoAnswer = false;
        helloProtocol = Protocol.PROTOCOL;
        serverEffects = false;
        warnedNoEffects = false;
        effectsNotice = false;
        FxRenderer.clear();
        OrbRenderer.reset();
        NoticeRenderer.clear();
        PanelRenderer.clear();
        XpRenderer.RATE.reset();
        WorldHudClient.reset(); // 2026-11-02（1.4 B 組）
        FeedsClient.reset(); // 2026-11-02（C 組）
        UiNet.reset(); // 2026-11-02（1.4 D 組）
        hud = null;
        java.util.Arrays.fill(lastDown, false);
    }

    private static void sendHello() {
        if (helloSent || !ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
            return;
        }
        helloSent = true;
        helloSentAt = clientTicks;
        LOG.info("星骸輔助模組：打招呼（協定 {}）", helloProtocol);
        ClientPlayNetworking.send(new Payloads.Hello(helloBytes()));
    }

    private static byte[] helloBytes() {
        // 2026-11-02（C 組）：再多寫 alerts、minimap、death、tooltip（Features）；（D 組）再多寫 treeui、atlasui（Features.withUi）
        return UiNet.extendHello(FeedsClient.withFlags(WorldHudClient.withFeatures(Protocol.hello(helloProtocol, modVersion, drawsHud(), drawsHearts(), drawsEffects(), drawsOrbs(), helloFlags())))); // 2026-11-02：A、B、C、D 組旗標
    }

    /**
     * 2026-11-02 照站長（1.4）：招呼的功能旗標——模組這次會畫的，伺服器才停掉原版那一條。異常、守護、戰鬥資源畫在球旁邊，沒有球（F9）不說；
     * 光環併進增益列、召喚物與撿到的東西是面板，增益列或面板關掉就不說。F7 關掉 HUD 時全部不說（照原版）。
     */
    static java.util.Set<String> helloFlags() {
        final java.util.Set<String> flags = new java.util.TreeSet<>();
        if (!config.hudEnabled) {
            return flags;
        }
        if (config.showNotices) {
            flags.add(Protocol.FLAG_NOTICE);
        }
        if (config.showPanels) {
            flags.add(Protocol.FLAG_PANELS);
        }
        if (drawsOrbs() && config.showResources) {
            flags.add(Protocol.FLAG_RES);
        }
        if (drawsOrbs() && config.showStatus) {
            flags.add(Protocol.FLAG_STATUS);
        }
        if (config.showSides && config.showBuffs && config.showPanels) {
            flags.add(Protocol.FLAG_SIDES);
        }
        return flags;
    }

    /** 2026-11-02（1.4）：現在的時間（tick，加上這一幀過了多少）。 */
    static double clock(final float partial) {
        return clientTicks + partial;
    }

    /** HUD 或特效開關換了：重新打招呼（伺服器才知道動作列要不要寫增益倒數、要不要用吸收愛心畫護盾、要不要送粒子）。 */
    static void resendHello() { // 2026-11-02（C 組）：設定畫面改了開關也要叫
        if (connected && ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
            ClientPlayNetworking.send(new Payloads.Hello(helloBytes()));
        }
    }

    /** 要不要自己畫特效（設定「特效改由模組繪製」）。 */
    static boolean drawsEffects() {
        return config.drawEffects;
    }

    static boolean drawsHud() {
        return config.hudEnabled && (config.showSkillBar || config.showBuffs);
    }

    static boolean drawsHearts() {
        return config.hudEnabled && config.replaceHearts;
    }

    /** 2026-11-02（1.3）：生命、魔力畫成球（伺服器照這個決定動作列、經驗條、快捷欄兩側的字）。 */
    static boolean drawsOrbs() {
        return drawsHearts() && config.orbs;
    }

    private static void onServerHello(final byte[] data) {
        final Protocol.ServerHello hello = Protocol.serverHello(data);
        final int fallback = hello == null ? 0 : Protocol.fallback(helloProtocol, hello.protocol());
        if (fallback > 0) {
            // 舊伺服器不認得我們的協定（把我們當成沒裝）：改用它的版本再打一次招呼
            LOG.info("星骸伺服器的協定是 {}（舊版），改用 {} 打招呼；特效照舊用伺服器的粒子", hello.protocol(), fallback);
            helloProtocol = fallback;
            if (ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
                ClientPlayNetworking.send(new Payloads.Hello(helloBytes()));
            }
            maybeUpdate(hello);
            return;
        }
        if (hello == null || !Protocol.supported(hello.protocol())) {
            connected = false;
            if (hello != null) {
                LOG.warn("星骸伺服器的協定版本是 {}，這個模組是 {}：先不啟用（請更新模組）", hello.protocol(), Protocol.PROTOCOL);
            }
            maybeUpdate(hello);
            return;
        }
        if (!connected) {
            LOG.info("星骸輔助模組：伺服器回了招呼（協定 {}），開始畫 HUD", hello.protocol());
        }
        connected = true;
        serverEffects = hello.effects();
        if (drawsEffects() && !hello.effects() && !warnedNoEffects) {
            warnedNoEffects = true;
            LOG.info("星骸伺服器沒有開模組繪製特效（協定 {}），照舊用伺服器的粒子", hello.protocol());
        }
        if (effectsNotice) {
            effectsNotice = false;
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + MOD_ID + "."
                        + (hello.effects() ? "effects_on" : "effects_unavailable")));
            }
        }
        maybeUpdate(hello);
    }

    /** 伺服器說有比較新的版本：記下說明檔的網址，下載（每次開遊戲最多查一次，除非伺服器說的版本又更新）。 */
    private static void maybeUpdate(final Protocol.ServerHello hello) {
        if (hello == null || hello.manifestUrl() == null || hello.manifestUrl().isBlank()) {
            return;
        }
        if (!hello.manifestUrl().equals(config.lastManifestUrl)) {
            config.lastManifestUrl = hello.manifestUrl();
            saveConfig();
        }
        if (updater == null || hello.latestModVersion().isBlank() || Protocol.compareVersions(hello.latestModVersion(), modVersion) <= 0) {
            return;
        }
        final Updater.Pending pending = updater.pending();
        if (pending != null && Protocol.compareVersions(hello.latestModVersion(), pending.version()) <= 0) {
            return; // 已經下載好了
        }
        if (hello.latestModVersion().equals(attemptedVersion)) {
            return; // 這次開遊戲已經為這個版本查過（下載失敗的話不要每次進伺服器都重試）
        }
        attemptedVersion = hello.latestModVersion();
        check(config.effectiveManifestUrl());
    }

    private static void startUpdater(final FabricLoader loader, final ModContainer self) {
        Path jar = null;
        try {
            final List<Path> paths = self.getOrigin().getPaths();
            jar = paths.size() == 1 ? paths.getFirst() : null;
        } catch (final UnsupportedOperationException e) {
            // 巢狀載入：不能自己更新
        }
        updater = new Updater(loader.getGameDir().resolve("mods"), loader.getConfigDir(), modVersion, jar);
        updater.startup(config.pendingVersion(), config.pendingSha256());
        final String url = config.effectiveManifestUrl();
        if (!url.isBlank()) {
            check(url);
        }
    }

    private static void check(final String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (final IllegalArgumentException e) {
            return;
        }
        updater.checkAsync(uri, config.autoUpdate, message -> {
            LOG.info(message);
            notifyPlayer(Component.literal(message), false);
        }, pending -> {
            config.setPending(pending.version(), pending.sha256());
            saveConfig();
            LOG.info("星骸輔助模組 {} 已下載（{}），關遊戲時換上", pending.version(), pending.file());
            notifyPlayer(Component.translatable("message." + MOD_ID + ".update_ready", pending.version()), true);
        });
    }

    /** 提示玩家（聊天訊息；toast = 也跳一個通知）。在遊戲主執行緒上做；還沒進世界的訊息等進世界再說。 */
    private static void notifyPlayer(final Component message, final boolean toast) {
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (toast) {
                SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("toast." + MOD_ID + ".title"), message);
            }
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[星骸] ").append(message));
            } else {
                pendingChat = message;
            }
        });
    }

    private static Component pendingChat;

    static void saveConfig() {
        try {
            config.save(configFile);
        } catch (final IOException e) {
            LOG.warn("存不了設定檔 {}：{}", configFile, e.toString());
        }
    }

    /** 招呼送出 5 秒伺服器沒回：重送一次；15 秒還沒回：聊天提示一次、寫進 log（看得出來是哪一段斷掉）。 */
    private static void watchHello(final Minecraft mc) {
        if (!helloSent || connected || mc.player == null) {
            return;
        }
        final long waited = clientTicks - helloSentAt;
        if (!helloRetried && waited >= 100) {
            helloRetried = true;
            LOG.warn("星骸輔助模組：伺服器 5 秒沒回招呼，再送一次（能送 hello：{}）", ClientPlayNetworking.canSend(Payloads.Hello.TYPE));
            if (ClientPlayNetworking.canSend(Payloads.Hello.TYPE)) {
                ClientPlayNetworking.send(new Payloads.Hello(helloBytes()));
            }
        } else if (!warnedNoAnswer && waited >= 300) {
            warnedNoAnswer = true;
            LOG.warn("星骸輔助模組：伺服器 15 秒都沒回招呼；伺服器登記的頻道：{}", ClientPlayNetworking.getSendable());
            mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + MOD_ID + ".no_answer"));
        }
    }

    private static void tick(final Minecraft mc) {
        hudAge++;
        clientTicks++;
        while (togglePanels.consumeClick()) {
            config.panelsCollapsed = !config.panelsCollapsed;
            saveConfig();
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + MOD_ID + "."
                        + (config.panelsCollapsed ? "panels_collapsed" : "panels_expanded")));
            }
        }
        while (toggleHud.consumeClick()) {
            config.hudEnabled = !config.hudEnabled;
            saveConfig();
            resendHello();
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable(config.hudEnabled
                        ? "message." + MOD_ID + ".hud_on" : "message." + MOD_ID + ".hud_off"));
            }
        }
        while (toggleEffects.consumeClick()) {
            config.drawEffects = !config.drawEffects;
            saveConfig();
            resendHello(); // 伺服器照新的招呼決定送不送粒子（已經在畫的範圍畫到結束）
            if (config.drawEffects && connected) {
                effectsNotice = true; // 等伺服器回招呼再說開了沒（伺服器可能不支援）
            } else if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + MOD_ID + "."
                        + (config.drawEffects ? "effects_on" : "effects_off")));
            }
        }
        while (toggleOrbs.consumeClick()) {
            config.orbs = !config.orbs;
            saveConfig();
            resendHello(); // 1.3：伺服器照這個決定動作列寫不寫生命魔力與等級、經驗條顯示魔力還是角色經驗
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + MOD_ID + "." + (config.orbs ? "orbs_on" : "orbs_off")));
            }
        }
        watchHello(mc);
        UiNet.tick(mc); // 2026-11-02（1.4 D 組）：開天賦樹、星圖的鍵
        FxRenderer.tick(mc); // 換世界（進地圖、回城）時清掉畫面上的範圍（FxRenderer.syncLevel）
        if (mc.player != null && pendingChat != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[星骸] ").append(pendingChat));
            pendingChat = null;
        }
        for (int slot = 0; slot < Protocol.SLOTS; slot++) {
            final KeyMapping key = SKILL_KEYS[slot];
            while (key.consumeClick()) {
                // 不讓按鍵的點擊累積（我們只看按下、放開）
            }
            final boolean down = connected && mc.player != null && mc.gui.screen() == null && key.isDown();
            if (down != lastDown[slot]) {
                lastDown[slot] = down;
                if (connected && ClientPlayNetworking.canSend(Payloads.Key.TYPE)) {
                    ClientPlayNetworking.send(new Payloads.Key(Protocol.key(slot, down)));
                }
            }
        }
    }
}
