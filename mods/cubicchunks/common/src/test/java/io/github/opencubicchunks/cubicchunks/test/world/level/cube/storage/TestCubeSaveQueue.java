package io.github.opencubicchunks.cubicchunks.test.world.level.cube.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeSaveQueue;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class TestCubeSaveQueue extends BaseTest {
    @Test
    void delayedWritesBoundSlotsAndRunAcknowledgementsOnlyOnOwner() {
        CubeSaveQueue<Integer> queue = new CubeSaveQueue<>(1, 4096);
        CompletableFuture<Void> disk = new CompletableFuture<>();
        AtomicInteger acknowledged = new AtomicInteger();
        assertTrue(queue.submit(1, new CompoundTag(), tag -> disk, acknowledged::incrementAndGet, () -> {}));
        assertFalse(queue.canSubmit(1));
        assertFalse(queue.canSubmit(2));
        CompletableFuture.runAsync(() -> disk.complete(null)).join();
        assertEquals(0, acknowledged.get());
        queue.poll();
        assertEquals(1, acknowledged.get());
        assertTrue(queue.canSubmit(2));
        assertFalse(queue.hasWork());
    }

    @Test
    void byteBudgetDefersASecondSnapshotUntilTheFirstIsAcknowledged() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("data", new byte[1024]);
        CubeSaveQueue<Integer> queue = new CubeSaveQueue<>(10, tag.sizeInBytes());
        CompletableFuture<Void> disk = new CompletableFuture<>();
        assertTrue(queue.submit(1, tag, data -> disk, () -> {}, () -> {}));
        assertFalse(queue.submit(2, tag, data -> {
            throw new AssertionError("Must not submit beyond the byte budget");
        }, () -> {}, () -> {}));
        disk.complete(null);
        queue.awaitAll();
        assertTrue(queue.submit(2, tag, data -> CompletableFuture.completedFuture(null), () -> {}, () -> {}));
        queue.awaitAll();
    }

    @Test
    void failuresRestoreOwnerStateAndRemainVisibleToFlushAndLaterSaves() {
        CubeSaveQueue<Integer> queue = new CubeSaveQueue<>();
        CompletableFuture<Void> disk = new CompletableFuture<>();
        AtomicInteger dirty = new AtomicInteger();
        queue.submit(1, new CompoundTag(), tag -> disk, () -> {}, dirty::incrementAndGet);
        CompletableFuture.runAsync(() -> disk.completeExceptionally(new IOException("Disk full"))).join();
        assertEquals(0, dirty.get());
        assertThrows(CompletionException.class, queue::awaitAll);
        assertEquals(1, dirty.get());
        assertThrows(CompletionException.class, () -> queue.canSubmit(2));
        assertThrows(CompletionException.class, queue::awaitAll);
    }

    @Test
    void blockingSubmissionsPipelineUntilTheSlotLimit() throws Exception {
        CompletableFuture<Void> disk = new CompletableFuture<>();
        CountDownLatch firstBatch = new CountDownLatch(2);
        AtomicInteger acknowledged = new AtomicInteger();
        CompletableFuture<Void> owner = CompletableFuture.runAsync(() -> {
            CubeSaveQueue<Integer> queue = new CubeSaveQueue<>(2, 4096);
            for (int key = 0; key < 5; key++) {
                int index = key;
                queue.submitBlocking(key, new CompoundTag(), tag -> {
                    if (index == 2) {
                        assertEquals(2, acknowledged.get(), "A full queue must be acknowledged before admitting another write");
                    }
                    firstBatch.countDown();
                    return disk;
                }, acknowledged::incrementAndGet, () -> {});
            }
            queue.awaitAll();
            assertFalse(queue.hasWork());
        });
        try {
            assertTrue(firstBatch.await(5, TimeUnit.SECONDS), "Two writes must be queued before either finishes");
        } finally {
            disk.complete(null);
            owner.get(5, TimeUnit.SECONDS);
        }
        assertEquals(5, acknowledged.get());
    }

    @Test
    void blockingSubmissionsRespectKeyOrderAndRemainingByteBudget() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("data", new byte[1024]);
        for (boolean sameKey : new boolean[] { true, false }) {
            CompletableFuture<Void> disk = new CompletableFuture<>();
            CountDownLatch submitted = new CountDownLatch(1);
            AtomicInteger acknowledged = new AtomicInteger();
            CompletableFuture<Void> owner = CompletableFuture.runAsync(() -> {
                // Distinct keys fit the slot limit and pass canSubmit, but their combined snapshots exceed the byte budget.
                CubeSaveQueue<Integer> queue = new CubeSaveQueue<>(10, tag.sizeInBytes() * 2 - 1);
                queue.submitBlocking(1, tag, data -> {
                    submitted.countDown();
                    return disk;
                }, acknowledged::incrementAndGet, () -> {});
                queue.submitBlocking(sameKey ? 1 : 2, tag, data -> {
                    assertEquals(1, acknowledged.get());
                    return CompletableFuture.completedFuture(null);
                }, acknowledged::incrementAndGet, () -> {});
                queue.awaitAll();
            });
            try {
                assertTrue(submitted.await(5, TimeUnit.SECONDS));
            } finally {
                disk.complete(null);
                owner.get(5, TimeUnit.SECONDS);
            }
            assertEquals(2, acknowledged.get());
        }
    }

    @Test
    void blockingSubmissionPropagatesFailuresAndRejectsOversizedSnapshots() {
        CubeSaveQueue<Integer> queue = new CubeSaveQueue<>(1, 4096);
        CompletableFuture<Void> disk = new CompletableFuture<>();
        AtomicInteger dirty = new AtomicInteger();
        queue.submitBlocking(1, new CompoundTag(), tag -> disk, () -> {}, dirty::incrementAndGet);
        disk.completeExceptionally(new IOException("Disk full"));
        assertThrows(CompletionException.class, () -> queue.submitBlocking(2, new CompoundTag(), tag -> {
            throw new AssertionError("A failed save must stop later submissions");
        }, () -> {}, () -> {}));
        assertEquals(1, dirty.get());

        CubeSaveQueue<Integer> bounded = new CubeSaveQueue<>(1, 1024);
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("data", new byte[1024]);
        assertThrows(IllegalArgumentException.class, () -> bounded.submitBlocking(1, oversized, tag -> {
            throw new AssertionError("Oversized snapshots must never reach disk");
        }, () -> {}, () -> {}));
        assertFalse(bounded.hasWork());
    }
}
