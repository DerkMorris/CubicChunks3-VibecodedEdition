package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeBlockEntityEditing;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.ClientPlatform;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;

/** Preserve the actual directed update tag, including command editor data absent from normal broadcast tags. */
public record CCClientboundCubeBlockEntityDataPacket(BlockPos pos, CubeBlockEntityUpdate data) implements CustomPacketPayload {
    public static final Type<CCClientboundCubeBlockEntityDataPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_block_entity_data"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundCubeBlockEntityDataPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeBlockEntityDataPacket decode(FriendlyByteBuf buffer) {
            return new CCClientboundCubeBlockEntityDataPacket(CubeActionCodec.readPosition(buffer),
                    CubeBlockEntityUpdate.read(buffer, CubeBlockEntityUpdate.MAX_BYTES));
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundCubeBlockEntityDataPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            packet.data.write(buffer);
        }
    };

    public CCClientboundCubeBlockEntityDataPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(data, "data");
    }

    public static CCClientboundCubeBlockEntityDataPacket from(ClientboundBlockEntityDataPacket packet) {
        return new CCClientboundCubeBlockEntityDataPacket(packet.getPos(), CubeBlockEntityUpdate.capture(packet.getType(), packet.getTag()));
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundCubeBlockEntityDataPacket packet, ClientPacketContext context) {
            ClientCubeBlockEntityEditing.update(packet, context);
        }
    }
}
