package tw.exilecore.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import tw.exilecore.client.core.AlertData;
import tw.exilecore.client.core.DeathData;
import tw.exilecore.client.core.Features;
import tw.exilecore.client.core.HudDrag;
import tw.exilecore.client.core.MinimapData;

/**
 * 2026-11-02 照站長（C 組）：預警（{@link AlertRenderer}）、小地圖（{@link MinimapRenderer}）、死亡回顧（{@link DeathPanel}）、
 * 精簡的裝備說明（{@link TooltipEnhancer}）、設定畫面（{@link SettingsScreen}、{@link HudEditScreen}）的入口。
 * {@link ExileCoreClient} 只在入口叫這裡（開機、按鍵、招呼、斷線）。
 * <p>
 * 按鍵（分類「星骸」，避開原版與 F6～F9）：設定畫面 O、大地圖 M、死亡回顧 J（F10 是 A 組的收起面板、F6 是 B 組的地圖資訊框）。
 */
final class FeedsClient {

    private FeedsClient() {
    }

    static final AlertData ALERTS = new AlertData();
    static volatile MinimapData minimap;
    static volatile DeathData lastDeath;
    /** 這次死亡的回顧（死亡畫面上畫；重生後要按鍵才看）。 */
    static volatile boolean deathFresh;
    /** 模組自己的 tick（預警、倒數用）。 */
    static long ticks;
    static boolean bigMap;
    static KeyMapping settingsKey;
    static KeyMapping mapKey;
    static KeyMapping deathKey;
    private static ClientLevel level;
    private static boolean wasDead;

    static void registerKeys(final KeyMapping.Category category) {
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ExileCoreClient.MOD_ID + ".settings", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O, category));
        mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ExileCoreClient.MOD_ID + ".big_map", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M, category));
        deathKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ExileCoreClient.MOD_ID + ".death_recap", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J, category));
    }

    static void init() {
        HudDrag.use(ExileCoreClient.config);
        FeedPayloads.register();
        ClientPlayNetworking.registerGlobalReceiver(FeedPayloads.Alert.TYPE, (payload, context) -> {
            if (ExileCoreClient.connected) {
                syncLevel(Minecraft.getInstance().level);
                ALERTS.apply(AlertData.parse(payload.data()), ticks);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(FeedPayloads.Minimap.TYPE, (payload, context) -> {
            if (ExileCoreClient.connected) {
                onMinimap(MinimapData.parse(payload.data()));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(FeedPayloads.Death.TYPE, (payload, context) -> {
            if (ExileCoreClient.connected) {
                final DeathData data = DeathData.parse(payload.data());
                if (data != null) {
                    lastDeath = data;
                    deathFresh = true;
                }
            }
        });
        HudDragBinding.registerDefaults();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ExileCoreClient.MOD_ID, "minimap"), MinimapRenderer::extract);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ExileCoreClient.MOD_ID, "alerts"), AlertRenderer::extract);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> DeathPanel.attach(screen));
        // 2026-11-02 照站長：聊天打開時點側邊面板的標題列收起／展開（這次之前只有 F10 整群收）
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                        event.button() != 0 || !PanelRenderer.click(event.x(), event.y()));
            }
        });
        TooltipEnhancer.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeedsClient::tick);
    }

    private static void onMinimap(final MinimapData.Packet packet) {
        if (packet == null) {
            return;
        }
        switch (packet.op()) {
            case "init" -> {
                minimap = packet.data();
                MinimapRenderer.clear();
            }
            case "clear" -> {
                minimap = null;
                bigMap = false;
            }
            default -> {
                final MinimapData current = minimap;
                if (current != null) {
                    current.apply(packet);
                }
            }
        }
    }

    /** 招呼多寫的旗標（{@link Features}）。 */
    static byte[] withFlags(final byte[] hello) {
        final var config = ExileCoreClient.config;
        return Features.withFlags(hello, config.showAlerts, config.showMinimap, config.showDeathPanel, config.compactTooltips);
    }

    /** 換伺服器、斷線。 */
    static void reset() {
        ALERTS.clear();
        minimap = null;
        bigMap = false;
        deathFresh = false;
        level = null;
        MinimapRenderer.clear();
    }

    /** 換世界（進地圖、回城）：預警清掉。 */
    private static void syncLevel(final ClientLevel current) {
        if (current != level) {
            level = current;
            ALERTS.clear();
        }
    }

    private static void tick(final Minecraft mc) {
        ticks++;
        syncLevel(mc.level);
        while (settingsKey.consumeClick()) {
            if (mc.gui.screen() == null) {
                mc.gui.setScreen(new SettingsScreen(null));
            }
        }
        while (mapKey.consumeClick()) {
            bigMap = minimap != null && !bigMap;
        }
        while (deathKey.consumeClick()) {
            if (mc.gui.screen() == null && ExileCoreClient.connected) {
                mc.gui.setScreen(new DeathPanel.RecapScreen());
            }
        }
        if (mc.player != null) {
            final boolean alive = mc.player.isAlive();
            if (alive && wasDead) {
                deathFresh = false; // 重生了：死亡畫面的面板收起來（按鍵還叫得出來）
            }
            wasDead = !alive;
        }
        final MinimapData data = minimap;
        if (data != null && mc.player != null && ExileCoreClient.connected) {
            data.reveal(mc.player.getX(), mc.player.getZ(), MinimapData.REVEAL);
        }
        if (mc.player != null) {
            final var window = mc.getWindow();
            HudDrag.frame(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        }
    }

    static String settingsKeyName() {
        return name(settingsKey, "O");
    }

    static String mapKeyName() {
        return name(mapKey, "M");
    }

    static String deathKeyName() {
        return name(deathKey, "J");
    }

    private static String name(final KeyMapping key, final String fallback) {
        return key == null ? fallback : key.getTranslatedKeyMessage().getString();
    }
}
