package io.github.opencubicchunks.cubicchunks.integrationtest.server.level;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityManagerAccess;
import io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level.ChunkMapPersistenceTestAccess;
import io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level.MinecraftServerTestAccess;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightInvalidatePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.server.level.GenerationCloHolder;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeStorageBarrier;
import io.github.opencubicchunks.cubicchunks.testutils.GenerationTestScope;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Real disk persistence across fresh ChunkMap instances, with world services mocked but no storage replacement. */
public class IntegrationTestCubePersistence extends BaseTest {
    @TempDir
    Path directory;

    private static boolean save(ChunkMapPersistenceTestAccess access, CloAccess cube) {
        boolean accepted = access.cc_testSaveAsync(cube);
        access.cc_testAwaitSaves();
        access.cc_testPoiManager().flushAll();
        return accepted;
    }

    private static void processUnloads(ChunkMapPersistenceTestAccess access, java.util.function.BooleanSupplier haveTime) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            access.cc_testProcessUnloadsAsync(haveTime);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Timed out draining test cube unloads");
            }
            LockSupport.parkNanos(1000000);
        } while (!access.cc_testPendingUnloads().isEmpty());
    }

    @Test
    void autosaveBoundsSnapshotWorkAndRetriesDeferredCubes() throws Exception {
        try (Fixture fixture = open()) {
            List<LevelCube> cubes = new ArrayList<>();
            for (int x = 0; x < 10; x++) {
                LevelCube cube = fullCube(fixture, CubePos.of(x, 1000000, 0), Blocks.GOLD_BLOCK.defaultBlockState());
                cubes.add(cube);
                ChunkHolder holder = pendingHolder(cube);
                when(holder.wasAccessibleSinceLastSave()).thenReturn(true);
                when(holder.isReadyForSaving()).thenReturn(true);
                long key = cube.cc_getCloPos().asLong();
                fixture.access.cc_testVisibleChunkMap().put(key, holder);
                fixture.access.cc_testUpdatingChunkMap().put(key, holder);
            }
            fixture.access.cc_testSaveAllChunks(false);
            fixture.access.cc_testAwaitSaves();
            assertEquals(4, cubeFiles().size());
            assertEquals(6, cubes.stream().filter(CubeAccess::isUnsaved).count());
            for (int pass = 0; pass < 3; pass++) {
                fixture.access.cc_testProcessUnloadsAsync(() -> true);
                fixture.access.cc_testAwaitSaves();
            }
            assertEquals(10, cubeFiles().size());
            assertTrue(cubes.stream().noneMatch(CubeAccess::isUnsaved));
        }
    }

    @Test
    void asyncPreflightCannotReplaceSavedFullCubeWithProto() throws Exception {
        CubePos pos = CubePos.of(1, -1000000, 0);
        Fixture fixture = open();
        try {
            assertTrue(save(fixture.access, fullCube(fixture, pos, Blocks.GOLD_BLOCK.defaultBlockState())));
            ProtoCube replacement = cube(fixture, pos, Blocks.STONE.defaultBlockState());
            assertTrue(fixture.access.cc_testSaveAsync(replacement));
            assertThrows(CompletionException.class, fixture.access::cc_testAwaitSaves);
            assertTrue(replacement.isUnsaved());
            assertThrows(CompletionException.class, fixture::close);
        } finally {
            fixture.close();
        }
        try (Fixture reopened = open()) {
            assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), reopened.load(CloPos.cube(pos)).getBlockState(sample(pos)));
        }
    }

    @Test
    void asyncSnapshotDoesNotWaitForDiskOrClearChangesMadeDuringWrite() throws Exception {
        CubePos pos = CubePos.of(7, 1000000, -4);
        try (Fixture fixture = open()) {
            fixture.load(CloPos.cube(pos));
            ProtoCube cube = cube(fixture, pos, Blocks.GOLD_BLOCK.defaultBlockState());
            CubeStorage storage = CubeStorageBarrier.storage(net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class, fixture.map,
                    "cc_cubeStorage");
            try (var barrier = new CubeStorageBarrier(storage)) {
                assertTimeout(Duration.ofSeconds(1), () -> assertTrue(fixture.access.cc_testSaveAsync(cube)));
                assertTrue(fixture.map.hasWork(), "Accepted disk writes must keep shutdown draining");
                cube.setBlockState(sample(pos), Blocks.EMERALD_BLOCK.defaultBlockState(), 0);
                cube.markUnsaved();
                assertFalse(fixture.access.cc_testSaveAsync(cube), "Only one snapshot per cube may be pending");
                assertTrue(cube.isUnsaved());
            }
            fixture.access.cc_testAwaitSaves();
            assertTrue(cube.isUnsaved(), "Acknowledging the old snapshot must not clear newer edits");
            assertTrue(save(fixture.access, cube));
        }
        try (Fixture reopened = open()) {
            assertEquals(Blocks.EMERALD_BLOCK.defaultBlockState(), reopened.load(CloPos.cube(pos)).getBlockState(sample(pos)));
        }
    }

    @Test
    void asyncUnloadRetainsHolderUntilDiskAcknowledgesSnapshot() throws Exception {
        CubePos pos = CubePos.of(7, -1000000, -4);
        try (Fixture fixture = open()) {
            fixture.load(CloPos.cube(pos));
            ProtoCube cube = cube(fixture, pos, Blocks.GOLD_BLOCK.defaultBlockState());
            long key = CloPos.cube(pos).asLong();
            ChunkHolder holder = pendingHolder(cube);
            fixture.access.cc_testPendingUnloads().put(key, holder);
            CubeStorage storage = CubeStorageBarrier.storage(net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class, fixture.map,
                    "cc_cubeStorage");
            try (var barrier = new CubeStorageBarrier(storage)) {
                fixture.access.cc_testScheduleUnload(key, holder);
                assertTimeout(Duration.ofSeconds(1), () -> fixture.access.cc_testProcessUnloadsAsync(() -> true));
                assertSame(holder, fixture.access.cc_testPendingUnloads().get(key));
                assertTrue(fixture.map.hasWork());
            }
            processUnloads(fixture.access, () -> true);
            assertFalse(fixture.access.cc_testPendingUnloads().containsKey(key));
        }
        try (Fixture reopened = open()) {
            assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), reopened.load(CloPos.cube(pos)).getBlockState(sample(pos)));
        }
    }

    @Test
    void closesAndReopensCubesAtDistinctVerticalCoordinates() throws Exception {
        CubePos below = CubePos.of(-3, -11, 5);
        CubePos above = CubePos.of(-3, 12, 5);
        try (Fixture first = open()) {
            ProtoCube lower = cube(first, below, Blocks.STONE.defaultBlockState());
            ProtoCube upper = cube(first, above, Blocks.DIAMOND_BLOCK.defaultBlockState());
            lower.setInhabitedTime(123);
            upper.setInhabitedTime(456);
            assertTrue(save(first.access, lower));
            assertTrue(save(first.access, upper));
            assertFalse(lower.isUnsaved());
            assertFalse(upper.isUnsaved());
        }
        assertEquals(2, cubeFiles().size());
        try (Fixture reopened = open()) {
            CubeAccess lower = assertInstanceOf(CubeAccess.class, reopened.load(CloPos.cube(below)));
            CubeAccess upper = assertInstanceOf(CubeAccess.class, reopened.load(CloPos.cube(above)));
            assertEquals(below, lower.cc_getCubePos());
            assertEquals(above, upper.cc_getCubePos());
            assertEquals(Blocks.STONE.defaultBlockState(), lower.getBlockState(sample(below)));
            assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), upper.getBlockState(sample(above)));
            assertEquals(123, lower.getInhabitedTime());
            assertEquals(456, upper.getInhabitedTime());
            assertEquals(ChunkStatus.TERRAIN, lower.getPersistedStatus());
            assertEquals(ChunkStatus.TERRAIN, upper.getPersistedStatus());
        }
    }

    @Test
    void saveAllChunksFlushesFullCubeAndReopensItsContents() throws Exception {
        CubePos pos = CubePos.of(4, -17, -6);
        try (Fixture first = open()) {
            LevelCube cube = new LevelCube(first.level, pos);
            cube.getSection(Coords.blockToIndex(sample(pos))).setBlockState(3, 7, 11, Blocks.LAPIS_BLOCK.defaultBlockState());
            cube.setInhabitedTime(781);
            cube.markUnsaved();
            ChunkHolder holder = pendingHolder(cube);
            when(holder.wasAccessibleSinceLastSave()).thenReturn(true);
            when(holder.isReadyForSaving()).thenReturn(true);
            first.access.cc_testVisibleChunkMap().put(CloPos.cube(pos).asLong(), holder);

            first.access.cc_testSaveAllChunks(true);

            assertFalse(cube.isUnsaved());
            assertEquals(1, cubeFiles().size());
        }
        try (Fixture reopened = open()) {
            ImposterProtoCube loaded = assertInstanceOf(ImposterProtoCube.class, reopened.load(CloPos.cube(pos)));
            LevelCube full = assertInstanceOf(LevelCube.class, loaded.cc_getWrappedClo());
            assertEquals(pos, full.cc_getCubePos());
            assertEquals(ChunkStatus.FULL, full.getPersistedStatus());
            assertEquals(Blocks.LAPIS_BLOCK.defaultBlockState(), full.getBlockState(sample(pos)));
            assertEquals(781, full.getInhabitedTime());
            assertFalse(full.isUnsaved());
        }
    }

    @Test
    void unloadWritesCubeBeforeRemovingItsHolder() throws Exception {
        CubePos pos = CubePos.of(7, -9, -4);
        try (Fixture first = open()) {
            ProtoCube cube = cube(first, pos, Blocks.GOLD_BLOCK.defaultBlockState());
            long packed = CloPos.cube(pos).asLong();
            ChunkHolder holder = pendingHolder(cube);
            first.access.cc_testPendingUnloads().put(packed, holder);
            first.access.cc_testScheduleUnload(packed, holder);
            processUnloads(first.access, () -> true);
            assertFalse(first.access.cc_testPendingUnloads().containsKey(packed));
            assertFalse(cube.isUnsaved());
        }
        try (Fixture reopened = open()) {
            CubeAccess cube = assertInstanceOf(CubeAccess.class, reopened.load(CloPos.cube(pos)));
            assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), cube.getBlockState(sample(pos)));
        }
    }

    @Test
    void fullCubeStatusLoadsEntitiesAndUnloadingPersistsThemWithTheirBlocks() throws Exception {
        CubePos pos = CubePos.of(-2, 1000000, 3);
        java.util.UUID id;
        try (Fixture first = open()) {
            first.enableEntities(directory.resolve("cube_entities"));
            LevelCube cube = fullCube(first, pos, Blocks.GOLD_BLOCK.defaultBlockState());
            cube.setLoaded(true);
            ((CubicChunkMap) first.map).cc_onFullChunkStatusChange(pos, FullChunkStatus.FULL);
            assertTrue(((CubicServerLevel) first.level).cc_areCubeEntitiesLoaded(pos));
            Entity pig = EntityTypes.PIG.create(first.level, EntitySpawnReason.STRUCTURE);
            assertNotNull(pig);
            pig.snapTo(Vec3.atLowerCornerOf(sample(pos)).add(0.25, 0.5, 0.75));
            id = pig.getUUID();
            assertTrue(first.level.addFreshEntity(pig));
            long key = CloPos.cube(pos).asLong();
            ChunkHolder holder = pendingHolder(cube);
            first.access.cc_testPendingUnloads().put(key, holder);
            first.access.cc_testScheduleUnload(key, holder);
            processUnloads(first.access, () -> true);
            assertFalse(first.access.cc_testPendingUnloads().containsKey(key));
            assertTrue(pig.isRemoved());
            assertFalse(first.entityManager.isLoaded(id));
        }
        try (Fixture second = open()) {
            second.enableEntities(directory.resolve("cube_entities"));
            var cube = second.load(CloPos.cube(pos));
            assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), cube.getBlockState(sample(pos)));
            assertFalse(second.entityManager.isLoaded(id));
            ((CubicChunkMap) second.map).cc_onFullChunkStatusChange(pos, FullChunkStatus.FULL);
            assertTrue(second.entityManager.isLoaded(id));
            var sections = ((EntityManagerAccess) second.entityManager).cc_sectionStorage();
            Entity pig = sections.getAllChunksWithExistingSections().longStream().boxed().flatMap(sections::getExistingSectionsInChunk)
                    .flatMap(section -> section.getEntities()).filter(entity -> entity.getUUID().equals(id)).findFirst().orElseThrow();
            assertEquals(Vec3.atLowerCornerOf(sample(pos)).add(0.25, 0.5, 0.75), pig.position());
        }
    }

    @Test
    void entityWriteFailureRetainsTheFullCubeHolderAndLiveEntity() throws Exception {
        CubePos pos = CubePos.of(0, -1000000, 0);
        Fixture f = open();
        try {
            f.enableEntities(directory.resolve("cube_entities"));
            LevelCube cube = fullCube(f, pos, Blocks.STONE.defaultBlockState());
            cube.setLoaded(true);
            ((CubicChunkMap) f.map).cc_onFullChunkStatusChange(pos, FullChunkStatus.FULL);
            Entity pig = EntityTypes.PIG.create(f.level, EntitySpawnReason.STRUCTURE);
            assertNotNull(pig);
            pig.snapTo(Vec3.atLowerCornerOf(sample(pos)));
            assertTrue(f.level.addFreshEntity(pig));
            Path file = directory.resolve("cube_entities/r.0.-31250.0/c.0.-1000000.0.nbt");
            Files.createDirectories(file);
            long key = CloPos.cube(pos).asLong();
            ChunkHolder holder = pendingHolder(cube);
            f.access.cc_testPendingUnloads().put(key, holder);
            f.access.cc_testScheduleUnload(key, holder);
            try {
                processUnloads(f.access, () -> true);
            } catch (RuntimeException failure) {
                assertIoCause(failure);
            }
            assertSame(holder, f.access.cc_testPendingUnloads().get(key));
            assertFalse(pig.isRemoved());
            assertTrue(f.entityManager.isLoaded(pig.getUUID()));
            assertThrows(RuntimeException.class, () -> processUnloads(f.access, () -> true));
            assertThrows(CompletionException.class, f::close);
            try (CubeStorage unlocked = new CubeStorage(directory.resolve("cube_entities"))) {
                assertNotNull(unlocked);
            }
        } finally {
            f.close();
        }
    }

    @Test
    void corruptCubeFailsLoadingWithoutReplacingItsFile() throws Exception {
        CubePos pos = CubePos.of(-1, 4, 2);
        try (Fixture first = open()) {
            assertTrue(save(first.access, cube(first, pos, Blocks.STONE.defaultBlockState())));
        }
        Path saved = cubeFiles().getFirst();
        byte[] corrupt = { 0, 1, 2, 3, 4 };
        Files.write(saved, corrupt);
        try (Fixture reopened = open()) {
            assertThrows(ExecutionException.class, () -> reopened.load(CloPos.cube(pos)));
        }
        assertArrayEquals(corrupt, Files.readAllBytes(saved));
        assertEquals(1, cubeFiles().size());
    }

    @Test
    void failedSaveRetainsDirtyStateAndFailsFlush() throws Exception {
        Fixture fixture = open();
        try {
            ProtoCube cube = cube(fixture, CubePos.ZERO, Blocks.STONE.defaultBlockState());
            obstructCubeRegion();
            try {
                assertFalse(save(fixture.access, cube));
            } catch (RuntimeException failure) {
                assertIoCause(failure);
            }
            assertTrue(cube.isUnsaved());
            CompletionException flushFailure = assertThrows(CompletionException.class, () -> fixture.map.synchronize(true).join());
            assertIoCause(flushFailure);
            CompletionException closeFailure = assertThrows(CompletionException.class, fixture::close);
            assertIoCause(closeFailure);
            assertCubeDirectoryUnlocked();
        } finally {
            fixture.close();
        }
    }

    @Test
    void failedUnloadKeepsThePendingHolderAndDirtyCube() throws Exception {
        Fixture fixture = open();
        try {
            ProtoCube cube = cube(fixture, CubePos.ZERO, Blocks.STONE.defaultBlockState());
            obstructCubeRegion();
            long packed = CloPos.cube(CubePos.ZERO).asLong();
            ChunkHolder holder = pendingHolder(cube);
            fixture.access.cc_testPendingUnloads().put(packed, holder);
            fixture.access.cc_testScheduleUnload(packed, holder);
            try {
                processUnloads(fixture.access, () -> true);
            } catch (RuntimeException failure) {
                assertIoCause(failure);
            }
            assertEquals(holder, fixture.access.cc_testPendingUnloads().get(packed));
            assertTrue(cube.isUnsaved());
            RuntimeException repeatedFailure = assertTimeout(Duration.ofSeconds(1),
                    () -> assertThrows(RuntimeException.class, () -> processUnloads(fixture.access, () -> true)));
            assertIoCause(repeatedFailure);
            assertEquals(holder, fixture.access.cc_testPendingUnloads().get(packed));
            assertTrue(cube.isUnsaved());
            assertThrows(CompletionException.class, () -> fixture.map.synchronize(true).join());
            CompletionException closeFailure = assertThrows(CompletionException.class, fixture::close);
            assertIoCause(closeFailure);
            assertCubeDirectoryUnlocked();
        } finally {
            fixture.close();
        }
    }

    @Test
    void stopServerAfterFailedCubeSaveClosesWorldAndServerStorage() throws Exception {
        try (Fixture fixture = open()) {
            ProtoCube cube = cube(fixture, CubePos.ZERO, Blocks.STONE.defaultBlockState());
            obstructCubeRegion();
            CompletionException saveFailure = assertThrows(CompletionException.class, () -> save(fixture.access, cube));
            fixture.access.cc_testPoiManager().flushAll();
            assertFalse(fixture.map.hasWork(), "This scenario exercises failure in the stopServer save-all stage after the task drain");

            PersistentEntitySectionManager<Entity> entities = mock();
            IOException entityCloseFailure = new IOException("Deliberate entity-storage cleanup failure");
            doThrow(entityCloseFailure).when(entities).close();
            setField(ServerLevel.class, fixture.level, "entityManager", entities);
            doCallRealMethod().when(fixture.level).close();

            MinecraftServer server = mock(MinecraftServer.class, invocation -> {
                String name = invocation.getMethod().getName();
                if (name.equals("stopServer") || name.equals("invoke_stopServer")) {
                    return invocation.callRealMethod();
                }
                return Answers.RETURNS_DEEP_STUBS.answer(invocation);
            });
            for (String name : List.of("packetProcessor", "metricsRecorder")) {
                var field = MinecraftServer.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(server, mock(field.getType()));
            }
            setField(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, fixture.level));
            when(server.getAllLevels()).thenReturn(List.of(fixture.level));
            SavedDataStorage serverData = mock();
            LevelStorageSource.LevelStorageAccess serverStorage = mock();
            CloseableResourceManager resources = mock();
            setField(MinecraftServer.class, server, "savedDataStorage", serverData);
            setField(MinecraftServer.class, server, "storageSource", serverStorage);
            when(server.getResourceManager()).thenReturn(resources);
            doAnswer(ignored -> {
                fixture.access.cc_testSaveAllChunks(true);
                return true;
            }).when(server).saveAllChunks(false, true, false);

            CompletionException stopFailure = assertTimeout(Duration.ofSeconds(5),
                    () -> assertThrows(CompletionException.class, () -> ((MinecraftServerTestAccess) server).invoke_stopServer()));
            fixture.closedExternally();

            assertSame(saveFailure, stopFailure, "Shutdown must preserve the original save failure");
            assertIoCause(stopFailure);
            assertTrue(List.of(stopFailure.getSuppressed()).contains(entityCloseFailure));
            assertTrue(cube.isUnsaved());
            verify(server.getConnection()).stop();
            verify(server).saveAllChunks(false, true, false);
            verify(fixture.level).close();
            verify(entities).close();
            verify(serverData).close();
            verify(resources).close();
            verify(serverStorage).close();
            assertCubeDirectoryUnlocked();
        }
    }

    @Test
    void columnsStillUseVanillaRegionPersistence() throws Exception {
        ChunkPos pos = new ChunkPos(-8, 6);
        BlockPos block = new BlockPos(pos.x() * 16 + 3, 32, pos.z() * 16 + 7);
        try (Fixture first = open()) {
            ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, first.level, first.level.palettedContainerFactory(), null);
            chunk.setBlockState(block, Blocks.EMERALD_BLOCK.defaultBlockState());
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
            chunk.markUnsaved();
            assertTrue(save(first.access, (CloAccess) chunk));
            first.map.synchronize(true).join();
        }
        try (var files = Files.walk(directory.resolve("region"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mca")));
        }
        assertTrue(cubeFiles().isEmpty());
        try (Fixture reopened = open()) {
            CloAccess loaded = reopened.load(CloPos.chunk(pos));
            assertInstanceOf(ProtoChunk.class, loaded);
            assertEquals(Blocks.EMERALD_BLOCK.defaultBlockState(), loaded.getBlockState(block));
            assertEquals(ChunkStatus.TERRAIN, loaded.getPersistedStatus());
        }
    }

    @Test
    void columnSurfaceRetainsUnloadedUpperCubeAcrossReopen() throws Exception {
        CubePos lower = CubePos.of(-2, -3, 4);
        CubePos upper = CubePos.of(-2, -2, 4);
        CubePos empty = CubePos.of(-2, -1, 4);
        try (Fixture first = open()) {
            assertTrue(save(first.access, fullCube(first, lower, Blocks.STONE.defaultBlockState())));
            assertTrue(save(first.access, fullCube(first, upper, Blocks.DIAMOND_BLOCK.defaultBlockState())));
            assertTrue(save(first.access, fullCube(first, empty, Blocks.AIR.defaultBlockState())));
            assertKnownHeight(sample(upper).getY(), first.surface(sample(lower), -3, -1));
        }
        try (Fixture reopened = open()) {
            assertKnownHeight(sample(upper).getY(), reopened.surface(sample(lower), -3, -1));
            assertTrue(reopened.access.cc_testUpdatingChunkMap().isEmpty());
            assertTrue(reopened.access.cc_testVisibleChunkMap().isEmpty());
            assertTrue(reopened.access.cc_testPendingUnloads().isEmpty(), "Surface queries must not load persisted cubes into holders");
            ColumnSurfaceResult air = reopened.surface(sample(empty), -1, -1);
            assertEquals(ColumnSurfaceResult.State.KNOWN_EMPTY, air.state());
            assertTrue(air.isComplete());
            assertTrue(air.highestKnownBlockY().isEmpty(), "A cube-local empty sentinel must not become a global surface");
            assertTrue(air.firstUnknownCubeY().isEmpty());
        }
    }

    @Test
    void liveColumnSurfaceSnapshotOverridesDiskAndPendingHolder() throws Exception {
        CubePos lowerPos = CubePos.of(-1, -1, -2);
        CubePos upperPos = CubePos.of(-1, 0, -2);
        try (Fixture fixture = open()) {
            LevelCube lower = fullCube(fixture, lowerPos, Blocks.DIRT.defaultBlockState());
            LevelCube upper = fullCube(fixture, upperPos, Blocks.STONE.defaultBlockState());
            assertTrue(save(fixture.access, lower));
            assertTrue(save(fixture.access, upper));
            LevelCube obsoletePending = fullCube(fixture, upperPos, Blocks.AIR.defaultBlockState());
            long packed = upper.cc_getCloPos().asLong();
            fixture.access.cc_testPendingUnloads().put(packed, pendingHolder(obsoletePending));
            fixture.access.cc_testUpdatingChunkMap().put(packed, pendingHolder(new ImposterProtoCube(upper, false)));

            CompletableFuture<ColumnSurfaceResult> beforeRemoval = fixture.surfaceFuture(sample(upperPos), -1, 0);
            upper.setBlockState(sample(upperPos), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);

            assertKnownHeight(sample(upperPos).getY(), fixture.await(beforeRemoval));
            assertKnownHeight(sample(lowerPos).getY(), fixture.surface(sample(upperPos), -1, 0));
            assertTrue(upper.isUnsaved());
            assertTrue(save(fixture.access, upper));
            fixture.access.cc_testUpdatingChunkMap().remove(packed);
            ChunkHolder unloading = pendingHolder(new ImposterProtoCube(upper, false));
            fixture.access.cc_testPendingUnloads().put(packed, unloading);
            fixture.access.cc_testScheduleUnload(packed, unloading);
            processUnloads(fixture.access, () -> true);
            assertFalse(fixture.access.cc_testPendingUnloads().containsKey(packed));
            assertKnownHeight(sample(lowerPos).getY(), fixture.surface(sample(upperPos), -1, 0));
        }
        try (Fixture reopened = open()) {
            assertKnownHeight(sample(lowerPos).getY(), reopened.surface(sample(upperPos), -1, 0));
        }
    }

    @Test
    void offThreadColumnSurfaceCapturesWhenTheServerTaskExecutes() throws Exception {
        CubePos lowerPos = CubePos.of(1, -1, -2);
        CubePos upperPos = CubePos.of(1, 0, -2);
        try (Fixture fixture = open()) {
            assertTrue(save(fixture.access, fullCube(fixture, lowerPos, Blocks.DIRT.defaultBlockState())));
            LevelCube upper = fullCube(fixture, upperPos, Blocks.STONE.defaultBlockState());
            assertTrue(save(fixture.access, upper));
            ChunkHolder holder = pendingHolder(upper);
            Thread serverThread = Thread.currentThread();
            doAnswer(invocation -> {
                assertSame(serverThread, Thread.currentThread(), "Live cube access must run on its owning server thread");
                return upper;
            }).when((GenerationCloHolder) holder).cc_getLatestClo();
            fixture.access.cc_testUpdatingChunkMap().put(upper.cc_getCloPos().asLong(), holder);
            BlockPos sample = sample(upperPos);
            CubicServerLevel level = (CubicServerLevel) fixture.level;
            doCallRealMethod().when(level).cc_queryColumnSurface(Heightmap.Types.WORLD_SURFACE, sample.getX(), sample.getZ(), -1, 0);

            CompletableFuture<ColumnSurfaceResult> result = CompletableFuture
                    .supplyAsync(() -> level.cc_queryColumnSurface(Heightmap.Types.WORLD_SURFACE, sample.getX(), sample.getZ(), -1, 0))
                    .get(15, TimeUnit.SECONDS);
            assertFalse(result.isDone(), "The worker must enqueue live cube access until the server thread polls its task queue");
            upper.setBlockState(sample, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);

            assertKnownHeight(sample(lowerPos).getY(), fixture.await(result));
            verify((GenerationCloHolder) holder).cc_getLatestClo();
        }
    }

    @Test
    void columnSurfaceReportsMissingAndProvisionalCubesAsUnknown() throws Exception {
        CubePos fullPos = CubePos.of(2, -2, -3);
        CubePos provisionalPos = CubePos.of(2, 0, -3);
        try (Fixture fixture = open()) {
            assertTrue(save(fixture.access, fullCube(fixture, fullPos, Blocks.STONE.defaultBlockState())));
            ProtoCube provisional = cube(fixture, provisionalPos, Blocks.DIAMOND_BLOCK.defaultBlockState());
            assertTrue(save(fixture.access, provisional));

            ColumnSurfaceResult gap = fixture.surface(sample(fullPos), -2, 0);
            assertEquals(ColumnSurfaceResult.State.UNKNOWN, gap.state());
            assertFalse(gap.isComplete());
            assertEquals(OptionalInt.of(sample(fullPos).getY()), gap.highestKnownBlockY());
            assertEquals(OptionalInt.of(0), gap.firstUnknownCubeY());
            ColumnSurfaceResult missing = fixture.surface(sample(fullPos), -2, -1);
            assertEquals(ColumnSurfaceResult.State.UNKNOWN, missing.state());
            assertEquals(OptionalInt.of(sample(fullPos).getY()), missing.highestKnownBlockY());
            assertEquals(OptionalInt.of(-1), missing.firstUnknownCubeY());
            ColumnSurfaceResult unfinished = fixture.surface(sample(provisionalPos), 0, 0);
            assertEquals(ColumnSurfaceResult.State.UNKNOWN, unfinished.state());
            assertTrue(unfinished.highestKnownBlockY().isEmpty());
            assertEquals(OptionalInt.of(0), unfinished.firstUnknownCubeY());

            // An in-progress live source must also hide an older FULL disk summary at the same coordinates.
            ProtoCube replacement = cube(fixture, fullPos, Blocks.AIR.defaultBlockState());
            fixture.access.cc_testUpdatingChunkMap().put(replacement.cc_getCloPos().asLong(), pendingHolder(replacement));
            ColumnSurfaceResult shadowed = fixture.surface(sample(fullPos), -2, -2);
            assertEquals(ColumnSurfaceResult.State.UNKNOWN, shadowed.state());
            assertTrue(shadowed.highestKnownBlockY().isEmpty());
            assertEquals(OptionalInt.of(-2), shadowed.firstUnknownCubeY());
            fixture.access.cc_testUpdatingChunkMap().clear();
        }
    }

    @Test
    void columnSurfaceRebuildsLegacyFullCubeHeightmaps() throws Exception {
        CubePos pos = CubePos.of(-3, -7, 1);
        try (Fixture first = open()) {
            assertTrue(save(first.access, fullCube(first, pos, Blocks.EMERALD_BLOCK.defaultBlockState())));
        }
        try (CubeStorage storage = new CubeStorage(directory.resolve("cubes"))) {
            var legacy = storage.read(pos).get(5, TimeUnit.SECONDS).orElseThrow();
            legacy.putInt("CubeFormat", 1);
            legacy.remove("CubeHeightmaps");
            storage.write(pos, legacy).get(5, TimeUnit.SECONDS);
        }
        byte[] unchanged = Files.readAllBytes(cubeFiles().getFirst());
        try (Fixture reopened = open()) {
            assertKnownHeight(sample(pos).getY(), reopened.surface(sample(pos), -7, -7));
            assertTrue(reopened.access.cc_testUpdatingChunkMap().isEmpty());
        }
        assertArrayEquals(unchanged, Files.readAllBytes(cubeFiles().getFirst()), "A derived surface query must not rewrite the legacy cube");
    }

    @Test
    void columnSurfaceFailsForCorruptAndBlockedUpperCubeFiles() throws Exception {
        CubePos lower = CubePos.of(1, 3, 2);
        CubePos upper = CubePos.of(1, 4, 2);
        try (Fixture first = open()) {
            assertTrue(save(first.access, fullCube(first, lower, Blocks.STONE.defaultBlockState())));
            assertTrue(save(first.access, fullCube(first, upper, Blocks.DIAMOND_BLOCK.defaultBlockState())));
        }
        Path upperFile = cubeFiles().stream().filter(path -> path.getFileName().toString().equals("c.1.4.2.nbt")).findFirst().orElseThrow();
        byte[] corrupt = { 4, 3, 2, 1, 0 };
        Files.write(upperFile, corrupt);
        try (Fixture reopened = open()) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> reopened.surface(sample(lower), 3, 4));
            assertIoCause(failure);
            assertArrayEquals(corrupt, Files.readAllBytes(upperFile));
        }
        Files.delete(upperFile);
        Files.createDirectory(upperFile);
        try (Fixture reopened = open()) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> reopened.surface(sample(lower), 3, 4));
            assertIoCause(failure);
            assertTrue(Files.isDirectory(upperFile));
        }
    }

    @Test
    void failedUnloadRetainsLiveColumnSurfaceDespiteAnOlderDiskSummary() throws Exception {
        Fixture fixture = open();
        try {
            LevelCube cube = fullCube(fixture, CubePos.ZERO, Blocks.STONE.defaultBlockState());
            assertTrue(save(fixture.access, cube));
            cube.setBlockState(sample(CubePos.ZERO), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            cube.setLightCorrect(true); // The serializer rejects initialized lighting before touching the existing file.
            long packed = cube.cc_getCloPos().asLong();
            ChunkHolder holder = pendingHolder(cube);
            fixture.access.cc_testPendingUnloads().put(packed, holder);
            fixture.access.cc_testScheduleUnload(packed, holder);
            try {
                processUnloads(fixture.access, () -> true);
            } catch (RuntimeException expected) {
                // The unload continuation also records this error for the next process/flush/close call.
            }
            assertSame(holder, fixture.access.cc_testPendingUnloads().get(packed));
            assertTrue(cube.isUnsaved());
            ColumnSurfaceResult surface = fixture.surface(sample(CubePos.ZERO), 0, 0);
            assertEquals(ColumnSurfaceResult.State.KNOWN_EMPTY, surface.state());
            assertTrue(surface.highestKnownBlockY().isEmpty());
            assertTrue(surface.isComplete());
            assertThrows(CompletionException.class, fixture::close);
            assertCubeDirectoryUnlocked();
        } finally {
            fixture.close();
        }
        try (Fixture reopened = open()) {
            assertKnownHeight(sample(CubePos.ZERO).getY(), reopened.surface(sample(CubePos.ZERO), 0, 0));
        }
    }

    @Test
    void skyLightColumnRetainsUnloadedBoundaryOcclusionAcrossReopen() throws Exception {
        CubePos lowerPos = CubePos.of(-2, -1, 3);
        CubePos upperPos = CubePos.of(-2, 0, 3);
        BlockPos column = sample(upperPos);
        try (Fixture first = open()) {
            assertTrue(save(first.access, boundaryCube(first, lowerPos)));
            assertTrue(save(first.access, fullCube(first, upperPos, Blocks.AIR.defaultBlockState())));
            assertBlockingEdge(upperPos.minCubeY(), first.skyLight(column, -1, 0));
        }
        try (Fixture reopened = open()) {
            assertBlockingEdge(upperPos.minCubeY(), reopened.skyLight(column, -1, 0));
            assertBlockingEdge(upperPos.minCubeY(), reopened.skyLight(column, -2, 0));
            assertClearWithinRange(reopened.skyLight(column, 0, 0));
            assertTrue(reopened.access.cc_testUpdatingChunkMap().isEmpty());
            assertTrue(reopened.access.cc_testVisibleChunkMap().isEmpty());
            assertTrue(reopened.access.cc_testPendingUnloads().isEmpty(), "Occlusion queries must not load saved cubes into holders");
        }
    }

    @Test
    void liveSkyLightBoundaryOverridesSavedDataAndPreservesRequestSnapshot() throws Exception {
        CubePos lowerPos = CubePos.of(2, -3, -1);
        CubePos upperPos = CubePos.of(2, -2, -1);
        BlockPos column = sample(upperPos);
        try (Fixture fixture = open()) {
            LevelCube lower = boundaryCube(fixture, lowerPos);
            assertTrue(save(fixture.access, lower));
            assertTrue(save(fixture.access, fullCube(fixture, upperPos, Blocks.AIR.defaultBlockState())));
            long packed = lower.cc_getCloPos().asLong();
            fixture.access.cc_testPendingUnloads().put(packed, pendingHolder(fullCube(fixture, lowerPos, Blocks.AIR.defaultBlockState())));
            fixture.access.cc_testUpdatingChunkMap().put(packed, pendingHolder(new ImposterProtoCube(lower, false)));

            CompletableFuture<ColumnSkyLightResult> beforeRemoval = fixture.skyLightFuture(column, -3, -2);
            lower.setBlockState(boundaryBlock(lowerPos), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);

            assertBlockingEdge(upperPos.minCubeY(), fixture.await(beforeRemoval));
            assertClearWithinRange(fixture.skyLight(column, -3, -2));
            assertTrue(lower.isUnsaved());
            assertTrue(save(fixture.access, lower));
            fixture.access.cc_testUpdatingChunkMap().remove(packed);
            ChunkHolder holder = pendingHolder(new ImposterProtoCube(lower, false));
            fixture.access.cc_testPendingUnloads().put(packed, holder);
            fixture.access.cc_testScheduleUnload(packed, holder);
            processUnloads(fixture.access, () -> true);
            assertFalse(fixture.access.cc_testPendingUnloads().containsKey(packed));
            assertClearWithinRange(fixture.skyLight(column, -3, -2));
        }
        try (Fixture reopened = open()) {
            assertClearWithinRange(reopened.skyLight(column, -3, -2));
        }
    }

    @Test
    void skyLightColumnPreservesCandidateBelowMissingOrProvisionalCubes() throws Exception {
        CubePos lowerPos = CubePos.of(-1, 2, 4);
        CubePos upperPos = CubePos.of(-1, 3, 4);
        try (Fixture fixture = open()) {
            assertTrue(save(fixture.access, fullCube(fixture, lowerPos, Blocks.STONE.defaultBlockState())));
            ColumnSkyLightResult missing = fixture.skyLight(sample(lowerPos), 2, 3);
            assertEquals(ColumnSkyLightResult.State.UNKNOWN, missing.state());
            assertEquals(OptionalInt.of(sample(lowerPos).getY() + 1), missing.highestKnownBlockingY());
            assertEquals(OptionalInt.of(3), missing.firstUnknownCubeY());
            assertFalse(missing.isComplete());

            assertTrue(save(fixture.access, cube(fixture, upperPos, Blocks.AIR.defaultBlockState())));
            ColumnSkyLightResult provisional = fixture.skyLight(sample(lowerPos), 2, 3);
            assertEquals(missing, provisional);

            ProtoCube replacement = cube(fixture, lowerPos, Blocks.AIR.defaultBlockState());
            fixture.access.cc_testUpdatingChunkMap().put(replacement.cc_getCloPos().asLong(), pendingHolder(replacement));
            ColumnSkyLightResult shadowed = fixture.skyLight(sample(lowerPos), 2, 2);
            assertEquals(ColumnSkyLightResult.State.UNKNOWN, shadowed.state());
            assertTrue(shadowed.highestKnownBlockingY().isEmpty());
            assertEquals(OptionalInt.of(2), shadowed.firstUnknownCubeY());
            fixture.access.cc_testUpdatingChunkMap().clear();
        }
    }

    @Test
    void skyLightColumnRebuildsLegacyBoundaryStatesWithoutRewritingFiles() throws Exception {
        CubePos lowerPos = CubePos.of(3, -5, -2);
        CubePos upperPos = CubePos.of(3, -4, -2);
        try (Fixture first = open()) {
            assertTrue(save(first.access, boundaryCube(first, lowerPos)));
            assertTrue(save(first.access, fullCube(first, upperPos, Blocks.AIR.defaultBlockState())));
        }
        try (CubeStorage storage = new CubeStorage(directory.resolve("cubes"))) {
            for (CubePos pos : List.of(lowerPos, upperPos)) {
                var legacy = storage.read(pos).get(5, TimeUnit.SECONDS).orElseThrow();
                legacy.putInt("CubeFormat", 1);
                legacy.remove("CubeHeightmaps");
                storage.write(pos, legacy).get(5, TimeUnit.SECONDS);
            }
        }
        List<Path> savedFiles = cubeFiles();
        byte[] firstBefore = Files.readAllBytes(savedFiles.getFirst());
        byte[] secondBefore = Files.readAllBytes(savedFiles.getLast());
        try (Fixture reopened = open()) {
            assertBlockingEdge(upperPos.minCubeY(), reopened.skyLight(sample(upperPos), -5, -4));
        }
        assertArrayEquals(firstBefore, Files.readAllBytes(savedFiles.getFirst()));
        assertArrayEquals(secondBefore, Files.readAllBytes(savedFiles.getLast()));
    }

    @Test
    void skyLightColumnFailsForCorruptUpperCubeInsteadOfClaimingClearance() throws Exception {
        CubePos lowerPos = CubePos.of(4, -1, -3);
        CubePos upperPos = CubePos.of(4, 0, -3);
        try (Fixture first = open()) {
            assertTrue(save(first.access, boundaryCube(first, lowerPos)));
            assertTrue(save(first.access, fullCube(first, upperPos, Blocks.AIR.defaultBlockState())));
        }
        Path upperFile = cubeFiles().stream().filter(path -> path.getFileName().toString().equals("c.4.0.-3.nbt")).findFirst().orElseThrow();
        byte[] corrupt = { 6, 5, 4, 3, 2, 1 };
        Files.write(upperFile, corrupt);
        try (Fixture reopened = open()) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> reopened.skyLight(sample(upperPos), -1, 0));
            assertIoCause(failure);
            assertArrayEquals(corrupt, Files.readAllBytes(upperFile));
        }
    }

    @Test
    void offThreadSkyLightBridgeCapturesOnServerThreadAndDoesNotReplaceCanSeeSky() throws Exception {
        CubePos lowerPos = CubePos.of(-4, 0, 2);
        CubePos upperPos = CubePos.of(-4, 1, 2);
        try (Fixture fixture = open()) {
            LevelCube lower = boundaryCube(fixture, lowerPos);
            assertTrue(save(fixture.access, lower));
            assertTrue(save(fixture.access, fullCube(fixture, upperPos, Blocks.AIR.defaultBlockState())));
            ChunkHolder holder = pendingHolder(lower);
            Thread serverThread = Thread.currentThread();
            doAnswer(invocation -> {
                assertSame(serverThread, Thread.currentThread(), "Skylight occlusion snapshots must access live cubes on their server thread");
                return lower;
            }).when((GenerationCloHolder) holder).cc_getLatestClo();
            fixture.access.cc_testUpdatingChunkMap().put(lower.cc_getCloPos().asLong(), holder);
            BlockPos column = sample(upperPos);
            CubicServerLevel level = (CubicServerLevel) fixture.level;
            doCallRealMethod().when(level).cc_querySkyLightColumn(column.getX(), column.getZ(), 0, 1);

            CompletableFuture<ColumnSkyLightResult> result = CompletableFuture
                    .supplyAsync(() -> level.cc_querySkyLightColumn(column.getX(), column.getZ(), 0, 1)).get(15, TimeUnit.SECONDS);
            assertFalse(result.isDone());
            lower.setBlockState(boundaryBlock(lowerPos), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);

            assertClearWithinRange(fixture.await(result));
            verify((GenerationCloHolder) holder).cc_getLatestClo();
            doCallRealMethod().when(fixture.level).canSeeSky(column);
            when(fixture.level.getBrightness(LightLayer.SKY, column)).thenReturn(0);
            assertFalse(fixture.level.canSeeSky(column), "CLEAR_WITHIN_RANGE does not establish initialized sky light or open sky");
            when(fixture.level.getBrightness(LightLayer.SKY, column)).thenReturn(15);
            assertTrue(fixture.level.canSeeSky(column), "Vanilla sky visibility still follows the light engine's brightness contract");
        }
    }

    @Test
    void boundedLightingCrossesLiveCubeFacesWithoutClaimingVanillaReadiness() throws Exception {
        CubePos leftPos = CubePos.of(-2, 3, -4);
        CubePos rightPos = CubePos.of(-1, 3, -4);
        CubeLightBounds bounds = new CubeLightBounds(leftPos, rightPos);
        BlockPos source = leftPos.asBlockPos(31, 16, 16);
        BlockPos across = rightPos.asBlockPos(0, 16, 16);
        try (Fixture fixture = open()) {
            LevelCube left = lightCube(fixture, leftPos);
            LevelCube right = lightCube(fixture, rightPos);
            left.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(new ImposterProtoCube(left, false));
            fixture.activate(right);

            assertUnavailable(fixture.light(LightLayer.BLOCK, across));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(15, fixture.light(LightLayer.BLOCK, source));
            assertExactLight(14, fixture.light(LightLayer.BLOCK, across));
            assertExactLight(13, fixture.light(LightLayer.BLOCK, across.east()));
            assertLightRange(0, 15, fixture.light(LightLayer.SKY, across));

            assertFalse(left.isLightCorrect());
            assertFalse(right.isLightCorrect());
            assertEquals(0, fixture.cache.getLightEngine().getRawBrightness(across, 0));
            assertEquals(14, fixture.cache.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(across));
            assertTrue(save(fixture.access, left), "A bounded observation must remain compatible with unlit cube persistence");
            assertTrue(save(fixture.access, right));
        }
        try (Fixture reopened = open()) {
            ImposterProtoCube left = assertInstanceOf(ImposterProtoCube.class, reopened.load(CloPos.cube(leftPos)));
            assertEquals(Blocks.GLOWSTONE.defaultBlockState(), left.getBlockState(source));
            assertFalse(left.isLightCorrect());
            assertUnavailable(reopened.light(LightLayer.BLOCK, across));
        }
    }

    @Test
    void boundedLightingInvalidatesEditsAndBulkChangesAndRemovesOldLight() throws Exception {
        CubePos leftPos = CubePos.ZERO;
        CubePos rightPos = CubePos.of(1, 0, 0);
        CubeLightBounds bounds = new CubeLightBounds(leftPos, rightPos);
        BlockPos leftSource = leftPos.asBlockPos(31, 16, 16);
        BlockPos across = rightPos.asBlockPos(0, 16, 16);
        BlockPos rightSource = across.east();
        try (Fixture fixture = open()) {
            LevelCube left = lightCube(fixture, leftPos);
            LevelCube right = lightCube(fixture, rightPos);
            left.setBlockState(leftSource, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(left);
            fixture.activate(right);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(14, fixture.light(LightLayer.BLOCK, across));

            left.setBlockState(leftSource, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertStale(fixture.light(LightLayer.BLOCK, across));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(0, fixture.light(LightLayer.BLOCK, across));

            right.setBlockState(rightSource, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertStale(fixture.light(LightLayer.BLOCK, leftSource));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(13, fixture.light(LightLayer.BLOCK, leftSource));

            // Packet and other bulk section replacements invalidate before mutating their palette data.
            right.cc_invalidateHeightmaps();
            right.getSection(Coords.blockToIndex(rightSource)).setBlockState(1, 0, 0, Blocks.AIR.defaultBlockState());
            assertStale(fixture.light(LightLayer.BLOCK, leftSource));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(0, fixture.light(LightLayer.BLOCK, across));
        }
    }

    @Test
    void boundedLightingTracksHolderIdentityCubeIdentityAndMissingNeighbors() throws Exception {
        CubePos leftPos = CubePos.of(2, -4, 1);
        CubePos rightPos = CubePos.of(3, -4, 1);
        CubeLightBounds bounds = new CubeLightBounds(leftPos, rightPos);
        BlockPos source = leftPos.asBlockPos(31, 16, 16);
        BlockPos across = rightPos.asBlockPos(0, 16, 16);
        try (Fixture fixture = open()) {
            LevelCube left = lightCube(fixture, leftPos);
            LevelCube right = lightCube(fixture, rightPos);
            left.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            ChunkHolder leftHolder = fixture.activate(left);
            fixture.activate(right);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));

            fixture.activate(right);
            assertStale(fixture.light(LightLayer.BLOCK, source));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(14, fixture.light(LightLayer.BLOCK, across));

            LevelCube replacement = lightCube(fixture, leftPos);
            when(((GenerationCloHolder) leftHolder).cc_getLatestClo()).thenReturn(replacement);
            assertStale(fixture.light(LightLayer.BLOCK, across));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(0, fixture.light(LightLayer.BLOCK, across));

            fixture.access.cc_testUpdatingChunkMap().remove(right.cc_getCloPos().asLong());
            assertStale(fixture.light(LightLayer.BLOCK, source));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertLightRange(0, 14, fixture.light(LightLayer.BLOCK, source));
            assertUnavailable(fixture.light(LightLayer.BLOCK, across));

            fixture.activate(right);
            assertStale(fixture.light(LightLayer.BLOCK, source));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(0, fixture.light(LightLayer.BLOCK, across));
        }
    }

    @Test
    void boundedLightingDoesNotUseSavedPendingOrProvisionalCubesAsLiveSources() throws Exception {
        CubePos leftPos = CubePos.of(-3, 5, 2);
        CubePos rightPos = CubePos.of(-2, 5, 2);
        CubeLightBounds bounds = new CubeLightBounds(leftPos, rightPos);
        BlockPos source = leftPos.asBlockPos(31, 16, 16);
        BlockPos across = rightPos.asBlockPos(0, 16, 16);
        try (Fixture fixture = open()) {
            LevelCube saved = lightCube(fixture, leftPos);
            saved.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertTrue(save(fixture.access, saved));
        }
        try (Fixture fixture = open()) {
            ImposterProtoCube saved = assertInstanceOf(ImposterProtoCube.class, fixture.load(CloPos.cube(leftPos)));
            long leftKey = saved.cc_getCloPos().asLong();
            fixture.access.cc_testPendingUnloads().put(leftKey, pendingHolder(saved));
            fixture.activate(lightCube(fixture, rightPos));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertLightRange(0, 14, fixture.light(LightLayer.BLOCK, across));
            assertUnavailable(fixture.light(LightLayer.BLOCK, source));

            fixture.access.cc_testPendingUnloads().remove(leftKey);
            assertLightRange(0, 14, fixture.light(LightLayer.BLOCK, across));
            fixture.activate(cube(fixture, leftPos, Blocks.GLOWSTONE.defaultBlockState()));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertLightRange(0, 14, fixture.light(LightLayer.BLOCK, across));

            fixture.activate(saved);
            assertStale(fixture.light(LightLayer.BLOCK, across));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(bounds));
            assertExactLight(14, fixture.light(LightLayer.BLOCK, across));
        }
    }

    @Test
    void boundedLightingDiscardsInFlightEditsAndCannotPublishAfterCacheClose() throws Exception {
        CubePos leftPos = CubePos.of(0, -3, -2);
        CubePos rightPos = CubePos.of(1, -3, -2);
        CubeLightBounds bounds = new CubeLightBounds(leftPos, rightPos);
        BlockPos source = leftPos.asBlockPos(31, 16, 16);
        BlockPos across = rightPos.asBlockPos(0, 16, 16);
        try (Fixture fixture = open()) {
            ArrayDeque<Runnable> workers = new ArrayDeque<>();
            Thread owner = Thread.currentThread();
            CubeLightManager manager = new CubeLightManager(fixture.access::cc_testFindLightingSource, () -> Thread.currentThread() == owner,
                    workers::addLast, fixture.access.cc_testMainThreadExecutor());
            fixture.access.cc_testSetCubeLightManager(manager);
            LevelCube left = lightCube(fixture, leftPos);
            left.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(left);
            fixture.activate(lightCube(fixture, rightPos));

            CompletableFuture<CubeLightUpdate> obsolete = fixture.rebuildLightFuture(bounds);
            assertFalse(obsolete.isDone());
            assertEquals(1, workers.size());
            left.setBlockState(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            runLightWorker(workers);
            assertEquals(CubeLightUpdate.STALE, fixture.await(obsolete));
            assertStale(fixture.light(LightLayer.BLOCK, across));

            CompletableFuture<CubeLightUpdate> current = fixture.rebuildLightFuture(bounds);
            runLightWorker(workers);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.await(current));
            assertExactLight(0, fixture.light(LightLayer.BLOCK, across));

            CompletableFuture<CubeLightUpdate> closing = fixture.rebuildLightFuture(bounds);
            fixture.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> closing.get(1, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            runLightWorker(workers);
            assertUnavailable(fixture.light(LightLayer.BLOCK, across));
        }
    }

    @Test
    void boundedLightingServerLevelBridgeCapturesOnOwnerThreadAndCopiesQueryPositions() throws Exception {
        CubePos leftPos = CubePos.of(4, 2, -1);
        CubePos rightPos = CubePos.of(5, 2, -1);
        CubeLightBounds bounds = new CubeLightBounds(leftPos, rightPos);
        BlockPos source = leftPos.asBlockPos(31, 16, 16);
        BlockPos across = rightPos.asBlockPos(0, 16, 16);
        try (Fixture fixture = open()) {
            LevelCube left = lightCube(fixture, leftPos);
            ChunkHolder holder = fixture.activate(left);
            fixture.activate(lightCube(fixture, rightPos));
            Thread owner = Thread.currentThread();
            doAnswer(invocation -> {
                assertSame(owner, Thread.currentThread(), "Live lighting sources must only be read on their owner thread");
                return left;
            }).when((GenerationCloHolder) holder).cc_getLatestClo();
            CubicServerLevel level = (CubicServerLevel) fixture.level;
            doCallRealMethod().when(level).cc_rebuildCubeLighting(bounds);
            doCallRealMethod().when(level).cc_queryCubeLight(any(), any());

            CompletableFuture<CubeLightUpdate> update = CompletableFuture.supplyAsync(() -> level.cc_rebuildCubeLighting(bounds)).get(15,
                    TimeUnit.SECONDS);
            assertFalse(update.isDone());
            left.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.await(update));

            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(across.getX(), across.getY(), across.getZ());
            CompletableFuture<CubeLightQuery> query = CompletableFuture.supplyAsync(() -> level.cc_queryCubeLight(LightLayer.BLOCK, mutable)).get(15,
                    TimeUnit.SECONDS);
            assertFalse(query.isDone());
            mutable.set(across.east());
            assertExactLight(14, fixture.await(query));
            assertExactLight(13, fixture.await(level.cc_queryCubeLight(LightLayer.BLOCK, mutable)));
            verify((GenerationCloHolder) holder, atLeastOnce()).cc_getLatestClo();
        }
    }

    @Test
    void boundedLightingCloseBetweenOwnerDispatchAndAdmissionCannotCreateManager() throws Exception {
        try (Fixture fixture = open()) {
            CubeLightBounds bounds = new CubeLightBounds(CubePos.ZERO, CubePos.ZERO);
            BlockableEventLoop<Runnable> originalExecutor = fixture.access.cc_testMainThreadExecutor();
            BlockableEventLoop<Runnable> pausedExecutor = mock();
            CountDownLatch reachedOwnerCheck = new CountDownLatch(1);
            CountDownLatch closedLighting = new CountDownLatch(1);
            when(pausedExecutor.isSameThread()).thenAnswer(ignored -> {
                reachedOwnerCheck.countDown();
                assertTrue(closedLighting.await(10, TimeUnit.SECONDS), "The close phase must finish before owner admission resumes");
                return true;
            });
            setField(ChunkMap.class, fixture.map, "mainThreadExecutor", pausedExecutor);
            try {
                CompletableFuture<Void> close = CompletableFuture
                        .runAsync(() -> closeLightAfterOwnerCheck(fixture, reachedOwnerCheck, closedLighting));

                CompletableFuture<CubeLightUpdate> update = fixture.rebuildLightFuture(bounds);
                close.get(10, TimeUnit.SECONDS);
                assertNull(fixture.access.cc_testCubeLightManager(), "Shutdown must prevent lazy manager creation after the initial closed check");
                ExecutionException failure = assertThrows(ExecutionException.class, () -> update.get(1, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertUnavailable(fixture.light(LightLayer.BLOCK, BlockPos.ZERO));
            } finally {
                closedLighting.countDown();
                setField(ChunkMap.class, fixture.map, "mainThreadExecutor", originalExecutor);
            }
        }
    }

    private static void closeLightAfterOwnerCheck(Fixture fixture, CountDownLatch reachedOwnerCheck, CountDownLatch closedLighting) {
        try {
            assertTrue(reachedOwnerCheck.await(10, TimeUnit.SECONDS), "Rebuild must reach its owner-thread check");
            fixture.access.cc_testCloseCubeLighting();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompletionException(interrupted);
        } finally {
            closedLighting.countDown();
        }
    }

    @Test
    void exactCubeLightFeedsRealEngineAndDetachedPacketsButStaysUnpersisted() throws Exception {
        CubePos pos = CubePos.of(-2, -71, 3);
        BlockPos source = pos.asBlockPos(16, 16, 16);
        BlockPos adjacent = source.east();
        try (Fixture fixture = open()) {
            LevelCube cube = sealedLightCube(fixture, pos);
            cube.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(new ImposterProtoCube(cube, false));
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuildLight(new CubeLightBounds(pos, pos)));
            ServerCubeCache cache = (ServerCubeCache) fixture.cache;
            CubeLightView view = cache.cc_getCubeLightView(pos);
            assertNotNull(view);
            assertTrue(view.isReady());
            var engine = fixture.cache.getLightEngine();
            assertEquals(15, engine.getLayerListener(LightLayer.BLOCK).getLightValue(source));
            assertEquals(14, engine.getRawBrightness(adjacent, 0));
            assertEquals(14, engine.getRawBrightness(adjacent, 15));
            assertEquals(0, engine.getLayerListener(LightLayer.SKY).getLightValue(source));
            assertFalse(engine.lightOnInColumn(SectionPos.getZeroNode(SectionPos.of(source).asLong())));

            DataLayer block = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(source));
            assertNotNull(block);
            assertEquals(15, block.get(0, 0, 0));
            block.set(0, 0, 0, 1);
            assertEquals(15, engine.getLayerListener(LightLayer.BLOCK).getLightValue(source));
            assertEquals(15, engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(source)).get(0, 0, 0));
            for (int section = 0; section < 8; section++) {
                for (LightLayer layer : LightLayer.values()) {
                    assertNotNull(engine.getLayerListener(layer).getDataLayerData(Coords.sectionPosByIndex(pos, section)));
                }
            }

            CubeLightPacketSnapshot packet = cache.cc_captureCubeLight(cube);
            assertTrue(packet.epoch() > 0);
            assertSame(view, packet.light());
            assertNull(cache.cc_captureCubeLight(sealedLightCube(fixture, pos)).light(),
                    "Equal positions do not authorize a different cube instance");
            assertEquals(packet.epoch(), cache.cc_captureCubeLight(cube).epoch());
            assertFalse(cube.isLightCorrect());
            assertTrue(save(fixture.access, cube));
        }
        try (Fixture reopened = open()) {
            ImposterProtoCube loaded = assertInstanceOf(ImposterProtoCube.class, reopened.load(CloPos.cube(pos)));
            assertFalse(loaded.isLightCorrect());
            assertNull(((ServerCubeCache) reopened.cache).cc_getCubeLightView(pos));
            assertNull(((ServerCubeCache) reopened.cache).cc_captureCubeLight((LevelCube) loaded.cc_getWrappedClo()).light());
        }
    }

    @Test
    void cubeBlockEditsImmediatelyRevokePublishedEngineAndPacketReadiness() throws Exception {
        CubePos pos = CubePos.of(2, 4, -3);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        BlockPos roof = pos.asBlockPos(16, 31, 16);
        try (Fixture fixture = open()) {
            LevelCube cube = sealedLightCube(fixture, pos);
            cube.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(cube);
            CubeLightBounds bounds = new CubeLightBounds(pos, pos);
            fixture.rebuildLight(bounds);
            ServerCubeCache cache = (ServerCubeCache) fixture.cache;
            CubeLightPacketSnapshot old = cache.cc_captureCubeLight(cube);
            assertNotNull(old.light());
            cube.setBlockState(roof, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertTrue(fixture.access.cc_testCubeLightManager().epoch() > old.epoch(),
                    "The block-data hook must revoke before a later read discovers staleness");
            assertNull(cache.cc_getCubeLightView(pos));
            assertNull(cache.cc_captureCubeLight(cube).light());
            assertEquals(0, fixture.cache.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(lamp));

            fixture.rebuildLight(bounds);
            CubeLightView uncertain = cache.cc_getCubeLightView(pos);
            assertNotNull(uncertain);
            assertFalse(uncertain.isReady());
            assertNull(cache.cc_captureCubeLight(cube).light());
            assertNull(fixture.cache.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(roof)));
            assertEquals(0, fixture.cache.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(roof.below()));
            assertEquals(15, old.light().sample(LightLayer.BLOCK, lamp).orElseThrow().lower(),
                    "Revocation must not mutate detached historical snapshots");
        }
    }

    @Test
    void serverTickRevokesNewNeighborsAndRebuildRevokesThePreviousDomain() throws Exception {
        CubePos firstPos = CubePos.of(-4, 2, -2);
        CubePos secondPos = CubePos.of(3, 2, -2);
        CubePos neighborPos = CubePos.of(4, 2, -2);
        try (Fixture fixture = open()) {
            LevelCube first = sealedLightCube(fixture, firstPos);
            LevelCube second = sealedLightCube(fixture, secondPos);
            fixture.activate(first);
            fixture.activate(second);
            ServerCubeCache cache = (ServerCubeCache) fixture.cache;
            fixture.rebuildLight(new CubeLightBounds(firstPos, firstPos));
            CubeLightPacketSnapshot old = cache.cc_captureCubeLight(first);
            assertNotNull(old.light());
            fixture.rebuildLight(new CubeLightBounds(secondPos, neighborPos));
            assertNull(cache.cc_getCubeLightView(firstPos));
            CubeLightPacketSnapshot current = cache.cc_captureCubeLight(second);
            assertNotNull(current.light());
            assertTrue(current.epoch() > old.epoch());

            fixture.activate(lightCube(fixture, neighborPos));
            assertEquals(current.epoch(), fixture.access.cc_testCubeLightManager().epoch());
            fixture.access.cc_testTick();
            assertTrue(fixture.access.cc_testCubeLightManager().epoch() > current.epoch(),
                    "A tick must revoke absent-source changes even without light reads");
            assertNull(cache.cc_getCubeLightView(secondPos));
            assertNull(cache.cc_captureCubeLight(second).light());
            assertNotNull(fixture.awaitLightView(secondPos));
            assertNotNull(cache.cc_captureCubeLight(second).light());
        }
    }

    @Test
    void offThreadEngineAndPacketReadsDoNotTouchLiveHoldersOrWaitForTheOwner() throws Exception {
        CubePos pos = CubePos.of(1, -9, -3);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        try (Fixture fixture = open()) {
            LevelCube cube = sealedLightCube(fixture, pos);
            cube.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            ChunkHolder holder = fixture.activate(cube);
            fixture.rebuildLight(new CubeLightBounds(pos, pos));
            Thread owner = Thread.currentThread();
            doAnswer(ignored -> {
                assertSame(owner, Thread.currentThread(), "A synchronous light adapter must not read a live holder on a worker");
                return cube;
            }).when((GenerationCloHolder) holder).cc_getLatestClo();
            CompletableFuture.runAsync(() -> {
                ServerCubeCache cache = (ServerCubeCache) fixture.cache;
                assertNull(cache.cc_getCubeLightView(pos));
                assertNull(cache.cc_captureCubeLight(cube).light());
                assertEquals(0, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
                assertEquals(0, fixture.cache.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(lamp));
                assertNull(fixture.cache.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(lamp)));
            }).get(10, TimeUnit.SECONDS);
            assertEquals(15, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
        }
    }

    @Test
    void lightingPublicationBroadcastsOneRevocationBeforeFullSnapshotsToTrackedPlayers() throws Exception {
        CubePos firstPos = CubePos.of(-1, 0, 1);
        CubePos secondPos = CubePos.of(0, 0, 1);
        try (Fixture fixture = open(); MockedStatic<ServerPlayNetworking> network = Mockito.mockStatic(ServerPlayNetworking.class)) {
            LevelCube first = sealedLightCube(fixture, firstPos);
            LevelCube second = sealedLightCube(fixture, secondPos);
            fixture.activate(first);
            fixture.activate(second);
            ServerPlayer tracked = lightingPlayer(true);
            ServerPlayer untracked = lightingPlayer(false);
            PlayerMap players = new PlayerMap();
            players.addPlayer(tracked, false);
            players.addPlayer(untracked, false);
            setField(ChunkMap.class, fixture.map, "playerMap", players);
            when(fixture.level.players()).thenReturn(List.of(tracked, untracked));
            Map<ServerPlayer, List<CustomPacketPayload>> sent = new HashMap<>();
            Thread owner = Thread.currentThread();
            network.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CustomPacketPayload.class))).thenAnswer(invocation -> {
                assertSame(owner, Thread.currentThread());
                sent.computeIfAbsent(invocation.getArgument(0), ignored -> new ArrayList<>()).add(invocation.getArgument(1));
                return null;
            });
            fixture.rebuildLight(new CubeLightBounds(firstPos, secondPos));
            assertFalse(sent.containsKey(untracked));
            List<CustomPacketPayload> delivered = sent.get(tracked);
            assertNotNull(delivered);
            assertEquals(4, delivered.size());
            CCClientboundCubeLightInvalidatePacket firstInvalidation = assertInstanceOf(CCClientboundCubeLightInvalidatePacket.class,
                    delivered.get(0));
            CCClientboundCubeLightInvalidatePacket publication = assertInstanceOf(CCClientboundCubeLightInvalidatePacket.class, delivered.get(1));
            assertTrue(publication.epoch() > firstInvalidation.epoch());
            Set<CubePos> positions = new java.util.HashSet<>();
            for (int index = 2; index < delivered.size(); index++) {
                CCClientboundLevelCubeWithLightPacket packet = assertInstanceOf(CCClientboundLevelCubeWithLightPacket.class, delivered.get(index));
                assertEquals(publication.epoch(), packet.lightEpoch());
                assertNotNull(packet.light());
                positions.add(packet.pos());
            }
            assertEquals(Set.of(firstPos, secondPos), positions);
            first.setBlockState(firstPos.asBlockPos(16, 16, 16), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertEquals(5, delivered.size(), "A dependency edit sends one revocation, not one duplicate per affected cube");
            assertTrue(assertInstanceOf(CCClientboundCubeLightInvalidatePacket.class, delivered.getLast()).epoch() > publication.epoch());
            fixture.close();
            assertEquals(5, delivered.size(), "Shutdown must not send light publications");
        }
    }

    private static ServerPlayer lightingPlayer(boolean tracked) throws ReflectiveOperationException {
        ServerPlayer player = mock();
        CloTrackingView view = mock();
        when(view.cc_contains(anyInt(), anyInt(), anyInt())).thenReturn(tracked);
        when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(view);
        ServerGamePacketListenerImpl connection = mock();
        setField(ServerPlayer.class, player, "connection", connection);
        setField(ServerGamePacketListenerImpl.class, connection, "chunkSender", mock(PlayerChunkSender.class));
        return player;
    }

    @Test
    void serverTickAutomaticallyRebuildsEditedLightAndSendsTheNewFullSnapshot() throws Exception {
        CubePos pos = CubePos.of(-3, -4, 2);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        try (Fixture fixture = open(); MockedStatic<ServerPlayNetworking> network = Mockito.mockStatic(ServerPlayNetworking.class)) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube cube = sealedLightCube(fixture, pos);
            cube.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(cube);
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.await(initial));
            assertEquals(15, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
            long oldEpoch = fixture.access.cc_testCubeLightManager().epoch();

            cube.setBlockState(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            assertTrue(workers.isEmpty(), "The block hook revokes immediately but does not calculate inside a setter");
            assertTrue(fixture.access.cc_testCubeLightManager().epoch() > oldEpoch);
            assertNull(((ServerCubeCache) fixture.cache).cc_captureCubeLight(cube).light());
            fixture.access.cc_testTick();
            assertEquals(1, workers.size());

            ServerPlayer player = lightingPlayer(true);
            PlayerMap players = new PlayerMap();
            players.addPlayer(player, false);
            setField(ChunkMap.class, fixture.map, "playerMap", players);
            when(fixture.level.players()).thenReturn(List.of(player));
            List<CustomPacketPayload> sent = new ArrayList<>();
            network.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CustomPacketPayload.class))).thenAnswer(invocation -> {
                assertSame(player, invocation.getArgument(0));
                sent.add(invocation.getArgument(1));
                return null;
            });
            finishLightWorker(fixture, workers);

            assertEquals(0, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
            assertNotNull(fixture.cache.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(lamp)));
            assertNotNull(((ServerCubeCache) fixture.cache).cc_captureCubeLight(cube).light());
            assertEquals(2, sent.size());
            long epoch = assertInstanceOf(CCClientboundCubeLightInvalidatePacket.class, sent.getFirst()).epoch();
            CCClientboundLevelCubeWithLightPacket packet = assertInstanceOf(CCClientboundLevelCubeWithLightPacket.class, sent.getLast());
            assertEquals(epoch, packet.lightEpoch());
            assertTrue(epoch > oldEpoch);
            assertNotNull(packet.light());
            assertEquals(OptionalInt.of(0), packet.light().sample(LightLayer.BLOCK, lamp).orElseThrow().exactValue());
            assertEquals(1, fixture.access.cc_testUpdatingChunkMap().size());
            assertTrue(fixture.access.cc_testVisibleChunkMap().isEmpty());
            assertTrue(cubeFiles().isEmpty(), "Automatic lighting must not load or save cubes as a side effect");
            assertFalse(cube.isLightCorrect());
        }
    }

    @Test
    void staleAutomaticCompletionWaitsForNextTickAndRebuildsOnlyTheNewestEdits() throws Exception {
        CubePos pos = CubePos.of(1, -6, -2);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        try (Fixture fixture = open()) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube cube = sealedLightCube(fixture, pos);
            fixture.activate(cube);
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            fixture.await(initial);

            cube.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.access.cc_testTick();
            cube.setBlockState(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            cube.setBlockState(lamp.east(), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            for (int i = 0; i < 8; i++) {
                fixture.access.cc_testTick();
            }
            assertEquals(1, workers.size(), "Busy ticks must coalesce edits rather than queue additional block snapshots");
            finishLightWorker(fixture, workers);
            assertTrue(workers.isEmpty(), "A stale completion must not recursively submit the next calculation");
            assertNull(((ServerCubeCache) fixture.cache).cc_getCubeLightView(pos));
            assertEquals(0, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));

            fixture.access.cc_testTick();
            assertEquals(1, workers.size());
            finishLightWorker(fixture, workers);
            assertEquals(14, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
            assertEquals(15, fixture.cache.getLightEngine().getRawBrightness(lamp.east(), 0));
            assertIdleLightTicks(fixture, workers);
        }
    }

    @Test
    void emptyLightingDomainStaysIdleWithoutCreatingHoldersAndRefreshesWhenCubeArrives() throws Exception {
        CubePos pos = CubePos.of(-2, 7, -3);
        CubePos outside = CubePos.of(12, 7, -3);
        try (Fixture fixture = open()) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.await(initial));
            assertIdleLightTicks(fixture, workers);
            assertTrue(fixture.access.cc_testUpdatingChunkMap().isEmpty());
            assertTrue(fixture.access.cc_testPendingUnloads().isEmpty());
            assertTrue(cubeFiles().isEmpty());

            fixture.activate(sealedLightCube(fixture, outside));
            assertIdleLightTicks(fixture, workers);
            LevelCube arriving = sealedLightCube(fixture, pos);
            BlockPos lamp = pos.asBlockPos(16, 16, 16);
            arriving.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.activate(arriving);
            fixture.access.cc_testTick();
            finishLightWorker(fixture, workers);
            assertEquals(15, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
            assertNotNull(((ServerCubeCache) fixture.cache).cc_captureCubeLight(arriving).light());
            assertNull(((ServerCubeCache) fixture.cache).cc_getCubeLightView(outside));
            assertEquals(2, fixture.access.cc_testUpdatingChunkMap().size());
            assertIdleLightTicks(fixture, workers);
        }
    }

    @Test
    void automaticLightingRefreshTracksPendingUnloadAndReplacementWithoutUsingSavedCubes() throws Exception {
        CubePos pos = CubePos.of(4, -5, 3);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        try (Fixture fixture = open()) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube original = sealedLightCube(fixture, pos);
            original.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            ChunkHolder holder = fixture.activate(original);
            assertTrue(save(fixture.access, original));
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            fixture.await(initial);

            long key = original.cc_getCloPos().asLong();
            fixture.access.cc_testUpdatingChunkMap().remove(key);
            fixture.access.cc_testPendingUnloads().put(key, holder);
            fixture.access.cc_testTick();
            finishLightWorker(fixture, workers);
            assertNull(((ServerCubeCache) fixture.cache).cc_getCubeLightView(pos));
            assertUnavailable(fixture.light(LightLayer.BLOCK, lamp));
            assertEquals(holder, fixture.access.cc_testPendingUnloads().get(key));
            assertTrue(fixture.access.cc_testUpdatingChunkMap().isEmpty());
            assertIdleLightTicks(fixture, workers);

            LevelCube replacement = sealedLightCube(fixture, pos);
            fixture.activate(replacement);
            fixture.access.cc_testTick();
            finishLightWorker(fixture, workers);
            assertExactLight(0, fixture.light(LightLayer.BLOCK, lamp));
            assertNull(((ServerCubeCache) fixture.cache).cc_captureCubeLight(original).light());
            assertNotNull(((ServerCubeCache) fixture.cache).cc_captureCubeLight(replacement).light());
            fixture.access.cc_testPendingUnloads().remove(key);
            assertEquals(1, cubeFiles().size());
        }
    }

    @Test
    void rejectedExplicitRequestsDoNotRetargetTheAutomaticallyMaintainedDomain() throws Exception {
        CubePos pos = CubePos.of(-4, 0, 2);
        CubePos rejectedPos = CubePos.of(7, 0, 2);
        CubeLightBounds bounds = new CubeLightBounds(pos, pos);
        CubeLightBounds rejectedBounds = new CubeLightBounds(rejectedPos, rejectedPos);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        try (Fixture fixture = open()) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube cube = sealedLightCube(fixture, pos);
            fixture.activate(cube);
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(bounds);
            ExecutionException busy = assertThrows(ExecutionException.class, () -> fixture.await(fixture.rebuildLightFuture(rejectedBounds)));
            assertInstanceOf(IllegalStateException.class, busy.getCause());
            assertEquals(1, workers.size());
            finishLightWorker(fixture, workers);
            fixture.await(initial);

            ChunkHolder wrongPosition = pendingHolder(cube);
            fixture.access.cc_testUpdatingChunkMap().put(CloPos.cube(rejectedPos).asLong(), wrongPosition);
            ExecutionException invalid = assertThrows(ExecutionException.class, () -> fixture.await(fixture.rebuildLightFuture(rejectedBounds)));
            assertInstanceOf(IllegalStateException.class, invalid.getCause());
            fixture.access.cc_testUpdatingChunkMap().remove(CloPos.cube(rejectedPos).asLong());
            assertTrue(workers.isEmpty());

            cube.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.access.cc_testTick();
            finishLightWorker(fixture, workers);
            assertEquals(15, fixture.cache.getLightEngine().getRawBrightness(lamp, 0));
            assertNotNull(((ServerCubeCache) fixture.cache).cc_captureCubeLight(cube).light());
            assertNull(((ServerCubeCache) fixture.cache).cc_getCubeLightView(rejectedPos));
            assertEquals(1, fixture.access.cc_testUpdatingChunkMap().size());
        }
    }

    @Test
    void stableUncertainPublicationDoesNotRebuildAndCloseCancelsAnAutomaticRefresh() throws Exception {
        CubePos pos = CubePos.of(2, 1, -5);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        try (Fixture fixture = open()) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube cube = lightCube(fixture, pos);
            fixture.activate(cube);
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            fixture.await(initial);
            CubeLightView uncertain = ((ServerCubeCache) fixture.cache).cc_getCubeLightView(pos);
            assertNotNull(uncertain);
            assertFalse(uncertain.isReady());
            assertIdleLightTicks(fixture, workers);

            cube.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_SKIP_ON_PLACE);
            fixture.access.cc_testTick();
            assertEquals(1, workers.size());
            fixture.close();
            runLightWorker(workers);
            assertNull(((ServerCubeCache) fixture.cache).cc_getCubeLightView(pos));
            assertUnavailable(fixture.light(LightLayer.BLOCK, lamp));
            assertTrue(workers.isEmpty());
        }
    }

    private static void assertIdleLightTicks(Fixture fixture, ArrayDeque<Runnable> workers) {
        long epoch = fixture.access.cc_testCubeLightManager().epoch();
        for (int i = 0; i < 25; i++) {
            fixture.access.cc_testTick();
        }
        assertTrue(workers.isEmpty(), "Stable known, empty or uncertain dependencies must not schedule a calculation every tick");
        assertEquals(epoch, fixture.access.cc_testCubeLightManager().epoch());
    }

    @Test
    void trackedRecoveryRepublishesReadyLightWithoutSolvingAndSharesOneWorldBudget() throws Exception {
        CubePos pos = CubePos.of(2, -1, 4);
        try (Fixture fixture = open(); MockedStatic<ServerPlayNetworking> network = Mockito.mockStatic(ServerPlayNetworking.class)) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube cube = sealedLightCube(fixture, pos);
            fixture.activate(cube);
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            fixture.await(initial);
            ServerPlayer first = lightingPlayer(true);
            ServerPlayer second = lightingPlayer(true);
            when(first.level()).thenReturn(fixture.level);
            when(second.level()).thenReturn(fixture.level);
            PlayerMap players = new PlayerMap();
            players.addPlayer(first, false);
            players.addPlayer(second, false);
            setField(ChunkMap.class, fixture.map, "playerMap", players);
            when(fixture.level.players()).thenReturn(List.of(first, second));
            Map<ServerPlayer, List<CustomPacketPayload>> sent = new HashMap<>();
            network.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CustomPacketPayload.class))).thenAnswer(invocation -> {
                sent.computeIfAbsent(invocation.getArgument(0), ignored -> new ArrayList<>()).add(invocation.getArgument(1));
                return null;
            });
            CubicChunkMap map = (CubicChunkMap) fixture.map;
            long oldEpoch = fixture.access.cc_testCubeLightManager().epoch();
            assertTrue(map.cc_requestCubeLightRefresh(first, pos, oldEpoch));
            long recoveredEpoch = fixture.access.cc_testCubeLightManager().epoch();
            assertTrue(recoveredEpoch > oldEpoch);
            assertTrue(workers.isEmpty(), "Recovery republishes an existing exact view and never invokes the solver");
            assertRecoveryPackets(sent, List.of(first, second), recoveredEpoch, 2);
            assertFalse(map.cc_requestCubeLightRefresh(first, pos, oldEpoch));
            assertFalse(map.cc_requestCubeLightRefresh(second, pos, oldEpoch));
            for (int i = 0; i < 19; i++) {
                fixture.access.cc_testTickCubeLighting();
            }
            assertFalse(map.cc_requestCubeLightRefresh(second, pos, oldEpoch));
            fixture.access.cc_testTickCubeLighting();
            assertTrue(map.cc_requestCubeLightRefresh(second, pos, oldEpoch));
            assertTrue(workers.isEmpty());
            assertRecoveryPackets(sent, List.of(first, second), fixture.access.cc_testCubeLightManager().epoch(), 4);
        }
    }

    @Test
    void invalidOrUnreadyRecoveryAnchorsCannotConsumeBudgetOrStartALightingJob() throws Exception {
        CubePos pos = CubePos.of(-2, 1, 2);
        CubePos missing = CubePos.of(-1, 1, 2);
        try (Fixture fixture = open(); MockedStatic<ServerPlayNetworking> ignored = Mockito.mockStatic(ServerPlayNetworking.class)) {
            ArrayDeque<Runnable> workers = fixture.controlLighting();
            LevelCube cube = sealedLightCube(fixture, pos);
            fixture.activate(cube);
            CompletableFuture<CubeLightUpdate> initial = fixture.rebuildLightFuture(new CubeLightBounds(pos, pos));
            finishLightWorker(fixture, workers);
            fixture.await(initial);
            ServerPlayer valid = lightingPlayer(true);
            ServerPlayer untracked = lightingPlayer(false);
            ServerPlayer wrongWorld = lightingPlayer(true);
            ServerPlayer disconnected = lightingPlayer(true);
            when(valid.level()).thenReturn(fixture.level);
            when(untracked.level()).thenReturn(fixture.level);
            when(disconnected.level()).thenReturn(fixture.level);
            when(wrongWorld.level()).thenReturn(mock(ServerLevel.class));
            when(fixture.level.players()).thenReturn(List.of(valid, untracked, wrongWorld));
            CubicChunkMap map = (CubicChunkMap) fixture.map;
            long epoch = fixture.access.cc_testCubeLightManager().epoch();
            assertFalse(map.cc_requestCubeLightRefresh(untracked, pos, epoch));
            assertFalse(map.cc_requestCubeLightRefresh(wrongWorld, pos, epoch));
            assertFalse(map.cc_requestCubeLightRefresh(disconnected, pos, epoch));
            assertFalse(map.cc_requestCubeLightRefresh(valid, pos, -1));
            assertFalse(map.cc_requestCubeLightRefresh(valid, pos, epoch + 1));
            assertFalse(map.cc_requestCubeLightRefresh(valid, CubePos.of(1 << 20, 0, 0), epoch));
            assertFalse(map.cc_requestCubeLightRefresh(valid, missing, epoch));
            assertFalse(CompletableFuture.supplyAsync(() -> map.cc_requestCubeLightRefresh(valid, pos, epoch)).get(10, TimeUnit.SECONDS));
            assertEquals(epoch, fixture.access.cc_testCubeLightManager().epoch());
            assertTrue(workers.isEmpty());
            assertEquals(1, fixture.access.cc_testUpdatingChunkMap().size());
            assertTrue(map.cc_requestCubeLightRefresh(valid, pos, epoch), "Rejected requests must leave the shared recovery budget available");
            assertTrue(workers.isEmpty());
        }
    }

    @Test
    void lightStatusBridgeMarshalsToOwnerAndReportsIdleAndClosedWithoutCreatingAManager() throws Exception {
        try (Fixture fixture = open()) {
            CubicServerLevel level = (CubicServerLevel) fixture.level;
            doCallRealMethod().when(level).cc_getCubeLightStatus();
            CompletableFuture<CubeLightStatus> idle = CompletableFuture.supplyAsync(level::cc_getCubeLightStatus).get(10, TimeUnit.SECONDS);
            assertFalse(idle.isDone(), "A status request from a worker must be dispatched to the server owner");
            assertEquals(CubeLightStatus.Phase.IDLE, fixture.await(idle).phase());
            assertNull(fixture.access.cc_testCubeLightManager());
            fixture.close();
            CompletableFuture<CubeLightStatus> closed = CompletableFuture.supplyAsync(level::cc_getCubeLightStatus).get(10, TimeUnit.SECONDS);
            assertEquals(CubeLightStatus.Phase.CLOSED, closed.get(1, TimeUnit.SECONDS).phase());
            assertNull(fixture.access.cc_testCubeLightManager());
        }
    }

    private static void assertRecoveryPackets(Map<ServerPlayer, List<CustomPacketPayload>> sent, List<ServerPlayer> players, long epoch, int count) {
        for (ServerPlayer player : players) {
            List<CustomPacketPayload> packets = sent.get(player);
            assertNotNull(packets);
            assertEquals(count, packets.size());
            assertEquals(epoch, assertInstanceOf(CCClientboundCubeLightInvalidatePacket.class, packets.get(count - 2)).epoch());
            CCClientboundLevelCubeWithLightPacket full = assertInstanceOf(CCClientboundLevelCubeWithLightPacket.class, packets.getLast());
            assertEquals(epoch, full.lightEpoch());
            assertNotNull(full.light());
        }
    }

    private static void finishLightWorker(Fixture fixture, ArrayDeque<Runnable> workers) throws Exception {
        assertEquals(1, workers.size());
        Runnable worker = workers.removeFirst();
        CompletableFuture<Void> ownerFinished = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            worker.run();
            fixture.access.cc_testMainThreadExecutor().execute(() -> ownerFinished.complete(null));
        }).get(15, TimeUnit.SECONDS);
        fixture.await(ownerFinished);
        assertTrue(workers.isEmpty());
    }

    private static void runLightWorker(ArrayDeque<Runnable> workers) throws Exception {
        assertEquals(1, workers.size());
        CompletableFuture.runAsync(workers.removeFirst()).get(15, TimeUnit.SECONDS);
        assertTrue(workers.isEmpty());
    }

    private static void assertExactLight(int expected, CubeLightQuery query) {
        assertLightRange(expected, expected, query);
        assertTrue(query.isReady());
        assertEquals(OptionalInt.of(expected), query.exactValue());
    }

    private static void assertLightRange(int lower, int upper, CubeLightQuery query) {
        assertEquals(CubeLightQuery.State.CURRENT, query.state());
        assertEquals(lower, query.range().orElseThrow().lower());
        assertEquals(upper, query.range().orElseThrow().upper());
        assertEquals(lower == upper, query.isReady());
        if (lower != upper) {
            assertTrue(query.exactValue().isEmpty());
        }
    }

    private static void assertStale(CubeLightQuery query) {
        assertEquals(CubeLightQuery.State.STALE, query.state());
        assertTrue(query.range().isEmpty());
        assertFalse(query.isReady());
        assertTrue(query.exactValue().isEmpty());
    }

    private static void assertUnavailable(CubeLightQuery query) {
        assertEquals(CubeLightQuery.State.UNAVAILABLE, query.state());
        assertTrue(query.range().isEmpty());
        assertFalse(query.isReady());
        assertTrue(query.exactValue().isEmpty());
    }

    private static void assertBlockingEdge(int expectedUpperBlockY, ColumnSkyLightResult result) {
        assertEquals(ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, result.state());
        assertEquals(OptionalInt.of(expectedUpperBlockY), result.highestKnownBlockingY());
        assertTrue(result.firstUnknownCubeY().isEmpty());
        assertTrue(result.isComplete());
    }

    private static void assertClearWithinRange(ColumnSkyLightResult result) {
        assertEquals(ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, result.state());
        assertTrue(result.highestKnownBlockingY().isEmpty());
        assertTrue(result.firstUnknownCubeY().isEmpty());
        assertTrue(result.isComplete());
    }

    private static void assertKnownHeight(int expectedBlockY, ColumnSurfaceResult result) {
        assertEquals(ColumnSurfaceResult.State.KNOWN_HEIGHT, result.state());
        assertEquals(OptionalInt.of(expectedBlockY), result.highestKnownBlockY());
        assertTrue(result.firstUnknownCubeY().isEmpty());
        assertTrue(result.isComplete());
    }

    private void obstructCubeRegion() throws IOException {
        Path cubeDirectory = directory.resolve("cubes");
        Files.createDirectories(cubeDirectory);
        Files.writeString(cubeDirectory.resolve("r.0.0.0"), "This regular file deliberately obstructs the cube region directory.");
    }

    private void assertCubeDirectoryUnlocked() throws IOException {
        try (CubeStorage reopened = new CubeStorage(directory.resolve("cubes"))) {
            reopened.flush().join();
        }
    }

    private List<Path> cubeFiles() throws IOException {
        Path cubes = directory.resolve("cubes");
        if (!Files.exists(cubes)) {
            return List.of();
        }
        try (var files = Files.walk(cubes)) {
            return files.filter(path -> path.getFileName().toString().startsWith("c.") && path.getFileName().toString().endsWith(".nbt")).toList();
        }
    }

    private static BlockPos sample(CubePos pos) {
        return pos.asBlockPos(3, 7, 11);
    }

    private static ProtoCube cube(Fixture fixture, CubePos pos, BlockState state) {
        ProtoCube cube = new ProtoCube(pos, UpgradeData.EMPTY, fixture.level, fixture.level.palettedContainerFactory(), null);
        cube.setBlockState(sample(pos), state);
        cube.setPersistedStatus(ChunkStatus.TERRAIN);
        cube.markUnsaved();
        return cube;
    }

    private static LevelCube fullCube(Fixture fixture, CubePos pos, BlockState state) {
        LevelCube cube = new LevelCube(fixture.level, pos);
        var section = cube.getSection(Coords.blockToIndex(sample(pos)));
        section.setBlockState(3, 7, 11, state);
        // Keep this section nonempty when the queried block is removed, so the test does not enter unfinished cubic lighting.
        section.setBlockState(4, 7, 11, Blocks.STONE.defaultBlockState());
        cube.markUnsaved();
        return cube;
    }

    private static BlockPos boundaryBlock(CubePos pos) {
        return pos.asBlockPos(3, 31, 11);
    }

    private static LevelCube lightCube(Fixture fixture, CubePos pos) {
        LevelCube cube = new LevelCube(fixture.level, pos);
        // Keep both sections containing shared-face samples nonempty while lamps are added and removed.
        for (int localX : List.of(1, 17)) {
            BlockPos anchor = pos.asBlockPos(localX, 17, 17);
            cube.getSection(Coords.blockToIndex(anchor)).setBlockState(1, 1, 1, Blocks.STONE.defaultBlockState());
        }
        cube.markUnsaved();
        return cube;
    }

    private static LevelCube sealedLightCube(Fixture fixture, CubePos pos) {
        LevelCube cube = new LevelCube(fixture.level, pos);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    if (x == 0 || y == 0 || z == 0 || x == 31 || y == 31 || z == 31) {
                        cube.getSection(Coords.blockToIndex(x, y, z)).setBlockState(x & 15, y & 15, z & 15, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        cube.markUnsaved();
        return cube;
    }

    private static LevelCube boundaryCube(Fixture fixture, CubePos pos) {
        LevelCube cube = fullCube(fixture, pos, Blocks.AIR.defaultBlockState());
        var section = cube.getSection(Coords.blockToIndex(boundaryBlock(pos)));
        section.setBlockState(3, 15, 11, Blocks.STONE.defaultBlockState());
        section.setBlockState(4, 15, 11, Blocks.STONE.defaultBlockState());
        cube.cc_invalidateHeightmaps();
        cube.markUnsaved();
        return cube;
    }

    private static ChunkHolder pendingHolder(CubeAccess cube) {
        ChunkHolder holder = mock();
        when(holder.getSaveSyncFuture()).thenReturn(CompletableFuture.completedFuture(null));
        when(((GenerationCloHolder) holder).cc_getLatestClo()).thenReturn(cube);
        when(((GenerationCloHolder) holder).cc_getCloPos()).thenReturn(cube.cc_getCloPos());
        return holder;
    }

    private static void assertIoCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof IOException)) {
            current = current.getCause();
        }
        assertNotNull(current, "The persistence failure must retain its original IOException");
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void cubePoiClaimsFollowRealCacheSaveUnloadAndReload() throws Exception {
        CubePos pos = CubePos.of(0, 1000000, 0);
        BlockPos home = pos.asBlockPos(2, 8, 2);
        var bed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()
                .setValue(net.minecraft.world.level.block.AbstractBedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD);
        try (Fixture first = open()) {
            var cube = fullCube(first, pos, Blocks.AIR.defaultBlockState());
            cube.setBlockState(home, bed, Block.UPDATE_SKIP_ON_PLACE);
            var poi = first.level.getPoiManager();
            poi.take(type -> type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), (type, point) -> true, home, 0).orElseThrow();
            long key = CloPos.cube(pos).asLong();
            ChunkHolder holder = pendingHolder(cube);
            first.access.cc_testPendingUnloads().put(key, holder);
            first.access.cc_testScheduleUnload(key, holder);
            processUnloads(first.access, () -> true);
            assertFalse(first.access.cc_testPendingUnloads().containsKey(key));
            assertEquals(7, poi.sectionsToVillage(net.minecraft.core.SectionPos.of(home)));
        }
        try (Fixture reopened = open()) {
            var cube = reopened.load(CloPos.cube(pos));
            assertEquals(bed, cube.getBlockState(home));
            var poi = reopened.level.getPoiManager();
            assertEquals(0, poi.sectionsToVillage(net.minecraft.core.SectionPos.of(home)));
            assertEquals(1, poi.getCountInRange(type -> true, home, 0, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.IS_OCCUPIED));
            assertTrue(poi.release(home));
            assertFalse(save(reopened.access, cube), "Ticket-only changes must be saved even when block data is clean");
        }
        try (Fixture reopened = open()) {
            reopened.load(CloPos.cube(pos));
            assertEquals(1, reopened.level.getPoiManager().getCountInRange(type -> true, home, 0,
                    net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.HAS_SPACE));
        }
    }

    private Fixture open() throws Exception {
        var registries = VanillaRegistriesExtension.shared();
        MockedStatic<RandomState> randomState = Mockito.mockStatic(RandomState.class, withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS));
        GenerationTestScope scope = GenerationTestScope.open();
        try {
            NoiseBasedChunkGenerator generator = mock();
            when(generator.generatorSettings())
                    .thenReturn(registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD));
            ServerLevel level;
            try (var ignored = Mockito.mockConstruction(ServerChunkCache.class, withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS))) {
                level = mock(withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS).extraInterfaces(CanBeCubic.class));
            }
            var cubicField = Level.class.getDeclaredField("cc_isCubic");
            cubicField.setAccessible(true);
            cubicField.set(level, true);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.dimensionType())
                    .thenReturn(registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value());
            when(level.registryAccess()).thenReturn(registries);
            when(level.getServer().registryAccess()).thenReturn(registries);
            when(level.palettedContainerFactory()).thenReturn(PalettedContainerFactory.create(registries));
            when(level.getGameTime()).thenReturn(6000L);
            when(level.players()).thenReturn(List.of());
            when(((CubicServerLevel) level).cc_tryUnloadCubeEntities(any())).thenReturn(true);
            LevelStorageSource.LevelStorageAccess storage = mock(Answers.RETURNS_DEEP_STUBS);
            when(storage.getDimensionPath(any())).thenReturn(directory);
            when(storage.getLevelId()).thenReturn("cube-persistence-test");
            ServerChunkCache cache = new ServerChunkCache(level, storage, mock(Answers.RETURNS_DEEP_STUBS), mock(Answers.RETURNS_DEEP_STUBS),
                    Runnable::run, generator, 10, 10, true, mock(Answers.RETURNS_DEEP_STUBS));
            var chunkSource = ServerLevel.class.getDeclaredField("chunkSource");
            chunkSource.setAccessible(true);
            chunkSource.set(level, cache);
            when(level.getChunkSource()).thenReturn(cache);
            when(level.getPoiManager()).thenReturn(cache.getPoiManager());
            // Deliberately do not attach GenerationTestScope's in-memory storage to this ChunkMap.
            return new Fixture(level, cache, randomState, scope);
        } catch (Throwable failure) {
            scope.close();
            randomState.close();
            throw failure;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level;
        private final ServerChunkCache cache;
        private final ChunkMap map;
        private final ChunkMapPersistenceTestAccess access;
        private final MockedStatic<RandomState> randomState;
        private final GenerationTestScope scope;
        private PersistentEntitySectionManager<Entity> entityManager;
        private int nextEntityId;
        private boolean closed;

        private Fixture(ServerLevel level, ServerChunkCache cache, MockedStatic<RandomState> randomState, GenerationTestScope scope) {
            this.level = level;
            this.cache = cache;
            this.map = cache.chunkMap;
            this.access = (ChunkMapPersistenceTestAccess) map;
            this.randomState = randomState;
            this.scope = scope;
        }

        private CloAccess load(CloPos pos) throws Exception {
            return await(access.cc_testLoad(pos));
        }

        private void enableEntities(Path directory) throws Exception {
            entityManager = new PersistentEntitySectionManager<>(Entity.class, mock(), mock());
            ((CubicEntityManager) entityManager).cc_configureEntityStorage(level, directory);
            setField(ServerLevel.class, level, "entityManager", entityManager);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.getNextEntityId()).thenAnswer(i -> ++nextEntityId);
            doCallRealMethod().when(level).addFreshEntity(any());
            CubicServerLevel cubic = (CubicServerLevel) level;
            doCallRealMethod().when(cubic).cc_onCubeEntityStatus(any(), any());
            doCallRealMethod().when(cubic).cc_unloadCubeEntities(any());
            doCallRealMethod().when(cubic).cc_tryUnloadCubeEntities(any());
            doCallRealMethod().when(cubic).cc_areCubeEntitiesLoaded(any());
        }

        private ColumnSurfaceResult surface(BlockPos pos, int minCubeY, int maxCubeY) throws Exception {
            return await(surfaceFuture(pos, minCubeY, maxCubeY));
        }

        private CompletableFuture<ColumnSurfaceResult> surfaceFuture(BlockPos pos, int minCubeY, int maxCubeY) {
            return ((ServerCubeCache) cache).cc_queryColumnSurface(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ(), minCubeY, maxCubeY);
        }

        private ColumnSkyLightResult skyLight(BlockPos pos, int minCubeY, int maxCubeY) throws Exception {
            return await(skyLightFuture(pos, minCubeY, maxCubeY));
        }

        private CompletableFuture<ColumnSkyLightResult> skyLightFuture(BlockPos pos, int minCubeY, int maxCubeY) {
            return ((ServerCubeCache) cache).cc_querySkyLightColumn(pos.getX(), pos.getZ(), minCubeY, maxCubeY);
        }

        private ChunkHolder activate(CubeAccess cube) {
            ChunkHolder holder = pendingHolder(cube);
            access.cc_testUpdatingChunkMap().put(cube.cc_getCloPos().asLong(), holder);
            return holder;
        }

        private ArrayDeque<Runnable> controlLighting() {
            ArrayDeque<Runnable> workers = new ArrayDeque<>();
            Thread owner = Thread.currentThread();
            CubeLightManager manager = new CubeLightManager(access::cc_testFindLightingSource, () -> Thread.currentThread() == owner,
                    workers::addLast, access.cc_testMainThreadExecutor(), access::cc_testLightPublicationChanged);
            access.cc_testSetCubeLightManager(manager);
            return workers;
        }

        private CubeLightUpdate rebuildLight(CubeLightBounds bounds) throws Exception {
            return await(rebuildLightFuture(bounds));
        }

        private CompletableFuture<CubeLightUpdate> rebuildLightFuture(CubeLightBounds bounds) {
            return ((ServerCubeCache) cache).cc_rebuildCubeLighting(bounds);
        }

        private CubeLightQuery light(LightLayer layer, BlockPos pos) throws Exception {
            return await(((ServerCubeCache) cache).cc_queryCubeLight(layer, pos));
        }

        private CubeLightView awaitLightView(CubePos pos) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            CubeLightView view;
            while ((view = ((ServerCubeCache) cache).cc_getCubeLightView(pos)) == null && System.nanoTime() < deadline) {
                cache.pollTask();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertNotNull(view, "The already scheduled automatic rebuild must eventually publish");
            return view;
        }

        private <T> T await(CompletableFuture<T> future) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!future.isDone() && System.nanoTime() < deadline) {
                cache.pollTask();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            return future.get(1, TimeUnit.SECONDS);
        }

        @Override public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try (PersistentEntitySectionManager<Entity> closeEntities = entityManager) {
                cache.close();
            } finally {
                closedExternally();
            }
        }

        private void closedExternally() {
            closed = true;
            scope.close();
            randomState.close();
        }
    }
}
