package tw.exilecore.client;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import tw.exilecore.client.core.UiWire;

/**
 * 2026-11-02 照站長（D 組）：原生畫面的自訂封包（exilecore:ui 伺服器送資料、exilecore:uiact 模組送動作）。內容是原始的位元組
 * （插件用 plugin messaging 收送），格式見 {@link UiWire}。跟 {@link Payloads} 分開放，HUD 那幾組改 Payloads 時不會撞在一起。
 */
final class UiPayloads {

    private UiPayloads() {
    }

    static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(UiWire.NAMESPACE, UiWire.DATA);
    static final Identifier ACTION_ID = Identifier.fromNamespaceAndPath(UiWire.NAMESPACE, UiWire.ACTION);

    /** 伺服器送的資料（一段）。 */
    record Data(byte[] data) implements CustomPacketPayload {
        static final Type<Data> TYPE = new Type<>(DATA_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Data(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 模組送的動作。 */
    record Action(byte[] data) implements CustomPacketPayload {
        static final Type<Action> TYPE = new Type<>(ACTION_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Action(readAll(buf)));

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
        PayloadTypeRegistry.clientboundPlay().register(Data.TYPE, Data.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
    }
}
