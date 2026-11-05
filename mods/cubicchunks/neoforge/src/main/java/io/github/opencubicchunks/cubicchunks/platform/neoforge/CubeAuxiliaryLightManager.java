package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;

/** Routes cube block-entity cleanup to the same column managers exposed by NeoForge's Level API. */
public final class CubeAuxiliaryLightManager implements AuxiliaryLightManager {
    private final LevelCube owner;

    public CubeAuxiliaryLightManager(LevelCube owner) {
        this.owner = owner;
    }

    @Override public void setLightAt(BlockPos pos, int value) {
        var manager = owner.getLevel().getAuxLightManager(ChunkPos.containing(pos));
        if (manager != null) {
            manager.setLightAt(pos, value);
        }
    }

    @Override public int getLightAt(BlockPos pos) {
        var manager = owner.getLevel().getAuxLightManager(ChunkPos.containing(pos));
        return manager == null ? 0 : manager.getLightAt(pos);
    }
}
