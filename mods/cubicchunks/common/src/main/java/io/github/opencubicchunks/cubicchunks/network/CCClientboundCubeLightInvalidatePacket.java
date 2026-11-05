package io.github.opencubicchunks.cubicchunks.network;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Invalidates previous publications for this client world before any replacement full-cube snapshots arrive. */
public record CCClientboundCubeLightInvalidatePacket(long epoch) implements CustomPacketPayload {
    private static final int FORMAT_VERSION = 1;
    public static final Type<CCClientboundCubeLightInvalidatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_light_invalidate"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundCubeLightInvalidatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeLightInvalidatePacket decode(FriendlyByteBuf buffer) {
            if (buffer.readVarInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported cube light invalidation format");
            }
            return new CCClientboundCubeLightInvalidatePacket(buffer.readLong());
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundCubeLightInvalidatePacket packet) {
            buffer.writeVarInt(FORMAT_VERSION);
            buffer.writeLong(packet.epoch);
        }
    };

    public CCClientboundCubeLightInvalidatePacket {
        CubeLightPacketData.validateEpoch(epoch);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundCubeLightInvalidatePacket payload, ClientPacketContext context) {
            ClientLevel level = (ClientLevel) context.player().level();
            if (context.client().level == level) {
                ((ClientCubeCache) level.getChunkSource()).cc_invalidateCubeLight(payload.epoch);
            }
        }
    }
}
