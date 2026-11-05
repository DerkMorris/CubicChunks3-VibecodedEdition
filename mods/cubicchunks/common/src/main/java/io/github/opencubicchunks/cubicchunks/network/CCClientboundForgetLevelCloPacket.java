package io.github.opencubicchunks.cubicchunks.network;

import static io.github.opencubicchunks.cubicchunks.network.MiscStreamCodecs.CLO_POS_STREAM_CODEC;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.netty.buffer.ByteBuf;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CCClientboundForgetLevelCloPacket(CloPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CCClientboundForgetLevelCloPacket> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "forget_clo"));

    public static final StreamCodec<ByteBuf, CCClientboundForgetLevelCloPacket> STREAM_CODEC = StreamCodec.composite(CLO_POS_STREAM_CODEC,
            CCClientboundForgetLevelCloPacket::pos, CCClientboundForgetLevelCloPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundForgetLevelCloPacket payload, ClientPacketContext context) {
            var clientChunkCache = ((ClientChunkCache) context.player().level().getChunkSource());
            // TODO P2: queueLightRemoval - look at vanilla packet handler
            if (payload.pos.isChunk()) {
                clientChunkCache.drop(payload.pos.chunkPos());
            } else {
                ((ClientCubeCache) clientChunkCache).cc_drop(payload.pos.cubePos());
            }
        }
    }
}
