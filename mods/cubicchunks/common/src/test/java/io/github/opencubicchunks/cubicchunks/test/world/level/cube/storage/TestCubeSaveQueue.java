package io.github.opencubicchunks.cubicchunks.test.world.level.cube.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
}
