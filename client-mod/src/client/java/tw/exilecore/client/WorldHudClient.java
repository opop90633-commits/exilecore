package tw.exilecore.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import tw.exilecore.client.core.MobBars;
import tw.exilecore.client.core.ModConfig;
import tw.exilecore.client.core.Protocol;
import tw.exilecore.client.core.WorldCodec;

/**
 * 2026-11-02 照站長（模組 1.4 B 組：世界中與地圖）：地圖資訊框（{@link MapPanelRenderer}）、傷害數字（{@link DamageNumberRenderer}）、
 * 怪物頭上的血條（{@link MobBarRenderer}，原版名牌只在準心指著或很近時顯示）、準心指著誰（exilecore:aim，伺服器的目標框照它優先）。
 * 封包 exilecore:map、exilecore:mobs、exilecore:aim（{@link WorldCodec}）；招呼多 mapbox／dmgnum／mobbars（{@link #withFeatures}）。
 * 這次之前這些都是伺服器畫的（進圖大標題、文字顯示實體的傷害數字、原版名牌）或沒有。
 */
public final class WorldHudClient {

    /** 準心看多遠的怪（跟伺服器的目標框一樣 20 格）。 */
    static final double AIM_RANGE = 20;

    static final MobBars MOBS = new MobBars();
    /** 現在的地圖資訊框（null = 不在地圖裡）。 */
    static volatile WorldCodec.MapPanel map;
    /** 準心指著的怪（實體 id；-1 = 沒有）。 */
    static int aimed = -1;
    private static int sentAim = Integer.MIN_VALUE;
    private static ClientLevel level;
    static KeyMapping toggleMapPanel;

    static final Identifier MAP_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, WorldCodec.MAP);
    static final Identifier MOBS_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, WorldCodec.MOBS);
    static final Identifier AIM_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, WorldCodec.AIM);

    private WorldHudClient() {
    }

    /** 地圖資訊框（S→C，JSON）。 */
    record MapPayload(byte[] data) implements CustomPacketPayload {
        static final Type<MapPayload> TYPE = new Type<>(MAP_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, MapPayload> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new MapPayload(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 頭上的血條（S→C，二進位）。 */
    record MobsPayload(byte[] data) implements CustomPacketPayload {
        static final Type<MobsPayload> TYPE = new Type<>(MOBS_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, MobsPayload> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new MobsPayload(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 準心指著誰（C→S）。 */
    record AimPayload(byte[] data) implements CustomPacketPayload {
        static final Type<AimPayload> TYPE = new Type<>(AIM_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, AimPayload> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new AimPayload(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static byte[] readAll(final RegistryFriendlyByteBuf buf) {
        final byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    /** 模組初始化時叫一次（{@link ExileCoreClient#onInitializeClient}）。 */
    static void init() {
        PayloadTypeRegistry.clientboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MobsPayload.TYPE, MobsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AimPayload.TYPE, AimPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(MapPayload.TYPE, (payload, context) -> {
            if (!ExileCoreClient.connected) {
                return;
            }
            final WorldCodec.MapPanel parsed = WorldCodec.parseMap(payload.data());
            if (parsed != null) {
                map = parsed == WorldCodec.CLEAR ? null : parsed;
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MobsPayload.TYPE, (payload, context) -> {
            if (ExileCoreClient.connected) {
                syncLevel(Minecraft.getInstance().level);
                MOBS.apply(WorldCodec.parseMobs(payload.data()));
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ExileCoreClient.MOD_ID, "world_hud"), (graphics, delta) -> {
            HudDragBinding.begin(graphics, HudDragBinding.MAP_PANEL); // 2026-11-02（C 組合併）：設定畫面拖過的位置
            MapPanelRenderer.extract(graphics);
            HudDragBinding.end(graphics);
            DamageNumberRenderer.extractHud(graphics, delta);
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            MobBarRenderer.render(context);
            DamageNumberRenderer.render(context);
        });
        ClientTickEvents.END_CLIENT_TICK.register(WorldHudClient::tick);
    }

    /** 按鍵（跟其他星骸的鍵同一個分類）：收起／展開地圖資訊框，預設 F6。 */
    static void registerKeys(final KeyMapping.Category category) {
        toggleMapPanel = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ExileCoreClient.MOD_ID + ".toggle_map_panel",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));
    }

    /** 招呼多寫 mapbox／dmgnum／mobbars（設定開著才寫）。 */
    static byte[] withFeatures(final byte[] hello) {
        final ModConfig config = ExileCoreClient.config;
        return WorldCodec.withFeatures(hello, config.hudEnabled && config.mapPanel, config.damageNumbers, config.mobBars);
    }

    /** 斷線、換伺服器：全部清掉。 */
    static void reset() {
        map = null;
        MOBS.clear();
        DamageNumberRenderer.clear();
        aimed = -1;
        sentAim = Integer.MIN_VALUE;
    }

    /** 換世界（進地圖、回城）：血條、數字清掉（伺服器也會重送完整的血條）。 */
    private static void syncLevel(final ClientLevel current) {
        if (current != level) {
            level = current;
            MOBS.clear();
            DamageNumberRenderer.clear();
        }
    }

    private static void tick(final Minecraft mc) {
        syncLevel(mc.level);
        DamageNumberRenderer.tick();
        while (toggleMapPanel != null && toggleMapPanel.consumeClick()) {
            final ModConfig config = ExileCoreClient.config;
            config.mapPanelCollapsed = !config.mapPanelCollapsed;
            ExileCoreClient.saveConfig();
            if (mc.player != null && map == null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message." + ExileCoreClient.MOD_ID + ".map_panel_"
                        + (config.mapPanelCollapsed ? "collapsed" : "expanded")));
            }
        }
        aimed = ExileCoreClient.connected ? pickAim(mc) : -1;
        if (ExileCoreClient.connected && aimed != sentAim && ClientPlayNetworking.canSend(AimPayload.TYPE)) {
            sentAim = aimed;
            ClientPlayNetworking.send(new AimPayload(WorldCodec.aim(aimed)));
        }
    }

    /** 準心指著的怪（20 格內、沒有被方塊擋住、活著、不是玩家）；沒有 -1。 */
    static int pickAim(final Minecraft mc) {
        final Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null || mc.player == null || mc.player.isSpectator()) {
            return -1;
        }
        final float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        final Vec3 eye = camera.getEyePosition(partial);
        final Vec3 look = camera.getViewVector(partial);
        final HitResult block = camera.pick(AIM_RANGE, partial, false);
        final double reach = block != null && block.getType() != HitResult.Type.MISS ? block.getLocation().distanceTo(eye) : AIM_RANGE;
        final Vec3 end = eye.add(look.scale(reach));
        final AABB box = camera.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        final EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, eye, end, box,
                entity -> entity instanceof LivingEntity && !(entity instanceof Player) && entity.isAlive() && !entity.isSpectator()
                        && entity.isPickable(), reach * reach);
        return hit == null ? -1 : hit.getEntity().getId();
    }

    /** {@link tw.exilecore.client.mixin.EntityRendererMixin} 問：這隻的原版名牌要不要藏。 */
    public static boolean hidesName(final Entity entity, final double distanceSq) {
        return ExileCoreClient.connected && ExileCoreClient.config.mobBars && MOBS.hidesName(entity.getId(), aimed, distanceSq);
    }
}
