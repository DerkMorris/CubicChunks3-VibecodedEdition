package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.BoundedCubeLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.LightRange;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TestCubeLightManager extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-3, -1000, 5);
    private static final CubePos NEIGHBOR = CubePos.of(-2, -1000, 5);
    private static final CubeLightBounds ONE_CUBE = new CubeLightBounds(POSITION, POSITION);
    private static final CubeLightBounds TWO_CUBES = new CubeLightBounds(POSITION, NEIGHBOR);
    private static final BlockPos SAMPLE = POSITION.asBlockPos(16, 16, 16);

    @Test
    void publicationWaitsForOwnerValidationAndRejectsParallelSnapshots() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POSITION);
            cube.setBlockState(SAMPLE, Blocks.GLOWSTONE.defaultBlockState());
            cube.tryMarkSaved();
            long revision = cube.cc_getLightRevision();
            assertEquals(CubeLightQuery.unavailable(), fixture.query());
            CompletableFuture<CubeLightUpdate> rebuild = fixture.manager.rebuild(ONE_CUBE);
            assertFalse(rebuild.isDone());
            assertInstanceOf(IllegalStateException.class, failure(fixture.manager.rebuild(ONE_CUBE)));
            assertEquals(1, fixture.worker.tasks.size());
            fixture.runWorker();
            assertFalse(rebuild.isDone());
            assertEquals(CubeLightQuery.unavailable(), fixture.query());

            fixture.owner.runNext();
            assertEquals(CubeLightUpdate.PUBLISHED, rebuild.join());
            CubeLightQuery query = fixture.query();
            assertEquals(CubeLightQuery.State.CURRENT, query.state());
            assertTrue(query.isReady());
            assertEquals(OptionalInt.of(15), query.exactValue());
            assertEquals(revision, cube.cc_getLightRevision());
            assertFalse(cube.isUnsaved());
            assertFalse(cube.isLightCorrect());
            assertEquals(CubeLightQuery.unavailable(), fixture.manager.query(LightLayer.BLOCK, NEIGHBOR.asBlockPos(0, 0, 0)));
        }
    }

    @Test
    void editsBeforeComputationOrBeforePublicationRejectTheCapturedBatch() {
        for (boolean afterComputation : new boolean[] { false, true }) {
            try (Fixture fixture = new Fixture()) {
                LevelCube cube = fixture.add(POSITION);
                CompletableFuture<CubeLightUpdate> rebuild = fixture.manager.rebuild(ONE_CUBE);
                if (afterComputation) {
                    fixture.runWorker();
                }
                cube.setBlockState(SAMPLE, Blocks.GLOWSTONE.defaultBlockState());
                if (!afterComputation) {
                    fixture.runWorker();
                }
                fixture.owner.runNext();

                assertEquals(CubeLightUpdate.STALE, rebuild.join());
                assertStale(fixture.query());
            }
        }
    }

    @Test
    void anyDependencyEditInvalidatesEveryPublishedSampleUntilRebuild() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POSITION);
            LevelCube neighbor = fixture.add(NEIGHBOR);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(TWO_CUBES));
            assertEquals(CubeLightQuery.State.CURRENT, fixture.query().state());

            neighbor.setBlockState(NEIGHBOR.asBlockPos(0, 16, 16), Blocks.GLOWSTONE.defaultBlockState());
            assertStale(fixture.query());
            neighbor.setBlockState(NEIGHBOR.asBlockPos(0, 16, 16), Blocks.AIR.defaultBlockState());
            assertStale(fixture.query());
            CompletableFuture<CubeLightUpdate> next = fixture.manager.rebuild(TWO_CUBES);
            assertEquals(CubeLightQuery.unavailable(), fixture.query());
            fixture.finish();
            assertEquals(CubeLightUpdate.PUBLISHED, next.join());
            assertEquals(CubeLightQuery.State.CURRENT, fixture.query().state());
        }
    }

    @Test
    void unloadHolderReplacementAndCubeReplacementInvalidatePublication() {
        for (int change = 0; change < 3; change++) {
            try (Fixture fixture = new Fixture()) {
                LevelCube cube = fixture.add(POSITION);
                fixture.rebuild(ONE_CUBE);
                CubeLightManager.Source source = fixture.sources.get(POSITION);
                if (change == 0) {
                    fixture.sources.remove(POSITION);
                } else if (change == 1) {
                    fixture.sources.put(POSITION, new CubeLightManager.Source(new Object(), cube));
                } else {
                    fixture.sources.put(POSITION, new CubeLightManager.Source(source.holder(), newCube(POSITION)));
                }
                assertStale(fixture.query());
            }
        }
    }

    @Test
    void previouslyMissingDependencyBecomingLoadedRejectsAnInflightBatch() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POSITION);
            CompletableFuture<CubeLightUpdate> rebuild = fixture.manager.rebuild(TWO_CUBES);
            fixture.runWorker();
            fixture.add(NEIGHBOR);
            fixture.owner.runNext();

            assertEquals(CubeLightUpdate.STALE, rebuild.join());
            assertStale(fixture.query());
        }
    }

    @Test
    void closeFailsQueuedOrCompletedWorkAndPreventsLaterPublication() {
        for (boolean afterComputation : new boolean[] { false, true }) {
            try (Fixture fixture = new Fixture()) {
                fixture.add(POSITION);
                CompletableFuture<CubeLightUpdate> rebuild = fixture.manager.rebuild(ONE_CUBE);
                if (afterComputation) {
                    fixture.runWorker();
                }
                fixture.isOwner.set(false);
                fixture.manager.close();
                fixture.isOwner.set(true);
                assertInstanceOf(IllegalStateException.class, failure(rebuild));
                if (afterComputation) {
                    fixture.owner.runNext();
                } else {
                    fixture.runWorker();
                    assertTrue(fixture.owner.tasks.isEmpty());
                }
                assertEquals(CubeLightQuery.unavailable(), fixture.query());
                assertInstanceOf(IllegalStateException.class, failure(fixture.manager.rebuild(ONE_CUBE)));
            }
        }
    }

    @Test
    void rejectedWorkerOrOwnerDispatchReleasesTheBusySlot() {
        for (boolean rejectOwner : new boolean[] { false, true }) {
            try (Fixture fixture = new Fixture()) {
                fixture.add(POSITION);
                ManualExecutor rejecting = rejectOwner ? fixture.owner : fixture.worker;
                rejecting.rejection = new RejectedExecutionException("expected test rejection");
                CompletableFuture<CubeLightUpdate> rebuild = fixture.manager.rebuild(ONE_CUBE);
                if (rejectOwner) {
                    fixture.runWorker();
                }
                assertSame(rejecting.rejection, failure(rebuild));
                assertEquals(CubeLightQuery.unavailable(), fixture.query());
                rejecting.rejection = null;
                assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE_CUBE));
            }
        }
    }

    @Test
    void solverFailureCompletesOnOwnerAndPermitsRetry() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POSITION);
            IllegalStateException expected = new IllegalStateException("expected solver failure");
            CompletableFuture<CubeLightUpdate> rebuild = fixture.manager.rebuild(ONE_CUBE);
            try (MockedStatic<BoundedCubeLightSolver> solver = mockStatic(BoundedCubeLightSolver.class)) {
                solver.when(() -> BoundedCubeLightSolver.solve(any(), anyMap(), eq(CubeLightBoundary.UNKNOWN))).thenThrow(expected);
                fixture.runWorker();
            }
            assertFalse(rebuild.isDone());
            fixture.owner.runNext();
            assertSame(expected, failure(rebuild));
            assertEquals(CubeLightQuery.unavailable(), fixture.query());
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE_CUBE));
        }
    }

    @Test
    void revisionsFollowWrappedBlockEditsAndBulkInvalidationButNotCapturesOrSaves() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POSITION);
            ImposterProtoCube readOnly = new ImposterProtoCube(cube, false);
            ImposterProtoCube writable = new ImposterProtoCube(cube, true);
            CubeLightManager.Source source = fixture.sources.get(POSITION);
            fixture.sources.put(POSITION, new CubeLightManager.Source(source.holder(), readOnly));
            long initial = cube.cc_getLightRevision();
            cube.cc_snapshotSkyOcclusion();
            cube.cc_snapshotHeightmaps();
            cube.tryMarkSaved();
            assertEquals(initial, cube.cc_getLightRevision());
            fixture.rebuild(ONE_CUBE);
            readOnly.setBlockState(SAMPLE, Blocks.STONE.defaultBlockState());
            assertEquals(initial, readOnly.cc_getLightRevision());
            assertEquals(CubeLightQuery.State.CURRENT, fixture.query().state());

            writable.setBlockState(SAMPLE, Blocks.STONE.defaultBlockState());
            assertEquals(initial + 1, readOnly.cc_getLightRevision());
            assertStale(fixture.query());
            fixture.rebuild(ONE_CUBE);
            readOnly.cc_invalidateHeightmaps();
            assertEquals(initial + 2, writable.cc_getLightRevision());
            assertStale(fixture.query());
        }
    }

    @Test
    void captureRejectsWrongPositionsAndNonFullSourcesWithoutKeepingManagerBusy() {
        try (Fixture fixture = new Fixture()) {
            fixture.sources.put(POSITION, new CubeLightManager.Source(new Object(), newCube(NEIGHBOR)));
            assertInstanceOf(IllegalArgumentException.class, failure(fixture.manager.rebuild(ONE_CUBE)));
            Level level = mockLevelWithPalettes(Level.class);
            ProtoCube proto = new ProtoCube(POSITION, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            fixture.sources.put(POSITION, new CubeLightManager.Source(new Object(), proto));
            assertInstanceOf(IllegalArgumentException.class, failure(fixture.manager.rebuild(ONE_CUBE)));
            fixture.add(POSITION);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE_CUBE));
        }
    }

    @Test
    void ownerThreadAndQueryStateContractsAreExplicit() {
        try (Fixture fixture = new Fixture()) {
            fixture.isOwner.set(false);
            assertThrows(IllegalStateException.class, () -> fixture.manager.rebuild(ONE_CUBE));
            assertThrows(IllegalStateException.class, fixture::query);
            fixture.isOwner.set(true);
        }
        CubeLightQuery uncertain = new CubeLightQuery(CubeLightQuery.State.CURRENT, Optional.of(new LightRange(0, 15)));
        assertFalse(uncertain.isReady());
        assertTrue(uncertain.exactValue().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CubeLightQuery(CubeLightQuery.State.CURRENT, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightQuery(CubeLightQuery.State.STALE, Optional.of(new LightRange(0, 0))));
    }

    @Test
    void epochsRevokeOldDomainsAndTickValidationNotifiesWithoutAQuery() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POSITION);
            fixture.rebuild(TWO_CUBES);
            assertEquals(List.of(new LightChange(1, Set.of(POSITION)), new LightChange(2, Set.of(POSITION))), fixture.changes);
            fixture.manager.view(POSITION);
            assertEquals(2, fixture.manager.epoch());

            fixture.add(NEIGHBOR);
            fixture.manager.validate();
            assertEquals(new LightChange(3, Set.of(POSITION)), fixture.changes.getLast());
            fixture.manager.validate();
            assertEquals(3, fixture.manager.epoch(), "Repeated validation must not duplicate a revocation");
            fixture.rebuild(ONE_CUBE);
            fixture.rebuild(new CubeLightBounds(NEIGHBOR, NEIGHBOR));
            assertEquals(new LightChange(6, Set.of(POSITION, NEIGHBOR)), fixture.changes.get(fixture.changes.size() - 2));
            assertEquals(new LightChange(7, Set.of(NEIGHBOR)), fixture.changes.getLast());
            fixture.manager.close();
            assertEquals(new LightChange(8, Set.of(NEIGHBOR)), fixture.changes.getLast());
            fixture.manager.close();
            assertEquals(8, fixture.manager.epoch(), "Closing twice must not create a new publication epoch");
        }
    }

    private static Throwable failure(CompletableFuture<?> future) {
        assertTrue(future.isDone(), "A rejected or failed operation must finish its future");
        return assertThrows(CompletionException.class, future::join).getCause();
    }

    private static void assertStale(CubeLightQuery query) {
        assertEquals(CubeLightQuery.State.STALE, query.state());
        assertTrue(query.range().isEmpty());
        assertFalse(query.isReady());
        assertTrue(query.exactValue().isEmpty());
    }

    private static LevelCube newCube(CubePos pos) {
        return new LevelCube(mockLevelWithPalettes(Level.class), pos);
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private RuntimeException rejection;

        @Override public void execute(Runnable command) {
            if (this.rejection != null) {
                throw this.rejection;
            }
            this.tasks.add(command);
        }

        private void runNext() {
            this.tasks.remove().run();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Map<CubePos, CubeLightManager.Source> sources = new HashMap<>();
        private final AtomicBoolean isOwner = new AtomicBoolean(true);
        private final ManualExecutor worker = new ManualExecutor();
        private final ManualExecutor owner = new ManualExecutor();
        private final List<LightChange> changes = new ArrayList<>();
        private final CubeLightManager manager = new CubeLightManager(pos -> {
            assertTrue(this.isOwner.get(), "Live holders must never be read on the worker");
            return this.sources.get(pos);
        }, this.isOwner::get, this.worker, this.owner, (epoch, affected) -> this.changes.add(new LightChange(epoch, affected)));

        private LevelCube add(CubePos pos) {
            LevelCube cube = newCube(pos);
            this.sources.put(pos, new CubeLightManager.Source(new Object(), cube));
            return cube;
        }

        private CubeLightQuery query() {
            return this.manager.query(LightLayer.BLOCK, SAMPLE);
        }

        private void runWorker() {
            this.isOwner.set(false);
            try {
                this.worker.runNext();
            } finally {
                this.isOwner.set(true);
            }
        }

        private void finish() {
            runWorker();
            this.owner.runNext();
        }

        private CubeLightUpdate rebuild(CubeLightBounds bounds) {
            CompletableFuture<CubeLightUpdate> result = this.manager.rebuild(bounds);
            finish();
            return result.join();
        }

        @Override public void close() {
            this.manager.close();
        }
    }

    private record LightChange(long epoch, Set<CubePos> affected) {}
}
