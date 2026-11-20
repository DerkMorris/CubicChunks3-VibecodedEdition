package io.github.opencubicchunks.cubicchunks.test.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnCache;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestCubeWeatherColumns extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void liveRoofOverridesDiskAndRemainsAuthoritativeAfterUnload(int y) {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var pos = new BlockPos(16, y, 16);
        var cube = new LevelCube(level, CubePos.from(pos));
        var disk = snapshot(cube, y, y + 31);
        var reads = new AtomicInteger();
        var cache = new CubeColumnCache(y, y + 31, key -> {
            reads.incrementAndGet();
            return CompletableFuture.completedFuture(disk);
        });
        cache.get(16, 16, List.of(cube));
        cube.setBlockState(pos, Blocks.GLASS.defaultBlockState(), 0);
        cache.changed(cube);
        assertEquals(y + 1, cache.get(16, 16, List.of()).firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
        assertEquals(y + 1, cache.get(16, 16, List.of()).firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
        assertEquals(1, reads.get());
        assertTrue(disk.state(16, y, 16).isAir(), "Captured palettes must remain detached from later edits");
    }

    @Test
    void unfinishedIoIsUnknownAndLiveOverlayWinsWhenItCompletes() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var pos = new BlockPos(16, 32000, 16);
        var cube = new LevelCube(level, CubePos.from(pos));
        var pending = new CompletableFuture<CubeColumnSnapshot>();
        var cache = new CubeColumnCache(-64, 319, key -> pending);
        assertNull(cache.get(16, 16, List.of(cube)));
        cube.setBlockState(pos, Blocks.GLASS.defaultBlockState(), 0);
        cache.changed(cube);
        pending.complete(new CubeColumnSnapshot(0, 0, -64, 319, new TreeMap<>(), Set.of()));
        assertEquals(32001, cache.get(16, 16, List.of(cube)).firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
    }

    @Test
    void generatedBandAndDeepGapsAreNeverAssumedOpen() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var deep = new LevelCube(level, CubePos.of(0, -1000, 0));
        deep.setBlockState(new BlockPos(16, -32000, 16), Blocks.STONE.defaultBlockState(), 0);
        var column = snapshot(deep, -64, 319);
        assertTrue(column.firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).isEmpty());
        assertNull(column.state(16, -100, 16));
        assertTrue(column.state(16, 32000, 16).isAir());
        var unknownRoof = new CubeColumnSnapshot(0, 0, -64, 319, column.cubes(), Set.of(1000));
        assertNull(unknownRoof.state(16, 32000, 16));
        assertTrue(unknownRoof.firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).isEmpty());
    }

    @Test
    void worldCeilingHasNoWritablePrecipitationCell() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var pos = new BlockPos(16, CubicBlockBounds.MAX, 16);
        var cube = new LevelCube(level, CubePos.from(pos));
        cube.setBlockState(pos, Blocks.STONE.defaultBlockState(), 0);
        var column = snapshot(cube, -64, 319);
        assertTrue(column.firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).isEmpty());
        assertNull(column.state(16, CubicBlockBounds.MAX + 1, 16));
    }

    @Test
    void unfinishedNonemptyTerrainCannotExposeTheGeneratedAirAboveIt() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var pos = CubePos.of(0, 1000, 0);
        var unfinished = new ProtoCube(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(32000, 32), level.palettedContainerFactory(), null);
        unfinished.setPersistedStatus(ChunkStatus.BIOMES);
        unfinished.setBlockState(new BlockPos(16, 32000, 16), Blocks.GLASS.defaultBlockState(), 0);
        var base = new CubeColumnSnapshot(0, 0, -64, 319, new TreeMap<>(), Set.of());
        var cache = new CubeColumnCache(-64, 319, key -> CompletableFuture.completedFuture(base));
        var result = cache.get(16, 16, List.of(unfinished));
        assertNull(result.state(16, 32000, 16));
        assertTrue(result.unknown().contains(1000));
    }

    @Test
    void emptyGenerationHaloDoesNotHideTheAuthoritativeGeneratedSurface() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var floor = new LevelCube(level, CubePos.of(0, 3, 0));
        floor.setBlockState(new BlockPos(16, 96, 16), Blocks.STONE.defaultBlockState(), 0);
        var halo = new ProtoCube(CubePos.of(0, 1000, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(32000, 32), level.palettedContainerFactory(),
                null);
        halo.setPersistedStatus(ChunkStatus.BIOMES);
        var cache = new CubeColumnCache(96, 127, key -> CompletableFuture.completedFuture(snapshot(floor, 96, 127)));
        var result = cache.get(16, 16, List.of(floor, halo));
        assertEquals(97, result.firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
        assertTrue(result.unknown().isEmpty());
        assertNull(result.state(16, -32000, 16));
    }

    @Test
    void terrainStageAlreadyContainsTheDecoratedNativeRoof() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var roof = new ProtoCube(CubePos.of(0, 1000, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(32000, 32), level.palettedContainerFactory(),
                null);
        roof.setPersistedStatus(ChunkStatus.TERRAIN);
        roof.setBlockState(new BlockPos(16, 32000, 16), Blocks.GLASS.defaultBlockState(), 0);
        var cache = new CubeColumnCache(-64, 319,
                key -> CompletableFuture.completedFuture(new CubeColumnSnapshot(0, 0, -64, 319, new TreeMap<>(), Set.of())));
        var result = cache.get(16, 16, List.of(roof));
        assertEquals(32001, result.firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
    }

    @Test
    void lruChurnDoesNotEvictAnOutstandingReadAndStartItAgain() {
        var pending = new CompletableFuture<CubeColumnSnapshot>();
        var starts = new AtomicInteger();
        var cache = new CubeColumnCache(-64, 319, key -> {
            if (key.getX() == 0) {
                starts.incrementAndGet();
                return pending;
            }
            return CompletableFuture.completedFuture(new CubeColumnSnapshot(key.getX(), key.getZ(), -64, 319, new TreeMap<>(), Set.of()));
        });
        assertNull(cache.get(0, 0, List.of()));
        for (int x = 1; x <= 100; x++) {
            cache.get(x * 32, 0, List.of());
        }
        assertNull(cache.get(0, 0, List.of()));
        assertEquals(1, starts.get());
    }

    @Test
    void transientReadFailureRetriesAfterBackoffWithoutLosingLiveRoof() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var pos = new BlockPos(16, 32000, 16);
        var cube = new LevelCube(level, CubePos.from(pos));
        cube.setBlockState(pos, Blocks.GLASS.defaultBlockState(), 0);
        var clock = new AtomicLong();
        var starts = new AtomicInteger();
        var cache = new CubeColumnCache(-64, 319,
                key -> starts.getAndIncrement() == 0 ? CompletableFuture.failedFuture(new IllegalStateException("temporary unavailable column"))
                        : CompletableFuture.completedFuture(new CubeColumnSnapshot(0, 0, -64, 319, new TreeMap<>(), Set.of())),
                clock::get);
        assertNull(cache.get(16, 16, List.of(cube)));
        clock.set(99);
        assertNull(cache.get(16, 16, List.of()));
        assertEquals(1, starts.get());
        clock.set(100);
        assertEquals(32001, cache.get(16, 16, List.of()).firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
        assertEquals(2, starts.get());
    }

    private static CubeColumnSnapshot snapshot(LevelCube cube, int min, int max) {
        var blocks = new TreeMap<Integer, CubeColumnBlocks>();
        blocks.put(cube.cc_getCubePos().getY(), CubeColumnBlocks.capture(cube));
        return new CubeColumnSnapshot(0, 0, min, max, blocks, Set.of());
    }
}
