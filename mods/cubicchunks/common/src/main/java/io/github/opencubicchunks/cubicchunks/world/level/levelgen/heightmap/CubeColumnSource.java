package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import javax.annotation.Nullable;

/** Server-thread-only, nonblocking authoritative column reads. Null means loading, unavailable, or over budget. */
public interface CubeColumnSource {
    @Nullable CubeColumnSnapshot cc_column(int blockX, int blockZ);

    default void cc_columnChanged(io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess cube) {}
}
