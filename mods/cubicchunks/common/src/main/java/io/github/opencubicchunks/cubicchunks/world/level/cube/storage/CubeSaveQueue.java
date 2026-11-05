package io.github.opencubicchunks.cubicchunks.world.level.cube.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import net.minecraft.nbt.CompoundTag;

/** Bounded writes with acknowledgements applied only by the world owner, never by the I/O worker. */
public final class CubeSaveQueue<K> {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_WRITES = 32;
    private final Thread owner = Thread.currentThread();
    private final Map<K, Pending> pending = new LinkedHashMap<>();
    private final int limit;
    private final long byteLimit;
    private long bytes;
    private RuntimeException failure;

    public CubeSaveQueue() {
        this(MAX_WRITES, MAX_BYTES);
    }

    public CubeSaveQueue(int limit, long byteLimit) {
        if (limit <= 0 || byteLimit <= 0) {
            throw new IllegalArgumentException("Save queue limits must be positive");
        }
        this.limit = limit;
        this.byteLimit = byteLimit;
    }

    public boolean canSubmit(K key) {
        poll();
        return !pending.containsKey(key) && pending.size() < limit && bytes < byteLimit;
    }

    public boolean submit(K key, CompoundTag snapshot, Function<CompoundTag, CompletableFuture<Void>> writer, Runnable success, Runnable failed) {
        if (!canSubmit(key)) {
            return false;
        }
        long size = snapshot.sizeInBytes();
        if (size > byteLimit) {
            throw new IllegalArgumentException("Cube snapshot exceeds the save queue byte limit");
        }
        if (size > byteLimit - bytes) {
            return false;
        }
        CompletableFuture<Void> future;
        try {
            future = writer.apply(snapshot);
        } catch (RuntimeException exception) {
            future = CompletableFuture.failedFuture(exception);
        }
        pending.put(key, new Pending(future, size, success, failed));
        bytes += size;
        return true;
    }

    public boolean contains(K key) {
        poll();
        return pending.containsKey(key);
    }

    public boolean hasWork() {
        checkOwner();
        return !pending.isEmpty();
    }

    public void poll() {
        checkOwner();
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending save = iterator.next();
            if (!save.future().isDone()) {
                continue;
            }
            iterator.remove();
            bytes -= save.bytes();
            try {
                save.future().join();
                save.success().run();
            } catch (RuntimeException exception) {
                save.failed().run();
                if (failure == null) {
                    failure = new CompletionException("Asynchronous cube save failed", exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Explicit flush/close only; completion callbacks do not require the owner event loop to make progress. */
    public void awaitAll() {
        checkOwner();
        CompletableFuture.allOf(pending.values().stream().map(Pending::future).toArray(CompletableFuture[]::new)).handle((result, exception) -> null)
                .join();
        poll();
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Save acknowledgements must run on their owner thread");
        }
    }

    private record Pending(CompletableFuture<Void> future, long bytes, Runnable success, Runnable failed) {}
}
