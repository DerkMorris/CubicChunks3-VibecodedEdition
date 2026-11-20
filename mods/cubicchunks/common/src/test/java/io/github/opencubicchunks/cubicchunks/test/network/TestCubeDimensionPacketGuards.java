package io.github.opencubicchunks.cubicchunks.test.network;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundForgetLevelCloPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundSetCubeCacheCenterPacket;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** Final packets from the departing cubic dimension can follow the native respawn packet. */
class TestCubeDimensionPacketGuards extends BaseTest {
    private static final CubePos CUBE = CubePos.of(49, -1000, 15);
    private static final ChunkPos COLUMN = new ChunkPos(98, 30);

    @Test
    void currentCubicLevelReceivesFullHeightCenterAndCubeAndColumnRemoval() {
        Fixture f = new Fixture(true);
        f.center();
        f.forgetCube();
        f.forgetColumn();
        verify((ClientCubeCache) f.cache).cc_updateViewCenter(CUBE.getX(), CUBE.getY(), CUBE.getZ());
        verify((ClientCubeCache) f.cache).cc_drop(CUBE);
        verify(f.cache).drop(COLUMN);
        verifyNoMoreInteractions(f.cache);
    }

    @Test
    void vanillaDestinationRejectsOldCubePacketsButRetainsNativeColumnRemoval() {
        Fixture f = new Fixture(false);
        f.center();
        f.forgetCube();
        verifyNoInteractions(f.cache);
        f.forgetColumn();
        verify(f.cache).drop(COLUMN);
        verifyNoMoreInteractions(f.cache);
    }

    @Test
    void stalePlayerLevelCannotChangeCurrentDimensionCache() {
        Fixture f = new Fixture(true);
        f.client.level = mock(ClientLevel.class);
        f.center();
        f.forgetCube();
        f.forgetColumn();
        verifyNoInteractions(f.cache);
    }

    @Test
    void disconnectWithoutAPlayerIgnoresLatePackets() {
        Fixture f = new Fixture(true);
        when(f.context.player()).thenReturn(null);
        f.client.level = null;
        f.center();
        f.forgetCube();
        f.forgetColumn();
        verifyNoInteractions(f.cache);
    }

    private static final class Fixture {
        private final ClientLevel level = mock();
        private final Minecraft client = mock();
        private final LocalPlayer player = mock();
        private final ClientPacketContext context = mock();
        private final ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(ClientCubeCache.class));

        private Fixture(boolean cubic) {
            client.level = level;
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
            when(level.getChunkSource()).thenReturn(cache);
            when(player.level()).thenReturn(level);
            when(context.player()).thenReturn(player);
            when(context.client()).thenReturn(client);
        }

        private void center() {
            new CCClientboundSetCubeCacheCenterPacket.Handler().receive(new CCClientboundSetCubeCacheCenterPacket(CUBE), context);
        }

        private void forgetCube() {
            new CCClientboundForgetLevelCloPacket.Handler().receive(new CCClientboundForgetLevelCloPacket(CloPos.cube(CUBE)), context);
        }

        private void forgetColumn() {
            new CCClientboundForgetLevelCloPacket.Handler().receive(new CCClientboundForgetLevelCloPacket(CloPos.chunk(COLUMN)), context);
        }
    }
}
