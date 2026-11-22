package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeWorldSkySources extends BaseTest {
    @Test
    void computedDeepSourcesRemainAvailableWithoutGeneratingUnknownHeights() {
        var level = mockLevelWithPalettes(Level.class);
        var top = new LevelCube(level, CubePos.of(-2, -3, 5));
        var bottom = new LevelCube(level, CubePos.of(-2, -4, 5));
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                bottom.setBlockState(bottom.cc_getCubePos().asBlockPos(x, 12, z), Blocks.TUFF.defaultBlockState());
            }
        }
        Map<Integer, CubeSkyOcclusion> generated = Map.of(-3, CubeSkyOcclusion.capture(top), -4, CubeSkyOcclusion.capture(bottom));
        AtomicInteger reads = new AtomicInteger();
        var index = new CubeWorldSkyColumn((x, z) -> -65, -2, 5, Map.of(), y -> {
            reads.incrementAndGet();
            return generated.get(y);
        });
        assertEquals(bottom.cc_getCubePos().minCubeY() + 13, index.edge(-64, 160));
        assertEquals(2, reads.get());
        assertSame(generated.get(-3), index.source(-3));
        assertSame(generated.get(-4), index.source(-4));
        assertNull(index.source(-5), "An unvisited deeper layer has no certified baseline");
        assertNull(index.source(100), "Looking up a missing layer must not generate it");
        assertEquals(2, reads.get());
        top.setBlockState(top.cc_getCubePos().asBlockPos(0, 31, 0), Blocks.GLOWSTONE.defaultBlockState());
        assertSame(Blocks.AIR.defaultBlockState(), index.source(-3).topState(0, 0));
    }

    @Test
    void suppliedSourceMapIsNotDuplicatedAndIndexStaysDetached() {
        var level = mockLevelWithPalettes(Level.class);
        var cube = new LevelCube(level, CubePos.of(0, 2, 0));
        cube.setBlockState(cube.cc_getCubePos().asBlockPos(8, 16, 8), Blocks.STONE.defaultBlockState());
        var original = cube.cc_snapshotSkyOcclusion();
        var overrides = new HashMap<Integer, CubeSkyOcclusion>();
        overrides.put(2, original);
        var index = new CubeWorldSkyColumn((x, z) -> -65, 0, 0, overrides);
        int edge = index.edge(8, 8);
        cube.setBlockState(cube.cc_getCubePos().asBlockPos(8, 16, 8), Blocks.AIR.defaultBlockState());
        overrides.put(2, cube.cc_snapshotSkyOcclusion());
        overrides.clear();
        assertNull(index.source(2), "Existing native/saved/live overrides are retained by the caller, not duplicated here");
        assertEquals(edge, index.edge(8, 8));
        assertNull(index.source(1));
    }

    @Test
    void exceptionallyDeepShaftRetainsOnlyOneHundredTwentyEightGeneratedSummaries() {
        var level = mockLevelWithPalettes(Level.class);
        var air = new LevelCube(level, CubePos.ZERO).getSections();
        var floor = new LevelCube(level, CubePos.of(0, -132, 0));
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                floor.setBlockState(floor.cc_getCubePos().asBlockPos(x, 12, z), Blocks.STONE.defaultBlockState());
            }
        }
        var floorSummary = floor.cc_snapshotSkyOcclusion();
        AtomicInteger reads = new AtomicInteger();
        var index = new CubeWorldSkyColumn((x, z) -> -65, 0, 0, Map.of(), y -> {
            reads.incrementAndGet();
            return y == -132 ? floorSummary : CubeSkyOcclusion.capture(CubePos.of(0, y, 0), air);
        });
        assertEquals(130, reads.get());
        int retained = 0;
        for (int y = -132; y <= -3; y++) {
            if (index.source(y) != null) {
                retained++;
            }
        }
        assertEquals(128, retained);
        assertNull(index.source(-132), "An omitted baseline is unknown, not assumed equal");
        assertEquals(floor.cc_getCubePos().minCubeY() + 13, index.edge(0, 0), "Retention must not change the exact computed sky field");
        assertEquals(130, reads.get());
    }
}
