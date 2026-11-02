package tw.exilecore.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tw.exilecore.client.core.AtlasModel;
import tw.exilecore.client.core.Features;
import tw.exilecore.client.core.TreeCache;
import tw.exilecore.client.core.TreeModel;
import tw.exilecore.client.core.UiWire;

import java.util.HashMap;
import java.util.Map;

/**
 * 2026-11-02 照站長（D 組）：天賦樹、星圖、星圖天賦、昇華天賦的原生畫面（{@link PassiveTreeScreen}、{@link AtlasScreen}）的連線這一邊。
 * <ul>
 *   <li>招呼多寫 {@code "treeui":true,"atlasui":true}（{@link #extendHello}）：新插件就把開天賦樹、星圖的地方改成送狀態給我們；
 *       舊插件不認得、照舊開箱子選單。</li>
 *   <li>收 exilecore:ui 的資料（{@link UiWire.Assembler} 接起來）：狀態裡寫了靜態資料的雜湊，{@link TreeCache} 沒有才送 static 去要。</li>
 *   <li>按鍵（分類「星骸」）：天賦樹（預設 K）、星圖（預設 N）：送 open，伺服器檢查完回狀態，我們打開畫面。</li>
 * </ul>
 */
final class UiNet {

    private UiNet() {
    }

    static KeyMapping openTree;
    static KeyMapping openAtlas;
    private static final UiWire.Assembler ASSEMBLER = new UiWire.Assembler();
    private static TreeCache cache = new TreeCache(null);
    /** 等靜態資料的狀態（種類 → 狀態 JSON）。 */
    private static final Map<String, JsonObject> WAITING = new HashMap<>();
    /** 這次連線已經提示過伺服器不支援。 */
    private static boolean warned;

    static void init() {
        UiPayloads.register();
        cache = new TreeCache(FabricLoader.getInstance().getConfigDir().resolve(ExileCoreClient.MOD_ID).resolve("ui-cache"));
        ClientPlayNetworking.registerGlobalReceiver(UiPayloads.Data.TYPE, (payload, context) -> onFrame(payload.data()));
    }

    /** 按鍵放在「星骸」分類（F6～F10 是 HUD 的開關，O 設定、M 大地圖、J 死亡回顧是 C 組的；這裡用 K、N）。 */
    static void registerKeys(final KeyMapping.Category category) {
        openTree = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ExileCoreClient.MOD_ID + ".open_tree", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K, category));
        openAtlas = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ExileCoreClient.MOD_ID + ".open_atlas", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N, category));
    }

    /** 招呼多寫我們的旗標（{@link Features#withUi}；只多欄位，舊插件忽略）。 */
    static byte[] extendHello(final byte[] hello) {
        return Features.withUi(hello, true, true);
    }

    static void reset() {
        ASSEMBLER.clear();
        WAITING.clear();
        warned = false;
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui.screen() instanceof PassiveTreeScreen || mc.gui.screen() instanceof AtlasScreen) {
                mc.gui.setScreen(null);
            }
        });
    }

    static boolean canSend() {
        return ExileCoreClient.connected && ClientPlayNetworking.canSend(UiPayloads.Action.TYPE);
    }

    static void send(final byte[] action) {
        if (canSend()) {
            ClientPlayNetworking.send(new UiPayloads.Action(action));
        }
    }

    /** 要打開某個畫面（按鍵、畫面上的按鈕）。 */
    static void requestOpen(final String kind) {
        final Minecraft mc = Minecraft.getInstance();
        if (!canSend()) {
            if (!warned && mc.player != null && ExileCoreClient.connected) {
                warned = true;
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + ExileCoreClient.MOD_ID + ".ui_unavailable"));
            }
            return;
        }
        send(UiWire.action(kind, "open"));
    }

    static void tick(final Minecraft mc) {
        while (openTree.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                requestOpen("passive");
            }
        }
        while (openAtlas.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                requestOpen("atlas");
            }
        }
    }

    private static void onFrame(final byte[] message) {
        if (!ExileCoreClient.connected) {
            return;
        }
        final UiWire.Blob blob = ASSEMBLER.accept(UiWire.decode(message));
        if (blob == null) {
            return;
        }
        try {
            final String name = blob.name();
            final int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                return;
            }
            final String kind = name.substring(0, dot);
            final String part = name.substring(dot + 1);
            if ("static".equals(part)) {
                cache.put(kind, blob.hash(), blob.json());
                final JsonObject waiting = WAITING.remove(kind);
                if (waiting != null) {
                    onState(kind, waiting);
                }
            } else if ("sum".equals(part)) {
                // 2026-11-02 照站長：路徑預覽「整條路加起來的能力」
                final JsonObject reply = JsonParser.parseString(blob.text()).getAsJsonObject();
                if (Minecraft.getInstance().gui.screen() instanceof PassiveTreeScreen screen && screen.kind().equals(kind)) {
                    final java.util.List<String> path = new java.util.ArrayList<>();
                    reply.getAsJsonArray("path").forEach(e -> path.add(e.getAsString()));
                    final java.util.List<String> lines = new java.util.ArrayList<>();
                    reply.getAsJsonArray("lines").forEach(e -> lines.add(e.getAsString()));
                    screen.onSummary(path, lines);
                }
            } else if ("state".equals(part)) {
                onState(kind, JsonParser.parseString(blob.text()).getAsJsonObject());
            }
        } catch (final RuntimeException e) {
            ExileCoreClient.LOG.warn("星骸原生畫面：讀不懂伺服器的資料 {}：{}", blob.name(), e.toString());
        }
    }

    private static void onState(final String kind, final JsonObject state) {
        final Minecraft mc = Minecraft.getInstance();
        final boolean open = state.has("open") && state.get("open").getAsBoolean();
        if ("atlas".equals(kind)) {
            final AtlasModel model = new AtlasModel(state);
            if (mc.gui.screen() instanceof AtlasScreen screen) {
                screen.update(model);
            } else if (open) {
                mc.gui.setScreen(new AtlasScreen(model));
            }
            return;
        }
        final TreeModel.Data data;
        if (state.has("inline") && state.get("inline").getAsBoolean()) {
            data = TreeModel.parseData(kind, 0, state); // 昇華天賦：樹直接帶在狀態裡
        } else {
            final long hash = TreeModel.staticHashOf(state);
            data = cache.get(kind, hash);
            if (data == null) {
                WAITING.put(kind, state);
                send(UiWire.action(kind, "static"));
                return;
            }
        }
        final TreeModel model = new TreeModel(data, TreeModel.parseState(state, data));
        final Screen current = mc.gui.screen();
        if (current instanceof PassiveTreeScreen screen && screen.kind().equals(kind)) {
            screen.update(model);
        } else if (open) {
            mc.gui.setScreen(new PassiveTreeScreen(model));
        }
    }

    /** 2026-11-02 照站長：模組的自訂畫面按物品欄鍵（預設 E）也能離開（正在打字的輸入框除外）。 */
    static boolean closeKey(final net.minecraft.client.gui.screens.Screen screen, final net.minecraft.client.input.KeyEvent event) {
        if (screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused()) {
            return false;
        }
        return net.minecraft.client.Minecraft.getInstance().options.keyInventory.matches(event);
    }
}
