package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientWorldSettings;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWorldHeightsPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestWorldHeightsPacket extends BaseTest {
    private static final WorldHeightSettings CUSTOM = new WorldHeightSettings(32000, -16000, -9000);

    @AfterEach
    void resetConnection() {
        CubicWorldMode.resetClient();
    }

    @Test
    void fullSignedHeightsRoundTripWithoutTruncation() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new CCClientboundWorldHeightsPacket(CUSTOM);
            CCClientboundWorldHeightsPacket.STREAM_CODEC.encode(buffer, packet);
            assertEquals(packet, CCClientboundWorldHeightsPacket.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void malformedHeightRejectedBeforeClientStateChanges() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(Integer.MAX_VALUE).writeInt(0).writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundWorldHeightsPacket.STREAM_CODEC.decode(buffer));
            assertEquals(WorldHeightSettings.DEFAULT, CubicWorldMode.clientHeights);
        } finally {
            buffer.release();
        }
    }

    @Test
    void serverSettingsOnlyApplyToCurrentCubicOverworld() {
        var level = mock(ClientLevel.class, withSettings().extraInterfaces(CanBeCubic.class));
        CubicWorldMode.clientHeights = CUSTOM;
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        assertEquals(CUSTOM, CubicClientWorldSettings.heights(level));
        when(level.dimension()).thenReturn(Level.NETHER);
        assertEquals(WorldHeightSettings.DEFAULT, CubicClientWorldSettings.heights(level));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
        assertEquals(WorldHeightSettings.DEFAULT, CubicClientWorldSettings.heights(level));
        assertEquals(WorldHeightSettings.DEFAULT, CubicClientWorldSettings.heights(null));
    }

    @Test
    void reconnectClearsPreviousServersSettings() {
        CubicWorldMode.clientHeights = CUSTOM;
        CubicWorldMode.clientOverworld = true;
        CubicWorldMode.resetClient();
        assertEquals(WorldHeightSettings.DEFAULT, CubicWorldMode.clientHeights);
        assertFalse(CubicWorldMode.clientOverworld);
    }
}
