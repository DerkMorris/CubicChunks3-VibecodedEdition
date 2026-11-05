package io.github.opencubicchunks.cubicchunks.network;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Revokes one automatic publication; it cannot clear valid light in other cubes. */
public record CCClientboundCubeLightRevokePacket(CubePos pos, long epoch) implements CustomPacketPayload {
    public static final Type<CCClientboundCubeLightRevokePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_light_revoke"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundCubeLightRevokePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeLightRevokePacket decode(FriendlyByteBuf buffer) {
            long packed = buffer.readLong();
            CubePos pos = CubePos.from(packed);
            if (pos.asLong() != packed) {
                throw new IllegalArgumentException("Noncanonical cube position");
            }
            return new CCClientboundCubeLightRevokePacket(pos, buffer.readLong());
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundCubeLightRevokePacket packet) {
            buffer.writeLong(packet.pos.asLong());
            buffer.writeLong(packet.epoch);
        }
    };

    public CCClientboundCubeLightRevokePacket {
        if (!CubePos.from(pos.asLong()).equals(pos)) {
            throw new IllegalArgumentException("Invalid cube light position");
        }
        CubeLightPacketData.validateEpoch(epoch);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundCubeLightRevokePacket payload, ClientPacketContext context) {
            if (context.client().level == context.player().level()) {
                ((ClientCubeCache) context.client().level.getChunkSource()).cc_invalidateScopedCubeLight(payload.pos, payload.epoch);
            }
        }
    }
}
