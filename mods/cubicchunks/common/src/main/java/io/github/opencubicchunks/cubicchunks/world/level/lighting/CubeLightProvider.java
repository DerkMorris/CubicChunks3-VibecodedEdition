package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Chunk-source bridge for immutable current cube light publications. A missing or stale publication returns null. */
public interface CubeLightProvider {
    @Nullable CubeLightView cc_getCubeLightView(CubePos pos);

    /** Client display may retain the last accepted field during relighting; server reads always use current data. */
    default @Nullable CubeLightView cc_getVisibleCubeLightView(CubePos pos) {
        return cc_getCubeLightView(pos);
    }

    default void cc_onCubeBlockDataChanged(CubeAccess cube) {}

    /** A single changed cell, after the revision hook. Clients can update its visible light before building geometry. */
    default void cc_onCubeLightCellChanged(CubeAccess cube, BlockPos pos, BlockState previous) {}
}
