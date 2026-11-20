package io.github.opencubicchunks.cubicchunks.test.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real cube sections and native block/fluid callbacks; sampling, light, and world services are controlled. */
class TestCubeRandomTicks extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeCropsGrowInEverySectionThroughTransformedTickEntry(int y) throws Exception {
        var world = new World();
        var cube = world.cube(new BlockPos(-32, y, 32));
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
            BlockPos pos = sample(cube.cc_getCubePos(), index);
            world.put(pos.below(), Blocks.FARMLAND.defaultBlockState());
            world.put(pos, Blocks.WHEAT.defaultBlockState());
            expected.put(pos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3));
        }

        ((CubicServerLevel) world.level).cc_tickClo(cube, 3);

        expected.forEach((pos, state) -> assertEquals(state, world.get(pos), "Crop at " + pos));
        verify(world.level, times(3 * CubicConstants.SECTION_COUNT)).getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void zeroSpeedAndStableSectionsConsumeNoSamples(int y) throws Exception {
        var world = new World();
        var cube = world.cube(new BlockPos(0, y, 0));
        BlockPos pos = sample(cube.cc_getCubePos(), 0);
        world.put(pos, Blocks.WHEAT.defaultBlockState());
        ((CubicServerLevel) world.level).cc_tickClo(cube, 0);
        assertEquals(0, world.get(pos).getValue(CropBlock.AGE));
        world.put(pos, Blocks.STONE.defaultBlockState());
        ((CubicServerLevel) world.level).cc_tickClo(cube, 3);
        verify(world.level, never()).getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void sugarCaneGrowsAcrossVerticalCubeBoundary(int y) throws Exception {
        var world = new World();
        BlockPos cane = new BlockPos(15, Coords.cubeToMinBlock(Coords.blockToCube(y)) + CubicConstants.DIAMETER_IN_BLOCKS - 1, 15);
        world.put(cane.below(), Blocks.SAND.defaultBlockState());
        world.put(cane, Blocks.SUGAR_CANE.defaultBlockState().setValue(SugarCaneBlock.AGE, 15));
        world.cube(cane.above());

        ((CubicServerLevel) world.level).cc_tickClo(world.cube(cane), 1);

        assertEquals(Blocks.SUGAR_CANE.defaultBlockState(), world.get(cane.above()));
        assertEquals(0, world.get(cane).getValue(SugarCaneBlock.AGE));
        assertTrue(!CubePos.from(cane).equals(CubePos.from(cane.above())));
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void nativeLavaRandomTickIgnitesAcrossVerticalCubeBoundary(int y) throws Exception {
        var world = new World();
        when(world.random.nextInt(anyInt())).thenReturn(1);
        when(world.level.canSpreadFireAround(any())).thenReturn(true);
        when(world.level.isLoaded(any())).thenReturn(true);
        BlockPos lava = new BlockPos(15, Coords.cubeToMinBlock(Coords.blockToCube(y)) + CubicConstants.DIAMETER_IN_BLOCKS - 1, 15);
        world.put(lava, Blocks.LAVA.defaultBlockState());
        world.put(lava.above().east(), Blocks.OAK_PLANKS.defaultBlockState());

        ((CubicServerLevel) world.level).cc_tickClo(world.cube(lava), 1);

        assertTrue(world.get(lava.above()).is(Blocks.FIRE));
        assertTrue(world.get(lava).is(Blocks.LAVA));
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void nativeGrowthStillRespectsLightAndMaturity(int y) throws Exception {
        var world = new World();
        var cube = world.cube(new BlockPos(0, y, 0));
        BlockPos pos = sample(cube.cc_getCubePos(), 0);
        world.put(pos, Blocks.WHEAT.defaultBlockState());
        when(world.level.getRawBrightness(any(), anyInt())).thenReturn(8);
        ((CubicServerLevel) world.level).cc_tickClo(cube, 3);
        assertEquals(0, world.get(pos).getValue(CropBlock.AGE));
        when(world.level.getRawBrightness(any(), anyInt())).thenReturn(15);
        world.put(pos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        ((CubicServerLevel) world.level).cc_tickClo(cube, 3);
        assertEquals(7, world.get(pos).getValue(CropBlock.AGE));
    }

    private static BlockPos sample(CubePos origin, int index) {
        return new BlockPos(Coords.cubeToMinBlock(origin.getX()) + 16 * Coords.indexToX(index) + 15,
                Coords.cubeToMinBlock(origin.getY()) + 16 * Coords.indexToY(index) + 15,
                Coords.cubeToMinBlock(origin.getZ()) + 16 * Coords.indexToZ(index) + 15);
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final RandomSource random = mock();
        final Map<CubePos, LevelCube> cubes = new HashMap<>();

        World() throws Exception {
            Misc.setupLevelHeight(level, -64, 384);
            CubeMobTestWorld.setField(Level.class, level, "cc_isCubic", true);
            doCallRealMethod().when(level).isInsideBuildHeight(anyInt());
            when(level.hasChunkAt(any(BlockPos.class))).thenAnswer(call -> cubes.containsKey(CubePos.from((BlockPos) call.getArgument(0))));
            when(level.getRandom()).thenReturn(random);
            when(level.getRawBrightness(any(), anyInt())).thenReturn(15);
            when(level.getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt()))
                    .thenAnswer(call -> new BlockPos((int) call.getArgument(0) + 15, (int) call.getArgument(1) + 15, (int) call.getArgument(2) + 15));
            when(level.getBlockState(any())).thenAnswer(call -> get(call.getArgument(0)));
            when(level.isEmptyBlock(any())).thenAnswer(call -> get(call.getArgument(0)).isAir());
            when(level.setBlock(any(), any(), anyInt())).thenAnswer(call -> {
                put(call.getArgument(0), call.getArgument(1));
                return true;
            });
            when(level.setBlockAndUpdate(any(), any())).thenAnswer(call -> {
                put(call.getArgument(0), call.getArgument(1));
                return true;
            });
            // cc_tickCube is a DASM-added method; its interface exposes the cc_tickClo dispatch above it.
            var tickCube = ServerLevel.class.getDeclaredMethod("cc_tickCube", LevelCube.class, int.class);
            tickCube.invoke(doCallRealMethod().when(level), any(LevelCube.class), anyInt());
            doCallRealMethod().when((CubicServerLevel) level).cc_tickClo(any(), anyInt());
        }

        LevelCube cube(BlockPos pos) {
            return cubes.computeIfAbsent(CubePos.from(pos), key -> new LevelCube(level, key));
        }

        BlockState get(BlockPos pos) {
            var cube = cubes.get(CubePos.from(pos));
            return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
        }

        void put(BlockPos pos, BlockState state) {
            cube(pos).getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        }
    }
}
