package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class TestUndecoratedColumn extends BaseTest {
    @Test
    void decorationsNeverMutateAnotherTilesTerrainOrHeightmaps() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        var column = new ProtoChunk(new ChunkPos(-1, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), level.palettedContainerFactory(),
                null);
        BlockPos surface = new BlockPos(-1, 67, 1);
        column.setBlockState(surface, Blocks.STONE.defaultBlockState(), 0);
        Heightmap.primeHeightmaps(column, ChunkStatus.FINAL_HEIGHTMAPS);
        column.setPersistedStatus(ChunkStatus.TERRAIN);
        var snapshot = new UndecoratedColumn(level, column);
        var decorated = snapshot.copy(level);
        decorated.setBlockState(surface.above(), Blocks.OAK_LOG.defaultBlockState(), 0);
        var adjacentTile = snapshot.copy(level);
        assertSame(Blocks.STONE.defaultBlockState(), adjacentTile.getBlockState(surface));
        assertSame(Blocks.AIR.defaultBlockState(), adjacentTile.getBlockState(surface.above()));
        assertEquals(surface.getY(), adjacentTile.getHeight(Heightmap.Types.WORLD_SURFACE, surface.getX() & 15, surface.getZ()));
        assertEquals(surface.getY() + 1, decorated.getHeight(Heightmap.Types.WORLD_SURFACE, surface.getX() & 15, surface.getZ()));
    }
}
