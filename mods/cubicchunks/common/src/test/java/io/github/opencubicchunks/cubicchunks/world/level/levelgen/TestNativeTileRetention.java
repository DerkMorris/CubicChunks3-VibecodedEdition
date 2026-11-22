package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class TestNativeTileRetention extends BaseTest {
    @Test
    void aLargePendingViewDoesNotImmediatelyEvictItsCompletedTiles() throws Exception {
        var terrain = new NativeBiomeTerrain(mockLevelWithPalettes(ServerLevel.class), Runnable::run);
        var tiles = tiles(terrain);
        for (int x = 0; x < 257; x++) {
            tiles.put(new ChunkPos(x, 0), new CompletableFuture<>());
        }
        for (int x = 0; x < 128; x++) {
            ChunkPos key = new ChunkPos(x, 0);
            var future = tiles.get(key);
            future.complete(Map.of());
            finish(terrain, key, future);
            assertSame(future, tiles.get(key), "A completed result must remain reusable while other tiles are still pending");
            assertEquals(257, tiles.size(), "The retained-result limit must not count unfinished shared futures");
        }
        assertEquals(128, tiles.values().stream().filter(CompletableFuture::isDone).count());
        assertEquals(129, tiles.values().stream().filter(future -> !future.isDone()).count());
        ChunkPos next = new ChunkPos(128, 0);
        var nextFuture = tiles.get(next);
        nextFuture.complete(Map.of());
        finish(terrain, next, nextFuture);
        assertSame(nextFuture, tiles.get(next));
        assertFalse(tiles.containsKey(new ChunkPos(0, 0)), "Only the oldest completed result should leave the cache");
        assertEquals(128, tiles.values().stream().filter(CompletableFuture::isDone).count());
        assertEquals(128, tiles.values().stream().filter(future -> !future.isDone()).count());
    }

    @Test
    void completedResultLimitIsOneHundredTwentyEightAndRespectsRecentAccess() throws Exception {
        var terrain = new NativeBiomeTerrain(mockLevelWithPalettes(ServerLevel.class), Runnable::run);
        var tiles = tiles(terrain);
        for (int x = 0; x < 128; x++) {
            tiles.put(new ChunkPos(x, 0), CompletableFuture.completedFuture(Map.of()));
        }
        ChunkPos recent = new ChunkPos(0, 0);
        var recentFuture = tiles.get(recent);
        ChunkPos next = new ChunkPos(128, 0);
        var nextFuture = CompletableFuture.<Map<ChunkPos, NativeBiomeColumn>>completedFuture(Map.of());
        tiles.put(next, nextFuture);
        finish(terrain, next, nextFuture);
        assertEquals(128, tiles.size());
        assertSame(recentFuture, tiles.get(recent));
        assertSame(nextFuture, tiles.get(next));
        assertFalse(tiles.containsKey(new ChunkPos(1, 0)));
        assertTrue(tiles.values().stream().allMatch(CompletableFuture::isDone));
    }

    @SuppressWarnings("unchecked")
    private static Map<ChunkPos, CompletableFuture<Map<ChunkPos, NativeBiomeColumn>>> tiles(NativeBiomeTerrain terrain) throws Exception {
        var field = NativeBiomeTerrain.class.getDeclaredField("tiles");
        field.setAccessible(true);
        return (Map<ChunkPos, CompletableFuture<Map<ChunkPos, NativeBiomeColumn>>>) field.get(terrain);
    }

    private static void finish(NativeBiomeTerrain terrain, ChunkPos key, CompletableFuture<?> future) throws Exception {
        var method = NativeBiomeTerrain.class.getDeclaredMethod("finishTile", ChunkPos.class, CompletableFuture.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(terrain, key, future, null);
    }
}
