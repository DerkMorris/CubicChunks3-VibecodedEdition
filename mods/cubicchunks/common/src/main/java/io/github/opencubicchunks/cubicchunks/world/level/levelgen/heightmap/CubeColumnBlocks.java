package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.Arrays;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Detached paletted sections: sparse column consumers do not retain mutable world cubes. */
public final class CubeColumnBlocks {
    private final CubePos pos;
    private final LevelChunkSection[] sections;

    public CubeColumnBlocks(CubePos pos, LevelChunkSection[] sections) {
        this.pos = pos;
        this.sections = Arrays.stream(sections).map(LevelChunkSection::copy).toArray(LevelChunkSection[]::new);
    }

    public static CubeColumnBlocks capture(CubeAccess cube) {
        return new CubeColumnBlocks(cube.cc_getCubePos(), cube.getSections());
    }

    public CubePos pos() {
        return pos;
    }

    public boolean isEmpty() {
        return Arrays.stream(sections).allMatch(LevelChunkSection::hasOnlyAir);
    }

    public BlockState state(int x, int y, int z) {
        if (Coords.blockToCube(x) != pos.getX() || Coords.blockToCube(y) != pos.getY() || Coords.blockToCube(z) != pos.getZ()) {
            throw new IllegalArgumentException("Position outside captured cube");
        }
        return sections[Coords.blockToIndex(x, y, z)].getBlockState(SectionPos.sectionRelative(x), SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(z));
    }
}
