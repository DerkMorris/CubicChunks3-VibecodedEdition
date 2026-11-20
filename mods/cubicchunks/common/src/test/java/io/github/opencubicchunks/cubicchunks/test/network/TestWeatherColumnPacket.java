package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientWeatherColumns;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWeatherColumnPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestWeatherColumnPacket extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void wireAndClientCachePreserveFullSignedHeight(int y) {
        var state = new ClientWeatherColumns(Level.OVERWORLD.identifier());
        int[] heights = filled(y);
        var packet = new CCClientboundWeatherColumnPacket(Level.OVERWORLD.identifier(), -1, 2, 5, heights);
        heights[0] = 0;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundWeatherColumnPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = CCClientboundWeatherColumnPacket.STREAM_CODEC.decode(buffer);
            assertTrue(state.apply(decoded));
            assertEquals(y, state.height(-32, 64));
            assertEquals(y, state.height(-1, 95));
            assertEquals(CCClientboundWeatherColumnPacket.UNKNOWN, state.height(0, 64));
        } finally {
            buffer.release();
        }
    }

    @Test
    void revocationRejectsStaleDataAndWorldInstancesAreIsolated() {
        var state = new ClientWeatherColumns(Level.OVERWORLD.identifier());
        assertTrue(state.apply(packet(2, filled(32000))));
        assertFalse(state.apply(packet(1, filled(96))));
        assertEquals(32000, state.height(16, 16));
        assertTrue(state.apply(packet(3, null)));
        assertEquals(CCClientboundWeatherColumnPacket.UNKNOWN, state.height(16, 16));
        assertFalse(state.apply(packet(2, filled(32000))));
        assertTrue(state.apply(packet(3, filled(-32000))));
        assertEquals(-32000, state.height(16, 16));
        assertFalse(state.apply(new CCClientboundWeatherColumnPacket(Level.NETHER.identifier(), 0, 0, 100, filled(64))));
        assertEquals(-32000, state.height(16, 16));
        assertEquals(CCClientboundWeatherColumnPacket.UNKNOWN, new ClientWeatherColumns(Level.OVERWORLD.identifier()).height(16, 16));
    }

    @Test
    void malformedSizesCoordinatesAndHeightsAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> packet(1, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> packet(-1, filled(96)));
        assertThrows(IllegalArgumentException.class, () -> packet(1, filled(CubicBlockBounds.MAX + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new CCClientboundWeatherColumnPacket(Level.OVERWORLD.identifier(), CubePos.MAX_COORDINATE_VALUE + 1, 0, 0, null));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1);
            buffer.writeIdentifier(Level.OVERWORLD.identifier());
            buffer.writeInt(0);
            buffer.writeInt(0);
            buffer.writeLong(0);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundWeatherColumnPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static CCClientboundWeatherColumnPacket packet(long epoch, int[] heights) {
        return new CCClientboundWeatherColumnPacket(Level.OVERWORLD.identifier(), 0, 0, epoch, heights);
    }

    private static int[] filled(int y) {
        int[] heights = new int[CCClientboundWeatherColumnPacket.COUNT];
        Arrays.fill(heights, y);
        return heights;
    }
}
