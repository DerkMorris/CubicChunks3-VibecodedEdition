package io.github.opencubicchunks.cubicchunks.world.ticks;

import java.util.function.LongPredicate;

import io.github.opencubicchunks.cc_core.api.CubePos;
import net.minecraft.world.ticks.LevelChunkTicks;

/** Cube addressing for the native scheduled-tick queue. Predicate keys are packed CubePos values. */
public interface CubicLevelTicks<T> {
    /** Standalone queues retain delay aging until their containers are explicitly removed. */
    default void cc_configure(LongPredicate tickingCube) {
        cc_configure(tickingCube, key -> true);
    }

    /** Execution may pause while loaded; delay aging stops after one final snapshot notification when unloading starts. */
    void cc_configure(LongPredicate tickingCube, LongPredicate agingCube);

    void cc_addContainer(CubePos pos, LevelChunkTicks<T> ticks);

    void cc_removeContainer(CubePos pos);
}
