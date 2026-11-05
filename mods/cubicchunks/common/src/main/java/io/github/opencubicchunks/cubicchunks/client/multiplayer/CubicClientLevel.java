package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;

public interface CubicClientLevel extends CubicLevel {
    void cc_onCubeLoaded(CubePos cubePos);

    void cc_onCubeUnloaded(CubePos cubePos);

    void cc_refreshEntityTicks();

    ClientCubePredictions cc_getCubePredictions();

    void cc_forgetCubePredictions(CubePos pos);
}
