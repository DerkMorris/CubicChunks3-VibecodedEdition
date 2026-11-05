package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.Set;

import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Shared heightmap operations; horizontal coordinates are local to the owning chunk or cube and Y is absolute. */
public interface CloHeightmap {
    boolean update(int x, int y, int z, BlockState state);

    int getFirstAvailable(int x, int z);

    int getHighestTaken(int x, int z);

    long[] getRawData();

    static void primeHeightmaps(CloAccess clo, Set<Heightmap.Types> types) {
        if (clo instanceof CubeAccess cube) {
            CubeHeightmap.primeHeightmaps(cube, types);
        } else {
            Heightmap.primeHeightmaps((ChunkAccess) clo, types);
        }
    }
}
