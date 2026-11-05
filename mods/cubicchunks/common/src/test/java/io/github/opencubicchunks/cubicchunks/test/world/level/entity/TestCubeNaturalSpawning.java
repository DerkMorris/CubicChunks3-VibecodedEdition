package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.CubeSpawnTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeNaturalSpawner;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeNaturalSpawning extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void actualCacheTickEntryInvokesCubicSpawnerAndKeepsBlockTickDispatch() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000016;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            RandomSource random = mock();
            when(random.nextInt(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == 32 ? 16 : 0);
            when(random.nextFloat()).thenReturn(0.9F);
            when(f.level.getRandom()).thenReturn(random);
            var source = f.level.getChunkSource();
            CubeMobTestWorld.setField(ServerChunkCache.class, source, "level", f.level);
            CubeMobTestWorld.setField(ChunkSource.class, source, "cc_isCubic", true);
            var entry = ServerChunkCache.class.getDeclaredMethod("tickChunks", ProfilerFiller.class);
            entry.setAccessible(true);
            entry.invoke(source, Profiler.get());
            assertTrue(new CubeSpawnState(f.level).count(MobCategory.CREATURE) > 0);
            verify((CubicChunkMap) source.chunkMap).cc_forEachBlockTickingClo(any());
            verify(f.world.distance, never()).getNaturalSpawnChunkCount();
            verify(f.level).tickCustomSpawners(org.mockito.ArgumentMatchers.anyBoolean());
        }
    }

    @Test
    void monsterLightRulesAndNativeCreationWorkAtHighY() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            f.settings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.ZOMBIE, 1, 1, 1).build());
            var state = new CubeSpawnState(f.level);
            BlockPos start = new BlockPos(16, y, 16);
            assertEquals(0, CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.MONSTER, start));
            f.platform(y, 0);
            assertTrue(CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.MONSTER, start) > 0);
        }
    }

    @Test
    void failedInsertionDoesNotConsumeCapAndPacksStopAtSharedLimit() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            var state = new CubeSpawnState(f.level);
            doReturn(false).when(f.level).tryAddFreshEntityWithPassengers(any());
            BlockPos start = new BlockPos(16, y, 16);
            assertEquals(0, CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, start));
            assertEquals(0, state.count(MobCategory.CREATURE));
            doCallRealMethod().when(f.level).tryAddFreshEntityWithPassengers(any());
            for (int i = 0; i < 20; i++) {
                CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, start);
            }
            assertEquals(MobCategory.CREATURE.getMaxInstancesPerChunk(), state.count(MobCategory.CREATURE));
        }
    }

    @Test
    void nativePigsSpawnAtBothExtremeHeightsAndJoinTickList() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.platform(y, 15);
                f.player(16, y + 40, 16);
                var state = new CubeSpawnState(f.level);
                try (var context = new CubeSpawnContext(f.level)) {
                    BlockPos pos = new BlockPos(16, y, 16);
                    assertTrue(CubeNaturalSpawner.active(f.level, pos), "active");
                    assertTrue(net.minecraft.world.entity.SpawnPlacements.isSpawnPositionOk(EntityTypes.PIG, f.level, pos), "placement");
                    assertTrue(net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(EntityTypes.PIG, f.level, EntitySpawnReason.NATURAL, pos,
                            f.level.getRandom()), "species rule");
                    var pig = EntityTypes.PIG.create(f.level, EntitySpawnReason.NATURAL);
                    pig.snapTo(16.5, y, 16.5);
                    assertTrue(pig.checkSpawnRules(f.level, EntitySpawnReason.NATURAL), "mob rule");
                    assertTrue(pig.checkSpawnObstruction(f.level), "obstruction");
                    assertTrue(context.isKnown(), "known");
                }
                int count = CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, new BlockPos(16, y, 16));
                assertTrue(count > 0);
                assertEquals(count, state.count(MobCategory.CREATURE));
                int visible = 0;
                for (var entity : f.level.getAllEntities()) {
                    visible++;
                    assertEquals(y, entity.getY());
                    assertSame(EntityTypes.PIG, entity.getType());
                    assertTrue(f.world.ticks.contains(entity));
                }
                assertEquals(count, visible);
                verify(f.level, never()).getChunk(anyInt(), anyInt());
                assertNull(CubeSpawnContext.current(f.level));
            }
        }
    }

    @Test
    void unknownLightingAndInactiveCubeNeverAdmitMobs() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            var state = new CubeSpawnState(f.level);
            f.lights.clear();
            assertEquals(0, CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, new BlockPos(16, y, 16)));
            f.platform(y, 15);
            ((CubicServerLevel) f.level).cc_onCubeEntityStatus(CubePos.from(new BlockPos(16, y, 16)), FullChunkStatus.FULL);
            assertEquals(0, CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, new BlockPos(16, y, 16)));
            verify(f.level, never()).tryAddFreshEntityWithPassengers(any());
        }
    }

    @Test
    void nativeGroundAndLightRulesRejectUnsuitableSites() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.platform(y, 0);
            f.player(16, y + 40, 16);
            var state = new CubeSpawnState(f.level);
            assertEquals(0, CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, new BlockPos(16, y, 16)));
            f.platform(y, 15);
            assertEquals(0, CubeNaturalSpawner.spawnCategory(f.level, state, MobCategory.CREATURE, new BlockPos(16, y + 2, 16)));
            verify(f.level, never()).tryAddFreshEntityWithPassengers(any());
        }
    }

    @Test
    void candidatesDeduplicatePlayersAndUseVerticalRangeWithoutLoading() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            f.platform(32000008, 15);
            f.platform(-32000024, 15);
            f.player(16, 32000048, 16);
            f.player(16, 32000048, 16);
            var cubes = CubeNaturalSpawner.candidates(f.level, new CubeSpawnState(f.level));
            assertEquals(1, cubes.size());
            assertTrue(cubes.getFirst().cc_getCubePos().minCubeY() > 0);
            when(f.players.getFirst().isSpectator()).thenReturn(true);
            when(f.players.getLast().isSpectator()).thenReturn(true);
            assertTrue(CubeNaturalSpawner.candidates(f.level, new CubeSpawnState(f.level)).isEmpty());
            verify(f.level, never()).getChunk(anyInt(), anyInt());
        }
    }

    @Test
    void exclusionRadiusAndWorldSpawnUseFullY() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.player(16.5, y, 16.5);
            var state = new CubeSpawnState(f.level);
            assertFalse(CubeNaturalSpawner.rightDistance(f.level, state, new BlockPos(16, y + 24, 16)));
            assertTrue(CubeNaturalSpawner.rightDistance(f.level, state, new BlockPos(16, y + 25, 16)));
            assertFalse(CubeNaturalSpawner.rightDistance(f.level, state, new BlockPos(16, y + 128, 16)));
            var respawn = RespawnData.of(f.level.dimension(), new BlockPos(16, y + 40, 16), 0, 0);
            when(f.level.getRespawnData()).thenReturn(respawn);
            assertFalse(CubeNaturalSpawner.rightDistance(f.level, state, new BlockPos(16, y + 40, 16)));
        }
    }

    @Test
    void capsAreSharedAcrossLayersButNotDistantPlayers() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            f.player(16, y - 4096, 16);
            var state = new CubeSpawnState(f.level);
            for (int i = 0; i < MobCategory.CREATURE.getMaxInstancesPerChunk(); i++) {
                Mob pig = EntityTypes.PIG.create(f.level, EntitySpawnReason.NATURAL);
                pig.snapTo(16, y + i, 16);
                state.count(pig);
            }
            assertFalse(state.canSpawn(MobCategory.CREATURE, new BlockPos(16, y + 70, 16)));
            assertTrue(state.canSpawn(MobCategory.CREATURE, new BlockPos(16, y - 4096, 16)));
            Mob persistent = f.world.pig(y);
            state.count(persistent);
            assertEquals(MobCategory.CREATURE.getMaxInstancesPerChunk(), state.count(MobCategory.CREATURE));
        }
    }

    @Test
    void loadedPopulationAndSpawnCostsUseUnpackedPositions() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.platform(y, 15);
            f.player(16, y + 40, 16);
            f.settings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.PIG, 1, 1, 1).addMobSpawnCost(EntityTypes.PIG, 1, 0.01).build());
            Mob pig = EntityTypes.PIG.create(f.level, EntitySpawnReason.NATURAL);
            pig.snapTo(16, y, 16);
            assertTrue(f.level.addFreshEntity(pig));
            var state = new CubeSpawnState(f.level);
            assertEquals(1, state.count(MobCategory.CREATURE));
            assertFalse(state.withinCost(EntityTypes.PIG, pig.blockPosition()));
            assertTrue(state.withinCost(EntityTypes.PIG, pig.blockPosition().above(4096)));
        }
    }

    @Test
    void biomeReadsSelectAllThreeSectionAxesAtHighY() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            var desert = f.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT);
            for (int y : List.of(32000008, -32000024)) {
                BlockPos pos = new BlockPos(20, y + 16, 20);
                var cube = f.world.cube(pos);
                cube.getSection(Coords.blockToIndex(pos)).fillBiomesFromNoise((x, yy, z) -> desert, 0, 0, 0);
                assertEquals(desert,
                        f.level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ())));
            }
            verify(f.level, never()).getChunk(anyInt(), anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }
    }

    @Test
    void nativeScopedReadsDeferUnknownColumnAndRestoreAfterException() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            f.platform(32000008, 15);
            doCallRealMethod().when(f.level).getHeight(any(Heightmap.Types.class), anyInt(), anyInt());
            try (var scope = new CubeSpawnContext(f.level)) {
                assertEquals(15, f.level.getBrightness(LightLayer.BLOCK, new BlockPos(16, 32000008, 16)));
                assertTrue(scope.isKnown());
                f.level.getHeight(Heightmap.Types.WORLD_SURFACE, 16, 16);
                assertFalse(scope.isKnown());
            }
            try (var scope = new CubeSpawnContext(f.level)) {
                assertEquals(Blocks.BARRIER.defaultBlockState(), f.level.getBlockState(new BlockPos(16, 32004008, 16)));
                assertFalse(scope.isKnown());
                throw new IllegalStateException("probe");
            } catch (IllegalStateException expected) {
                assertEquals("probe", expected.getMessage());
            }
            assertNull(CubeSpawnContext.current(f.level));
            verify(f.level, never()).getChunk(anyInt(), anyInt());
        }
    }

    @Test
    void positionSamplingCoversOnlyTheSelectedCubeIncludingNegativeY() {
        var random = RandomSource.create(3);
        for (var cube : List.of(CubePos.of(1, 1000000, -1), CubePos.of(-2, -1000000, 2))) {
            var heights = new HashSet<Integer>();
            for (int i = 0; i < 4096; i++) {
                BlockPos pos = CubeNaturalSpawner.randomPosition(cube, random);
                assertEquals(cube, CubePos.from(pos));
                heights.add(pos.getY());
            }
            assertEquals(32, heights.size());
        }
    }

    @Test
    void schedulerHonorsFreezeGameRuleAndPersistentCadence() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            f.player(16, 32000048, 16);
            when(f.level.tickRateManager().runsNormally()).thenReturn(false);
            CubeNaturalSpawner.tick(f.level, true);
            verify(f.level, never()).getAllEntities();
            when(f.level.tickRateManager().runsNormally()).thenReturn(true);
            f.level.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
            CubeNaturalSpawner.tick(f.level, true);
            verify(f.level, never()).getAllEntities();
            assertFalse(CubeNaturalSpawner.categoryAllowed(MobCategory.MONSTER, false, 400));
            assertFalse(CubeNaturalSpawner.categoryAllowed(MobCategory.CREATURE, true, 399));
            assertTrue(CubeNaturalSpawner.categoryAllowed(MobCategory.CREATURE, true, 400));
        }
    }
}
