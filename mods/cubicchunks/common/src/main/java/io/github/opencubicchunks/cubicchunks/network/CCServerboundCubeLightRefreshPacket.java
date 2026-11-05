package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Requests a fresh epoch for an existing tracked publication; never chooses bounds, loads cubes, or starts a solve. */
public record CCServerboundCubeLightRefreshPacket(CubePos pos, long observedEpoch) implements CustomPacketPayload {
    private static final int FORMAT_VERSION = 1;
    public static final Type<CCServerboundCubeLightRefreshPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_light_refresh"));
    public static final StreamCodec<FriendlyByteBuf, CCServerboundCubeLightRefreshPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCServerboundCubeLightRefreshPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readVarInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported cube light refresh format");
            }
            long packed = buffer.readLong();
            CubePos pos = CubePos.from(packed);
            if (pos.asLong() != packed) {
                throw new IllegalArgumentException("Noncanonical cube light refresh position");
            }
            return new CCServerboundCubeLightRefreshPacket(pos, buffer.readLong());
        }

        @Override public void encode(FriendlyByteBuf buffer, CCServerboundCubeLightRefreshPacket packet) {
            buffer.writeVarInt(FORMAT_VERSION);
            buffer.writeLong(packet.pos.asLong());
            buffer.writeLong(packet.observedEpoch);
        }
    };

    public CCServerboundCubeLightRefreshPacket {
        Objects.requireNonNull(pos, "pos");
        if (!CubePos.from(pos.asLong()).equals(pos)) {
            throw new IllegalArgumentException("Cube light refresh position exceeds the supported range");
        }
        CubeLightPacketData.validateEpoch(observedEpoch);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCServerboundCubeLightRefreshPacket payload, net.minecraft.server.level.ServerPlayer player) {
            // Fabric dispatches play handlers on the server thread; the cache validates tracking, epoch and rate limits.
            
            if (((CanBeCubic) player.level()).cc_isCubic() && player.level().getChunkSource() instanceof ServerCubeCache cache) {
                cache.cc_requestCubeLightRefresh(player, payload.pos, payload.observedEpoch);
            }
        }
    }
}
