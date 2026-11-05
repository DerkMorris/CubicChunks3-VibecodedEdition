package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateBlockMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeTemplateBlockMatcher extends BaseTest {
    private CubeTemplateBlockMatcher matcher() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var origin = new BlockPos(0, 32_000_000, 0);
        var position = CubePos.from(origin);
        var cube = mock(LevelCube.class);
        when(cube.getLevel()).thenReturn(level);
        when(cube.cc_getCubePos()).thenReturn(position);
        when(cube.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
        when(((CubeSource) level.getChunkSource()).cc_getCubeNow(position.getX(), position.getY(), position.getZ())).thenReturn(cube);
        return new CubeTemplateBlockMatcher(level, origin, origin.offset(2, 0, 0));
    }

    @Test
    void positionFiltersApplyToCountsTraversalAndAllMatchedAtCubicHeights() {
        var matcher = matcher();
        matcher.filterPosition((state, pos) -> pos.getX() >= 1);
        matcher.filterPosition((state, pos) -> pos.getX() <= 1);
        assertTrue(matcher.anyMatched());
        assertTrue(matcher.atLeastMatched(1));
        assertFalse(matcher.atLeastMatched(2));
        assertFalse(matcher.allMatched());
        var positions = new ArrayList<BlockPos>();
        matcher.forEach((pos, state) -> positions.add(pos));
        assertEquals(java.util.List.of(new BlockPos(1, 32_000_000, 0)), positions);
    }

    @Test
    void stateRejectionSkipsPositionFilterAndUnfilteredCaptureStillIncludesEveryBlock() {
        var all = matcher();
        assertTrue(all.allMatched());
        assertTrue(all.atLeastMatched(3));
        var calls = new AtomicInteger();
        all.filterState(state -> state.isAir());
        all.filterPosition((state, pos) -> {
            calls.incrementAndGet();
            return true;
        });
        assertFalse(all.anyMatched());
        assertFalse(all.atLeastMatched(1));
        assertFalse(all.allMatched());
        all.forEach((pos, state) -> fail("Rejected block was visited"));
        assertEquals(0, calls.get());
    }
}
