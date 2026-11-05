package io.github.opencubicchunks.cubicchunks.test.world.level.poi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.CubeStorageBarrier;
import io.github.opencubicchunks.cubicchunks.testutils.PoiTestSupport;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiStorage;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubicPoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubePoiManager extends BaseTest {
    @TempDir
    Path directory;

    static BlockState bed() {
        return Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(AbstractBedBlock.PART, BedPart.HEAD);
    }

    private static CubePoiStorage cubes(PoiManager manager) {
        return ((CubicPoiManager) manager).cc_cubePoi();
    }

    @Test
    void claimChangesDuringAsyncWriteSurviveReopen() throws Exception {
        BlockPos pos = new BlockPos(2, -32000024, 2);
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            world.block(pos, bed());
            poi.take(type -> true, (type, point) -> true, pos, 0).orElseThrow();
            CubePoiStorage storage = cubes(poi);
            try (var barrier = new CubeStorageBarrier(CubeStorageBarrier.storage(CubePoiStorage.class, storage, "storage"))) {
                assertTimeout(Duration.ofSeconds(1), () -> storage.tick(() -> true));
                assertTrue(poi.release(pos));
                assertTimeout(Duration.ofSeconds(1), () -> storage.tick(() -> true));
                assertFalse(storage.tryUnload(CubePos.from(pos)));
                assertTrue(storage.hasWork());
            }
            storage.flushAll();
        }
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            assertEquals(1, poi.getCountInRange(type -> true, pos, 0, PoiManager.Occupancy.HAS_SPACE));
        }
    }

    @Test
    void fullCoordinatesAndNativeTicketsAreIndependentAtAliasedHeights() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            for (int y : new int[] { 32000008, -32000024 }) {
                BlockPos first = new BlockPos(2, y, 2);
                BlockPos alias = first.above(4096);
                world.block(first, bed());
                world.block(alias, Blocks.COMPOSTER.defaultBlockState());
                assertTrue(poi.existsAtPosition(PoiTypes.HOME, first));
                assertTrue(poi.existsAtPosition(PoiTypes.FARMER, alias));
                assertTrue(poi.take(type -> type.is(PoiTypes.HOME), (type, pos) -> true, first, 1).isPresent());
                assertTrue(poi.take(type -> true, (type, pos) -> true, first, 1).isEmpty());
                assertEquals(1, poi.getCountInRange(type -> true, alias, 1, PoiManager.Occupancy.HAS_SPACE));
                assertTrue(poi.release(first));
                assertFalse(poi.release(first));
                assertNotNull(poi.getDebugPoiInfo(first));
                poi.remove(first);
                assertTrue(poi.existsAtPosition(PoiTypes.FARMER, alias));
            }
        }
    }

    @Test
    void rangeClosestTypeRandomAndBoxQueriesUseThreeDimensions() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            BlockPos center = new BlockPos(31, 32000031, 31);
            for (BlockPos pos : List.of(center.above(2), center.east(3), center.below(5), center.offset(4, 4, 4), center.above(4096))) {
                world.block(pos, Blocks.BELL.defaultBlockState());
            }
            assertEquals(center.above(2), poi.findClosest(type -> true, center, 4, PoiManager.Occupancy.ANY).orElseThrow());
            assertEquals(3, poi.getInSquare(type -> true, center, 4, PoiManager.Occupancy.ANY).count());
            assertEquals(2, poi.getInRange(type -> true, center, 4, PoiManager.Occupancy.ANY).count());
            assertEquals(center.east(3),
                    poi.findClosest(type -> true, pos -> pos.getY() == center.getY(), center, 4, PoiManager.Occupancy.ANY).orElseThrow());
            assertTrue(poi.findClosestWithType(type -> true, center, 4, PoiManager.Occupancy.ANY).orElseThrow().getFirst().is(PoiTypes.MEETING));
            assertEquals(center.above(2),
                    poi.getRandom(type -> true, pos -> pos.equals(center.above(2)), PoiManager.Occupancy.ANY, center, 4, world.level.getRandom())
                            .orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> poi.getInRange(type -> true, center, Integer.MAX_VALUE, PoiManager.Occupancy.ANY));
            assertThrows(IllegalArgumentException.class, () -> poi.getInSquare(type -> true, center, -1, PoiManager.Occupancy.ANY));
        }
    }

    @Test
    void villageDistanceFollowsOccupiedTaggedSectionsAndRemoval() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            BlockPos pos = new BlockPos(3, -32000024, 3);
            world.block(pos, bed());
            SectionPos section = SectionPos.of(pos);
            assertEquals(7, poi.sectionsToVillage(section));
            poi.take(type -> true, (type, point) -> true, pos, 0).orElseThrow();
            assertEquals(0, poi.sectionsToVillage(section));
            assertEquals(6, poi.sectionsToVillage(SectionPos.of(section.x() + 6, section.y() - 6, section.z() + 6)));
            assertEquals(7, poi.sectionsToVillage(SectionPos.of(section.x(), section.y() + (1 << 20), section.z())));
            assertEquals(7, poi.sectionsToVillage(SectionPos.of(section.x(), section.y() + 7, section.z())));
            poi.release(pos);
            assertEquals(7, poi.sectionsToVillage(section));
            poi.take(type -> true, (type, point) -> true, pos, 0).orElseThrow();
            world.block(pos, Blocks.AIR.defaultBlockState());
            assertEquals(7, poi.sectionsToVillage(section));
        }
    }

    @Test
    void savedClaimsSurviveFreshManagerAndUnloadedVillagersAtBothSigns() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            Path root = directory.resolve(Integer.toString(y));
            BlockPos home = new BlockPos(1, y, 1);
            BlockPos job = home.above(4096);
            try (var world = new CubeMobTestWorld(root.resolve("entities"))) {
                try (var poi = PoiTestSupport.attach(world.level, root)) {
                    world.block(home, bed());
                    world.block(job, Blocks.COMPOSTER.defaultBlockState());
                    poi.take(type -> true, (type, point) -> true, home, 0).orElseThrow();
                    poi.take(type -> true, (type, point) -> true, job, 0).orElseThrow();
                    poi.flushAll();
                    assertFalse(poi.hasWork());
                    cubes(poi).unload(CubePos.from(home));
                    assertEquals(7, poi.sectionsToVillage(SectionPos.of(home)));
                    assertEquals(1, poi.getCountInRange(type -> true, home, 0, PoiManager.Occupancy.IS_OCCUPIED));
                    assertEquals(0, poi.sectionsToVillage(SectionPos.of(home)));
                }
                try (var poi = PoiTestSupport.attach(world.level, root)) {
                    assertEquals(1, poi.getCountInRange(type -> type.is(PoiTypes.HOME), home, 0, PoiManager.Occupancy.IS_OCCUPIED));
                    assertEquals(1, poi.getCountInRange(type -> type.is(PoiTypes.FARMER), job, 0, PoiManager.Occupancy.IS_OCCUPIED));
                    assertTrue(poi.release(home));
                    poi.flushAll();
                }
                try (var poi = PoiTestSupport.attach(world.level, root)) {
                    assertEquals(1, poi.getCountInRange(type -> type.is(PoiTypes.HOME), home, 0, PoiManager.Occupancy.HAS_SPACE));
                    poi.remove(home);
                }
                try (var poi = PoiTestSupport.attach(world.level, root)) {
                    assertTrue(poi.getType(home).isEmpty());
                    assertTrue(poi.getType(job).isPresent());
                }
            }
        }
    }

    @Test
    void sectionReconciliationPreservesMatchingClaimsAndRepairsChangedTypes() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            BlockPos pos = new BlockPos(1, 32000008, 1);
            var cube = world.cube(pos);
            world.block(pos, bed());
            poi.take(type -> true, (type, point) -> true, pos, 0).orElseThrow();
            cube.setLoaded(true);
            assertEquals(1, poi.getCountInRange(type -> true, pos, 0, PoiManager.Occupancy.IS_OCCUPIED));
            // Bypass event hooks to model edited/stale persisted palettes.
            var section = cube.getSection(io.github.opencubicchunks.cc_core.utils.Coords.blockToIndex(pos));
            section.setBlockState(1, 8, 1, Blocks.BELL.defaultBlockState());
            poi.checkConsistencyWithBlocks(SectionPos.of(pos), section);
            assertTrue(poi.existsAtPosition(PoiTypes.MEETING, pos));
            assertEquals(0, poi.getCountInRange(type -> true, pos, 0, PoiManager.Occupancy.IS_OCCUPIED));
            section.setBlockState(1, 8, 1, Blocks.AIR.defaultBlockState());
            poi.checkConsistencyWithBlocks(SectionPos.of(pos), section);
            assertTrue(poi.getType(pos).isEmpty());
        }
    }

    @Test
    void blockCallbacksAreIdempotentAndDoNotResetClaimOnDuplicateNotification() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            BlockPos pos = new BlockPos(4, -32000024, 4);
            world.block(pos, bed());
            poi.take(type -> true, (type, point) -> true, pos, 0).orElseThrow();
            doCallRealMethod().when(world.level).updatePOIOnBlockStateChange(any(), any(), any());
            world.level.updatePOIOnBlockStateChange(pos, Blocks.AIR.defaultBlockState(), bed());
            assertEquals(1, poi.getCountInRange(type -> true, pos, 0, PoiManager.Occupancy.IS_OCCUPIED));
            world.block(pos, Blocks.COMPOSTER.defaultBlockState());
            assertTrue(poi.existsAtPosition(PoiTypes.FARMER, pos));
            assertEquals(1, poi.getCountInRange(type -> true, pos, 0, PoiManager.Occupancy.HAS_SPACE));
        }
    }

    @Test
    void onlyExplicitEnsureMayLoadTerrainAndFailedLoadsCanBeRetried() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            BlockPos pos = new BlockPos(4, 32000008, 4);
            poi.findClosest(type -> true, pos, 0, PoiManager.Occupancy.ANY);
            verify((CubicLevelReader) world.level, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean());
            poi.ensureLoadedAndValid(world.level, pos, 0);
            var cube = world.cube(pos);
            cube.getSection(io.github.opencubicchunks.cc_core.utils.Coords.blockToIndex(pos)).setBlockState(4, 8, 4, bed());
            when(((CubicLevelReader) world.level).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean())).thenReturn(cube);
            poi.ensureLoadedAndValid(world.level, pos, 0);
            assertTrue(poi.existsAtPosition(PoiTypes.HOME, pos));
            verify(world.level, never()).getChunk(anyInt(), anyInt(), any());
        }
    }

    @Test
    void allEightSectionsAndCoordinateEndpointsRemainDistinct() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            CubePos cube = CubePos.from(new BlockPos(0, 32000000, 0));
            for (int index = 0; index < 8; index++) {
                var pos = CubePoiStorage.sectionPos(cube, index).origin().offset(1, 1, 1);
                world.block(pos, Blocks.BELL.defaultBlockState());
                assertTrue(poi.existsAtPosition(PoiTypes.MEETING, pos));
            }
            assertEquals(8, poi.getInSquare(type -> true, cube.asBlockPos().offset(16, 16, 16), 16, PoiManager.Occupancy.ANY).count());
            var type = world.level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.MEETING);
            for (int endpoint : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX }) {
                BlockPos pos = new BlockPos(endpoint, endpoint, endpoint);
                poi.add(pos, type);
                assertEquals(pos, poi.findClosest(t -> true, pos, 1, PoiManager.Occupancy.ANY).orElseThrow());
            }
        }
    }

    @Test
    void badDiskDataIsRejectedWithoutReplacingFile() throws Exception {
        BlockPos pos = new BlockPos(1, 32000008, 1);
        try (var world = new CubeMobTestWorld(directory.resolve("entities"))) {
            try (var poi = PoiTestSupport.attach(world.level, directory)) {
                world.block(pos, bed());
            }
            CubePos cube = CubePos.from(pos);
            try (var store = new CubeStorage(directory.resolve("cube_poi"))) {
                CompoundTag saved = store.read(cube).join().orElseThrow();
                saved.putInt("Version", 999);
                store.write(cube, saved).join();
            }
            PoiManager broken = PoiTestSupport.attach(world.level, directory);
            assertThrows(RuntimeException.class, () -> broken.getType(pos));
            assertThrows(IOException.class, broken::close);
            try (var store = new CubeStorage(directory.resolve("cube_poi"))) {
                assertEquals(999, store.read(cube).join().orElseThrow().getInt("Version").orElseThrow());
            }
        }
    }

    @Test
    void failedWriteRetainsFailureAndReleasesStoreLockOnClose() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities"))) {
            PoiManager poi = PoiTestSupport.attach(world.level, directory);
            BlockPos pos = new BlockPos(1, 32000008, 1);
            world.block(pos, bed());
            CubePos cube = CubePos.from(pos);
            String region = "r." + Math.floorDiv(cube.getX(), 32) + "." + Math.floorDiv(cube.getY(), 32) + "." + Math.floorDiv(cube.getZ(), 32);
            Path obstruction = directory.resolve("cube_poi").resolve(region)
                    .resolve("c." + cube.getX() + "." + cube.getY() + "." + cube.getZ() + ".nbt");
            Files.createDirectories(obstruction);
            assertThrows(RuntimeException.class, poi::flushAll);
            assertThrows(RuntimeException.class, () -> cubes(poi).unload(cube));
            assertThrows(IOException.class, poi::close);
            try (var reopened = new CubeStorage(directory.resolve("cube_poi"))) {
                assertNotNull(reopened);
            }
        }
    }

    @Test
    void malformedRecordsNeverRegisterAPartialCube() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities"))) {
            BlockPos pos = new BlockPos(1, 32000008, 1);
            CubePos cube = CubePos.from(pos);
            try (var poi = PoiTestSupport.attach(world.level, directory)) {
                world.block(pos, bed());
            }
            CompoundTag original;
            try (var store = new CubeStorage(directory.resolve("cube_poi"))) {
                original = store.read(cube).join().orElseThrow();
            }
            for (int kind = 0; kind < 6; kind++) {
                CompoundTag bad = original.copy();
                var sections = bad.getListOrEmpty("Sections");
                var records = ((CompoundTag) sections.get(io.github.opencubicchunks.cc_core.utils.Coords.blockToIndex(pos)))
                        .getListOrEmpty("Records");
                var record = (CompoundTag) records.getFirst();
                switch (kind) {
                    case 0 -> record.putInt("free_tickets", -1);
                    case 1 -> record.putInt("free_tickets", 2);
                    case 2 -> records.add(record.copy());
                    case 3 -> record.putIntArray("pos", new int[] { 1, pos.getY() + 4096, 1 });
                    case 4 -> bad.putIntArray("Pos", new int[] { 1, cube.getY(), 0 });
                    default -> record.putString("type", "minecraft:missing_poi");
                }
                try (var store = new CubeStorage(directory.resolve("cube_poi"))) {
                    store.write(cube, bad).join();
                }
                PoiManager poi = PoiTestSupport.attach(world.level, directory);
                assertThrows(RuntimeException.class, () -> poi.getType(pos));
                assertThrows(IOException.class, poi::close);
                try (var store = new CubeStorage(directory.resolve("cube_poi"))) {
                    assertEquals(bad, store.read(cube).join().orElseThrow());
                }
            }
        }
    }

    @Test
    void tickBudgetAndOwnerThreadAreEnforced() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities")); var poi = PoiTestSupport.attach(world.level, directory)) {
            BlockPos pos = new BlockPos(1, -32000024, 1);
            world.block(pos, bed());
            assertTrue(poi.hasWork());
            poi.tick(() -> false);
            assertTrue(poi.hasWork());
            poi.tick(() -> true);
            poi.flushAll();
            assertFalse(poi.hasWork());
            var wrongThread = java.util.concurrent.CompletableFuture.runAsync(() -> poi.getType(pos));
            assertThrows(java.util.concurrent.CompletionException.class, wrongThread::join);
            assertTrue(poi.getType(pos).isPresent());
        }
    }

    @Test
    void ordinaryWorldUsesNativeSectionsAndNoCubicStore() throws Exception {
        try (var world = new CubeMobTestWorld(directory.resolve("entities"))) {
            when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
            CubeMobTestWorld.setField(net.minecraft.world.level.Level.class, world.level, "cc_isCubic", false);
            try (var poi = PoiTestSupport.attach(world.level, directory)) {
                assertNull(cubes(poi));
                var type = world.level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.HOME);
                BlockPos pos = new BlockPos(1, 70, 1);
                poi.add(pos, type);
                assertTrue(poi.existsAtPosition(PoiTypes.HOME, pos));
                assertThrows(IllegalArgumentException.class, () -> poi.add(pos.atY(32000000), type));
                assertFalse(Files.exists(directory.resolve("cube_poi")));
            }
        }
    }
}
