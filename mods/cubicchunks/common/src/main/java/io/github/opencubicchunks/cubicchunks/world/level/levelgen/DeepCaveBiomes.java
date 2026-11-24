package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.mixin.access.common.ClimateParameterListAccess;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.ClimateRTreeAccess;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.MultiNoiseBiomeSourceAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/** Extends the Overworld's underground climate interval into the unbounded cave volume. */
final class DeepCaveBiomes implements BiomeResolver {
    private static final int TRANSITION_BLOCKS = 64;
    private static final int SMOOTH_COEFFICIENT = 3;
    private static final double HORIZONTAL_SCALE = 192;
    private static final double VERTICAL_SCALE = 96;
    private static final double MIN_DEPTH = 0.2;
    private static final double MAX_DEPTH = 1.1;
    private static final double MID_DEPTH = (MIN_DEPTH + MAX_DEPTH) / 2;
    private static final long DEPTH_SALT = 0x6D5A56DA4B57C9E3L;
    private final int minQuartY;
    private final Climate.Sampler sampler;
    private final BiomeResolver nativeResolver;
    private final PerlinNoise depth;
    private final @Nullable Climate.ParameterList<Holder<Biome>> deepParameters;
    private final @Nullable ThreadLocal<?> lastResult;

    DeepCaveBiomes(long seed, int nativeMinY, BiomeSource source, RandomState randomState) {
        minQuartY = QuartPos.fromBlock(nativeMinY);
        sampler = randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        nativeResolver = source.createResolver(sampler);
        depth = new PerlinNoise(new XoroshiroRandomSource(seed ^ DEPTH_SALT));
        if (source instanceof MultiNoiseBiomeSource multiNoise) {
            // Own the index so clearing its tie-history never changes native generation or another resolver.
            deepParameters = new Climate.ParameterList<>(((MultiNoiseBiomeSourceAccess) multiNoise).cc_parameters().values());
            var index = ((ClimateParameterListAccess) deepParameters).cc_index();
            lastResult = ((ClimateRTreeAccess) (Object) index).cc_lastResult();
        } else {
            deepParameters = null;
            lastResult = null;
        }
    }

    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        if (y >= minQuartY || deepParameters == null) {
            return nativeResolver.getNoiseBiome(x, y, z);
        }
        var climate = sampler.sample(x, y, z);
        // Vanilla's depth gradient stops at its bottom endpoint. Sampling it at ever lower Y cannot revisit
        // the 0.2..0.9 underground interval. Keep every other climate axis and the world's own biome table.
        double caveDepth = Math.clamp(MID_DEPTH + depth.get(QuartPos.toBlock(x) / HORIZONTAL_SCALE,
                (double) QuartPos.toBlock(y) / VERTICAL_SCALE, QuartPos.toBlock(z) / HORIZONTAL_SCALE), MIN_DEPTH, MAX_DEPTH);
        double transition = Math.clamp((double) (minQuartY - y) * QuartPos.SIZE / TRANSITION_BLOCKS, 0, 1);
        double blend = transition * transition * (SMOOTH_COEFFICIENT - 2 * transition);
        float mixedDepth = (float) (Climate.unquantizeCoord(climate.depth()) * (1 - blend) + caveDepth * blend);
        // Overlapping climate ranges can have equally near biomes. A cold search uses a fixed tree order;
        // otherwise a neighboring query could change both regenerated decoration and the lighting baseline.
        lastResult.set(null);
        return deepParameters.findValue(new Climate.TargetPoint(climate.temperature(), climate.humidity(), climate.continentalness(),
                climate.erosion(), Climate.quantizeCoord(mixedDepth), climate.weirdness()));
    }
}
