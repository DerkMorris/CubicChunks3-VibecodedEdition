package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native placement/growth callbacks and cube storage; unrelated world services and random choices are controlled. */
class TestCubeBlockHeightMechanics extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void doorsAndTallLandPlantsPlaceBothHalvesAcrossCubeSeam(int y) {
        var world = new World();
        BlockPos pos = new BlockPos(15, y - 1, 15);
        List<Block> blocks = new ArrayList<>(
                List.of(Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.TALL_GRASS, Blocks.LARGE_FERN));
        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block instanceof DoorBlock) {
                blocks.add(block);
            }
        });
        for (Block block : blocks) {
            world.put(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            world.put(pos, Blocks.AIR.defaultBlockState());
            world.put(pos.above(), Blocks.AIR.defaultBlockState());
            var stack = new ItemStack(block.asItem());
            var state = block.getStateForPlacement(new DirectionalPlaceContext(world.level, pos, Direction.NORTH, stack, Direction.UP));
            assertNotNull(state, "Placement rejected " + block + " at " + pos);
            world.put(pos, state);
            block.setPlacedBy(world.level, pos, state, null, stack);
            assertEquals(DoubleBlockHalf.LOWER, world.get(pos).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
            assertEquals(DoubleBlockHalf.UPPER, world.get(pos.above()).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
            assertTrue(world.get(pos.above()).is(block));
            assertTrue(world.get(pos).canSurvive(world.level, pos));
            assertTrue(world.get(pos.above()).canSurvive(world.level, pos.above()));
        }
    }

    @Test
    void tallPlacementStillRejectsOccupiedUpperCellAndWorldCeiling() {
        var world = new World();
        for (Block block : List.of(Blocks.OAK_DOOR, Blocks.SUNFLOWER)) {
            BlockPos top = new BlockPos(15, CubicBlockBounds.MAX, 15);
            assertNull(block.getStateForPlacement(context(world, block, top)));
            assertNotNull(block.getStateForPlacement(context(world, block, top.below())));
            BlockPos blocked = new BlockPos(15, 32000, 15);
            world.put(blocked.above(), Blocks.STONE.defaultBlockState());
            assertNull(block.getStateForPlacement(context(world, block, blocked)));
            when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
            assertNull(block.getStateForPlacement(context(world, block, new BlockPos(0, 32000, 0))));
            when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(true);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeChorusFlowerGrowsAcrossCubeSeam(int y) {
        var world = new World();
        BlockPos pos = new BlockPos(15, y - 1, 15);
        world.put(pos.below(), Blocks.END_STONE.defaultBlockState());
        world.put(pos, Blocks.CHORUS_FLOWER.defaultBlockState());
        world.get(pos).randomTick(world.level, pos, world.random);
        assertTrue(world.get(pos).is(Blocks.CHORUS_PLANT));
        assertTrue(world.get(pos.above()).is(Blocks.CHORUS_FLOWER));
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeVinesSpreadUpAndDownAcrossCubeSeam(int y) {
        for (Direction direction : List.of(Direction.UP, Direction.DOWN)) {
            var world = new World();
            when(world.random.nextInt(anyInt())).thenAnswer(call -> (int) call.getArgument(0) == 6 ? direction.ordinal() : 0);
            when(world.random.nextBoolean()).thenReturn(direction == Direction.DOWN);
            BlockPos pos = new BlockPos(15, direction == Direction.UP ? y - 1 : y, 15);
            BlockPos target = pos.relative(direction);
            world.put(pos.north(), Blocks.STONE.defaultBlockState());
            world.put(target.north(), Blocks.STONE.defaultBlockState());
            world.put(pos, Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true));
            world.get(pos).randomTick(world.level, pos, world.random);
            assertTrue(world.get(target).is(Blocks.VINE), "Vine growth " + direction + " at " + pos);
            assertTrue(world.get(target).getValue(VineBlock.NORTH));
        }
    }

    private static DirectionalPlaceContext context(World world, Block block, BlockPos pos) {
        return new DirectionalPlaceContext(world.level, pos, Direction.NORTH, new ItemStack(block.asItem()), Direction.UP);
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeVineDensityLimitCountsRealCubesAcrossSeam(int y) {
        var world = new World();
        when(world.random.nextInt(anyInt())).thenAnswer(call -> (int) call.getArgument(0) == 6 ? Direction.UP.ordinal() : 0);
        when(world.random.nextBoolean()).thenReturn(false);
        BlockPos pos = new BlockPos(31, y - 1, 31);
        for (int x = -2; x <= 2; x++) {
            BlockPos vine = pos.offset(x, 0, 0);
            world.put(vine.north(), Blocks.STONE.defaultBlockState());
            world.put(vine, Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true));
        }
        world.put(pos.above().north(), Blocks.STONE.defaultBlockState());
        world.get(pos).randomTick(world.level, pos, world.random);
        assertTrue(world.get(pos.above()).isAir(), "Five vines must suppress the native upward spread at every height");
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final RandomSource random = mock();
        final Map<CubePos, LevelCube> cubes = new HashMap<>();

        World() {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            doCallRealMethod().when(level).findBlocksIn(any(BlockPos.class), any(BlockPos.class));
            when(((CubicLevelReader) level).cc_getCube(anyInt(), anyInt(), anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenAnswer(call -> cubes.computeIfAbsent(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2)),
                            key -> new LevelCube(level, key)));
            when(level.getGameRules()).thenReturn(new GameRules(FeatureFlags.DEFAULT_FLAGS));
            when(level.getRandom()).thenReturn(random);
            when(level.getBlockState(any())).thenAnswer(call -> get(call.getArgument(0)));
            when(level.getFluidState(any())).thenAnswer(call -> get(call.getArgument(0)).getFluidState());
            when(level.isEmptyBlock(any())).thenAnswer(call -> get(call.getArgument(0)).isAir());
            when(level.setBlock(any(), any(), anyInt())).thenAnswer(call -> {
                put(call.getArgument(0), call.getArgument(1));
                return true;
            });
            when(level.setBlockAndUpdate(any(), any())).thenAnswer(call -> {
                put(call.getArgument(0), call.getArgument(1));
                return true;
            });
        }

        BlockState get(BlockPos pos) {
            var cube = cubes.get(CubePos.from(pos));
            return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
        }

        void put(BlockPos pos, BlockState state) {
            var cube = cubes.computeIfAbsent(CubePos.from(pos), key -> new LevelCube(level, key));
            cube.getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        }
    }
}
