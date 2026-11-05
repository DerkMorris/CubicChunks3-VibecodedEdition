package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.server.network.CubePlayerActions;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.Identifier;

/** Destroy/start/stop/abort/direction actions retain vanilla sequences and server validation. */
public record CCServerboundCubePlayerActionPacket(BlockPos pos, Action action, Direction direction, int sequence) implements CustomPacketPayload {

    public static final Type<CCServerboundCubePlayerActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_player_action"));
    public static final StreamCodec<FriendlyByteBuf, CCServerboundCubePlayerActionPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCServerboundCubePlayerActionPacket decode(FriendlyByteBuf buffer) {
            return new CCServerboundCubePlayerActionPacket(CubeActionCodec.readPosition(buffer), CubeActionCodec.readEnum(buffer, Action.values()),
                    CubeActionCodec.readEnum(buffer, Direction.values()), buffer.readVarInt());
        }

        @Override public void encode(FriendlyByteBuf buffer, CCServerboundCubePlayerActionPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeEnum(packet.action);
            buffer.writeEnum(packet.direction);
            buffer.writeVarInt(packet.sequence);
        }
    };

    public CCServerboundCubePlayerActionPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(direction, "direction");
        if (!isBlockAction(action)) {
            throw new IllegalArgumentException("Not a block action");
        }
        CubeActionCodec.sequence(sequence);
    }

    public static boolean isBlockAction(Action action) {
        return switch (action) {
            case START_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> true;
            default -> false;
        };
    }

    public ServerboundPlayerActionPacket vanilla() {
        return new ServerboundPlayerActionPacket(action, pos, direction, sequence);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCServerboundCubePlayerActionPacket packet, net.minecraft.server.level.ServerPlayer player) {
            CubePlayerActions.handle(player, packet);
        }
    }
}
