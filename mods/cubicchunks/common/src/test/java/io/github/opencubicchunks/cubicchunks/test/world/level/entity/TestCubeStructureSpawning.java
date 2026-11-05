package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeSpawnTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeNaturalSpawner;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnState;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeStructureSpawns;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeStructureSpawning extends BaseTest {
    @TempDir
    Path directory;

    static StructureStart start(Structure type, BlockPos... pieces) {
        return new StructureStart(type, ChunkPos.containing(pieces[0]), 0,
                new PiecesContainer(java.util.Arrays.stream(pieces)
                        .map(pos -> (net.minecraft.world.level.levelgen.structure.StructurePiece) new BuriedTreasurePieces.BuriedTreasurePiece(pos))
                        .toList()));
    }

    @Test
    void generatedColumnReferencesResolveWithoutLoadingAMissingOwner() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            var pos = new BlockPos(-16, 64, 16);
            when(f.level.getMinY()).thenReturn(-64);
            when(f.level.getMaxY()).thenReturn(319);
            f.world.cube(pos);
            var type = f.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.SWAMP_HUT);
            var start = start(type, pos);
            LevelChunk column = mock();
            LevelChunk owner = mock();
            var origin = new ChunkPos(-2, 2);
            var source = f.level.getChunkSource();
            when(source.getChunkNow(-1, 1)).thenReturn(column);
            when(column.getAllReferences()).thenReturn(Map.of(type, LongOpenHashSet.of(origin.pack())));
            when(source.getChunkNow(-2, 2)).thenReturn(null);
            assertTrue(CubeStructureSpawns.starts(f.level, pos, s -> true).isEmpty());
            when(source.getChunkNow(-2, 2)).thenReturn(owner);
            when(owner.getStartForStructure(type)).thenReturn(start);
            assertEquals(List.of(start), CubeStructureSpawns.starts(f.level, pos, s -> true).orElseThrow());
            verify(f.level, never()).getChunk(anyInt(), anyInt());
        }
    }

    @Test
    void fortressPieceAndNetherBrickBoundingBoxRulesUseFullY() throws Exception {
        for (int y : List.of(32000008, -32000024)) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.platform(y, 0);
                BlockPos pos = new BlockPos(16, y, 16);
                var fortress = f.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.FORTRESS);
                var cube = f.world.cube(pos);
                cube.setStartForStructure(fortress, start(fortress, pos.offset(-4, 0, -4), pos.offset(4, 0, 4)));
                try (var scope = new CubeSpawnContext(f.level)) {
                    assertTrue(CubeStructureSpawns.mobsAt(f.level, MobCategory.MONSTER, pos).orElseThrow().isEmpty());
                    f.world.block(pos.below(), Blocks.NETHER_BRICKS.defaultBlockState());
                    assertSame(NetherFortressStructure.FORTRESS_ENEMIES, CubeStructureSpawns.mobsAt(f.level, MobCategory.MONSTER, pos).orElseThrow());
                    assertTrue(CubeStructureSpawns.mobsAt(f.level, MobCategory.MONSTER, pos.above(4096)).isEmpty());
                    assertTrue(CubeStructureSpawns.mobsAt(f.level, MobCategory.CREATURE, pos).orElseThrow()
                            .contains(f.level.environmentAttributes()
                                    .getValue(net.minecraft.world.attribute.EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos)
                                    .getMobsToSpawn(MobCategory.CREATURE).getRandom(RandomSource.create(1)).orElseThrow()));
                }
                verify(f.level, never()).getChunk(anyInt(), anyInt());
            }
        }
    }

    @Test
    void missingReferencedOwnerDefersInsteadOfUsingBiomeFallback() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            BlockPos pos = new BlockPos(16, -32000024, 16);
            f.platform(pos.getY(), 0);
            var structure = f.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.SWAMP_HUT);
            CubePos owner = CubePos.from(pos.above(1 << 24));
            f.world.cube(pos).addReferenceForStructure(structure, owner.asLong());
            assertTrue(CubeStructureSpawns.starts(f.level, pos, type -> true).isEmpty());
            try (var scope = new CubeSpawnContext(f.level)) {
                assertTrue(CubeStructureSpawns.mobsAt(f.level, MobCategory.MONSTER, pos).isEmpty());
            }
            var start = start(structure, pos);
            f.world.cube(owner.asBlockPos()).setStartForStructure(structure, start);
            assertEquals(List.of(start), CubeStructureSpawns.starts(f.level, pos, type -> true).orElseThrow());
            f.world.cubes.remove(owner);
            assertTrue(CubeStructureSpawns.starts(f.level, pos, type -> true).isEmpty());
        }
    }

    @Test
    void pieceAndWholeStructureOverridesAndEmptyListsRemainDistinct() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            BlockPos pos = new BlockPos(16, 32000008, 16);
            f.platform(pos.getY(), 15);
            var type = mock(Structure.class);
            when(type.adjustBoundingBox(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
            var empty = new MobSpawnSettings.Builder().build().getMobsToSpawn(MobCategory.CREATURE);
            when(type.spawnOverrides())
                    .thenReturn(Map.of(MobCategory.CREATURE, new StructureSpawnOverride(StructureSpawnOverride.BoundingBoxType.PIECE, empty)));
            var cube = f.world.cube(pos);
            cube.setStartForStructure(type, start(type, pos.offset(-4, 0, -4), pos.offset(4, 0, 4)));
            try (var scope = new CubeSpawnContext(f.level)) {
                assertFalse(CubeStructureSpawns.mobsAt(f.level, MobCategory.CREATURE, pos).orElseThrow().isEmpty());
                when(type.spawnOverrides()).thenReturn(
                        Map.of(MobCategory.CREATURE, new StructureSpawnOverride(StructureSpawnOverride.BoundingBoxType.STRUCTURE, empty)));
                assertTrue(CubeStructureSpawns.mobsAt(f.level, MobCategory.CREATURE, pos).orElseThrow().isEmpty());
                assertFalse(CubeStructureSpawns.mobsAt(f.level, MobCategory.CREATURE, pos.above()).orElseThrow().isEmpty());
            }
        }
    }

    @Test
    void nativeMobsActuallyUseTheStructureListWhenTheBiomeListIsEmpty() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            f.settings(new MobSpawnSettings.Builder().build());
            var type = mock(Structure.class);
            when(type.adjustBoundingBox(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
            var pigs = new MobSpawnSettings.Builder().addSpawn(EntityTypes.PIG, 1, 1, 1).build().getMobsToSpawn(MobCategory.CREATURE);
            when(type.spawnOverrides())
                    .thenReturn(Map.of(MobCategory.CREATURE, new StructureSpawnOverride(StructureSpawnOverride.BoundingBoxType.STRUCTURE, pigs)));
            BlockPos pos = new BlockPos(16, y, 16);
            f.world.cube(pos).setStartForStructure(type, start(type, new BlockPos(1, y, 1), new BlockPos(30, y, 30)));
            var state = new CubeSpawnState(f.level);
            assertTrue(CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, pos) > 0);
            assertTrue(state.count(MobCategory.CREATURE) > 0);
        }
    }

    @Test
    void referenceBudgetIsBoundedAndSelfReferenceIsDeduplicated() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            BlockPos pos = new BlockPos(16, 32000008, 16);
            var structure = f.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.SWAMP_HUT);
            var cube = f.world.cube(pos);
            cube.setStartForStructure(structure, start(structure, pos));
            cube.addReferenceForStructure(structure, cube.cc_getCubePos().asLong());
            assertEquals(1, CubeStructureSpawns.starts(f.level, pos, type -> true).orElseThrow().size());
            for (int i = 0; i < 1025; i++) {
                cube.addReferenceForStructure(structure, CubePos.of(i, 0, 0).asLong());
            }
            assertTrue(CubeStructureSpawns.starts(f.level, pos, type -> true).isEmpty());
        }
    }
}
