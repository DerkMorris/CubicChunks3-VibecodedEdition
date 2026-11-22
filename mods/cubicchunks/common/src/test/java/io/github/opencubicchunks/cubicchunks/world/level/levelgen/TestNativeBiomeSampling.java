package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

class TestNativeBiomeSampling extends BaseTest {
    @Test
    void nativeOverworldSamplingPreservesEveryBiomeQuartIncludingNegativeAndFarColumns() {
        compare(NoiseGeneratorSettings.OVERWORLD, MultiNoiseBiomeSourceParameterLists.OVERWORLD, -64, 384);
    }

    @Test
    void nativeNetherSamplingPreservesEveryBiomeQuartIncludingAboveTheNativeNoiseVolume() {
        compare(NoiseGeneratorSettings.NETHER, MultiNoiseBiomeSourceParameterLists.NETHER, 0, 256);
    }

    private static void compare(
            ResourceKey<NoiseGeneratorSettings> settingsKey, ResourceKey<MultiNoiseBiomeSourceParameterList> biomesKey, int minY, int height
    ) {
        var registries = VanillaRegistriesExtension.shared();
        var palettes = PalettedContainerFactory.create(registries);
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey).value();
        var parameters = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(biomesKey).value().parameters().values();
        var bounds = LevelHeightAccessor.create(minY, height);
        var positions = new ChunkPos[] { new ChunkPos(-2, 0), new ChunkPos(-1, -1), new ChunkPos(0, 0), new ChunkPos(1, 1),
            new ChunkPos(64, 0), new ChunkPos(2048, -1024), new ChunkPos(-1500000, 1500000) };
        for (long seed : new long[] { 5015645173147404823L, 0, -721946114L }) {
            var random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
            // Climate's nearest-biome index remembers its previous result for ties. Give both paths the same independent query history.
            var originalSource = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(parameters));
            var source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(parameters));
            // Repeat in reverse order to exercise resolver caches and the biome index after previous columns.
            for (int index = 0; index < positions.length * 2; index++) {
                var pos = positions[index < positions.length ? index : positions.length * 2 - index - 1];
                var original = new ProtoChunk(pos, UpgradeData.EMPTY, bounds, palettes, null);
                // Follow the old production path exactly, including section order and each section's x/y/z sampling order.
                original.fillBiomesFromNoise(originalSource.createUncachedResolver(random));
                original.setPersistedStatus(ChunkStatus.BIOMES);
                var generated = new ProtoChunk(pos, UpgradeData.EMPTY, bounds, palettes, null);
                NativeBiomeTerrain.fillNativeBiomes(generated, source, random);
                generated.setPersistedStatus(ChunkStatus.BIOMES);
                for (int y = QuartPos.fromBlock(minY); y < QuartPos.fromBlock(minY + height); y++) {
                    for (int z = QuartPos.fromBlock(pos.getMinBlockZ()); z <= QuartPos.fromBlock(pos.getMaxBlockZ()); z++) {
                        for (int x = QuartPos.fromBlock(pos.getMinBlockX()); x <= QuartPos.fromBlock(pos.getMaxBlockX()); x++) {
                            assertEquals(original.getNoiseBiome(x, y, z), generated.getNoiseBiome(x, y, z),
                                    "Biome changed for seed=" + seed + " quart=" + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }
}
