package io.github.opencubicchunks.cubicchunks.world.ticks;

/** Persistence notifications for queues owned by a cube; ordinary column queues remain unconfigured. */
public interface CubicTickContainer {
    void cc_setUnsavedListener(Runnable listener);

    void cc_markUnsaved();
}
