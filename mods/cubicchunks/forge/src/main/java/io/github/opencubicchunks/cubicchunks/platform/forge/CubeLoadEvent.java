package io.github.opencubicchunks.cubicchunks.platform.forge;

import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;

/** Fired while promoting a cube to FULL. Defer world interactions until the next server tick. */
public record CubeLoadEvent(LevelCube cube, boolean isNewCube) implements net.minecraftforge.eventbus.api.event.RecordEvent {
    public static final net.minecraftforge.eventbus.api.bus.EventBus<CubeLoadEvent> BUS = net.minecraftforge.eventbus.api.bus.EventBus
            .create(CubeLoadEvent.class);
}
