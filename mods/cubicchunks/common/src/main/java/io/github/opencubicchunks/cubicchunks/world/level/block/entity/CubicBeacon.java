package io.github.opencubicchunks.cubicchunks.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public interface CubicBeacon {
    int cc_prepareBeam(Level level, BlockPos pos);

    void cc_publishBeam(Level level, BlockPos pos, BlockState state);
}
