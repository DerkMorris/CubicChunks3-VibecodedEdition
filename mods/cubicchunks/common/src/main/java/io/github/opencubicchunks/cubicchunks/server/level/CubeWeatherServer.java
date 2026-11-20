package io.github.opencubicchunks.cubicchunks.server.level;

import io.github.opencubicchunks.cc_core.api.CubePos;

public interface CubeWeatherServer {
    void cc_weatherColumnChanged(CubePos cube);
}
