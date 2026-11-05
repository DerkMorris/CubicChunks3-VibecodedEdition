package io.github.opencubicchunks.cubicchunks.world.level.poi;

import javax.annotation.Nullable;

/** The native manager remains the public API; cubic storage never accepts packed vanilla section keys. */
public interface CubicPoiManager {
    @Nullable CubePoiStorage cc_cubePoi();
}
