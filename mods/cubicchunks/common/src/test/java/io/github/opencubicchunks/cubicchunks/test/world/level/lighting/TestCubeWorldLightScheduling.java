package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightScheduling extends BaseTest {
    @Test
    void readyUpdatesAndBlockedFirstDeliveriesDoNotHideAFreshReadyFirstDelivery() {
        Fixture fixture = new Fixture(0);
        List<CubePos> updates = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            CubePos pos = CubePos.of(30 + index * 4, 1000000, 0);
            addLoadedHalo(fixture, pos);
            fixture.targets.add(pos);
            updates.add(pos);
        }
        var blocked = new CompletableFuture<CubeLightBlockSnapshot>();
        AtomicInteger reads = new AtomicInteger();
        try (var manager = new CubeWorldLightManager(123, false, fixture.work::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                reads.incrementAndGet();
                return blocked;
            }
        }, (pos, epoch) -> {})) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(updates.stream().allMatch(pos -> manager.view(pos) != null));
            Level level = mockLevelWithPalettes(Level.class);
            for (int index = 0; index < 48; index++) {
                CubePos pos = CubePos.of(0, 1000000 + index, 0);
                fixture.sources.put(pos, new LevelCube(level, pos));
                fixture.targets.add(pos);
            }
            fixture.capture(manager);
            int initialReads = reads.get();
            CubePos independent = CubePos.of(10, 1000000, 0);
            addLoadedHalo(fixture, independent);
            fixture.targets.add(independent);
            for (int pass = 0; pass < 100 && manager.view(independent) == null; pass++) {
                updates.forEach(pos -> manager.changed(fixture.sources.get(pos)));
                manager.tick(fixture.sources, fixture.targets);
                fixture.drain();
            }
            assertNotNull(manager.view(independent), "Ready updates must leave both capture and calculation capacity for a first delivery");
            assertEquals(initialReads, reads.get(), "Searching past the blocked prefix must not eagerly request its missing terrain");
            assertTrue(!blocked.isDone());
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(updates.stream().anyMatch(pos -> manager.view(pos) != null), "First-delivery preference must retain update capacity");
        }
    }

    @Test
    void moreThanOnePreparedBatchWaitingForTerrainDoesNotHideAnUncapturedReadyHalo() {
        Fixture fixture = new Fixture(48);
        var blocked = new CompletableFuture<CubeLightBlockSnapshot>();
        AtomicInteger reads = new AtomicInteger();
        try (var manager = new CubeWorldLightManager(123, false, fixture.work::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                reads.incrementAndGet();
                return blocked;
            }
        }, (pos, epoch) -> {})) {
            fixture.capture(manager);
            int initialReads = reads.get();
            assertTrue(initialReads > 0);
            assertTrue(fixture.work.isEmpty());

            CubePos independent = CubePos.of(10, 1000000, 0);
            addLoadedHalo(fixture, independent);
            fixture.targets.add(independent);
            fixture.capture(manager);
            assertEquals(initialReads, reads.get(), "Lookahead only examines cached inputs; it must not start reads for every blocked target");
            assertEquals(1, fixture.work.size(), "Forty prepared targets behind the eight waiting jobs must leave capture capacity for the ready halo");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(independent));
            assertTrue(!blocked.isDone());
        }
    }

    @Test
    void eightPendingTerrainReadsYieldCapacityToAnAlreadyCapturedFartherCube() {
        Fixture fixture = new Fixture(8);
        var blocked = new CompletableFuture<CubeLightBlockSnapshot>();
        AtomicInteger reads = new AtomicInteger();
        try (var manager = new CubeWorldLightManager(123, false, fixture.work::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                reads.incrementAndGet();
                return blocked;
            }
        }, (pos, epoch) -> {})) {
            fixture.capture(manager);
            int initialReads = reads.get();
            assertTrue(initialReads > 0);
            assertTrue(fixture.work.isEmpty(), "The closest eight jobs are still waiting for terrain");

            CubePos independent = CubePos.of(10, 1000000, 0);
            addLoadedHalo(fixture, independent);
            fixture.targets.add(independent);
            Level level = mockLevelWithPalettes(Level.class);
            for (int index = 0; index < 40; index++) {
                CubePos pos = CubePos.of(20 + index * 4, 1000000, 0);
                fixture.sources.put(pos, new LevelCube(level, pos));
                fixture.targets.add(pos);
            }
            fixture.capture(manager);
            assertEquals(initialReads, reads.get(), "Looking for ready work must not initiate storage reads across the whole view");
            assertEquals(1, fixture.work.size(), "The independently ready cube must get a calculation slot before the closest inputs finish");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(independent));
            assertTrue(!blocked.isDone());
            assertEquals(initialReads, reads.get());
        }
    }

    @Test
    void readyBacklogReservesCapacityToStartTheClosestUnresolvedInputs() {
        Fixture fixture = new Fixture(0);
        Level level = mockLevelWithPalettes(Level.class);
        for (int x : new int[] { -8, -4 }) {
            CubePos pos = CubePos.of(x, 1000000, 0);
            fixture.sources.put(pos, new LevelCube(level, pos));
            fixture.targets.add(pos);
        }
        for (int index = 0; index < 12; index++) {
            CubePos pos = CubePos.of(index * 4, 1000000, 0);
            addLoadedHalo(fixture, pos);
            fixture.targets.add(pos);
        }
        Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> reads = new HashMap<>();
        try (var manager = new CubeWorldLightManager(123, false, fixture.work::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                return reads.computeIfAbsent(pos, ignored -> new CompletableFuture<>());
            }
        }, (pos, epoch) -> {})) {
            fixture.capture(manager);
            assertEquals(52, reads.size(), "Both closest cubes must start their 26 missing halo inputs despite the ready backlog");
            assertEquals(8, fixture.work.size(), "Two pending input groups leave all eight workers available for ready calculations");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(12, fixture.targets.stream().filter(pos -> manager.view(pos) != null).count());
            reads.forEach((pos, future) -> future.complete(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState())));
            for (int batch = 0; batch < 3; batch++) {
                fixture.drain();
                manager.tick(fixture.sources, fixture.targets);
            }
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null),
                    "The closest inputs and the remaining ready backlog must both progress");
        }
    }

    private static void addLoadedHalo(Fixture fixture, CubePos center) {
        Level level = mockLevelWithPalettes(Level.class);
        for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
            fixture.sources.put(pos, new LevelCube(level, pos));
        }
    }

    @Test
    void queuedCalculationsRetainPreparedSnapshotsWhenLoadedSourcesExceedTheCacheLimit() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(0);
        Level level = mockLevelWithPalettes(Level.class);
        CountingCube nearest = new CountingCube(level, CubePos.of(0, 1000000, 0));
        CountingCube nextPrepared = new CountingCube(level, CubePos.of(32, 1000000, 0));
        for (int column = 0; column < 300; column++) {
            for (int height = 0; height < 16; height++) {
                CubePos pos = CubePos.of(column * 4, 1000000 + height, 0);
                fixture.sources.put(pos, pos.equals(nearest.cc_getCubePos()) ? nearest
                        : pos.equals(nextPrepared.cc_getCubePos()) ? nextPrepared : new LevelCube(level, pos));
                if (height == 0) {
                    fixture.targets.add(pos);
                }
            }
        }
        try (var manager = fixture.manager(true, 4096)) {
            fixture.capture(manager);
            int preparedReads = nearest.sectionReads;
            int nextPreparedReads = nextPrepared.sectionReads;
            assertTrue(preparedReads > 0, "The nearest source must already be captured while its calculation waits");
            assertTrue(nextPreparedReads > 0, "The following preparation batch must have captured inputs before the cache fills");
            for (int tick = 0; tick < 1000; tick++) {
                manager.tick(fixture.sources, fixture.targets);
            }
            var captures = CubeWorldLightManager.class.getDeclaredField("captures");
            captures.setAccessible(true);
            assertEquals(4096, ((Map<?, ?>) captures.get(manager)).size(), "Exercise eviction while retaining the hard snapshot bound");
            assertEquals(preparedReads, nearest.sectionReads,
                    "A distant backlog larger than the snapshot cache must not repeatedly evict and recapture the next batch");
            assertEquals(nextPreparedReads, nextPrepared.sectionReads,
                    "LRU lookahead must preserve the next batch even before it has a job owning detached inputs");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(fixture.targets.getFirst()));
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(fixture.targets.subList(0, 16).stream().allMatch(pos -> manager.view(pos) != null),
                    "The next prepared batch must progress as soon as the workers become available");
        }
    }

    @Test
    void aPermanentlyPendingFirstInputDoesNotPreventPreparingAndLightingLaterTargets() {
        Fixture fixture = new Fixture(0);
        Level level = mockLevelWithPalettes(Level.class);
        for (int index = 0; index < 20; index++) {
            CubePos pos = CubePos.of(index * 4, 1000000, 0);
            fixture.sources.put(pos, new LevelCube(level, pos));
            fixture.targets.add(pos);
        }
        var blocked = new CompletableFuture<CubeLightBlockSnapshot>();
        try (var manager = new CubeWorldLightManager(123, false, fixture.work::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                return pos.getX() <= 1 ? blocked
                        : CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
            }
        }, (pos, epoch) -> {})) {
            for (int batch = 0; batch < 5; batch++) {
                fixture.capture(manager);
                fixture.drain();
                manager.tick(fixture.sources, fixture.targets);
            }
            assertNull(manager.view(fixture.targets.getFirst()));
            assertTrue(fixture.targets.subList(1, fixture.targets.size()).stream().allMatch(pos -> manager.view(pos) != null),
                    "One unresolved terrain input must not block the capture window or the remaining worker slots");
        }
    }

    @Test
    void preparedBatchDrainsMoreThanEightCalculationsWithoutExceedingWorkerConcurrency() {
        Fixture fixture = new Fixture(40);
        try (var manager = fixture.manager(false)) {
            fixture.capture(manager);
            assertEquals(8, fixture.work.size(), "Bound worker concurrency even while new targets keep arriving");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(32, fixture.targets.stream().filter(pos -> manager.view(pos) != null).count());
            assertEquals(8, fixture.work.size());
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));
        }
    }

    @Test
    void verticalBatchSharesSkyIndexesAndEditsInvalidateOnlyTheChangedIndex() {
        Fixture fixture = new Fixture(4);
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            assertEquals(9, fixture.work.size(), "The same nine sky indexes serve every vertical target");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(13, fixture.submissions.get(), "Nine sky indexes plus four cube solves");
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));
            CubePos top = fixture.targets.getLast();
            LevelCube roof = (LevelCube) fixture.sources.get(top);
            roof.setBlockState(top.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
            manager.changed(roof);
            assertNull(manager.view(fixture.targets.getFirst()));
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(18, fixture.submissions.get(), "Rebuild one sky index and four dependent cubes");
            assertEquals(14, manager.view(fixture.targets.getFirst()).sample(LightLayer.SKY, fixture.targets.getFirst().asBlockPos(8, 8, 8))
                    .orElseThrow().exactValue().orElseThrow());
        }
    }

    @Test
    void changedCubeTakesPriorityOverAnExistingVerticalRelightBacklog() {
        Fixture fixture = new Fixture(12);
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            CubePos last = fixture.targets.getLast();
            LevelCube edited = (LevelCube) fixture.sources.get(last);
            edited.setBlockState(last.asBlockPos(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState());
            manager.changed(edited);
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(last), "An edit at the end of the distance order must join the first relight batch");
            assertEquals(15, manager.view(last).sample(LightLayer.BLOCK, last.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow());
            assertTrue(fixture.targets.stream().anyMatch(pos -> manager.view(pos) == null), "The background backlog still exists");
        }
    }

    @Test
    void pendingInputsOutsideTheNewTrackingViewDoNotBlockItsFirstLight() {
        assertOldInputsDoNotBlockFirstLight(false, false);
    }

    @Test
    void invalidatedPendingInputsDoNotBlockAnIndependentCubesFirstLight() {
        assertOldInputsDoNotBlockFirstLight(true, true);
    }

    @Test
    void distantPendingInputsYieldToThePlayersNewNearbyCube() {
        assertOldInputsDoNotBlockFirstLight(true, false);
    }

    @Test
    void aBacklogOfUrgentEditsSharesTheFirstBatchWithTheNewNearestCube() {
        Fixture fixture = new Fixture(40);
        try (var manager = fixture.manager(false)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));
            for (int index : new int[] { 5, 8, 11, 14, 17, 20, 23, 26, 29, 32, 35, 38 }) {
                CubePos pos = fixture.targets.get(index);
                LevelCube cube = (LevelCube) fixture.sources.get(pos);
                cube.setBlockState(pos.asBlockPos(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState());
                manager.changed(cube);
            }
            CubePos current = CubePos.of(10, 1000000, 0);
            fixture.sources.put(current, new LevelCube(mockLevelWithPalettes(Level.class), current));
            fixture.targets.addFirst(current);
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(current), "First light near the player must share capacity with the urgent edit backlog");
            assertTrue(fixture.targets.stream().anyMatch(pos -> manager.view(pos) == null), "The test must still have background relighting work");
        }
    }

    @Test
    void repeatedNearbyRelightsCannotStarveFirstLightFartherAway() {
        assertFirstDeliveryProgress(false);
    }

    @Test
    void aRevokedPublicationStillGetsFirstDeliveryPriorityUntilTheBodyWasSent() {
        assertFirstDeliveryProgress(true);
    }

    private void assertFirstDeliveryProgress(boolean previouslyPublished) {
        Fixture fixture = new Fixture(8);
        try (var manager = fixture.manager(false)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));

            CubePos firstDelivery = CubePos.of(10, 1000000, 0);
            LevelCube waitingForDelivery = new LevelCube(mockLevelWithPalettes(Level.class), firstDelivery);
            fixture.sources.put(firstDelivery, waitingForDelivery);
            fixture.targets.add(firstDelivery);
            if (previouslyPublished) {
                fixture.capture(manager);
                fixture.drain();
                manager.tick(fixture.sources, fixture.targets);
                assertNotNull(manager.view(firstDelivery));
                waitingForDelivery.setBlockState(firstDelivery.asBlockPos(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState());
                manager.changed(waitingForDelivery);
                assertNull(manager.view(firstDelivery), "The first publication was revoked before the player received its cube body");
            }
            CubePos near = fixture.targets.getFirst();
            LevelCube edited = (LevelCube) fixture.sources.get(near);
            for (int pass = 0; pass < 6; pass++) {
                edited.setBlockState(near.asBlockPos(8, 8, 8), (pass % 2 == 0 ? Blocks.GLOWSTONE : Blocks.AIR).defaultBlockState());
                manager.changed(edited);
                if (previouslyPublished) {
                    for (int tick = 0; tick < 100; tick++) {
                        manager.tick(fixture.sources, fixture.targets, Set.of(firstDelivery));
                    }
                } else {
                    fixture.capture(manager);
                }
                fixture.drain();
                if (previouslyPublished) {
                    manager.tick(fixture.sources, fixture.targets, Set.of(firstDelivery));
                } else {
                    manager.tick(fixture.sources, fixture.targets);
                }
            }
            assertNotNull(manager.view(firstDelivery), "Repeated nearby lighting updates must leave capacity for cubes the player has never received");
            assertEquals(previouslyPublished ? 15 : 0,
                    manager.view(firstDelivery).sample(LightLayer.BLOCK, firstDelivery.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow());
        }
    }

    private void assertOldInputsDoNotBlockFirstLight(boolean retainOldTargets, boolean invalidateOldInputs) {
        Fixture fixture = new Fixture(8);
        var oldInputs = new CompletableFuture<CubeLightBlockSnapshot>();
        List<CubePos> publications = new ArrayList<>();
        try (var manager = new CubeWorldLightManager(123, false, fixture.work::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                return pos.getX() < 5 ? oldInputs
                        : CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
            }
        }, (pos, epoch) -> publications.add(pos))) {
            fixture.capture(manager);
            assertTrue(fixture.work.isEmpty(), "All eight old jobs are waiting for their terrain inputs");
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) == null));

            CubePos current = CubePos.of(10, 1000000, 0);
            fixture.sources.put(current, new LevelCube(mockLevelWithPalettes(Level.class), current));
            if (invalidateOldInputs) {
                CubePos old = fixture.targets.getFirst();
                LevelCube edited = (LevelCube) fixture.sources.get(old);
                edited.setBlockState(old.asBlockPos(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState());
                manager.changed(edited);
            }
            if (!retainOldTargets) {
                fixture.targets.clear();
            }
            fixture.targets.addFirst(current);
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);

            assertNotNull(manager.view(current), "Obsolete jobs must not occupy every slot while the new view is ready to light");
            assertEquals(0, manager.view(current).sample(LightLayer.BLOCK, current.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow());
            assertTrue(!oldInputs.isDone(), "The new view must progress independently of the old terrain requests");
            var currentLight = manager.view(current);
            oldInputs.complete(CubeLightBlockSnapshot.uniform(CubePos.of(0, 1000000, 0), Blocks.AIR.defaultBlockState()));
            for (int pass = 0; pass < 5; pass++) {
                fixture.drain();
                manager.tick(fixture.sources, fixture.targets);
            }
            assertSame(currentLight, manager.view(current), "Finishing a retired request must not revoke the new view");
            assertNull(manager.status().lastFailure(), "Retiring a waiting calculation is not a lighting failure");
            if (!retainOldTargets) {
                assertEquals(List.of(current), publications, "Completing old shared inputs must not publish untracked cubes");
            }
        }
    }

    @Test
    void unchangedUnloadKeepsPublicationsAndRetainsSavedRoofWhenAnotherEditRebuildsTheIndex() {
        Fixture fixture = new Fixture(4);
        CubePos high = fixture.targets.getLast();
        ((LevelCube) fixture.sources.get(high)).setBlockState(high.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            CubePos low = fixture.targets.getFirst();
            var before = manager.view(low);
            assertNotNull(before);
            fixture.sources.remove(high);
            fixture.targets.remove(high);
            manager.tick(fixture.sources, fixture.targets);
            assertSame(before, manager.view(low), "Unloading an unchanged, saved input must not darken or relight its column");
            assertTrue(fixture.work.isEmpty());
            LevelCube edited = (LevelCube) fixture.sources.get(low);
            edited.setBlockState(low.asBlockPos(2, 2, 2), Blocks.GLOWSTONE.defaultBlockState());
            manager.changed(edited);
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(14, manager.view(low).sample(LightLayer.SKY, low.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow(),
                    "The acknowledged roof remains in the column foundation after live capture retirement");
        }
    }

    @Test
    void unloadWithAnUnobservedEditDiscardsTheOldFoundation() {
        Fixture fixture = new Fixture(4);
        CubePos high = fixture.targets.getLast();
        LevelCube roof = (LevelCube) fixture.sources.get(high);
        roof.setBlockState(high.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            roof.setBlockState(high.asBlockPos(8, 8, 8), Blocks.AIR.defaultBlockState());
            fixture.sources.remove(high);
            fixture.targets.remove(high);
            manager.tick(fixture.sources, fixture.targets);
            CubePos low = fixture.targets.getFirst();
            assertNull(manager.view(low));
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(15, manager.view(low).sample(LightLayer.SKY, low.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow());
        }
    }

    @Test
    void generatedSnapshotsMatchAuthoritativeTerrainIncludingSurfaceAndBothHeightSigns() {
        for (int y : new int[] { -1000000, 2, 3, 1000000 }) {
            CubePos pos = CubePos.of(-2, y, 4);
            CubeLightBlockSnapshot snapshot = CubeLightBlockSnapshot.generated(pos, 246813579);
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    for (int dy = 0; dy < 32; dy++) {
                        assertEquals(CubeTerrainGenerator.state(246813579, pos.minCubeX() + x, pos.minCubeY() + dy, pos.minCubeZ() + z),
                                snapshot.state(x, dy, z));
                    }
                }
            }
        }
    }

    private static final class CountingCube extends LevelCube {
        private int sectionReads;

        private CountingCube(Level level, CubePos pos) {
            super(level, pos);
        }

        @Override public LevelChunkSection[] getSections() {
            sectionReads++;
            return super.getSections();
        }
    }

    private static final class Fixture {
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final List<CubePos> targets = new ArrayList<>();
        private final Queue<Runnable> work = new ArrayDeque<>();
        private final AtomicInteger submissions = new AtomicInteger();
        private final Map<CubePos, CubeLightBlockSnapshot> disk = new HashMap<>();

        private Fixture(int count) {
            Level level = mockLevelWithPalettes(Level.class);
            for (int y = 0; y < count; y++) {
                CubePos pos = CubePos.of(0, 1000000 + y, 0);
                targets.add(pos);
                sources.put(pos, new LevelCube(level, pos));
            }
        }

        private CubeWorldLightManager manager(boolean sky) {
            return manager(sky, 0);
        }

        private CubeWorldLightManager manager(boolean sky, int maxSnapshots) {
            Executor worker = task -> {
                submissions.incrementAndGet();
                work.add(task);
            };
            CubeWorldLightManager.Storage storage = new CubeWorldLightManager.Storage() {
                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    return CompletableFuture.completedFuture(Map.of());
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    return CompletableFuture.completedFuture(disk.computeIfAbsent(pos, key -> CubeLightBlockSnapshot.generated(key, 123)));
                }
            };
            if (maxSnapshots == 0) {
                return new CubeWorldLightManager(123, sky, worker, storage, (pos, epoch) -> {});
            }
            try {
                var constructor = CubeWorldLightManager.class.getDeclaredConstructor(long.class, boolean.class, Executor.class,
                        CubeWorldLightManager.Storage.class, CubeWorldLightManager.Listener.class, int.class);
                constructor.setAccessible(true);
                return constructor.newInstance(123L, sky, worker, storage, (CubeWorldLightManager.Listener) (pos, epoch) -> {}, maxSnapshots);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        private void capture(CubeWorldLightManager manager) {
            // Owner-thread capture has a time budget. Do not make the test depend on machine speed or JIT warm-up.
            for (int pass = 0; pass < 100; pass++) {
                manager.tick(sources, targets);
            }
        }

        private void drain() {
            while (!work.isEmpty()) {
                work.remove().run();
            }
        }
    }
}
