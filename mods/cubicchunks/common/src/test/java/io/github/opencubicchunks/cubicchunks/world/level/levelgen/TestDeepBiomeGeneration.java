package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubeStatusTasks;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.Test;

class TestDeepBiomeGeneration extends BaseTest {
    @Test
    void generatedCubesBiomesLightFallbackAndSavedDataAgreeAtEveryDepth() {
        for (var biome : List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK)) {
            var level = level(biome);
            var terrain = new NativeBiomeTerrain(level, Runnable::run);
            when(((CubicServerLevel) level).cc_nativeBiomeTerrain()).thenReturn(terrain);
            var context = mock(WorldGenContext.class);
            when(context.level()).thenReturn(level);
            for (int y : new int[] { -320, -32000, -32000000 }) {
                int decorations = 0;
                int entities = 0;
                for (int x = -1; x <= 1; x++) {
                    var pos = CubePos.of(x, Math.floorDiv(y, 32), -1);
                    var cube = new ProtoCube(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
                    CubeStatusTasks.generateBiomes(context, null, null, cube).join();
                    cube.setPersistedStatus(ChunkStatus.BIOMES);
                    var fallback = terrain.blocks(pos).join();
                    terrain.fill(cube).join();
                    cube.setPersistedStatus(ChunkStatus.FEATURES);
                    assertTrue(fallback.sameStates(CubeLightBlockSnapshot.capture(cube)), "Unloaded light input differs from generated blocks");
                    var restored = SerializableCubeData.parse(SerializableCubeData.copyOf(level, cube).write(), pos).read(level);
                    // ProtoCube retains pending block-entity NBT until the normal promotion to a full cube.
                    var restoredFull = new LevelCube(level, restored, null);
                    assertTrue(fallback.sameStates(CubeLightBlockSnapshot.capture(restored)), "Decoration changed after serialization");
                    for (int qy = 0; qy < 8; qy++) {
                        for (int qz = 0; qz < 8; qz++) {
                            for (int qx = 0; qx < 8; qx++) {
                                int bx = QuartPos.fromBlock(pos.minCubeX()) + qx;
                                int by = QuartPos.fromBlock(pos.minCubeY()) + qy;
                                int bz = QuartPos.fromBlock(pos.minCubeZ()) + qz;
                                assertTrue(cube.getNoiseBiome(bx, by, bz).is(biome));
                                assertEquals(cube.getNoiseBiome(bx, by, bz), restored.getNoiseBiome(bx, by, bz));
                            }
                        }
                    }
                    for (var blockPos : BlockPos.betweenClosed(pos.asBlockPos(), pos.asBlockPos(31, 31, 31))) {
                        var state = cube.getBlockState(blockPos);
                        if (biome == Biomes.LUSH_CAVES ? state.is(Blocks.MOSS_BLOCK)
                                : biome == Biomes.DRIPSTONE_CAVES ? state.is(Blocks.POINTED_DRIPSTONE) : state.is(Blocks.SCULK)) {
                            decorations++;
                        }
                        if (state.hasBlockEntity()) {
                            entities++;
                            assertNotNull(cube.getBlockEntity(blockPos), "Generated sculk must have a live block entity");
                            assertNotNull(restoredFull.getBlockEntity(blockPos), "Generated sculk block entity must survive saving");
                        }
                    }
                    // Mutating a generated cube must not mutate the cached source used by future cubes and lighting.
                    cube.setBlockState(pos.asBlockPos(), Blocks.GOLD_BLOCK.defaultBlockState());
                    assertTrue(fallback.sameStates(terrain.blocks(pos).join()));
                }
                assertTrue(decorations > 0, "No decorations for " + biome + " at Y=" + y);
                if (biome == Biomes.DEEP_DARK) {
                    assertTrue(entities > 0, "Deep dark must include functional sculk blocks at Y=" + y);
                }
            }
        }
    }

    private static ServerLevel level(ResourceKey<Biome> biome) {
        var registries = VanillaRegistriesExtension.shared();
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getSeed()).thenReturn(42L);
        var data = mock(PrimaryLevelData.class);
        when(level.getLevelData()).thenReturn(data);
        when(((CubicWorldData) data).cc_deepCaveSettings()).thenReturn(new DeepCaveSettings(true, 64, 65, 35));
        when(level.getChunkSource().getGenerator().getBiomeSource())
                .thenReturn(new FixedBiomeSource(registries.lookupOrThrow(Registries.BIOME).getOrThrow(biome)));
        when(level.getChunkSource().randomState()).thenReturn(RandomState.create(registries.lookupOrThrow(Registries.NOISE), 42,
                registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()));
        return level;
    }
}
