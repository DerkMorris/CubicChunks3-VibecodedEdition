package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeLightScheduling extends BaseTest {
    private static final CubePos POS = CubePos.of(-2, -1000, 3);
    private static final CubePos NEXT = CubePos.of(-1, -1000, 3);
    private static final CubePos OTHER = CubePos.of(0, -1000, 3);
    private static final CubeLightBounds ONE = new CubeLightBounds(POS, POS);
    private static final CubeLightBounds TWO = new CubeLightBounds(POS, NEXT);
    private static final BlockPos SAMPLE = POS.asBlockPos(16, 16, 16);

    @Test
    void managerRemainsIdleUntilAnExplicitDomainIsAccepted() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.ticks(100);
            assertEquals(CubeLightStatus.Phase.IDLE, fixture.manager.status().phase());
            assertNull(fixture.manager.status().bounds());
            assertFalse(fixture.manager.requestRefresh(POS));
            assertFalse(fixture.manager.republishCurrent(POS));
            assertEquals(0, fixture.worker.attempts);
            assertEquals(0, fixture.lookups);
        }
    }

    @Test
    void stableUnknownAndEntirelyMissingPublicationsDoNotRecompute() {
        for (boolean present : new boolean[] { false, true }) {
            try (Fixture fixture = new Fixture()) {
                if (present) {
                    fixture.add(POS);
                }
                assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE));
                fixture.ticks(100);
                assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
                assertEquals(1, fixture.worker.attempts);
                assertEquals(present ? CubeLightQuery.State.CURRENT : CubeLightQuery.State.UNAVAILABLE,
                        fixture.manager.query(LightLayer.SKY, SAMPLE).state());
                assertFalse(fixture.manager.query(LightLayer.SKY, SAMPLE).isReady());
            }
        }
    }

    @Test
    void blockEditsImmediatelyRevokeButOnlyOneTickCapturesTheirLatestState() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POS);
            fixture.rebuild(ONE);
            cube.setBlockState(SAMPLE, Blocks.STONE.defaultBlockState());
            fixture.manager.validate();
            assertNull(fixture.manager.view(POS));
            assertEquals(CubeLightStatus.Phase.DIRTY, fixture.manager.status().phase());
            assertEquals(1, fixture.worker.attempts);
            cube.setBlockState(SAMPLE, Blocks.GLOWSTONE.defaultBlockState());
            fixture.manager.validate();
            fixture.manager.tick();
            assertEquals(2, fixture.worker.attempts);
            assertEquals(1, fixture.worker.tasks.size());
            fixture.ticks(5);
            assertEquals(1, fixture.worker.tasks.size());
            fixture.finish();
            assertEquals(15, fixture.manager.query(LightLayer.BLOCK, SAMPLE).exactValue().orElseThrow());
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
        }
    }

    @Test
    void staleCompletionFinishesOneExplicitAttemptThenRetriesOnTheNextTick() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POS);
            CompletableFuture<CubeLightUpdate> explicit = fixture.manager.rebuild(ONE);
            fixture.runWorker();
            cube.setBlockState(SAMPLE, Blocks.GLOWSTONE.defaultBlockState());
            fixture.owner.runNext();
            assertEquals(CubeLightUpdate.STALE, explicit.join());
            assertEquals(CubeLightStatus.Phase.DIRTY, fixture.manager.status().phase());
            assertEquals(1, fixture.worker.attempts);
            assertTrue(fixture.worker.tasks.isEmpty());
            fixture.manager.tick();
            fixture.finish();
            assertEquals(CubeLightUpdate.STALE, explicit.join(), "Automatic retries must not change the original attempt's outcome");
            assertEquals(2, fixture.worker.attempts);
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
        }
    }

    @Test
    void missingArrivalUnloadAndIdentityReplacementRemainWatchedAfterEmptyResults() {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuild(TWO);
            LevelCube cube = fixture.add(POS);
            fixture.manager.tick();
            fixture.finish();
            assertNotNull(fixture.manager.view(POS));
            fixture.sources.remove(POS);
            fixture.manager.tick();
            fixture.finish();
            assertNull(fixture.manager.view(POS));
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
            fixture.sources.put(POS, new CubeLightManager.Source(new Object(), cube));
            fixture.manager.tick();
            fixture.finish();
            fixture.sources.put(POS, new CubeLightManager.Source(new Object(), cube));
            fixture.manager.tick();
            fixture.finish();
            fixture.add(NEXT);
            fixture.manager.tick();
            fixture.finish();
            assertEquals(6, fixture.worker.attempts);
            fixture.ticks(30);
            assertEquals(6, fixture.worker.attempts);
        }
    }

    @Test
    void busyAndInvalidRequestsCannotRetargetTheAcceptedDomain() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            CompletableFuture<CubeLightUpdate> first = fixture.manager.rebuild(ONE);
            CubeLightBounds other = new CubeLightBounds(NEXT, NEXT);
            assertInstanceOf(IllegalStateException.class, failure(fixture.manager.rebuild(other)));
            assertEquals(ONE, fixture.manager.status().bounds());
            fixture.finish();
            assertEquals(CubeLightUpdate.PUBLISHED, first.join());
            assertInstanceOf(NullPointerException.class, failure(fixture.manager.rebuild(null)));
            fixture.sources.put(NEXT, new CubeLightManager.Source(new Object(), fixture.sources.get(POS).cube()));
            assertInstanceOf(IllegalArgumentException.class, failure(fixture.manager.rebuild(other)));
            assertEquals(ONE, fixture.manager.status().bounds());
            assertNotNull(fixture.manager.view(POS));
            fixture.add(NEXT);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(other));
            assertEquals(other, fixture.manager.status().bounds());
        }
    }

    @Test
    void fiveCalculationFailuresBackOffThenSuspendUntilDependenciesChange() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POS);
            fixture.worker.rejection = new RejectedExecutionException("worker unavailable");
            RuntimeException original = fixture.worker.rejection;
            CompletableFuture<CubeLightUpdate> explicit = fixture.manager.rebuild(ONE);
            assertSame(fixture.worker.rejection, failure(explicit));
            int[] delays = { 20, 40, 80, 160 };
            for (int index = 0; index < delays.length; index++) {
                assertEquals(CubeLightStatus.Phase.RETRY_WAIT, fixture.manager.status().phase());
                assertEquals(index + 1, fixture.manager.status().consecutiveFailures());
                assertEquals(delays[index], fixture.manager.status().retryInTicks());
                fixture.ticks(delays[index] - 1);
                assertEquals(index + 1, fixture.worker.attempts);
                fixture.manager.tick();
                assertEquals(index + 2, fixture.worker.attempts);
            }
            assertEquals(CubeLightStatus.Phase.FAILED, fixture.manager.status().phase());
            assertEquals(5, fixture.manager.status().consecutiveFailures());
            assertEquals(0, fixture.manager.status().retryInTicks());
            assertTrue(fixture.manager.status().lastFailure().contains("worker unavailable"));
            fixture.ticks(500);
            assertEquals(5, fixture.worker.attempts);
            fixture.worker.rejection = null;
            cube.setBlockState(SAMPLE, Blocks.GLOWSTONE.defaultBlockState());
            fixture.manager.tick();
            fixture.finish();
            assertEquals(6, fixture.worker.attempts);
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
            assertEquals(0, fixture.manager.status().consecutiveFailures());
            assertNull(fixture.manager.status().lastFailure());
            assertSame(original, failure(explicit));
        }
    }

    @Test
    void explicitValidRebuildCanRetryWithoutWaitingOrChangingDependencies() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.worker.rejection = new RejectedExecutionException("retry explicitly");
            failure(fixture.manager.rebuild(ONE));
            assertEquals(20, fixture.manager.status().retryInTicks());
            fixture.worker.rejection = null;
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE));
            assertEquals(0, fixture.manager.status().consecutiveFailures());
            assertEquals(2, fixture.worker.attempts);
        }
    }

    @Test
    void malformedHolderCaptureSuspendsAndNewIdentitiesResetItsFailureBudget() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.rebuild(ONE);
            LevelCube wrongCube = fixture.add(NEXT);
            fixture.sources.put(POS, new CubeLightManager.Source(new Object(), wrongCube));
            fixture.manager.tick();
            assertEquals(1, fixture.manager.status().consecutiveFailures());
            assertTrue(fixture.manager.status().lastFailure().contains("requested position"));
            for (int delay : new int[] { 20, 40, 80, 160 }) {
                fixture.ticks(delay);
            }
            assertEquals(CubeLightStatus.Phase.FAILED, fixture.manager.status().phase());
            assertEquals(1, fixture.worker.attempts, "Invalid captures must never queue a worker");
            fixture.sources.put(POS, new CubeLightManager.Source(new Object(), wrongCube));
            fixture.manager.tick();
            assertEquals(1, fixture.manager.status().consecutiveFailures());
            assertEquals(CubeLightStatus.Phase.RETRY_WAIT, fixture.manager.status().phase());
            fixture.add(POS);
            fixture.manager.tick();
            fixture.finish();
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
            assertEquals(2, fixture.worker.attempts);
        }
    }

    @Test
    void rejectedOwnerDispatchUsesTheSameAutomaticBackoff() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.owner.rejection = new RejectedExecutionException("owner unavailable");
            CompletableFuture<CubeLightUpdate> explicit = fixture.manager.rebuild(ONE);
            fixture.runWorker();
            assertSame(fixture.owner.rejection, failure(explicit));
            assertEquals(CubeLightStatus.Phase.RETRY_WAIT, fixture.manager.status().phase());
            fixture.owner.rejection = null;
            fixture.ticks(19);
            assertEquals(1, fixture.worker.attempts);
            fixture.manager.tick();
            fixture.finish();
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
        }
    }

    @Test
    void notificationFailureAfterPublicationRetriesWithoutRecomputationOrFailingTheFuture() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.callback = () -> {
                if (fixture.manager.view(POS) != null) {
                    throw new IllegalStateException("send unavailable");
                }
            };
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE));
            assertNotNull(fixture.manager.view(POS));
            CubeLightStatus status = fixture.manager.status();
            assertEquals(CubeLightStatus.Phase.CURRENT, status.phase());
            assertEquals(0, status.consecutiveFailures());
            assertTrue(status.notificationPending());
            assertTrue(status.notificationFailure().contains("send unavailable"));
            long epoch = fixture.manager.epoch();
            int delivered = fixture.changes.size();
            fixture.callback = () -> {};
            fixture.ticks(19);
            assertEquals(delivered, fixture.changes.size());
            fixture.manager.tick();
            assertEquals(delivered + 1, fixture.changes.size());
            assertEquals(epoch, fixture.changes.getLast().epoch());
            assertEquals(1, fixture.worker.attempts);
            assertFalse(fixture.manager.status().notificationPending());
            assertNull(fixture.manager.status().notificationFailure());
        }
    }

    @Test
    void failedNotificationsCoalesceLatestEpochAndBoundRetargetingUntilDelivery() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.add(NEXT);
            fixture.add(OTHER);
            fixture.rebuild(ONE);
            fixture.callback = () -> {
                throw new IllegalStateException("transport failed");
            };
            CubeLightBounds nextBounds = new CubeLightBounds(NEXT, NEXT);
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(nextBounds));
            CubeLightBounds otherBounds = new CubeLightBounds(OTHER, OTHER);
            assertInstanceOf(IllegalStateException.class, failure(fixture.manager.rebuild(otherBounds)));
            assertEquals(nextBounds, fixture.manager.status().bounds());
            assertEquals(2, fixture.worker.attempts);
            fixture.callback = () -> {};
            fixture.ticks(20);
            assertEquals(Set.of(POS, NEXT), fixture.changes.getLast().affected());
            assertEquals(fixture.manager.epoch(), fixture.changes.getLast().epoch());
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(otherBounds));
        }
    }

    @Test
    void observerReadReentrancyCannotStartNestedAttemptsOrBlockFutureCompletion() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POS);
            fixture.callback = () -> {
                fixture.manager.view(POS);
                fixture.manager.capture(cube);
                fixture.manager.status();
                fixture.manager.tick();
                assertInstanceOf(IllegalStateException.class, failure(fixture.manager.rebuild(ONE)));
            };
            assertEquals(CubeLightUpdate.PUBLISHED, fixture.rebuild(ONE));
            assertEquals(1, fixture.worker.attempts);
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
            assertFalse(fixture.manager.status().notificationPending());
        }
    }

    @Test
    void manualRefreshCoalescesWithoutRetargetingAndDefersWorkUntilTick() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.rebuild(ONE);
            assertFalse(fixture.manager.requestRefresh(NEXT));
            for (int repeat = 0; repeat < 10; repeat++) {
                assertTrue(fixture.manager.requestRefresh(POS));
            }
            assertEquals(1, fixture.worker.attempts);
            assertEquals(CubeLightStatus.Phase.DIRTY, fixture.manager.status().phase());
            fixture.manager.tick();
            fixture.finish();
            fixture.ticks(30);
            assertEquals(2, fixture.worker.attempts);
            assertEquals(CubeLightStatus.Phase.CURRENT, fixture.manager.status().phase());
        }
    }

    @Test
    void clientRecoveryRepublishesOnlyReadyCurrentAnchorsWithoutASolve() {
        try (Fixture fixture = new Fixture()) {
            LevelCube cube = fixture.add(POS);
            fixture.rebuild(ONE);
            assertFalse(fixture.manager.republishCurrent(POS), "Open unknown skylight is not a complete client publication");
            seal(cube);
            fixture.rebuild(ONE);
            long epoch = fixture.manager.epoch();
            int attempts = fixture.worker.attempts;
            assertFalse(fixture.manager.republishCurrent(NEXT));
            assertTrue(fixture.manager.republishCurrent(POS));
            assertEquals(epoch + 1, fixture.manager.epoch());
            assertEquals(attempts, fixture.worker.attempts);
            assertNotNull(fixture.manager.capture(cube).light());
            cube.cc_invalidateHeightmaps();
            assertFalse(fixture.manager.republishCurrent(POS));
            assertEquals(CubeLightStatus.Phase.DIRTY, fixture.manager.status().phase());
            fixture.manager.tick();
            assertFalse(fixture.manager.republishCurrent(POS));
            fixture.finish();
            fixture.callback = () -> {
                throw new IllegalStateException("retry notification");
            };
            assertTrue(fixture.manager.republishCurrent(POS));
            assertFalse(fixture.manager.republishCurrent(POS));
        }
    }

    @Test
    void closingOffOwnerCancelsQueuedWorkAndDiscardsAllAutomaticRetries() {
        try (Fixture fixture = new Fixture()) {
            fixture.add(POS);
            fixture.callback = () -> {
                throw new IllegalStateException("notify failed");
            };
            CompletableFuture<CubeLightUpdate> explicit = fixture.manager.rebuild(ONE);
            fixture.isOwner.set(false);
            fixture.manager.close();
            fixture.isOwner.set(true);
            assertInstanceOf(IllegalStateException.class, failure(explicit));
            fixture.runWorker();
            assertTrue(fixture.owner.tasks.isEmpty());
            fixture.ticks(100);
            CubeLightStatus status = fixture.manager.status();
            assertEquals(CubeLightStatus.Phase.CLOSED, status.phase());
            assertNull(status.bounds());
            assertFalse(status.notificationPending());
            assertNull(status.notificationFailure());
            assertFalse(fixture.manager.requestRefresh(POS));
            assertFalse(fixture.manager.republishCurrent(POS));
            assertEquals(1, fixture.worker.attempts);
        }
    }

    @Test
    void newSchedulingMethodsRequireTheOwnerThread() {
        try (Fixture fixture = new Fixture()) {
            fixture.isOwner.set(false);
            assertThrows(IllegalStateException.class, fixture.manager::tick);
            assertThrows(IllegalStateException.class, fixture.manager::status);
            assertThrows(IllegalStateException.class, () -> fixture.manager.requestRefresh(POS));
            assertThrows(IllegalStateException.class, () -> fixture.manager.republishCurrent(POS));
            fixture.isOwner.set(true);
        }
    }

    private static void seal(LevelCube cube) {
        for (var section : cube.getSections()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        cube.cc_invalidateHeightmaps();
    }

    private static Throwable failure(CompletableFuture<?> result) {
        assertTrue(result.isDone());
        return assertThrows(CompletionException.class, result::join).getCause();
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private RuntimeException rejection;
        private int attempts;

        @Override public void execute(Runnable command) {
            this.attempts++;
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
        private final List<Change> changes = new ArrayList<>();
        private Runnable callback = () -> {};
        private int lookups;
        private final CubeLightManager manager = new CubeLightManager(pos -> {
            assertTrue(this.isOwner.get());
            this.lookups++;
            return this.sources.get(pos);
        }, this.isOwner::get, this.worker, this.owner, (epoch, affected) -> {
            this.changes.add(new Change(epoch, affected));
            this.callback.run();
        });

        private LevelCube add(CubePos pos) {
            LevelCube cube = new LevelCube(mockLevelWithPalettes(Level.class), pos);
            this.sources.put(pos, new CubeLightManager.Source(new Object(), cube));
            return cube;
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

        private void ticks(int count) {
            for (int tick = 0; tick < count; tick++) {
                this.manager.tick();
            }
        }

        @Override public void close() {
            this.manager.close();
        }
    }

    private record Change(long epoch, Set<CubePos> affected) {}
}
