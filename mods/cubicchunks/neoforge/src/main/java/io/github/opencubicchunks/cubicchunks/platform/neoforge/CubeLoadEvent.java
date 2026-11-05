package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;

/** Fired while promoting a cube to FULL. Defer world interactions until the next server tick. */
public final class CubeLoadEvent extends net.neoforged.bus.api.Event {
    private final LevelCube cube;
    private final boolean newCube;

    public CubeLoadEvent(LevelCube cube, boolean newCube) {
        this.cube = cube;
        this.newCube = newCube;
    }

    public LevelCube cube() {
        return cube;
    }

    public boolean isNewCube() {
        return newCube;
    }
}
