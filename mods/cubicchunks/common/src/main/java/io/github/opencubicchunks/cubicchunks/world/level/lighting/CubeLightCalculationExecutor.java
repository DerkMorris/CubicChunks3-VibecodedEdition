package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounds only detached light calculations. Input and sky-index tasks must use the underlying worker directly. */
final class CubeLightCalculationExecutor {
    private final Executor worker;
    private final int parallelism;
    private final int queueCapacity;
    private final Queue<Task> queued = new ArrayDeque<>();
    private int active;

    CubeLightCalculationExecutor(Executor worker, int parallelism, int queueCapacity) {
        if (parallelism < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Calculation limits must be positive");
        }
        this.worker = worker;
        this.parallelism = parallelism;
        this.queueCapacity = queueCapacity;
    }

    /** waiting reads only the job's atomic state; user work and failure callbacks always run outside this monitor. */
    void execute(Runnable command, BooleanSupplier waiting, Consumer<Throwable> failure) {
        Task task = new Task(command, waiting, failure);
        boolean dispatch = false;
        boolean rejected = false;
        synchronized (this) {
            queued.removeIf(candidate -> !candidate.waiting.getAsBoolean());
            if (!waiting.getAsBoolean()) {
                return;
            }
            if (active < parallelism) {
                active++;
                dispatch = true;
            } else if (queued.size() < queueCapacity) {
                queued.add(task);
            } else {
                rejected = true;
            }
        }
        if (rejected) {
            failure.accept(new RejectedExecutionException("Lighting calculation queue exceeds the prepared-job limit"));
        } else if (dispatch) {
            dispatch(task);
        }
    }

    private void dispatch(Task task) {
        try {
            worker.execute(() -> {
                task.started.set(true);
                try {
                    if (task.waiting.getAsBoolean()) {
                        task.command.run();
                    }
                } catch (Throwable failure) {
                    task.failure.accept(failure);
                } finally {
                    finished();
                }
            });
        } catch (RuntimeException | Error failure) {
            // A synchronous worker may have already run its wrapper; never release that permit twice.
            if (!task.started.get()) {
                try {
                    task.failure.accept(failure);
                } finally {
                    finished();
                }
            } else {
                throw failure;
            }
        }
    }

    private void finished() {
        Task next;
        synchronized (this) {
            do {
                next = queued.poll();
            } while (next != null && !next.waiting.getAsBoolean());
            if (next == null) {
                active--;
            }
        }
        if (next != null) {
            dispatch(next);
        }
    }

    private static final class Task {
        private final Runnable command;
        private final BooleanSupplier waiting;
        private final Consumer<Throwable> failure;
        private final AtomicBoolean started = new AtomicBoolean();

        private Task(Runnable command, BooleanSupplier waiting, Consumer<Throwable> failure) {
            this.command = command;
            this.waiting = waiting;
            this.failure = failure;
        }
    }
}
