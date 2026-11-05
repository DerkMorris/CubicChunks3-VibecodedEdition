package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.HashMap;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.EmptyLevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;

class TestStreamingRenderRegion extends BaseTest {
    @Test
    void buildsLoadedEdgeBeforeItsNeighborArrivesAndRebuildsWithItsRealBlocks() {
        var level = mockLevelWithPalettes(ClientLevel.class);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        Misc.setupLevelHeight(level, -64, 384);
        var cubes = new HashMap<CubePos, LevelCube>();
        var empty = new EmptyLevelCube(level, CubePos.of(0, 0, 0), level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        var cubic = (CubicLevel) level;
        when(cubic.cc_getCube(anyInt(), anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false)))
                .thenAnswer(call -> cubes.get(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
        when(cubic.cc_getCube(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> cubes.getOrDefault(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2)), empty));
        BlockPos edge = new BlockPos(-1, -400, 37);
        var neighbor = edge.east();
        long section = SectionPos.asLong(edge);
        var tracker = new SectionUpdateTracker(level, 3);
        assertFalse(tracker.hasAllNeighbors(level, section));

        var first = cubes.computeIfAbsent(CubePos.from(edge), pos -> new LevelCube(level, pos));
        first.setBlockState(edge, Blocks.STONE.defaultBlockState());
        assertTrue(tracker.hasAllNeighbors(level, section));
        var region = new RenderRegionCache().createRegion(level, section);
        assertSame(Blocks.STONE.defaultBlockState(), region.getBlockState(edge));
        assertTrue(region.getBlockState(neighbor).isAir());

        var second = cubes.computeIfAbsent(CubePos.from(neighbor), pos -> new LevelCube(level, pos));
        second.setBlockState(neighbor, Blocks.GLASS.defaultBlockState());
        assertSame(Blocks.GLASS.defaultBlockState(), new RenderRegionCache().createRegion(level, section).getBlockState(neighbor));
        cubes.remove(CubePos.from(edge));
        assertFalse(tracker.hasAllNeighbors(level, section));
    }
}
