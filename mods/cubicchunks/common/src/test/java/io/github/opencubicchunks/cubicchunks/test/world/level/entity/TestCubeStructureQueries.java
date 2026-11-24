package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeStructureQueries;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeStructureSpawns;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepStructureLayout;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.NativeBiomeTerrain;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeStructureQueries extends BaseTest {
    @TempDir
    Path directory;

    private static StructureManager manager(LevelAccessor level) {
        // Existing saved structures remain queryable even if further structure generation is disabled.
        return new StructureManager(level, new WorldOptions(1, false, false), mock());
    }

    @Test
    void allPositionOverloadsResolveIndependentStartsAtTheSameXZ() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var registry = world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var holder = registry.getOrThrow(BuiltinStructures.MINESHAFT);
            var type = holder.value();
            var holders = HolderSet.direct(holder);
            var manager = manager(world.level);
            Map<Integer, StructureStart> expected = new java.util.HashMap<>();
            for (int y : List.of(-320, -32000, -32000024)) {
                var pos = new BlockPos(16, y, 16);
                var start = TestCubeStructureSpawning.start(type, pos);
                expected.put(y, start);
                var cube = world.cube(pos);
                cube.setStartForStructure(type, start);
                cube.addReferenceForStructure(type, cube.cc_getCubePos().asLong());
            }
            assertTrue(holder.is(StructureTags.MINESHAFT));
            for (var entry : expected.entrySet()) {
                var pos = new BlockPos(16, entry.getKey(), 16);
                var start = entry.getValue();
                assertSame(start, manager.getStructureAt(pos, type));
                assertSame(start, manager.getStructureAt(pos, holders));
                assertSame(start, manager.getStructureWithPieceAt(pos, StructureTags.MINESHAFT));
                assertSame(start, manager.getStructureWithPieceAt(pos, holders));
                assertSame(start, manager.getStructureWithPieceAt(pos, candidate -> candidate.is(BuiltinStructures.MINESHAFT)));
                assertSame(start, manager.getStructureWithPieceAt(pos.getX(), pos.getY(), pos.getZ(), type));
                var refs = LongOpenHashSet.of(CubePos.from(pos).asLong());
                assertEquals(Map.of(type, refs), manager.getAllStructuresAt(pos));
                assertEquals(Map.of(type, refs), manager.getAllStructuresAt(pos.getX(), pos.getY(), pos.getZ()));
            }
            assertNoTerrainRequests(world.level);
        }
    }

    @Test
    void wholeBoundsIncludeGapsButPieceQueriesAndFiltersDoNot() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var registry = world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var holder = registry.getOrThrow(BuiltinStructures.MINESHAFT);
            var other = registry.getOrThrow(BuiltinStructures.STRONGHOLD);
            var pos = new BlockPos(16, -32000, 16);
            var start = TestCubeStructureSpawning.start(holder.value(), pos.offset(-4, 0, -4), pos.offset(4, 0, 4));
            world.cube(pos).setStartForStructure(holder.value(), start);
            var manager = manager(world.level);
            assertSame(start, manager.getStructureAt(pos, holder.value()));
            assertSame(start, manager.getStructureAt(pos, HolderSet.direct(holder)));
            assertSame(StructureStart.INVALID_START, manager.getStructureWithPieceAt(pos, HolderSet.direct(holder)));
            assertSame(StructureStart.INVALID_START, manager.getStructureWithPieceAt(pos.getX(), pos.getY(), pos.getZ(), holder.value()));
            assertSame(StructureStart.INVALID_START, manager.getStructureAt(pos, HolderSet.direct(other)));
            assertSame(StructureStart.INVALID_START, manager.getStructureAt(pos, other.value()));
            assertSame(StructureStart.INVALID_START, manager.getStructureWithPieceAt(pos, StructureTags.VILLAGE));
            assertSame(StructureStart.INVALID_START, manager.getStructureWithPieceAt(pos, candidate -> false));
            assertSame(StructureStart.INVALID_START, manager.getStructureAt(pos.above(), holder.value()));
            assertNoTerrainRequests(world.level);
        }
    }

    @Test
    void missingOwnersAndMissingTargetCubesReturnNoMatchWithoutGenerating() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var type = world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.MINESHAFT);
            var pos = new BlockPos(16, -32000024, 16);
            var manager = manager(world.level);
            assertSame(StructureStart.INVALID_START, manager.getStructureAt(pos, type));
            assertTrue(manager.getAllStructuresAt(pos).isEmpty());
            var owner = CubePos.from(pos.above(4096));
            world.cube(pos).addReferenceForStructure(type, owner.asLong());
            assertSame(StructureStart.INVALID_START, manager.getStructureAt(pos, type));
            var start = TestCubeStructureSpawning.start(type, pos);
            world.cube(owner.asBlockPos()).setStartForStructure(type, start);
            assertSame(start, manager.getStructureAt(pos, type));
            world.cubes.remove(owner);
            assertSame(StructureStart.INVALID_START, manager.getStructureAt(pos, type));
            assertNoTerrainRequests(world.level);
        }
    }

    @Test
    void distantDeepOwnerMetadataResolvesWithoutLoadingAndLoadedAbsenceWins() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var type = world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.MINESHAFT);
            var pos = new BlockPos(16, -32000, 16);
            var owner = CubePos.from(pos.offset(224, 0, 0));
            world.cube(pos).addReferenceForStructure(type, owner.asLong());
            var nativeStart = TestCubeStructureSpawning.start(type, pos);
            var terrain = mock(NativeBiomeTerrain.class);
            var layout = mock(DeepStructureLayout.class);
            var snapshot = mock(DeepStructureLayout.Start.class);
            when(((CubicServerLevel) world.level).cc_nativeBiomeTerrain()).thenReturn(terrain);
            when(terrain.deepStructures()).thenReturn(layout);
            when(layout.startForOwner(owner, type)).thenReturn(snapshot);
            when(snapshot.copy(world.level)).thenReturn(nativeStart);
            assertEquals(List.of(nativeStart), CubeStructureSpawns.starts(world.level, pos, structure -> structure == type).orElseThrow());
            assertSame(nativeStart, manager(world.level).getStructureAt(pos, type));

            clearInvocations(layout);
            world.cube(owner.asBlockPos());
            assertTrue(CubeStructureSpawns.starts(world.level, pos, structure -> true).isEmpty());
            verify(layout, never()).startForOwner(any(), any());
            world.cubes.remove(owner);
            when(layout.startForOwner(owner, type)).thenReturn(null);
            assertTrue(CubeStructureSpawns.starts(world.level, pos, structure -> true).isEmpty());
            when(layout.startForOwner(owner, type)).thenThrow(new IllegalStateException("Malformed saved owner"));
            assertSame(StructureStart.INVALID_START, manager(world.level).getStructureAt(pos, type));
            assertNoTerrainRequests(world.level);
        }
    }

    @Test
    void referenceSnapshotsPreserveFullOwnerYAndDoNotExposeMutableCubeStorage() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var type = world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.MINESHAFT);
            var pos = new BlockPos(16, -32000, 16);
            var first = CubePos.from(pos);
            var second = CubePos.from(pos.below(1 << 24));
            var cube = world.cube(pos);
            cube.addReferenceForStructure(type, first.asLong());
            cube.addReferenceForStructure(type, second.asLong());
            var snapshot = manager(world.level).getAllStructuresAt(pos);
            assertEquals(LongOpenHashSet.of(first.asLong(), second.asLong()), snapshot.get(type));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.get(type).clear());
            cube.addReferenceForStructure(type, CubePos.from(pos.below(4096)).asLong());
            assertEquals(2, snapshot.get(type).size());
            assertEquals(3, cube.getReferencesForStructure(type).size());
            assertNoTerrainRequests(world.level);
        }
    }

    @Test
    void nativeHeightNonCubicAndWorldGenerationQueriesKeepNativeColumnSemantics() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var type = world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.MINESHAFT);
            var nativePos = new BlockPos(16, -64, 16);
            assertFalse(CubeStructureQueries.handles(world.level, nativePos.getY()));
            assertNativeColumnResult(world.level, nativePos, type);
            when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
            var deepPos = new BlockPos(16, -32000, 16);
            assertFalse(CubeStructureQueries.handles(world.level, deepPos.getY()));
            assertNativeColumnResult(world.level, deepPos, type);
            WorldGenRegion region = mock();
            assertFalse(CubeStructureQueries.handles(region, deepPos.getY()));
            assertNativeColumnResult(region, deepPos, type);
        }
    }

    private static void assertNativeColumnResult(LevelAccessor level, BlockPos pos, Structure type) {
        var start = TestCubeStructureSpawning.start(type, pos);
        var owner = ChunkPos.containing(pos);
        var references = LongOpenHashSet.of(owner.pack());
        ChunkAccess column = mock();
        when(level.getChunk(owner.x(), owner.z(), ChunkStatus.STRUCTURE_REFERENCES)).thenReturn(column);
        when(level.getChunk(owner.x(), owner.z(), ChunkStatus.STRUCTURE_STARTS)).thenReturn(column);
        when(column.getReferencesForStructure(type)).thenReturn(references);
        when(column.getAllReferences()).thenReturn(Map.of(type, references));
        when(column.getStartForStructure(type)).thenReturn(start);
        var manager = manager(level);
        assertSame(start, manager.getStructureAt(pos, type));
        assertSame(start, manager.getStructureWithPieceAt(pos.getX(), pos.getY(), pos.getZ(), type));
        assertSame(column.getAllReferences(), manager.getAllStructuresAt(pos));
    }

    private static void assertNoTerrainRequests(ServerLevel level) {
        verify(level, never()).getChunk(anyInt(), anyInt());
        verify(level, never()).getChunk(anyInt(), anyInt(), any(ChunkStatus.class));
        verify(level, never()).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        var source = (CubeSource) level.getChunkSource();
        verify(source, never()).cc_getCube(anyInt(), anyInt(), anyInt(), anyBoolean());
        verify(source, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
    }
}
