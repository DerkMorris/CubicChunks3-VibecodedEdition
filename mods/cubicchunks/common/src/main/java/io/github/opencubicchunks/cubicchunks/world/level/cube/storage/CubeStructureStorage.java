package io.github.opencubicchunks.cubicchunks.world.level.cube.storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import net.minecraft.nbt.CompoundTag;

/** Read-only access to the existing serialized cube store without requesting generation or loading a cube. */
public interface CubeStructureStorage {
    CompletableFuture<Optional<CompoundTag>> cc_read(CloPos pos);
}
