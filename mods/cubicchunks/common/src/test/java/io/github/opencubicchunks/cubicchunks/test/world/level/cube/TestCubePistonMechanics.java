package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native piston planning against real cube sections; this does not execute moving-piston animation or network transport. */
class TestCubePistonMechanics extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeResolverPushesTwelveBlocksAcrossCubeSeamAndRejectsThirteen(int y) {
        var world = new World();
        BlockPos piston = new BlockPos(31, y - 1, 31);
        for (int step = 1; step <= 12; step++) {
            world.put(piston.above(step), Blocks.STONE.defaultBlockState());
        }
        var resolver = new PistonStructureResolver(world.level, piston, Direction.UP, true);
        assertTrue(resolver.resolve());
        assertEquals(12, resolver.getToPush().size());
        for (int step = 1; step <= 12; step++) {
            assertTrue(resolver.getToPush().contains(piston.above(step)));
        }
        assertTrue(resolver.getToDestroy().isEmpty());
        world.put(piston.above(13), Blocks.STONE.defaultBlockState());
        assertFalse(new PistonStructureResolver(world.level, piston, Direction.UP, true).resolve());
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeRetractionCanPullAcrossSeamButRetainsImmovableBlockRules(int y) {
        var world = new World();
        BlockPos piston = new BlockPos(31, y - 2, 31);
        BlockPos target = piston.above(2);
        world.put(target, Blocks.STONE.defaultBlockState());
        var resolver = new PistonStructureResolver(world.level, piston, Direction.UP, false);
        assertTrue(resolver.resolve());
        assertEquals(List.of(target), resolver.getToPush());
        assertEquals(Direction.DOWN, resolver.getPushDirection());
        for (var block : List.of(Blocks.OBSIDIAN, Blocks.BEDROCK, Blocks.CHEST)) {
            world.put(target, block.defaultBlockState());
            assertFalse(new PistonStructureResolver(world.level, piston, Direction.UP, false).resolve(), block.toString());
        }
    }

    @Test
    void cubicWorldBoundsPreventPushingBlocksOutsideRepresentableHeight() {
        var world = new World();
        var stone = Blocks.STONE.defaultBlockState();
        BlockPos bottom = new BlockPos(0, CubicBlockBounds.MIN, 0);
        BlockPos top = new BlockPos(0, CubicBlockBounds.MAX, 0);
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, bottom, Direction.DOWN, false, Direction.DOWN));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, top, Direction.UP, false, Direction.UP));
        assertTrue(PistonBaseBlock.isPushable(stone, world.level, bottom, Direction.UP, false, Direction.UP));
        assertTrue(PistonBaseBlock.isPushable(stone, world.level, top, Direction.DOWN, false, Direction.DOWN));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, bottom.below(), Direction.UP, false, Direction.UP));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, top.above(), Direction.DOWN, false, Direction.DOWN));
    }

    @Test
    void ordinaryWorldKeepsNativeDimensionLimits() {
        var world = new World();
        when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
        var stone = Blocks.STONE.defaultBlockState();
        assertTrue(PistonBaseBlock.isPushable(stone, world.level, new BlockPos(0, 96, 0), Direction.UP, false, Direction.UP));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, new BlockPos(0, 32000, 0), Direction.UP, false, Direction.UP));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, new BlockPos(0, -32000, 0), Direction.UP, false, Direction.UP));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, new BlockPos(0, -64, 0), Direction.DOWN, false, Direction.DOWN));
        assertFalse(PistonBaseBlock.isPushable(stone, world.level, new BlockPos(0, 319, 0), Direction.UP, false, Direction.UP));
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final Map<CubePos, LevelCube> cubes = new HashMap<>();

        World() {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getWorldBorder()).thenReturn(new WorldBorder());
            when(level.getBlockState(any())).thenAnswer(call -> {
                BlockPos pos = call.getArgument(0);
                var cube = cubes.get(CubePos.from(pos));
                return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
            });
        }

        void put(BlockPos pos, BlockState state) {
            var cube = cubes.computeIfAbsent(CubePos.from(pos), key -> new LevelCube(level, key));
            cube.getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        }
    }
}
