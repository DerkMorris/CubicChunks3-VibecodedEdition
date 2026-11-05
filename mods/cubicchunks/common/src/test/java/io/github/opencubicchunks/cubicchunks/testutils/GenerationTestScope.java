package io.github.opencubicchunks.cubicchunks.testutils;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Isolates generation tests from unfinished cube persistence. This fixture does not validate production disk storage.
 * The real scheduler and generation stages still execute, and their first failure is reported without expensive OS diagnostics.
 */
public final class GenerationTestScope implements AutoCloseable {
    private static final ThreadLocal<GenerationTestScope> CURRENT = new ThreadLocal<>();
    private static final Map<Object, GenerationTestScope> STORAGE_SCOPES = new IdentityHashMap<>();

    private final Map<Long, CompoundTag> cubes = new ConcurrentHashMap<>();
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    private AssertionError failure;

    private GenerationTestScope() {}

    public static GenerationTestScope open() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A generation fixture is already active on this thread");
        }
        GenerationTestScope scope = new GenerationTestScope();
        CURRENT.set(scope);
        return scope;
    }

    public void attachStorage(Object storage) {
        synchronized (STORAGE_SCOPES) {
            STORAGE_SCOPES.put(storage, this);
        }
    }

    public static @Nullable GenerationTestScope storageScope(Object storage) {
        synchronized (STORAGE_SCOPES) {
            return STORAGE_SCOPES.get(storage);
        }
    }

    public Optional<CompoundTag> read(CloPos pos) {
        return Optional.ofNullable(cubes.get(pos.asLong())).map(CompoundTag::copy);
    }

    public void write(CloPos pos, @Nullable CompoundTag data) {
        if (data == null) {
            cubes.remove(pos.asLong());
        } else {
            cubes.put(pos.asLong(), data.copy());
        }
    }

    public static void checkProgress() {
        GenerationTestScope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        if (scope.failure != null) {
            throw scope.failure;
        }
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= scope.deadline) {
            throw new AssertionError("Generation fixture did not complete within 60 seconds");
        }
    }

    public static void failOnCrashReport(Throwable cause, String description) {
        GenerationTestScope scope = CURRENT.get();
        if (scope != null) {
            if (scope.failure == null) {
                scope.failure = new AssertionError("Generation failed: " + description, cause);
            }
            throw scope.failure;
        }
    }

    @Override public void close() {
        synchronized (STORAGE_SCOPES) {
            STORAGE_SCOPES.values().removeIf(scope -> scope == this);
        }
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }
}
