package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;

public interface CubicClientEntityManager {
    void cc_configureCubeTicks(Predicate<CubePos> loaded);

    @Nullable ClientCubeEntityTicking cc_cubeTicks();
}
