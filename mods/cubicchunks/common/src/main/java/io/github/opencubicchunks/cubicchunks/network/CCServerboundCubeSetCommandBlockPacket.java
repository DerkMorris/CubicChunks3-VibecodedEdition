package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.server.network.CubeBlockEntityEditing;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.CommandBlockEntity;

public record CCServerboundCubeSetCommandBlockPacket(
        BlockPos pos, String command, CommandBlockEntity.Mode mode, boolean trackOutput, boolean conditional, boolean automatic
) implements CustomPacketPayload {

    public static final Type<CCServerboundCubeSetCommandBlockPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_set_command_block"));
    public static final StreamCodec<FriendlyByteBuf, CCServerboundCubeSetCommandBlockPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCServerboundCubeSetCommandBlockPacket decode(FriendlyByteBuf buffer) {
            return new CCServerboundCubeSetCommandBlockPacket(CubeActionCodec.readPosition(buffer), buffer.readUtf(CubeEditCodec.MAX_STRING_LENGTH),
                    CubeActionCodec.readEnum(buffer, CommandBlockEntity.Mode.values()), CubeActionCodec.readBoolean(buffer),
                    CubeActionCodec.readBoolean(buffer), CubeActionCodec.readBoolean(buffer));
        }

        @Override public void encode(FriendlyByteBuf buffer, CCServerboundCubeSetCommandBlockPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeUtf(packet.command, CubeEditCodec.MAX_STRING_LENGTH);
            buffer.writeEnum(packet.mode);
            buffer.writeBoolean(packet.trackOutput);
            buffer.writeBoolean(packet.conditional);
            buffer.writeBoolean(packet.automatic);
        }
    };

    public CCServerboundCubeSetCommandBlockPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(mode, "mode");
        CubeEditCodec.string(command, CubeEditCodec.MAX_STRING_LENGTH);
        CubeEditCodec.textBudget(command);
    }

    public static CCServerboundCubeSetCommandBlockPacket from(ServerboundSetCommandBlockPacket packet) {
        return new CCServerboundCubeSetCommandBlockPacket(packet.getPos(), packet.getCommand(), packet.getMode(), packet.isTrackOutput(),
                packet.isConditional(), packet.isAutomatic());
    }

    public ServerboundSetCommandBlockPacket vanilla() {
        return new ServerboundSetCommandBlockPacket(pos, command, mode, trackOutput, conditional, automatic);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCServerboundCubeSetCommandBlockPacket packet, net.minecraft.server.level.ServerPlayer player) {
            CubeBlockEntityEditing.handle(player, packet);
        }
    }
}
