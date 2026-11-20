package io.github.opencubicchunks.cubicchunks.network;

import static io.github.opencubicchunks.cubicchunks.network.MiscStreamCodecs.CUBE_POS_STREAM_CODEC;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CCClientboundSetCubeCacheCenterPacket(CubePos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CCClientboundSetCubeCacheCenterPacket> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "set_cube_cache_center"));

    public static final StreamCodec<ByteBuf, CCClientboundSetCubeCacheCenterPacket> STREAM_CODEC = StreamCodec.composite(CUBE_POS_STREAM_CODEC,
            CCClientboundSetCubeCacheCenterPacket::pos, CCClientboundSetCubeCacheCenterPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundSetCubeCacheCenterPacket payload, ClientPacketContext context) {
            if (context.player() == null || context.client().level != context.player().level()
                    || !((CanBeCubic) context.player().level()).cc_isCubic()) {
                return;
            }
            var clientChunkCache = ((ClientChunkCache) context.player().level().getChunkSource());
            ((ClientCubeCache) clientChunkCache).cc_updateViewCenter(payload.pos.getX(), payload.pos.getY(), payload.pos.getZ());
        }
    }
}
