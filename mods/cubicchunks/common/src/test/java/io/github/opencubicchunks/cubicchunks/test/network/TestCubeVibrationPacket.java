package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TestCubeVibrationPacket extends BaseTest {
    @ParameterizedTest
    @MethodSource("particleHeights")
    void nativeParticlePacketRoundTripsFullHeightEndpointAndSource(int y) {
        var destination = new BlockPos(8, y, -12);
        var packet = new ClientboundLevelParticlesPacket(new VibrationParticleOption(new BlockPositionSource(destination), 8), false, false, 4.5,
                y + .5, -12.5, 0, 0, 0, 0, 1);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), VanillaRegistriesExtension.shared());
        try {
            ClientboundLevelParticlesPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundLevelParticlesPacket.STREAM_CODEC.decode(buffer);
            assertEquals(y + .5, decoded.y());
            var vibration = (VibrationParticleOption) decoded.particle();
            assertEquals(destination, ((BlockPositionSource) vibration.getDestination()).pos());
            assertEquals(8, vibration.getArrivalInTicks());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static java.util.stream.IntStream particleHeights() {
        return java.util.stream.IntStream.of(96, 32000, -32000, 32000000, -32000000, CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }

    @ParameterizedTest
    @ValueSource(ints = { -2048, -64, 0, 96, 319, 2047 })
    void ordinaryDestinationsRetainExactVanillaBytes(int y) {
        var buffer = Unpooled.buffer();
        try {
            var destination = new BlockPos(29_999_999, y, -29_999_999);
            BlockPositionSource.STREAM_CODEC.encode(buffer, new BlockPositionSource(destination));
            assertEquals(Long.BYTES, buffer.readableBytes());
            assertEquals(destination.asLong(), buffer.readLong());
            buffer.clear();
            buffer.writeLong(destination.asLong());
            assertEquals(destination, BlockPositionSource.STREAM_CODEC.decode(buffer).pos());
        } finally {
            buffer.release();
        }
    }

    @Test
    void escapeCollisionAndAllSignedCoordinateExtremesRoundTrip() {
        var buffer = Unpooled.buffer();
        try {
            for (var position : new BlockPos[] { new BlockPos(CubicBlockBounds.MIN, 0, 0),
                new BlockPos(CubicBlockBounds.MIN, CubicBlockBounds.MIN, CubicBlockBounds.MIN),
                new BlockPos(CubicBlockBounds.MAX, CubicBlockBounds.MAX, CubicBlockBounds.MAX) }) {
                BlockPositionSource.STREAM_CODEC.encode(buffer, new BlockPositionSource(position));
                assertEquals(Long.BYTES + 3 * Integer.BYTES, buffer.readableBytes());
                assertEquals(position, BlockPositionSource.STREAM_CODEC.decode(buffer).pos());
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidExtendedCoordinatesAndTruncatedPayloadAreRejected() {
        var buffer = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BlockPositionSource.STREAM_CODEC.encode(buffer, new BlockPositionSource(new BlockPos(0, CubicBlockBounds.MAX + 1, 0))));
            assertEquals(0, buffer.readableBytes());
            buffer.writeLong(Long.MIN_VALUE);
            buffer.writeInt(0);
            buffer.writeInt(CubicBlockBounds.MAX + 1);
            buffer.writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> BlockPositionSource.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeLong(Long.MIN_VALUE);
            assertThrows(IndexOutOfBoundsException.class, () -> BlockPositionSource.STREAM_CODEC.decode(buffer));
            assertTrue(buffer.readableBytes() < 3 * Integer.BYTES);
        } finally {
            buffer.release();
        }
    }
}
