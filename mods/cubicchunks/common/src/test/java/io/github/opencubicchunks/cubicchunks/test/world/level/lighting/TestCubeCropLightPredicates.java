package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPredicates;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fresh native crop decisions while the exact light publication is revoked; no old sky or old block-light values are supplied. */
class TestCubeCropLightPredicates extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void cropsSurviveAndRandomlyGrowImmediatelyAfterAnUnrelatedDirtyEdit(int y) {
        World world = new World();
        BlockPos crop = new BlockPos(31, y, 31);
        BlockPos emitter = crop.east();
        world.put(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
        world.put(crop, Blocks.WHEAT.defaultBlockState());
        world.put(emitter, Blocks.GLOWSTONE.defaultBlockState());
        world.put(crop.offset(-3, -1, -5), Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false));
        assertEquals(0, world.level.getRawBrightness(crop, 0), "A revoked publication retains its exact-read dark fallback");
        assertTrue(Blocks.WHEAT.defaultBlockState().canSurvive(world.level, crop),
                "Fresh neighboring glowstone must prove the native survival threshold");
        world.state(crop).randomTick(world.level, crop, mock(RandomSource.class));
        assertEquals(1, world.state(crop).getValue(CropBlock.AGE));
        assertEquals(0, world.engine.getLayerListener(LightLayer.SKY).getLightValue(crop));
        assertEquals(0, world.level.getRawBrightness(crop, 0), "The crop predicate must not manufacture an exact raw light publication");
        world.put(emitter, Blocks.AIR.defaultBlockState());
        assertFalse(world.state(crop).canSurvive(world.level, crop), "Removing the actual emitter must take effect in this same tick");
        world.state(crop).randomTick(world.level, crop, mock(RandomSource.class));
        assertEquals(1, world.state(crop).getValue(CropBlock.AGE));
        world.noLoads();
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeSurvivalAndGrowthKeepTheirDistinctEightAndNineThresholds(int y) {
        World world = new World();
        BlockPos crop = new BlockPos(25, y, 25);
        world.put(crop.below(), Blocks.FARMLAND.defaultBlockState());
        world.put(crop, Blocks.WHEAT.defaultBlockState());
        world.put(crop.east(7), Blocks.GLOWSTONE.defaultBlockState());
        assertTrue(world.state(crop).canSurvive(world.level, crop), "15 minus seven steps is sufficient for survival");
        world.state(crop).randomTick(world.level, crop, mock(RandomSource.class));
        assertEquals(0, world.state(crop).getValue(CropBlock.AGE), "Light eight must not meet native growth's threshold nine");
        world.put(crop.east(7), Blocks.AIR.defaultBlockState());
        world.put(crop.east(8), Blocks.GLOWSTONE.defaultBlockState());
        assertFalse(world.state(crop).canSurvive(world.level, crop), "The bounded search must not accept a too-distant emitter");
        world.noLoads();
    }

    @Test
    void reverseAttenuationAndPartialFaceOcclusionAgreeWithTheExistingWorldSolver() {
        BlockState[] obstacles = { Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
            Blocks.STONE.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
            Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) };
        for (BlockState obstacle : obstacles) {
            World world = new World();
            BlockPos sample = new BlockPos(16, 32016, 16);
            CubePos center = CubePos.from(sample);
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        world.cube(CubePos.of(center.getX() + x, center.getY() + y, center.getZ() + z));
                    }
                }
            }
            world.put(sample.east(3), Blocks.GLOWSTONE.defaultBlockState());
            world.put(sample.east(), obstacle);
            world.put(sample.east(2), obstacle);
            Map<CubePos, CubeLightBlockSnapshot> snapshots = new HashMap<>();
            world.cubes.forEach((key, cube) -> snapshots.put(key, CubeLightBlockSnapshot.capture(cube)));
            CubeLightView solved = CubeWorldLightSolver.solve(center, snapshots, (x, z) -> Integer.MAX_VALUE);
            int exact = solved.sample(LightLayer.BLOCK, sample).orElseThrow().exactValue().orElseThrow();
            assertTrue(exact > 0, obstacle.toString());
            assertTrue(CubeLightPredicates.atLeast(world.level, sample, exact), obstacle.toString());
            assertFalse(CubeLightPredicates.atLeast(world.level, sample, exact + 1), obstacle.toString());
            world.noLoads();
        }
    }

    @Test
    void opaqueFacesUnloadedCubesAndRepresentableBoundariesCannotSupplyInventedLight() {
        World world = new World();
        BlockPos sample = new BlockPos(31, -32000, 31);
        world.put(sample, Blocks.AIR.defaultBlockState());
        world.put(sample.east(2), Blocks.GLOWSTONE.defaultBlockState());
        for (Direction direction : Direction.values()) {
            world.put(sample.relative(direction), Blocks.STONE.defaultBlockState());
        }
        assertFalse(CubeLightPredicates.atLeast(world.level, sample, 1));
        world.put(sample.east(), Blocks.AIR.defaultBlockState());
        assertTrue(CubeLightPredicates.atLeast(world.level, sample, 8));
        world.cubes.remove(CubePos.from(sample.east()));
        assertFalse(CubeLightPredicates.atLeast(world.level, sample, 8));
        for (int edge : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX }) {
            BlockPos boundary = new BlockPos(1, edge, 1);
            world.put(boundary, Blocks.GLOWSTONE.defaultBlockState());
            assertTrue(CubeLightPredicates.atLeast(world.level, boundary, 15));
            assertFalse(CubeLightPredicates.atLeast(world.level, new BlockPos(1, edge < 0 ? edge - 1 : edge + 1, 1), 1));
        }
        world.noLoads();
    }

    @Test
    void lightCannotCrossAnUnloadedCornerBetweenTwoLoadedCubes() {
        World world = new World();
        BlockPos sample = new BlockPos(31, 32016, 31);
        world.put(sample, Blocks.AIR.defaultBlockState());
        world.put(sample.offset(1, 0, 1), Blocks.GLOWSTONE.defaultBlockState());
        assertFalse(CubeLightPredicates.atLeast(world.level, sample, 8),
                "The two loaded cubes touch at a corner; both face-adjacent routes are unknown");
        world.put(sample.east(), Blocks.AIR.defaultBlockState());
        assertTrue(CubeLightPredicates.atLeast(world.level, sample, 8), "Loading the real air bridge now proves a current path");
        world.noLoads();
    }

    @Test
    void ordinaryWorldsAndForeignThreadsNeverUseTheFallbackAndInvalidThresholdsAreRejected() {
        World world = new World();
        BlockPos crop = new BlockPos(4, 96, 4);
        world.put(crop, Blocks.WHEAT.defaultBlockState());
        world.put(crop.below(), Blocks.FARMLAND.defaultBlockState());
        world.put(crop.east(), Blocks.GLOWSTONE.defaultBlockState());
        when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
        doReturn(0).when(world.level).getRawBrightness(any(), anyInt());
        assertFalse(world.state(crop).canSurvive(world.level, crop));
        doReturn(8).when(world.level).getRawBrightness(any(), anyInt());
        assertTrue(world.state(crop).canSurvive(world.level, crop));
        when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(true);
        var server = world.level.getServer();
        when(server.isSameThread()).thenReturn(false);
        assertFalse(CubeLightPredicates.atLeast(world.level, crop, 8));
        assertThrows(IllegalArgumentException.class, () -> CubeLightPredicates.atLeast(world.level, crop, 0));
        assertThrows(IllegalArgumentException.class, () -> CubeLightPredicates.atLeast(world.level, crop, 16));
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final Map<CubePos, LevelCube> cubes = new HashMap<>();
        final LevelLightEngine engine;

        World() {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            var server = level.getServer();
            when(server.isSameThread()).thenReturn(true);
            CubeSource source = (CubeSource) level.getChunkSource();
            when(source.cc_getCubeNow(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(call -> cubes.get(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
            CubeMobBlockView blocks = new CubeMobBlockView(level);
            when(level.getBlockState(any())).thenAnswer(call -> blocks.getBlockState(call.getArgument(0)));
            when(level.setBlock(any(), any(), anyInt())).thenAnswer(call -> {
                put(call.getArgument(0), call.getArgument(1));
                return true;
            });
            engine = new LevelLightEngine(new Source(level), true, true);
            when(level.getLightEngine()).thenReturn(engine);
            doCallRealMethod().when(level).getRawBrightness(any(), anyInt());
        }

        LevelCube cube(CubePos pos) {
            return cubes.computeIfAbsent(pos, key -> {
                LevelCube cube = new LevelCube(level, key);
                cube.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
                return cube;
            });
        }

        void put(BlockPos pos, BlockState state) {
            cube(CubePos.from(pos)).getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        }

        BlockState state(BlockPos pos) {
            return level.getBlockState(pos);
        }

        void noLoads() {
            verify((CubeSource) level.getChunkSource(), never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean());
            verify(level, never()).getChunk(anyInt(), anyInt());
        }
    }

    private record Source(ServerLevel level) implements LightChunkGetter, CubeLightProvider {
        @Override public LightChunk getChunkForLighting(int x, int z) {
            throw new AssertionError("No column loads are allowed for a fresh gameplay light predicate");
        }

        @Override public BlockGetter getLevel() {
            return level;
        }

        @Override public CubeLightView cc_getCubeLightView(CubePos pos) {
            return null;
        }
    }
}
