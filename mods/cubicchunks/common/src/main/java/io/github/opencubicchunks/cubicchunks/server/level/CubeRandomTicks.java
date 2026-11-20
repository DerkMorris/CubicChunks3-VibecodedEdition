package io.github.opencubicchunks.cubicchunks.server.level;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;

/** Native random block/fluid ticks, with one sampling budget for each 16-cubed section. */
public final class CubeRandomTicks {
    private CubeRandomTicks() {}

    public static void tick(ServerLevel level, LevelCube cube, int randomTickSpeed) {
        if (randomTickSpeed <= 0) {
            return;
        }
        var origin = cube.cc_getCubePos();
        var sections = cube.getSections();
        var random = level.getRandom();
        var profiler = Profiler.get();
        for (int index = 0; index < sections.length; index++) {
            var section = sections[index];
            if (!section.isRandomlyTicking()) {
                continue;
            }
            int minX = Coords.cubeToMinBlock(origin.getX()) + SectionPos.sectionToBlockCoord(Coords.indexToX(index));
            int minY = Coords.cubeToMinBlock(origin.getY()) + SectionPos.sectionToBlockCoord(Coords.indexToY(index));
            int minZ = Coords.cubeToMinBlock(origin.getZ()) + SectionPos.sectionToBlockCoord(Coords.indexToZ(index));
            for (int sample = 0; sample < randomTickSpeed; sample++) {
                BlockPos pos = level.getBlockRandomPos(minX, minY, minZ, SectionPos.SECTION_MASK);
                profiler.push("randomTick");
                try {
                    var state = section.getBlockState(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ);
                    if (state.isRandomlyTicking()) {
                        state.randomTick(level, pos, random);
                    }
                    // Vanilla uses the sampled state's fluid, even if its block callback changes the world.
                    var fluid = state.getFluidState();
                    if (fluid.isRandomlyTicking()) {
                        fluid.randomTick(level, pos, random);
                    }
                } finally {
                    profiler.pop();
                }
            }
        }
    }
}
