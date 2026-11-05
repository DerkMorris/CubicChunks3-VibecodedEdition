package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import javax.annotation.Nullable;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;

/** Private generation area with no pre-upgrade terrain to blend or live point-of-interest updates. */
public final class BiomeGenerationRegion extends WorldGenRegion {
    private final @Nullable OreDistribution ores;

    BiomeGenerationRegion(
            ServerLevel level, StaticCache2D<GenerationChunkHolder> cache, ChunkStep step, ChunkAccess chunk, @Nullable OreDistribution ores
    ) {
        super(level, cache, step, chunk);
        this.ores = ores != null && ores.hasRules() ? ores : null;
    }

    public boolean replaces(AbstractOreFeature feature) {
        return ores != null && !feature.targetStates().isEmpty()
                && feature.targetStates().stream().allMatch(target -> ores.replaces(target.state().getBlock()));
    }

    @Override public boolean isOldChunkAround(ChunkPos pos, int radius) {
        return false;
    }
}
