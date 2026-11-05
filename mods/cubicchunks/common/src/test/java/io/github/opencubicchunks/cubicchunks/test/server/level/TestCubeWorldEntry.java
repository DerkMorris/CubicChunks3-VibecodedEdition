package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.server.level.CubeHolder;
import io.github.opencubicchunks.cubicchunks.server.level.GenerationCloHolder;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

class TestCubeWorldEntry extends BaseTest {
    private static final CubePos POS = CubePos.of(-1, 1000000, 2);
    private Level level;

    @org.junit.jupiter.api.BeforeEach
    void setupLevel() {
        level = mockLevelWithPalettes(Level.class);
        io.github.opencubicchunks.cubicchunks.testutils.Misc.setupLevelHeight(level, -64, 384);
    }

    private ChunkHolder holder() throws Exception {
        return ChunkHolder.class.getConstructor(CubePos.class, int.class, LevelHeightAccessor.class, LevelLightEngine.class,
                CubeHolder.LevelChangeListener.class, CubeHolder.PlayerProvider.class).newInstance(POS, 31, LevelHeightAccessor.create(-64, 384),
                        LevelLightEngine.EMPTY, (CubeHolder.LevelChangeListener) (pos, queue, ticket, setQueue) -> {},
                        (CubeHolder.PlayerProvider) (pos, boundary) -> List.of());
    }

    @Test
    void initialLoadCounterDistinguishesColumnsAndCubesAtDifferentHeights() throws Exception {
        var server = mock(net.minecraft.server.level.ServerLevel.class);
        var cache = mock(net.minecraft.server.level.ServerChunkCache.class);
        var map = mock(ChunkMap.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var visible = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        setField(ChunkMap.class, map, "visibleChunkMap", visible);
        setField(net.minecraft.server.level.ServerChunkCache.class, cache, "chunkMap", map);
        when(server.getChunkSource()).thenReturn(cache);
        ChunkHolder existing = holder();
        visible.put(CloPos.cube(POS).asLong(), existing);
        ChunkHolder above = holder();
        setField(GenerationChunkHolder.class, above, "cc_cubePos", CubePos.of(POS.getX(), POS.getY() + 1, POS.getZ()));
        ChunkHolder column = new ChunkHolder(new ChunkPos(POS.getX(), POS.getZ()), 33, LevelHeightAccessor.create(-64, 384), LevelLightEngine.EMPTY,
                (pos, queue, ticket, setQueue) -> {}, (pos, boundary) -> List.of());
        var counter = new net.minecraft.server.level.ChunkLoadCounter();
        counter.track(server, () -> {
            visible.put(((GenerationCloHolder) above).cc_getCloPos().asLong(), above);
            visible.put(((GenerationCloHolder) column).cc_getCloPos().asLong(), column);
        });
        assertEquals(2, counter.totalChunks());
        assertEquals(2, counter.pendingChunks());
    }

    @Test
    void loadingProgressAcceptsFullCubeAndNativeColumnAccessDoesNotCastIt() throws Exception {
        ChunkHolder holder = holder();
        assertNull(holder.getLatestStatus());
        setField(GenerationChunkHolder.class, holder, "startedWork", new AtomicReference<>(ChunkStatus.FULL));
        assertEquals(ChunkStatus.FULL.getParent(), holder.getLatestStatus());
        AtomicReferenceArray<CompletableFuture<?>> futures = new AtomicReferenceArray<>(ChunkStatus.getStatusList().size());
        LevelCube cube = new LevelCube(level, POS);
        futures.set(ChunkStatus.FULL.getIndex(), CompletableFuture.completedFuture(ChunkResult.of(cube)));
        setField(GenerationChunkHolder.class, holder, "futures", futures);
        assertEquals(ChunkStatus.FULL, holder.getLatestStatus());
        assertNull(holder.getChunkIfPresentUnchecked(ChunkStatus.FULL));
        assertSame(cube, ((GenerationCloHolder) holder).cc_getLatestClo());
    }

    @Test
    void successfulCubeResultCompletesNativePromotionConfirmation() throws Exception {
        var method = ChunkHolder.class.getDeclaredMethod("lambda$scheduleFullChunkPromotion$1", CompletableFuture.class, ChunkResult.class);
        method.setAccessible(true);
        CompletableFuture<Void> confirmation = new CompletableFuture<>();
        method.invoke(null, confirmation, ChunkResult.of(new LevelCube(level, POS)));
        assertTrue(confirmation.isDone());
        assertFalse(confirmation.isCompletedExceptionally());
    }

    @Test
    void initialCubeSendingWaitsForCurrentLightAndColumnsRemainAvailable() throws Exception {
        ChunkMap map = mock(ChunkMap.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var visible = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        setField(ChunkMap.class, map, "visibleChunkMap", visible);
        var fetch = ChunkMap.class.getDeclaredMethod("cc_getChunkToSend", long.class);
        LevelCube cube = new LevelCube(level, POS);
        LevelClo column = (LevelClo) new LevelChunk(level, new ChunkPos(-2, 4));
        for (LevelClo clo : List.of(cube, column)) {
            ChunkHolder holder = clo instanceof LevelCube ? holder()
                    : new ChunkHolder(((LevelChunk) clo).getPos(), 31, LevelHeightAccessor.create(-64, 384), LevelLightEngine.EMPTY,
                            (pos, queue, ticket, setQueue) -> {}, (pos, boundary) -> List.of());
            setField(ChunkHolder.class, holder, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkResult.of(clo)));
            visible.put(clo.cc_getCloPos().asLong(), holder);
        }
        assertNull(fetch.invoke(map, cube.cc_getCloPos().asLong()));
        assertSame(column, fetch.invoke(map, column.cc_getCloPos().asLong()));
        var storage = new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                return CompletableFuture.completedFuture(CubeLightBlockSnapshot.generated(pos, 123));
            }
        };
        try (var manager = new CubeWorldLightManager(123, false, Runnable::run, storage, (pos, epoch) -> {})) {
            setField(ChunkMap.class, map, "cc_worldLights", manager);
            for (int pass = 0; pass < 4; pass++) {
                manager.tick(Map.of(POS, cube), List.of(POS));
            }
            assertSame(cube, fetch.invoke(map, cube.cc_getCloPos().asLong()));
            cube.setBlockState(POS.asBlockPos(1, 1, 1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            manager.changed(cube);
            assertNull(fetch.invoke(map, cube.cc_getCloPos().asLong()), "An edit before the initial send must not send stale light");
            assertSame(column, fetch.invoke(map, column.cc_getCloPos().asLong()));
        }
    }

    @Test
    void bothSenderBranchesOrderColumnsBeforeCubesWithoutConfusingObjectsAndKeys() throws Exception {
        var collect = PlayerChunkSender.class.getDeclaredMethod("cc_collectChunksToSend", ChunkMap.class, CloPos.class);
        collect.setAccessible(true);
        var fetch = ChunkMap.class.getDeclaredMethod("cc_getChunkToSend", long.class);
        fetch.setAccessible(true);
        for (boolean local : new boolean[] { true, false }) {
            PlayerChunkSender sender = new PlayerChunkSender(local);
            LevelCube near = new LevelCube(level, POS);
            LevelCube far = new LevelCube(level, CubePos.of(POS.getX(), POS.getY() + 2, POS.getZ()));
            LevelClo column = (LevelClo) new LevelChunk(level, new ChunkPos(-2, 4));
            ChunkMap map = mock();
            for (LevelClo clo : List.of(near, far, column)) {
                when((LevelClo) fetch.invoke(map, clo.cc_getCloPos().asLong())).thenReturn(clo);
            }
            setField(PlayerChunkSender.class, sender, "pendingChunks",
                    new LongOpenHashSet(new long[] { far.cc_getCloPos().asLong(), near.cc_getCloPos().asLong(), column.cc_getCloPos().asLong() }));
            setField(PlayerChunkSender.class, sender, "batchQuota", 2.0f);
            List<?> selected = (List<?>) collect.invoke(sender, map, CloPos.cube(POS));
            assertEquals(local ? List.of(column, near, far) : List.of(column, near), selected);
            assertFalse(sender.isPending(near.cc_getCloPos().asLong()));
            assertEquals(!local, sender.isPending(far.cc_getCloPos().asLong()));
        }
    }
}
