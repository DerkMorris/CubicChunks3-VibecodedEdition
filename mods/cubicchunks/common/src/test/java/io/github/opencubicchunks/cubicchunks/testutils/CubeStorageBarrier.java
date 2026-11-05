package io.github.opencubicchunks.cubicchunks.testutils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;

/** Holds the real I/O worker without replacing its write/flush/close implementation. */
public final class CubeStorageBarrier implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);

    public static CubeStorage storage(Class<?> owner, Object instance, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (CubeStorage) field.get(instance);
    }

    public CubeStorageBarrier(CubeStorage storage) throws Exception {
        var field = CubeStorage.class.getDeclaredField("executor");
        field.setAccessible(true);
        CountDownLatch entered = new CountDownLatch(1);
        ((ExecutorService) field.get(storage)).execute(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("Storage barrier did not start");
        }
    }

    @Override public void close() {
        release.countDown();
    }
}
