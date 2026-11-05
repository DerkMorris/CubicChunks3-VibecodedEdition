package io.github.opencubicchunks.cubicchunks.network;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.Identifier;

/** Full-height world effects, including the particles emitted by a working farmer's composter. */
public record CCClientboundCubeLevelEventPacket(BlockPos pos, int event, int data, boolean global) implements CustomPacketPayload {

    public static final Type<CCClientboundCubeLevelEventPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_level_event"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundCubeLevelEventPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeLevelEventPacket decode(FriendlyByteBuf buffer) {
            return new CCClientboundCubeLevelEventPacket(CubeActionCodec.readPosition(buffer), buffer.readInt(), buffer.readInt(),
                    CubeActionCodec.readBoolean(buffer));
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundCubeLevelEventPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeInt(packet.event);
            buffer.writeInt(packet.data);
            buffer.writeBoolean(packet.global);
        }
    };

    public CCClientboundCubeLevelEventPacket {
        pos = CubicBlockBounds.requireValid(pos);
    }

    public static CCClientboundCubeLevelEventPacket from(ClientboundLevelEventPacket packet) {
        return new CCClientboundCubeLevelEventPacket(packet.getPos(), packet.getType(), packet.getData(), packet.isGlobalEvent());
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundCubeLevelEventPacket packet, ClientPacketContext context) {
            var level = context.client().level;
            if (level == null || context.player().level() != level || !((CanBeCubic) level).cc_isCubic()) {
                return;
            }
            if (packet.global) {
                level.globalLevelEvent(packet.event, packet.pos, packet.data);
                return;
            }
            CubePos pos = CubePos.from(packet.pos);
            if (((ClientCubeCache) level.getChunkSource()).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null) {
                level.levelEvent(packet.event, packet.pos, packet.data);
            }
        }
    }
}
