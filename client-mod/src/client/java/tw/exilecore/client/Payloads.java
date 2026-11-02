package tw.exilecore.client;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import tw.exilecore.client.core.Protocol;

/**
 * 自訂封包（exilecore:hello、exilecore:key、exilecore:hud，1.1 加了 exilecore:fx）。內容就是原始的位元組（插件那邊用 plugin messaging 收送，
 * 前面沒有長度），格式見 {@link Protocol}。
 */
final class Payloads {

    private Payloads() {
    }

    static final Identifier HELLO_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.HELLO);
    static final Identifier KEY_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.KEY);
    static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.HUD);
    static final Identifier FX_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.FX);
    static final Identifier NOTICE_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.NOTICE);
    static final Identifier PANELS_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.PANELS);

    /** 招呼（兩個方向同一個頻道）。 */
    record Hello(byte[] data) implements CustomPacketPayload {
        static final Type<Hello> TYPE = new Type<>(HELLO_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Hello(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 技能鍵（C→S）。 */
    record Key(byte[] data) implements CustomPacketPayload {
        static final Type<Key> TYPE = new Type<>(KEY_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Key> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Key(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 畫面資料（S→C）。 */
    record Hud(byte[] data) implements CustomPacketPayload {
        static final Type<Hud> TYPE = new Type<>(HUD_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Hud> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Hud(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 特效（S→C，二進位，{@link tw.exilecore.client.core.FxCodec}）。 */
    record Fx(byte[] data) implements CustomPacketPayload {
        static final Type<Fx> TYPE = new Type<>(FX_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Fx> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Fx(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 2026-11-02（1.4）：提示列的一則（S→C，UTF-8 JSON）。 */
    record Notice(byte[] data) implements CustomPacketPayload {
        static final Type<Notice> TYPE = new Type<>(NOTICE_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Notice(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 2026-11-02（1.4）：側邊面板（S→C，UTF-8 JSON）。 */
    record PanelData(byte[] data) implements CustomPacketPayload {
        static final Type<PanelData> TYPE = new Type<>(PANELS_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, PanelData> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new PanelData(readAll(buf)));

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

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Key.TYPE, Key.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Hud.TYPE, Hud.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Fx.TYPE, Fx.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Notice.TYPE, Notice.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PanelData.TYPE, PanelData.CODEC);
    }
}
