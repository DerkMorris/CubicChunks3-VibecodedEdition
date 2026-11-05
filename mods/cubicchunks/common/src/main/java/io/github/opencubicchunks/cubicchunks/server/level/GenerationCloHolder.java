package io.github.opencubicchunks.cubicchunks.server.level;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;

public interface GenerationCloHolder {
    CloPos cc_getCloPos();

    @Nullable CubePos cc_getCubePos();

    @Nullable CloAccess cc_getLatestClo();

    void cc_replaceProtoCube(ImposterProtoCube cube);
}
