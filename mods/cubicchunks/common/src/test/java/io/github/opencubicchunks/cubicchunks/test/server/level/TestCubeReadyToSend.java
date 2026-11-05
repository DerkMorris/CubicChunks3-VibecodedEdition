package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level.ChunkMapSendingTestAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;

class TestCubeReadyToSend extends BaseTest {
    private static final CubePos POS = CubePos.of(-3, -1000, 7);

    @Test
    void readyCubeQueuesOnlyThreeDimensionallyTrackingPlayersAndNotifiesTheCache() throws Exception {
        Fixture fixture = new Fixture();
        PlayerChunkSender tracking = fixture.addPlayer(CloTrackingView.cc_of(CloPos.cube(POS), 2));
        PlayerChunkSender otherHeight = fixture.addPlayer(CloTrackingView.cc_of(CloPos.cube(POS.getX(), POS.getY() + 10, POS.getZ()), 2));
        PlayerChunkSender absent = fixture.addPlayer(CloTrackingView.EMPTY);
        LevelCube cube = new LevelCube(fixture.level, POS);
        fixture.access.cc_testReadyToSend(fixture.holder, cube);
        assertTrue(tracking.isPending(cube.cc_getCloPos().asLong()));
        assertFalse(otherHeight.isPending(cube.cc_getCloPos().asLong()));
        assertFalse(absent.isPending(cube.cc_getCloPos().asLong()));
        verify(fixture.cache).onChunkReadyToSend(fixture.holder);
        verifyNoInteractions(fixture.debug);
    }

    @Test
    void readyColumnRetainsVanillaDebugRegistrationAndColumnPendingKeys() throws Exception {
        Fixture fixture = new Fixture();
        PlayerChunkSender tracking = fixture.addPlayer(CloTrackingView.cc_of(CloPos.cube(POS), 2));
        LevelChunk chunk = new LevelChunk(fixture.level, new ChunkPos(POS.getX() * 2, POS.getZ() * 2));
        fixture.access.cc_testReadyToSend(fixture.holder, (LevelClo) chunk);
        assertTrue(tracking.isPending(((LevelClo) chunk).cc_getCloPos().asLong()));
        assertFalse(tracking.isPending(CloPos.cube(POS).asLong()));
        verify(fixture.debug).registerChunk(chunk);
        verify(fixture.cache).onChunkReadyToSend(fixture.holder);
    }

    @Test
    void cubeWithoutPlayersStillNotifiesTheCacheWithoutRegisteringAColumnAlias() throws Exception {
        Fixture fixture = new Fixture();
        fixture.access.cc_testReadyToSend(fixture.holder, new LevelCube(fixture.level, POS));
        verify(fixture.cache).onChunkReadyToSend(fixture.holder);
        verifyNoInteractions(fixture.debug);
    }

    private static void setField(Class<?> type, Object instance, String name, Object value) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static final class Fixture {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final ServerChunkCache cache = mock();
        private final LevelDebugSynchronizers debug = mock();
        private final ChunkHolder holder = mock();
        private final PlayerMap players = new PlayerMap();
        private final ChunkMapSendingTestAccess access;

        private Fixture() throws ReflectiveOperationException {
            Misc.setupLevelHeight(level, -64, 384);
            when(level.getChunkSource()).thenReturn(cache);
            when(level.debugSynchronizers()).thenReturn(debug);
            ChunkMap map = mock(ChunkMap.class, CALLS_REAL_METHODS);
            setField(ChunkMap.class, map, "level", level);
            setField(ChunkMap.class, map, "playerMap", players);
            access = (ChunkMapSendingTestAccess) map;
        }

        private PlayerChunkSender addPlayer(CloTrackingView view) throws ReflectiveOperationException {
            ServerPlayer player = mock();
            when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(view);
            player.connection = mock();
            PlayerChunkSender sender = new PlayerChunkSender(false);
            setField(ServerGamePacketListenerImpl.class, player.connection, "chunkSender", sender);
            players.addPlayer(player, false);
            return sender;
        }
    }
}
