package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubePlayerActionPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeUseItemOnPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TestCubePlayerActionPackets extends BaseTest {
    @Test
    void everyBlockActionAndFacePreservesExtremeCoordinatesAndSequence() {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
            BlockPos pos = new BlockPos(-17, y, 31);
            for (Action action : Action.values()) {
                if (!CCServerboundCubePlayerActionPacket.isBlockAction(action)) {
                    continue;
                }
                for (Direction face : Direction.values()) {
                    var packet = new CCServerboundCubePlayerActionPacket(pos, action, face, Integer.MAX_VALUE);
                    var decoded = roundTrip(CCServerboundCubePlayerActionPacket.STREAM_CODEC, packet);
                    assertEquals(packet, decoded);
                    assertEquals(pos, decoded.vanilla().getPos());
                    assertEquals(action, decoded.vanilla().getAction());
                    assertEquals(face, decoded.vanilla().getDirection());
                    assertEquals(Integer.MAX_VALUE, decoded.vanilla().getSequence());
                }
            }
            assertNotEquals(pos, BlockPos.of(pos.asLong()));
        }
    }

    @Test
    void useItemOnPreservesPreciseHitAndBothHandsAndFlagsAtExtremeHeights() {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
            BlockPos pos = new BlockPos(-17, y, 31);
            Vec3 hit = new Vec3(pos.getX() + 0.125, pos.getY() + 0.75, pos.getZ() + 1.0);
            for (InteractionHand hand : InteractionHand.values()) {
                for (Direction face : Direction.values()) {
                    for (boolean inside : new boolean[] { false, true }) {
                        var vanilla = new ServerboundUseItemOnPacket(hand, new BlockHitResult(hit, face, pos, inside, !inside), 33);
                        var packet = CCServerboundCubeUseItemOnPacket.from(vanilla);
                        var decoded = roundTrip(CCServerboundCubeUseItemOnPacket.STREAM_CODEC, packet);
                        assertEquals(packet, decoded);
                        assertEquals(pos, decoded.vanilla().hitResult().getBlockPos());
                        assertEquals(hit, decoded.vanilla().hitResult().getLocation());
                        assertEquals(inside, decoded.vanilla().hitResult().isInside());
                        assertEquals(!inside, decoded.vanilla().hitResult().isWorldBorderHit());
                        assertEquals(face, decoded.vanilla().hitResult().getDirection());
                        assertEquals(hand, decoded.vanilla().hand());
                        assertEquals(33, decoded.vanilla().sequence());
                    }
                }
            }
        }
    }

    @Test
    void constructorsFreezeMutablePositionsAndRejectOutOfRangeCoordinatesAndNonBlockActions() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(-1, 32000000, 2);
        var action = new CCServerboundCubePlayerActionPacket(mutable, Action.START_DESTROY_BLOCK, Direction.UP, 0);
        var use = new CCServerboundCubeUseItemOnPacket(mutable, Direction.UP, Vec3.ZERO, InteractionHand.MAIN_HAND, false, false, 0);
        mutable.set(0, 0, 0);
        assertEquals(32000000, action.pos().getY());
        assertEquals(action.pos(), use.pos());
        for (BlockPos invalid : new BlockPos[] { new BlockPos(0, CubicBlockBounds.MIN - 1, 0), new BlockPos(0, CubicBlockBounds.MAX + 1, 0),
            new BlockPos(Integer.MAX_VALUE, 0, 0), new BlockPos(0, 0, Integer.MIN_VALUE) }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CCServerboundCubePlayerActionPacket(invalid, Action.START_DESTROY_BLOCK, Direction.UP, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new CCServerboundCubePlayerActionPacket(BlockPos.ZERO, Action.DROP_ITEM, Direction.UP, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CCServerboundCubePlayerActionPacket(BlockPos.ZERO, Action.START_DESTROY_BLOCK, Direction.UP, -1));
    }

    @Test
    void rejectsNonFiniteAndDistantHitsAndNegativeUseSequences() {
        for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 2 }) {
            assertThrows(IllegalArgumentException.class, () -> new CCServerboundCubeUseItemOnPacket(BlockPos.ZERO, Direction.UP,
                    new Vec3(0.5, invalid, 0.5), InteractionHand.MAIN_HAND, false, false, 1));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CCServerboundCubeUseItemOnPacket(BlockPos.ZERO, Direction.UP, Vec3.ZERO, InteractionHand.MAIN_HAND, false, false, -1));
    }

    @Test
    void actionDecoderRejectsBadVersionCoordinatesActionFaceSequenceAndEveryTruncatedPrefix() {
        var codec = CCServerboundCubePlayerActionPacket.STREAM_CODEC;
        var packet = new CCServerboundCubePlayerActionPacket(new BlockPos(1, 32000000, 2), Action.START_DESTROY_BLOCK, Direction.UP, 9);
        reject(codec, packet, buffer -> buffer.setByte(0, 2));
        reject(codec, packet, buffer -> buffer.setInt(5, Integer.MAX_VALUE));
        reject(codec, packet, buffer -> buffer.setByte(13, Action.DROP_ITEM.ordinal()));
        reject(codec, packet, buffer -> buffer.setByte(14, 99));
        reject(codec, packet, buffer -> {
            buffer.writerIndex(15);
            buffer.writeVarInt(-1);
        });
        truncated(codec, packet);
    }

    @Test
    void useDecoderRejectsBadVersionCoordinatesFaceHandFlagsHitAndSequenceAndTruncation() {
        var codec = CCServerboundCubeUseItemOnPacket.STREAM_CODEC;
        var packet = new CCServerboundCubeUseItemOnPacket(new BlockPos(1, -32000000, 2), Direction.UP, Vec3.ZERO, InteractionHand.OFF_HAND, false,
                false, 9);
        reject(codec, packet, buffer -> buffer.setByte(0, 2));
        reject(codec, packet, buffer -> buffer.setInt(5, Integer.MIN_VALUE));
        reject(codec, packet, buffer -> buffer.setByte(13, 99));
        reject(codec, packet, buffer -> buffer.setDouble(14, Double.NaN));
        reject(codec, packet, buffer -> buffer.setByte(38, 2));
        reject(codec, packet, buffer -> buffer.setByte(39, 2));
        reject(codec, packet, buffer -> buffer.setByte(40, 2));
        reject(codec, packet, buffer -> {
            buffer.writerIndex(41);
            buffer.writeVarInt(-1);
        });
        truncated(codec, packet);
    }

    public static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static <T> void reject(StreamCodec<FriendlyByteBuf, T> codec, T packet, Consumer<FriendlyByteBuf> mutate) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            mutate.accept(buffer);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static <T> void truncated(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            int length = buffer.writerIndex();
            for (int end = 0; end < length; end++) {
                buffer.readerIndex(0);
                buffer.writerIndex(end);
                assertThrows(RuntimeException.class, () -> codec.decode(buffer));
            }
        } finally {
            buffer.release();
        }
    }
}
