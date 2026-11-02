package tw.exilecore.client;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import tw.exilecore.client.core.Protocol;

/**
 * 2026-11-02 照站長（C 組）：預警、小地圖、死亡回顧三個新封包（S→C，原始位元組，格式見插件的 FeedProtocol）。
 * 註冊了伺服器才看得到我們聽這些頻道（插件送之前會看）；舊插件不送，什麼都不會發生。
 */
final class FeedPayloads {

    private FeedPayloads() {
    }

    static final Identifier ALERT_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, "alert");
    static final Identifier MINIMAP_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, "minimap");
    static final Identifier DEATH_ID = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, "death");

    record Alert(byte[] data) implements CustomPacketPayload {
        static final Type<Alert> TYPE = new Type<>(ALERT_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Alert> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Alert(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    record Minimap(byte[] data) implements CustomPacketPayload {
        static final Type<Minimap> TYPE = new Type<>(MINIMAP_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Minimap> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Minimap(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    record Death(byte[] data) implements CustomPacketPayload {
        static final Type<Death> TYPE = new Type<>(DEATH_ID);
        static final StreamCodec<RegistryFriendlyByteBuf, Death> CODEC = StreamCodec.of((buf, p) -> buf.writeBytes(p.data()),
                buf -> new Death(readAll(buf)));

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
        PayloadTypeRegistry.clientboundPlay().register(Alert.TYPE, Alert.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Minimap.TYPE, Minimap.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Death.TYPE, Death.CODEC);
    }
}
