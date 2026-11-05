package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/** Native block event semantics, including unsigned-byte parameters, at an unpacked cube position. */
public record CCClientboundCubeBlockEventPacket(BlockPos pos, Block block, int action, int parameter) implements CustomPacketPayload {

    private static final int BYTE_MASK = 255;
    public static final Type<CCClientboundCubeBlockEventPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_block_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CCClientboundCubeBlockEventPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeBlockEventPacket decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = CubeActionCodec.readPosition(buffer);
            int action = buffer.readUnsignedByte();
            int parameter = buffer.readUnsignedByte();
            return new CCClientboundCubeBlockEventPacket(pos, ByteBufCodecs.registry(Registries.BLOCK).decode(buffer), action, parameter);
        }

        @Override public void encode(RegistryFriendlyByteBuf buffer, CCClientboundCubeBlockEventPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeByte(packet.action);
            buffer.writeByte(packet.parameter);
            ByteBufCodecs.registry(Registries.BLOCK).encode(buffer, packet.block);
        }
    };

    public CCClientboundCubeBlockEventPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(block, "block");
        action &= BYTE_MASK;
        parameter &= BYTE_MASK;
    }

    public static CCClientboundCubeBlockEventPacket from(ClientboundBlockEventPacket packet) {
        return new CCClientboundCubeBlockEventPacket(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundCubeBlockEventPacket packet, ClientPacketContext context) {
            var level = context.client().level;
            if (level == null || context.player().level() != level || !((CanBeCubic) level).cc_isCubic()) {
                return;
            }
            CubePos pos = CubePos.from(packet.pos);
            var cube = ((ClientCubeCache) level.getChunkSource()).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false);
            if (cube != null && cube.getBlockState(packet.pos).is(packet.block)) {
                level.blockEvent(packet.pos, packet.block, packet.action, packet.parameter);
            }
        }
    }
}
