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
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.SignTextSlot;

public record CCClientboundCubeOpenSignEditorPacket(BlockPos pos, SignTextSlot slot) implements CustomPacketPayload {
    public static final Type<CCClientboundCubeOpenSignEditorPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_open_sign_editor"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundCubeOpenSignEditorPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundCubeOpenSignEditorPacket decode(FriendlyByteBuf buffer) {
            return new CCClientboundCubeOpenSignEditorPacket(CubeActionCodec.readPosition(buffer),
                    CubeActionCodec.readEnum(buffer, SignTextSlot.values()));
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundCubeOpenSignEditorPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeEnum(packet.slot);
        }
    };

    public CCClientboundCubeOpenSignEditorPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(slot, "slot");
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundCubeOpenSignEditorPacket packet, ClientPacketContext context) {
            ClientCubeBlockEntityEditing.openSign(packet, context);
        }
    }
}
