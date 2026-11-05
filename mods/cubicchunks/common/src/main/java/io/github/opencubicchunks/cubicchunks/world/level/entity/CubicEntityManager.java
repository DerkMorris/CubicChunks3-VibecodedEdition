package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.nio.file.Path;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;

/** Cube persistence is independent of the vanilla manager's packed section visibility. */
public interface CubicEntityManager {
    void cc_configureEntityStorage(ServerLevel level, Path directory);

    @Nullable CubeEntityPersistence cc_entityPersistence();

    @Nullable CubeEntityVisibility cc_entityVisibility();
}
