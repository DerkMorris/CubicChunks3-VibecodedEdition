package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubePyramid;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;

class TestCubeTerrainGeneration extends BaseTest {
    private Level level;

    @org.junit.jupiter.api.BeforeEach
    void setupLevel() {
        level = mockLevelWithPalettes(Level.class);
    }

    private ProtoCube cube(int x, int y, int z) {
        return new ProtoCube(CubePos.of(x, y, z), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
    }

    @Test
    void everyRegisteredBiomeSurvivesFullCubeTransferAtBothHeightSigns() {
        var biomes = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).listElements().toList();
        int width = net.minecraft.core.QuartPos.fromBlock(32);
        for (int cy : new int[] { -1000000, 1000000 }) {
            var key = CubePos.of(-1, cy, 2);
            for (int base = 0; base < biomes.size(); base += width * width * width) {
                int start = base;
                var source = new io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube(level, key);
                source.fillBiomesFromNoise((x, y, z) -> biomes.get(
                        (start + Math.floorMod(x, width) + width * (Math.floorMod(z, width) + width * Math.floorMod(y, width))) % biomes.size()));
                var packet = new io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData(source);
                var target = new io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube(level, key);
                var buffer = packet.getReadBuffer();
                try {
                    target.replaceWithPacketData(buffer, packet.getHeightmaps(), ignored -> {});
                } finally {
                    buffer.release();
                }
                for (int i = 0; i < Math.min(biomes.size() - start, width * width * width); i++) {
                    assertEquals(biomes.get(start + i),
                            target.getNoiseBiome(-width + i % width, cy * width + i / (width * width), 2 * width + (i / width) % width));
                }
            }
        }
    }

    @Test
    void terrainIsSeededReproducibleAndContinuousAcrossPositiveAndNegativeCubeEdges() {
        Set<Integer> heights = new HashSet<>();
        boolean differs = false;
        for (int x = -65; x <= 65; x++) {
            int height = CubeTerrainGenerator.surface(1234, x, -32);
            heights.add(height);
            differs |= height != CubeTerrainGenerator.surface(5678, x, -32);
            assertTrue(Math.abs(height - CubeTerrainGenerator.surface(1234, x + 1, -32)) <= 2);
            ProtoCube first = cube(Math.floorDiv(x, 32), Math.floorDiv(height, 32), -1);
            ProtoCube second = cube(Math.floorDiv(x, 32), Math.floorDiv(height, 32), -1);
            CubeTerrainGenerator.fill(1234, first);
            CubeTerrainGenerator.fill(1234, second);
            BlockPos surface = new BlockPos(x, height, -32);
            assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), first.getBlockState(surface));
            assertEquals(first.getBlockState(surface), second.getBlockState(surface));
            if (height % 32 > 3) {
                assertEquals(Blocks.DIRT.defaultBlockState(), first.getBlockState(surface.below(3)));
            }
        }
        assertTrue(differs);
        assertTrue(heights.size() > 1);
    }

    @Test
    void deepAndHighCubesHaveNoVerticalWrapAndDoNotAcquireAFloorAtEachBoundary() {
        for (int y : new int[] { -1000000, -1, 1000000 }) {
            ProtoCube cube = cube(-1, y, 1);
            CubeTerrainGenerator.fill(1234, cube);
            for (int localY : new int[] { 0, 15, 16, 31 }) {
                assertEquals((y < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState(),
                        cube.getBlockState(cube.cc_getCubePos().asBlockPos(31, localY, 0)));
            }
        }
    }

    @Test
    void biomeResolverReceivesAllEightSectionsWithAbsoluteQuartCoordinates() {
        ProtoCube cube = cube(-1, 1000000, -2);
        var plains = cube.getSection(0).getNoiseBiome(0, 0, 0);
        Set<BlockPos> sampled = new HashSet<>();
        cube.fillBiomesFromNoise((x, y, z) -> {
            sampled.add(new BlockPos(x, y, z));
            return plains;
        });
        assertEquals(512, sampled.size());
        assertTrue(sampled.contains(new BlockPos(-8, 8000000, -16)));
        assertTrue(sampled.contains(new BlockPos(-1, 8000007, -9)));
    }

    @Test
    void unusedVanillaFeatureStagesDoNotGenerateLargeNeighborVolumes() {
        var full = CubePyramid.CC_GENERATION_PYRAMID_CUBES.getStepTo(ChunkStatus.FULL);
        assertEquals(1, full.accumulatedDependencies().getRadius());
        assertEquals(ChunkStatus.EMPTY, full.accumulatedDependencies().get(1));
    }
}
