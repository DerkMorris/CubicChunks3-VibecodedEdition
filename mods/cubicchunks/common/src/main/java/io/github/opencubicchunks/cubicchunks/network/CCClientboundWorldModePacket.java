package io.github.opencubicchunks.cubicchunks.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Configuration payload, delivered before the play-stage client level is created. */
public record CCClientboundWorldModePacket(boolean cubicOverworld) implements CustomPacketPayload {
    public static final Type<CCClientboundWorldModePacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("cubicchunks", "world_mode"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundWorldModePacket> STREAM_CODEC = StreamCodec
            .of((buffer, packet) -> buffer.writeBoolean(packet.cubicOverworld()), buffer -> new CCClientboundWorldModePacket(buffer.readBoolean()));

    @Override public Type<CCClientboundWorldModePacket> type() {
        return TYPE;
    }
}
