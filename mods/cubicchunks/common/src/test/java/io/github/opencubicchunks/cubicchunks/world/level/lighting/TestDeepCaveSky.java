package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestDeepCaveSky extends BaseTest {
    @Test
    void openNativeColumnFindsActualCaveFloorAndAnEditedRoofWins() {
        var level = Misc.mockLevelWithPalettes(Level.class);
        var top = new LevelCube(level, CubePos.of(0, -3, 0));
        var bottom = new LevelCube(level, CubePos.of(0, -4, 0));
        int side = CubicConstants.DIAMETER_IN_BLOCKS;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                bottom.setBlockState(bottom.cc_getCubePos().asBlockPos(x, 12, z), Blocks.TUFF.defaultBlockState());
            }
        }
        Map<Integer, CubeSkyOcclusion> generated = Map.of(-3, CubeSkyOcclusion.capture(top), -4, CubeSkyOcclusion.capture(bottom));
        var reads = new AtomicInteger();
        var overrides = new HashMap<Integer, CubeSkyOcclusion>();
        var index = new CubeWorldSkyColumn((x, z) -> -65, 0, 0, overrides, y -> {
            reads.incrementAndGet();
            return generated.get(y);
        });
        assertEquals(bottom.cc_getCubePos().minCubeY() + 13, index.edge(0, 0));
        assertEquals(2, reads.get());
        top.setBlockState(top.cc_getCubePos().asBlockPos(0, 22, 0), Blocks.OBSIDIAN.defaultBlockState());
        overrides.put(-3, CubeSkyOcclusion.capture(top));
        var edited = new CubeWorldSkyColumn((x, z) -> -65, 0, 0, overrides, generated::get);
        assertEquals(top.cc_getCubePos().minCubeY() + 23, edited.edge(0, 0));
        assertEquals(index.edge(1, 0), edited.edge(1, 0));
    }
}
