package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeLightCalculationQueue extends BaseTest {
    @Test
    void thirtyTwoPreparedCalculationsExecuteWithAtMostEightConcurrentWorkers() throws Exception {
        Queue<Runnable> worker = new ConcurrentLinkedQueue<>();
        var calculations = new CubeLightCalculationExecutor(worker::add, 8, 32);
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var completed = new AtomicInteger();
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int index = 0; index < 32; index++) {
            calculations.execute(() -> {
                assertFalse(Thread.holdsLock(calculations));
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Workers were not released");
                    }
                    completed.incrementAndGet();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                } finally {
                    running.decrementAndGet();
                }
            }, () -> true, failures::add);
        }
        assertEquals(8, worker.size(), "Only eight wrappers may enter the underlying executor");
        List<Thread> threads = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                Runnable task = worker.remove();
                threads.add(Thread.ofPlatform().start(task));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(8, running.get());
            assertTrue(worker.isEmpty(), "The remaining prepared calculations wait inside the limiter");
        } finally {
            release.countDown();
            for (Thread thread : threads) {
                thread.join(5000);
            }
        }
        drain(worker);
        assertEquals(32, completed.get());
        assertEquals(8, peak.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void retiredQueuedJobsArePrunedBeforeAnotherPreparedBatchIsAdmitted() {
        Queue<Runnable> worker = new ConcurrentLinkedQueue<>();
        var calculations = new CubeLightCalculationExecutor(worker::add, 8, 32);
        List<AtomicBoolean> waiting = new ArrayList<>();
        var oldCalculations = new AtomicInteger();
        var newCalculations = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int index = 0; index < 32; index++) {
            var live = new AtomicBoolean(true);
            waiting.add(live);
            calculations.execute(oldCalculations::incrementAndGet, live::get, failures::add);
        }
        waiting.forEach(live -> live.set(false));
        for (int index = 0; index < 32; index++) {
            calculations.execute(newCalculations::incrementAndGet, () -> true, failures::add);
        }
        assertEquals(8, worker.size(), "Retired already-dispatched wrappers retain their permits until drained");
        drain(worker);
        assertEquals(0, oldCalculations.get());
        assertEquals(32, newCalculations.get());
        assertTrue(failures.isEmpty(), "Canceled internal tasks must not consume the next prepared batch's queue capacity");
    }

    @Test
    void synchronousWorkersAndNestedSubmissionNeverRunWhileHoldingTheQueueLock() {
        var calculations = new CubeLightCalculationExecutor(Runnable::run, 1, 32);
        var completed = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        calculations.execute(() -> {
            assertFalse(Thread.holdsLock(calculations));
            completed.incrementAndGet();
            for (int index = 0; index < 32; index++) {
                calculations.execute(() -> {
                    assertFalse(Thread.holdsLock(calculations));
                    completed.incrementAndGet();
                }, () -> true, failures::add);
            }
        }, () -> true, failures::add);
        assertEquals(33, completed.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void underlyingWorkerRejectionFailsTheTaskAndDoesNotLeakItsPermit() {
        Queue<Runnable> worker = new ConcurrentLinkedQueue<>();
        var attempts = new AtomicInteger();
        var completed = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        var calculations = new CubeLightCalculationExecutor(command -> {
            if (attempts.getAndIncrement() == 0) {
                throw new RejectedExecutionException("Deliberate first rejection");
            }
            worker.add(command);
        }, 8, 32);
        for (int index = 0; index < 32; index++) {
            calculations.execute(completed::incrementAndGet, () -> true, failures::add);
        }
        assertEquals(8, worker.size());
        drain(worker);
        assertEquals(1, failures.size());
        assertEquals(31, completed.get());
    }

    @Test
    void rejectionDuringRefillFailsAnAlreadyAcceptedQueuedTaskAndKeepsDraining() {
        Queue<Runnable> worker = new ConcurrentLinkedQueue<>();
        var attempts = new AtomicInteger();
        var first = new CompletableFuture<Integer>();
        var second = new CompletableFuture<Integer>();
        var third = new CompletableFuture<Integer>();
        var calculations = new CubeLightCalculationExecutor(command -> {
            if (attempts.getAndIncrement() == 1) {
                throw new RejectedExecutionException("Deliberate queued rejection");
            }
            worker.add(command);
        }, 1, 32);
        calculations.execute(() -> first.complete(1), () -> true, first::completeExceptionally);
        calculations.execute(() -> second.complete(2), () -> true, second::completeExceptionally);
        calculations.execute(() -> third.complete(3), () -> true, third::completeExceptionally);
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(1, first.join());
        assertTrue(second.isCompletedExceptionally(), "Rejection after initial admission must complete the task's result");
        assertFalse(third.isDone());
        assertEquals(1, worker.size(), "Rejecting the second task releases its permit to the third task");
        drain(worker);
        assertEquals(3, third.join());
    }

    @Test
    void inputBacklogRemainsLimitedToEightJobsDespiteThirtyTwoPreparedSlots() {
        Queue<Runnable> worker = new ConcurrentLinkedQueue<>();
        Map<CubePos, CubeAccess> sources = sources(40);
        List<CubePos> targets = List.copyOf(sources.keySet());
        var pending = new CompletableFuture<CubeLightBlockSnapshot>();
        var reads = new AtomicInteger();
        try (var manager = new CubeWorldLightManager(123, false, worker::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                throw new AssertionError("Sky is disabled");
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                reads.incrementAndGet();
                return pending;
            }
        }, (pos, epoch) -> {})) {
            capture(manager, sources, targets);
            assertEquals(8 * 26, reads.get(), "The throughput change must not quadruple speculative native input groups");
            assertTrue(worker.isEmpty());
        }
        assertFalse(pending.isCancelled(), "Closing the manager must not cancel a shared terrain input");
    }

    @Test
    void aReadyPreparedBatchPublishesThirtyTwoCubesAfterOneWorkerDrain() {
        Queue<Runnable> worker = new ConcurrentLinkedQueue<>();
        Map<CubePos, CubeAccess> sources = sources(32);
        List<CubePos> targets = List.copyOf(sources.keySet());
        try (var manager = new CubeWorldLightManager(123, false, worker::add, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                throw new AssertionError("Sky is disabled");
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                return CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
            }
        }, (pos, epoch) -> {})) {
            capture(manager, sources, targets);
            assertEquals(8, worker.size());
            drain(worker);
            manager.tick(sources, targets);
            for (CubePos target : targets) {
                assertNotNull(manager.view(target));
            }
            assertEquals(32, manager.epoch(), "All results publish after the owner thread validates their source versions");
        }
    }

    private static Map<CubePos, CubeAccess> sources(int count) {
        Level level = mockLevelWithPalettes(Level.class);
        Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            CubePos pos = CubePos.of(index * 4, 1000000, 0);
            sources.put(pos, new LevelCube(level, pos));
        }
        return sources;
    }

    private static void capture(CubeWorldLightManager manager, Map<CubePos, CubeAccess> sources, List<CubePos> targets) {
        for (int tick = 0; tick < 100; tick++) {
            manager.tick(sources, targets);
        }
    }

    private static void drain(Queue<Runnable> worker) {
        while (!worker.isEmpty()) {
            worker.remove().run();
        }
    }
}
