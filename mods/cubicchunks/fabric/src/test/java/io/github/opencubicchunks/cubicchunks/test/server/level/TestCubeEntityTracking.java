package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class TestCubeEntityTracking extends BaseTest {
    @Test
    void nativeUpdatePlayerUsesCubeTrackingAndFullThreeDimensionalRange() throws Exception {
        for (int height : new int[] { 32000000, -32000000 }) {
            try (Fixture f = new Fixture(height)) {
                f.tracked.updatePlayer(f.player);
                f.tracked.updatePlayer(f.player);
                verify(f.sync()).addPairing(f.player);
                f.position.set(f.pig.position().add(0, 200, 0));
                f.tracked.updatePlayer(f.player);
                verify(f.sync()).removePairing(f.player);
                f.position.set(f.pig.position().add(90, 90, 0));
                f.tracked.updatePlayer(f.player);
                verify(f.sync(), times(2)).addPairing(f.player);
                f.position.set(f.pig.position().add(100, 100, 0));
                f.tracked.updatePlayer(f.player);
                verify(f.sync(), times(2)).removePairing(f.player);
            }
        }
    }

    @Test
    void pendingCubePreventsSpawnAndSendNotificationPairsAStationaryEntity() throws Exception {
        try (Fixture f = new Fixture(32000000)) {
            f.sent.set(false);
            f.tracked.updatePlayer(f.player);
            verify(f.sync(), never()).addPairing(f.player);
            f.pending.set(true);
            f.tracked.updatePlayer(f.player);
            verify(f.sync(), never()).addPairing(f.player);
            f.pending.set(false);
            f.sent.set(true);
            f.cubic.cc_onCubeTrackingChange(f.player, f.cube, true);
            verify(f.sync()).addPairing(f.player);
            f.cubic.cc_onCubeTrackingChange(f.player, f.cube, false);
            verify(f.sync()).removePairing(f.player);
            f.cubic.cc_onCubeTrackingChange(f.player, f.cube, true);
            verify(f.sync(), times(2)).addPairing(f.player);
        }
    }

    @Test
    void aDifferentCubeAtTheSameXZCannotExposeOrRemoveThisEntity() throws Exception {
        try (Fixture f = new Fixture(-32000000)) {
            when(((CCServerPlayer) f.player).cc_getCloTrackingView()).thenReturn(CloTrackingView.EMPTY);
            f.tracked.updatePlayer(f.player);
            verify(f.sync(), never()).addPairing(f.player);
            when(((CCServerPlayer) f.player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(f.cube), 10));
            f.cubic.cc_onCubeTrackingChange(f.player, CubePos.of(f.cube.getX(), f.cube.getY() + 1, f.cube.getZ()), true);
            verify(f.sync(), never()).addPairing(f.player);
            f.tracked.updatePlayer(f.player);
            f.cubic.cc_onCubeTrackingChange(f.player, CubePos.of(f.cube.getX(), f.cube.getY() + 1, f.cube.getZ()), false);
            verify(f.sync(), never()).removePairing(f.player);
        }
    }

    @Test
    void vanillaWorldRetainsHorizontalTrackingRangeAndColumnGate() throws Exception {
        try (Fixture f = new Fixture(100)) {
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            when(f.player.getChunkTrackingView()).thenReturn(ChunkTrackingView.of(f.pig.chunkPosition(), 10));
            f.position.set(f.pig.position().add(0, 1000, 0));
            f.tracked.updatePlayer(f.player);
            verify(f.sync()).addPairing(f.player);
            clearInvocations(f.sync());
            when(f.player.getChunkTrackingView()).thenReturn(ChunkTrackingView.EMPTY);
            f.tracked.updatePlayer(f.player);
            verify(f.sync()).removePairing(f.player);
        }
    }

    @Test
    void actualCubeSendPairsAfterPayloadAndDropClearsSentStateAndPairing() throws Exception {
        try (Fixture f = new Fixture(32000000); var network = mockStatic(ServerPlayNetworking.class)) {
            PlayerChunkSender sender = f.realSender();
            List<String> events = new ArrayList<>();
            network.when(() -> ServerPlayNetworking.send(org.mockito.ArgumentMatchers.eq(f.player), any())).thenAnswer(i -> {
                events.add(i.getArgument(1) instanceof CCClientboundLevelCubeWithLightPacket ? "cube" : "forget");
                return null;
            });
            doAnswer(i -> {
                events.add("entity");
                return null;
            }).when(f.sync()).addPairing(f.player);
            doAnswer(i -> {
                events.add("remove");
                return null;
            }).when(f.sync()).removePairing(f.player);
            f.sendCube();
            assertEquals(List.of("cube", "entity"), events);
            assertTrue(((CubeEntityTrackingSender) sender).cc_hasSentEntityCube(f.level, f.cube));
            var drop = PlayerChunkSender.class.getDeclaredMethod("cc_dropClo", ServerPlayer.class, CloPos.class);
            drop.invoke(sender, f.player, CloPos.cube(f.cube));
            assertEquals(List.of("cube", "entity", "forget", "remove"), events);
            assertFalse(((CubeEntityTrackingSender) sender).cc_hasSentEntityCube(f.level, f.cube));
        }
    }

    @Test
    void lightingSnapshotCanBeFirstDeliveryAndEnableSubsequentSpawns() throws Exception {
        try (Fixture f = new Fixture(-343); var network = mockStatic(ServerPlayNetworking.class)) {
            var sender = (CubeEntityTrackingSender) f.realSender();
            var packet = new CCClientboundLevelCubeWithLightPacket(new LevelCube(f.level, f.cube), new CubeLightPacketSnapshot(0, null));
            f.tracked.updatePlayer(f.player);
            verify(f.sync(), never()).addPairing(f.player);
            CubeEntityTrackingSender.sendFullCube(f.player, f.level, f.cube, packet, false);
            assertTrue(sender.cc_hasSentEntityCube(f.level, f.cube));
            CubeEntityTrackingSender.sendFullCube(f.player, f.level, f.cube, packet, false);
            verify(f.sync()).addPairing(f.player);
        }
    }

    @Test
    void failedCubeSendCannotMarkCubeSentOrPairEntities() throws Exception {
        try (Fixture f = new Fixture(-32000000); var network = mockStatic(ServerPlayNetworking.class)) {
            PlayerChunkSender sender = f.realSender();
            network.when(() -> ServerPlayNetworking.send(org.mockito.ArgumentMatchers.eq(f.player), any()))
                    .thenThrow(new IllegalStateException("Failed send"));
            assertThrows(InvocationTargetException.class, f::sendCube);
            assertFalse(((CubeEntityTrackingSender) sender).cc_hasSentEntityCube(f.level, f.cube));
            verify(f.sync(), never()).addPairing(f.player);
        }
    }

    @Test
    void sentCubeStateCannotLeakAcrossDimensions() {
        var sender = (CubeEntityTrackingSender) new PlayerChunkSender(false);
        ServerLevel first = mock();
        ServerLevel second = mock();
        CubePos cube = CubePos.of(0, 1000000, 0);
        sender.cc_markEntityCubeSent(first, cube);
        assertTrue(sender.cc_hasSentEntityCube(first, cube));
        assertFalse(sender.cc_hasSentEntityCube(second, cube));
        sender.cc_markEntityCubeSent(second, cube);
        assertFalse(sender.cc_hasSentEntityCube(first, cube));
        assertTrue(sender.cc_hasSentEntityCube(second, cube));
    }

    @Test
    void playerMovementAcrossAliasedSectionsUpdatesCubeTickets() throws Exception {
        try (Fixture f = new Fixture(32000000)) {
            SectionPos before = SectionPos.of(f.pig.blockPosition());
            var block = f.pig.blockPosition().below(1 << 24);
            SectionPos after = SectionPos.of(block);
            assertEquals(before.asLong(), after.asLong());
            when(f.player.getLastSectionPos()).thenReturn(before);
            when(f.player.blockPosition()).thenReturn(block);
            f.position.set(Vec3.atLowerCornerOf(block));
            CubePos cube = CubePos.from(block);
            org.mockito.Mockito.doReturn(CloPos.cube(cube)).when(f.player);
            Entity.class.getDeclaredMethod("cc_cubePositionAsClo").invoke(f.player);
            when(((CCServerPlayer) f.player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(cube), 10));
            var distance = mock(ChunkMap.DistanceManager.class);
            setField(ChunkMap.class, f.map, "distanceManager", distance);
            setField(ChunkMap.class, f.map, "playerMap", mock(PlayerMap.class));
            ChunkMap.class.getDeclaredMethod("cc_move", ServerPlayer.class).invoke(f.map, f.player);
            verify(f.player).setLastSectionPos(after);
            verify(distance).removePlayer(before, f.player);
            verify(distance).addPlayer(after, f.player);
        }
    }

    @Test
    void anotherDimensionCannotPairEvenWithMatchingCoordinatesAndSentCube() throws Exception {
        try (Fixture f = new Fixture(32000000)) {
            ServerLevel other = mock();
            when(f.player.level()).thenReturn(other);
            when(((CubeEntityTrackingSender) f.player.connection.chunkSender).cc_hasSentEntityCube(other, f.cube)).thenReturn(true);
            f.tracked.updatePlayer(f.player);
            verify(f.sync(), never()).addPairing(f.player);
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final ChunkMap map = mock(ChunkMap.class, CALLS_REAL_METHODS);
        private final CubicChunkMap cubic = (CubicChunkMap) map;
        private final ServerPlayer player = mock();
        private final AtomicReference<Vec3> position = new AtomicReference<>();
        private final AtomicBoolean pending = new AtomicBoolean();
        private final AtomicBoolean sent = new AtomicBoolean(true);
        private final MockedConstruction<ServerEntity> constructed;
        private final Entity pig;
        private final CubePos cube;
        private final ChunkMap.TrackedEntity tracked;

        private Fixture(int height) throws Exception {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.getNextEntityId()).thenReturn(1);
            when(level.getServer().getScaledTrackingDistance(anyInt())).thenAnswer(i -> i.getArgument(0));
            setField(ChunkMap.class, map, "level", level);
            setField(ChunkMap.class, map, "serverViewDistance", 10);
            pig = EntityTypes.PIG.create(level, EntitySpawnReason.LOAD);
            assertNotNull(pig);
            pig.snapTo(2.5, height + 2.5, 2.5);
            cube = CubePos.from(pig.blockPosition());
            position.set(pig.position().add(0, 1, 0));
            when(player.position()).thenAnswer(i -> position.get());
            when(player.getY()).thenAnswer(i -> position.get().y);
            when(player.level()).thenReturn(level);
            when(player.requestedViewDistance()).thenReturn(10);
            when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(cube), 10));
            player.connection = mock(ServerGamePacketListenerImpl.class);
            player.connection.player = player;
            when(player.isAlive()).thenReturn(true);
            PlayerChunkSender sender = mock();
            when(sender.isPending(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(i -> pending.get());
            when(((CubeEntityTrackingSender) sender).cc_hasSentEntityCube(level, cube)).thenAnswer(i -> sent.get());
            setField(ServerGamePacketListenerImpl.class, player.connection, "chunkSender", sender);
            when(player.connection.getPlayer()).thenReturn(player);
            constructed = mockConstruction(ServerEntity.class);
            var constructor = ChunkMap.TrackedEntity.class.getDeclaredConstructor(ChunkMap.class, Entity.class, int.class, UpdateInterval.class,
                    boolean.class);
            constructor.setAccessible(true);
            tracked = constructor.newInstance(map, pig, 128, UpdateInterval.periodic(3), false);
            var trackedEntities = new Int2ObjectOpenHashMap<ChunkMap.TrackedEntity>();
            trackedEntities.put(pig.getId(), tracked);
            setField(ChunkMap.class, map, "entityMap", trackedEntities);
        }

        private PlayerChunkSender realSender() throws Exception {
            PlayerChunkSender sender = new PlayerChunkSender(false);
            setField(ServerGamePacketListenerImpl.class, player.connection, "chunkSender", sender);
            ServerChunkCache cache = mock();
            setField(ServerChunkCache.class, cache, "chunkMap", map);
            when(level.getChunkSource()).thenReturn(cache);
            when(((ServerCubeCache) cache).cc_captureCubeLight(any())).thenReturn(new CubeLightPacketSnapshot(0, null));
            return sender;
        }

        private void sendCube() throws Exception {
            var send = PlayerChunkSender.class.getDeclaredMethod("cc_sendCube", ServerGamePacketListenerImpl.class, ServerLevel.class,
                    LevelCube.class);
            send.setAccessible(true);
            send.invoke(null, player.connection, level, new LevelCube(level, cube));
        }

        private ServerEntity sync() {
            return constructed.constructed().getFirst();
        }

        @Override public void close() {
            constructed.close();
        }
    }
}
