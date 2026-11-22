package io.github.opencubicchunks.cubicchunks.client.renderer;

/** Implemented by optional renderers that own cube geometry instead of the vanilla graph. */
public interface CubicRenderingBackend {
    CubicLevelRenderer cc_cubeRenderer();
}
