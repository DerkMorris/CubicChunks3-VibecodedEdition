package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.EmptyLevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CloHeightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

/** Exercises shared interfaces and the whole-class DASM wrappers, not just the standalone cube heightmap implementation. */
public class TestCloHeightmapBridge extends BaseTest {
    @Test
    void vanillaChunkBridgeUsesItsOriginalHeightmap() {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(3, -5), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384),
                PalettedContainerFactory.create(VanillaRegistriesExtension.shared()), null);
        BlockPos pos = new BlockPos(3 * 16 + 6, -23, -5 * 16 + 9);
        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState());
        CloAccess clo = (CloAccess) chunk;

        CloHeightmap.primeHeightmaps(clo, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        CloHeightmap map = clo.cc_getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

        assertSame(chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE), map);
        assertEquals(pos.getY() + 1, map.getFirstAvailable(6, 9));
        assertEquals(pos.getY(), map.getHighestTaken(6, 9));
        assertEquals(pos.getY(), clo.getHeight(Heightmap.Types.WORLD_SURFACE, 6, 9));
        assertTrue(clo.cc_getHeightmaps().stream().anyMatch(entry -> entry.getKey() == Heightmap.Types.WORLD_SURFACE && entry.getValue() == map));
    }

    @Test
    void readOnlyImposterQueriesWrappedMapsAndRejectsBlockWrites() {
        CubePos pos = CubePos.of(-2, -1000, 4);
        LevelCube full = new LevelCube(mockLevelWithPalettes(Level.class), pos);
        BlockPos lower = pos.asBlockPos(18, 5, 19);
        BlockPos upper = pos.asBlockPos(18, 21, 19);
        full.setBlockState(lower, Blocks.DIRT.defaultBlockState());
        full.setBlockState(upper, Blocks.STONE.defaultBlockState());
        ImposterProtoCube imposter = new ImposterProtoCube(full, false);
        CloAccess clo = imposter;

        assertEquals(upper.getY(), clo.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 18, 19));
        assertEquals(1, clo.getHighestFilledSectionIndex());
        assertEquals(pos.minCubeY() + 16, clo.getHighestSectionPosition());
        CloHeightmap map = clo.cc_getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        assertSame(full.cc_getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE), map);
        assertTrue(clo.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE));
        assertTrue(clo.cc_getHeightmaps().stream().anyMatch(entry -> entry.getKey() == Heightmap.Types.WORLD_SURFACE && entry.getValue() == map));

        imposter.setBlockState(upper, Blocks.AIR.defaultBlockState());
        assertEquals(Blocks.STONE.defaultBlockState(), full.getBlockState(upper));
        assertEquals(upper.getY() + 1, map.getFirstAvailable(18, 19));
        full.setBlockState(upper, Blocks.AIR.defaultBlockState());
        assertEquals(lower.getY(), clo.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 18, 19));
        assertEquals(lower.getY() + 1, map.getFirstAvailable(18, 19));
        assertEquals(pos.minCubeY() - 1, clo.getHeight(Heightmap.Types.WORLD_SURFACE, 2, 3), "The opposite 16-block quadrant stays empty");

        var snapshot = full.cc_snapshotHeightmaps();
        full.getSection(Coords.blockToIndex(lower)).setBlockState(2, 5, 3, Blocks.AIR.defaultBlockState());
        imposter.cc_invalidateHeightmaps();
        assertEquals(pos.minCubeY() - 1, full.getHeight(Heightmap.Types.WORLD_SURFACE, 18, 19));
        full.getSection(Coords.blockToIndex(lower)).setBlockState(2, 5, 3, Blocks.DIRT.defaultBlockState());
        imposter.cc_restoreHeightmaps(snapshot);
        assertEquals(lower.getY(), full.getHeight(Heightmap.Types.WORLD_SURFACE, 18, 19));
    }

    @Test
    void writableImposterUpdatesTheWrappedHeightmap() {
        CubePos pos = CubePos.of(7, 42, -3);
        LevelCube full = new LevelCube(mockLevelWithPalettes(Level.class), pos);
        ImposterProtoCube imposter = new ImposterProtoCube(full, true);
        CloHeightmap.primeHeightmaps(imposter, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        CloHeightmap map = imposter.cc_getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        BlockPos lower = pos.asBlockPos(31, 2, 0);
        BlockPos upper = pos.asBlockPos(31, 18, 0);

        imposter.setBlockState(lower, Blocks.STONE.defaultBlockState());
        imposter.setBlockState(upper, Blocks.DIRT.defaultBlockState());

        assertSame(full.cc_getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE), map);
        assertEquals(Blocks.DIRT.defaultBlockState(), full.getBlockState(upper));
        assertEquals(upper.getY(), map.getHighestTaken(31, 0));
        imposter.setBlockState(upper, Blocks.AIR.defaultBlockState());
        assertEquals(lower.getY(), full.getHeight(Heightmap.Types.WORLD_SURFACE, 31, 0));
        assertEquals(lower.getY(), imposter.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 31, 0));
        assertEquals(pos.minCubeY() - 1, map.getHighestTaken(15, 0));
    }

    @Test
    void emptyCubeUsesItsOwnVerticalBounds() {
        CubePos pos = CubePos.of(5, -1000000, -7);
        var plains = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        EmptyLevelCube empty = new EmptyLevelCube(mockLevelWithPalettes(Level.class), pos, plains);
        CloHeightmap.primeHeightmaps(empty, EnumSet.of(Heightmap.Types.WORLD_SURFACE));

        assertEquals(pos.minCubeY() - 1, empty.getHeight(Heightmap.Types.WORLD_SURFACE, 31, 31));
        assertEquals(pos.minCubeY(), empty.cc_getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(31, 31));
    }
}
