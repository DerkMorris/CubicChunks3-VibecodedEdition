package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** A generation-only view of actual deep biomes from vanilla's bounded layout coordinates. */
final class DeepStructureBiomeSource extends BiomeSource {
    private final BiomeSource source;
    private final BiomeResolver resolver;

    DeepStructureBiomeSource(BiomeSource source, BiomeResolver actual, int offsetY) {
        this.source = source;
        int quartOffset = QuartPos.fromBlock(offsetY);
        resolver = (x, y, z) -> actual.getNoiseBiome(x, y + quartOffset, z);
    }

    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return source.possibleBiomes().stream();
    }

    @Override protected MapCodec<? extends BiomeSource> codec() {
        throw new UnsupportedOperationException("A temporary structure biome view is never a saved world preset");
    }

    @Override public BiomeResolver createResolver(Climate.Sampler sampler) {
        return resolver;
    }
}
