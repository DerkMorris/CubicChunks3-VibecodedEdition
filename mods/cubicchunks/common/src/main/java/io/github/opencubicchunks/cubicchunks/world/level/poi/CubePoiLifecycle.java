package io.github.opencubicchunks.cubicchunks.world.level.poi;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class CubePoiLifecycle {
    private CubePoiLifecycle() {}

    public static @Nullable CubePoiStorage storage(Level level) {
        return level instanceof ServerLevel server && server.getPoiManager() instanceof CubicPoiManager manager ? manager.cc_cubePoi() : null;
    }

    public static void blockChanged(Level level, BlockPos pos, BlockState previous, BlockState current) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        var oldType = PoiTypes.forState(previous);
        var newType = PoiTypes.forState(current);
        if (oldType.equals(newType)) {
            return;
        }
        // Direct cube edits (including template placement) and the native Level callback are idempotent.
        var existing = server.getPoiManager().getType(pos);
        if (existing.equals(newType)) {
            return;
        }
        existing.ifPresent(type -> server.getPoiManager().remove(pos));
        newType.ifPresent(type -> server.getPoiManager().add(pos, type));
    }
}
