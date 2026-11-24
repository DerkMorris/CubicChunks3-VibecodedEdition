package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.mojang.datafixers.util.Pair;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

class TestDeepCaveBiomes extends BaseTest {
    @Test
    void nativeBandStillUsesTheUnchangedWorldSourceForEveryQuart() {
        var random = randomState(42);
        for (int minY : new int[] { -512, -64, 64 }) {
            var nativeResolver = source().createUncachedResolver(random);
            var extended = new DeepCaveBiomes(42, minY, source(), random);
            for (int y = QuartPos.fromBlock(minY); y < QuartPos.fromBlock(384); y++) {
                for (int x = -8; x <= 8; x++) {
                    assertEquals(nativeResolver.getNoiseBiome(x, y, -13), extended.getNoiseBiome(x, y, -13));
                }
            }
        }
    }

    @Test
    void vanillaUndergroundBiomesRemainReachableAtOrdinaryAndExtremeDepth() {
        var random = randomState(5015645173147404823L);
        var source = source();
        var extended = new DeepCaveBiomes(random.seed(), -64, source, random);
        var expected = Set.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK);
        for (int blockY : new int[] { -256, -32000, -32000000 }) {
            Set<ResourceKey<Biome>> found = new HashSet<>();
            for (int x = -1024; x <= 1024; x += 32) {
                for (int z = -1024; z <= 1024; z += 32) {
                    Holder<Biome> biome = extended.getNoiseBiome(x, QuartPos.fromBlock(blockY), z);
                    assertTrue(source.possibleBiomes().contains(biome));
                    found.add(biome.unwrapKey().orElseThrow());
                }
            }
            assertTrue(found.containsAll(expected), "Missing underground biomes at Y=" + blockY + ": " + found);
        }
    }

    @Test
    void deepClimateVariesWithActualYAndSeedWithoutPackedCoordinateAliasing() {
        var random = randomState(42);
        var first = new DeepCaveBiomes(42, -64, source(), random);
        var second = new DeepCaveBiomes(43, -64, source(), random);
        int verticalDifferences = 0;
        int seedDifferences = 0;
        for (int x = -512; x <= 512; x += 16) {
            for (int z = -512; z <= 512; z += 16) {
                var original = first.getNoiseBiome(x, -96, z);
                if (!original.equals(first.getNoiseBiome(x, -96 - (1 << 22), z))) {
                    verticalDifferences++;
                }
                if (!original.equals(second.getNoiseBiome(x, -96, z))) {
                    seedDifferences++;
                }
            }
        }
        assertTrue(verticalDifferences > 0, "Far sections must not repeat through packed Y aliasing");
        assertTrue(seedDifferences > 0, "The additional vertical climate must depend on the world seed");
    }

    @Test
    void fixedAndCustomSourcesNeverAcquireUnavailableVanillaBiomes() {
        var biomes = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.BIOME);
        var plains = biomes.getOrThrow(Biomes.PLAINS);
        var desert = biomes.getOrThrow(Biomes.DESERT);
        var custom = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
                Pair.of(Climate.parameters(0, 0, 0, 0, 0, 0, 0), plains), Pair.of(Climate.parameters(0, 0, 0, 0, 1, 0, 0), desert))));
        var random = randomState(7);
        var fixed = new DeepCaveBiomes(7, -64, new FixedBiomeSource(plains), random);
        var extended = new DeepCaveBiomes(7, -64, custom, random);
        for (int y : new int[] { -16, -17, -128, -8000, -8000000 }) {
            assertEquals(plains, fixed.getNoiseBiome(-24, y, 36));
            assertTrue(custom.possibleBiomes().contains(extended.getNoiseBiome(-24, y, 36)));
        }
    }

    @Test
    void overlappingClimateTiesAreStableAcrossReverseQueriesAndConcurrentWorkers() {
        var biomes = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.BIOME);
        var full = Climate.Parameter.span(-2, 2);
        // Both biomes have zero climate distance through a broad central volume, with distinct nearest neighbors on either side.
        var custom = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
                Pair.of(Climate.parameters(full, full, Climate.Parameter.span(-2, 0.2f), full, full, full, 0),
                        biomes.getOrThrow(Biomes.LUSH_CAVES)),
                Pair.of(Climate.parameters(full, full, Climate.Parameter.span(-0.2f, 2), full, full, full, 0),
                        biomes.getOrThrow(Biomes.DRIPSTONE_CAVES)))));
        var extended = new DeepCaveBiomes(42, -64, custom, randomState(42));
        var positions = new ArrayList<int[]>();
        var expected = new ArrayList<Holder<Biome>>();
        for (int x = -1024; x <= 1024; x += 32) {
            for (int z = -1024; z <= 1024; z += 32) {
                positions.add(new int[] { x, -8000000, z });
                expected.add(extended.getNoiseBiome(x, -8000000, z));
            }
        }
        assertEquals(custom.possibleBiomes(), new HashSet<>(expected), "The scan must include both exclusive climate regions");
        for (int i = positions.size() - 1; i >= 0; i--) {
            var pos = positions.get(i);
            assertEquals(expected.get(i), extended.getNoiseBiome(pos[0], pos[1], pos[2]));
        }
        try (var workers = Executors.newFixedThreadPool(4)) {
            var checks = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < positions.size(); i++) {
                var pos = positions.get(i);
                var biome = expected.get(i);
                checks.add(CompletableFuture.runAsync(() -> assertEquals(biome, extended.getNoiseBiome(pos[0], pos[1], pos[2])), workers));
            }
            CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).join();
        }
    }

    private static MultiNoiseBiomeSource source() {
        var parameters = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters().values();
        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(parameters));
    }

    private static RandomState randomState(long seed) {
        var registries = VanillaRegistriesExtension.shared();
        return RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed,
                registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value());
    }
}
