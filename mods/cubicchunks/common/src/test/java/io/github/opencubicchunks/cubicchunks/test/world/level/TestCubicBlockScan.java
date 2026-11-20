package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BoxBlockMatcher;
import net.minecraft.world.level.blockscan.OrderedBlockMatcher;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native Box/OrderedBlockMatcher bodies, real cube palettes, and controlled storage acquisition. */
class TestCubicBlockScan extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void boxCountsExactStatesAcrossAllCubeAxesAndReversedBounds(int y) {
        World world = new World();
        BlockPos low = new BlockPos(31, y - 1, -1);
        BlockPos high = low.offset(1, 1, 1);
        List<BlockPos> expected = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(low, high)) {
            BlockPos pos = cursor.immutable();
            world.put(pos, Blocks.STONE.defaultBlockState());
            expected.add(pos);
        }
        world.put(high, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var matcher = new BoxBlockMatcher(world.level, high, low).filterState(s -> s.is(Blocks.STONE));
        assertTrue(matcher.atLeastMatched(7));
        assertTrue(matcher.atMostMatched(7));
        assertFalse(matcher.atLeastMatched(8));
        assertFalse(matcher.allMatched());
        Map<BlockPos, BlockState> visited = new HashMap<>();
        matcher.forEach((pos, state) -> visited.put(pos.immutable(), state));
        expected.remove(high);
        assertEquals(new java.util.HashSet<>(expected), visited.keySet());
        assertTrue(visited.values().stream().allMatch(s -> s.is(Blocks.STONE)));
        assertTrue(new BoxBlockMatcher(world.level, high, low).filterState(s -> !s.isAir()).allMatched());
        verify(world.level, never()).getChunk(anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void orderedMatcherRetainsDuplicatesOrderAndEarlyAbort(int y) {
        World world = new World();
        BlockPos a = new BlockPos(31, y - 1, 31);
        BlockPos b = a.offset(1, 1, 1);
        BlockPos air = a.above(100);
        world.put(a, Blocks.STONE.defaultBlockState());
        world.put(b, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var matcher = new OrderedBlockMatcher(world.level, List.of(b, air, a, b)).filterState(s -> !s.isAir());
        List<BlockPos> seen = new ArrayList<>();
        matcher.forEach((pos, state) -> seen.add(pos.immutable()));
        assertEquals(List.of(b, a, b), seen);
        seen.clear();
        assertTrue(matcher.forEachUntil((pos, state) -> {
            seen.add(pos.immutable());
            return Continuation.ABORT;
        }));
        assertEquals(List.of(b), seen);
        verify(world.level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void abortedHugeBoxAcquiresOnlyFirstCube() {
        World world = new World();
        BlockPos first = new BlockPos(0, 32000000, 0);
        world.put(first, Blocks.STONE.defaultBlockState());
        assertTrue(new BoxBlockMatcher(world.level, first, first.offset(100000, 100000, 100000)).filterState(s -> s.is(Blocks.STONE)).anyMatched());
        assertEquals(1, world.requests, "An early match must not pre-load the rest of the requested volume");
    }

    @Test
    void outOfRepresentableWorldIsAirWithoutCubeKeyAliasing() {
        World world = new World();
        BlockPos edge = new BlockPos(1, CubicBlockBounds.MAX, 1);
        world.put(edge, Blocks.STONE.defaultBlockState());
        var air = new BoxBlockMatcher(world.level, edge, edge.above(2)).filterState(BlockState::isAir);
        List<BlockPos> seen = new ArrayList<>();
        air.forEach((pos, state) -> seen.add(pos.immutable()));
        assertEquals(List.of(edge.above(), edge.above(2)), seen);
        assertEquals(1, world.requests);
    }

    @Test
    void ordinaryWorldRetainsNativeChunkStorageAndHeightClipping() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
        var chunk = new LevelChunk(level, new ChunkPos(0, 0));
        chunk.getSection(chunk.getSectionIndex(96)).setBlockState(1, 0, 1, Blocks.STONE.defaultBlockState());
        when(level.getChunk(0, 0)).thenReturn(chunk);
        BlockPos nativePos = new BlockPos(1, 96, 1);
        assertTrue(new BoxBlockMatcher(level, nativePos, nativePos).filterState(s -> s.is(Blocks.STONE)).anyMatched());
        BlockPos outside = new BlockPos(1, 32000, 1);
        assertTrue(new BoxBlockMatcher(level, outside, outside).filterState(BlockState::isAir).anyMatched());
        verify((CubicLevelReader) level, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean());
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final Map<CubePos, LevelCube> cubes = new HashMap<>();
        int requests;

        World() {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(((CubicLevelReader) level).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean())).thenAnswer(call -> {
                requests++;
                return cubes.computeIfAbsent(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2)),
                        key -> new LevelCube(level, key));
            });
        }

        void put(BlockPos pos, BlockState state) {
            var cube = cubes.computeIfAbsent(CubePos.from(pos), key -> new LevelCube(level, key));
            cube.getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        }
    }
}
