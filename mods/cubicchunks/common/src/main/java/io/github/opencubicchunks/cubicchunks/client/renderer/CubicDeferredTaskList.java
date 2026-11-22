package io.github.opencubicchunks.cubicchunks.client.renderer;

/** Carries the vertical origin alongside Sodium's packed, priority-sorted section tasks. */
public interface CubicDeferredTaskList {
    void cc_setVerticalOffset(int offset);
}
