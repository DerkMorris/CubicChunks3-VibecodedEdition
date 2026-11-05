package io.github.opencubicchunks.cubicchunks.testutils;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CloProgressListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

public class DummyChunkProgressListener implements LevelLoadListener, CloProgressListener {
    @Override public void cc_updateSpawnPos(CloPos center) {

    }

    @Override public void cc_onStatusChange(CloPos chunkPosition, @Nullable ChunkStatus newStatus) {

    }

    @Override public void updateFocus(ResourceKey<Level> dimension, ChunkPos center) {

    }

    @Override public void update(Stage stage, int current, int total) {

    }

    @Override public void start(Stage stage, int total) {

    }

    @Override public void finish(Stage stage) {

    }
}
