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
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Full block coordinates plus double-precision hit offsets, sharing vanilla's prediction sequence. */
public record CCServerboundCubeUseItemOnPacket(
        BlockPos pos, Direction direction, Vec3 offset, InteractionHand hand, boolean inside, boolean worldBorderHit, int sequence
) implements CustomPacketPayload {

    private static final double HIT_LIMIT = 1.0000001;
    private static final double BLOCK_CENTER = 0.5;
    public static final Type<CCServerboundCubeUseItemOnPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_use_item_on"));
    public static final StreamCodec<FriendlyByteBuf, CCServerboundCubeUseItemOnPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCServerboundCubeUseItemOnPacket decode(FriendlyByteBuf buffer) {
            return new CCServerboundCubeUseItemOnPacket(CubeActionCodec.readPosition(buffer), CubeActionCodec.readEnum(buffer, Direction.values()),
                    new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                    CubeActionCodec.readEnum(buffer, InteractionHand.values()), CubeActionCodec.readBoolean(buffer),
                    CubeActionCodec.readBoolean(buffer), buffer.readVarInt());
        }

        @Override public void encode(FriendlyByteBuf buffer, CCServerboundCubeUseItemOnPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeEnum(packet.direction);
            buffer.writeDouble(packet.offset.x());
            buffer.writeDouble(packet.offset.y());
            buffer.writeDouble(packet.offset.z());
            buffer.writeEnum(packet.hand);
            buffer.writeBoolean(packet.inside);
            buffer.writeBoolean(packet.worldBorderHit);
            buffer.writeVarInt(packet.sequence);
        }
    };

    public CCServerboundCubeUseItemOnPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(hand, "hand");
        validateOffset(offset.x());
        validateOffset(offset.y());
        validateOffset(offset.z());
        CubeActionCodec.sequence(sequence);
    }

    private static void validateOffset(double coordinate) {
        if (!Double.isFinite(coordinate) || Math.abs(coordinate - BLOCK_CENTER) >= HIT_LIMIT) {
            throw new IllegalArgumentException("Hit location is outside the target block vicinity");
        }
    }

    public static CCServerboundCubeUseItemOnPacket from(ServerboundUseItemOnPacket packet) {
        BlockHitResult hit = packet.hitResult();
        BlockPos pos = hit.getBlockPos();
        return new CCServerboundCubeUseItemOnPacket(pos, hit.getDirection(), hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()),
                packet.hand(), hit.isInside(), hit.isWorldBorderHit(), packet.sequence());
    }

    public ServerboundUseItemOnPacket vanilla() {
        return new ServerboundUseItemOnPacket(hand,
                new BlockHitResult(offset.add(pos.getX(), pos.getY(), pos.getZ()), direction, pos, inside, worldBorderHit), sequence);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCServerboundCubeUseItemOnPacket packet, net.minecraft.server.level.ServerPlayer player) {
            CubePlayerActions.handle(player, packet);
        }
    }
}
