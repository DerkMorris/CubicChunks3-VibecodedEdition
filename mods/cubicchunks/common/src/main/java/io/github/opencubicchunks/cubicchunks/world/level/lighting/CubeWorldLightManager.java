package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/** Automatic per-cube lighting. Only immutable snapshots and disk data cross to the calculation worker. */
public final class CubeWorldLightManager implements AutoCloseable {
    private static final int CAPTURES_PER_TICK = 32;
    private static final long CAPTURE_NANOS = 4_000_000;
    private static final int MAX_JOBS = 8;
    private static final int NOTIFICATIONS_PER_TICK = 16;
    private static final int MAX_SNAPSHOTS = 4096;
    private static final int DISK_CACHE_SIZE = 128;
    private static final int RETRY_TICKS = 40;
    private final Thread owner = Thread.currentThread();
    private final long seed;
    private final boolean sky;
    private final Executor worker;
    private final Storage storage;
    private final Listener listener;
    private final Map<CubePos, Stamp> sources = new LinkedHashMap<>();
    private final Long2ObjectOpenHashMap<Set<CubePos>> sourceColumns = new Long2ObjectOpenHashMap<>();
    private final Map<CubePos, Capture> captures = new HashMap<>();
    private final Map<CubePos, Entry> views = new HashMap<>();
    private final Set<CubePos> dirty = new LinkedHashSet<>();
    private final Map<CubePos, Long> versions = new HashMap<>();
    private final Map<CubePos, Long> epochs = new HashMap<>();
    private final Map<CubePos, Long> notifications = new LinkedHashMap<>();
    private final Map<CubePos, Long> retry = new HashMap<>();
    private final Map<CubePos, CompletableFuture<Map<Integer, CubeSkyOcclusion>>> columns = new LinkedHashMap<>();
    private final Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> diskBlocks = new LinkedHashMap<>();
    private final Map<CubePos, CompletableFuture<CubeWorldSkyColumn>> skyIndexes = new LinkedHashMap<>();
    private final Map<CubePos, Job> jobs = new LinkedHashMap<>();
    private final Set<CubePos> urgent = new LinkedHashSet<>();
    private List<CubePos> targets = List.of();
    private @Nullable String failure;
    private long epoch;
    private long tick;
    private long notificationRetry;
    private boolean closed;

    public CubeWorldLightManager(long seed, boolean sky, Executor worker, Storage storage, Listener listener) {
        this.seed = seed;
        this.sky = sky;
        this.worker = worker;
        this.storage = storage;
        this.listener = listener;
    }

    public void tick(Map<CubePos, CubeAccess> available, List<CubePos> orderedTargets) {
        checkOwner();
        if (closed) {
            return;
        }
        tick++;
        targets = List.copyOf(orderedTargets);
        observe(available);
        Set<CubePos> targetSet = new HashSet<>(targets);
        views.keySet().retainAll(targetSet);
        epochs.keySet().retainAll(targetSet);
        notifications.keySet().retainAll(targetSet);
        retry.keySet().retainAll(targetSet);
        dirty.retainAll(targetSet);
        urgent.retainAll(targetSet);
        targets.stream().filter(pos -> !views.containsKey(pos)).forEach(dirty::add);
        Set<CubePos> neededVersions = new HashSet<>();
        sourceColumns.keySet().forEach((long key) -> neededVersions.add(CubePos.of(ChunkPos.getX(key), 0, ChunkPos.getZ(key))));
        jobs.values().forEach(job -> neededVersions.addAll(job.dependencies.keySet()));
        versions.keySet().retainAll(neededVersions);
        captureSome();
        for (Job job : new ArrayList<>(jobs.values())) {
            finish(job);
        }
        startNext();
        notifySome();
    }

    private void observe(Map<CubePos, CubeAccess> available) {
        Set<CubePos> changedColumns = new HashSet<>();
        for (CubePos pos : new ArrayList<>(sources.keySet())) {
            if (!available.containsKey(pos)) {
                retireSource(pos, changedColumns);
            }
        }
        available.forEach((pos, cube) -> {
            Stamp previous = sources.get(pos);
            if (previous == null || previous.cube != cube || previous.revision != cube.cc_getLightRevision()) {
                if (previous == null) {
                    sourceColumns.computeIfAbsent(columnKey(pos.getX(), pos.getZ()), ignored -> new LinkedHashSet<>()).add(pos);
                }
                sources.put(pos, new Stamp(cube, cube.cc_getLightRevision()));
                captures.remove(pos);
                diskBlocks.remove(pos);
                changedColumns.add(column(pos));
            }
        });
        changedColumns.forEach(this::invalidateColumn);
    }

    /** Sources leave this set only after unload saves are acknowledged. Their unchanged light inputs remain authoritative. */
    private void retireSource(CubePos pos, Set<CubePos> changedColumns) {
        Stamp source = sources.remove(pos);
        long keyXZ = columnKey(pos.getX(), pos.getZ());
        var columnSources = sourceColumns.get(keyXZ);
        columnSources.remove(pos);
        if (columnSources.isEmpty()) {
            sourceColumns.remove(keyXZ);
        }
        Capture capture = captures.remove(pos);
        if (capture == null || source.cube.cc_getLightRevision() != source.revision) {
            columns.remove(column(pos));
            diskBlocks.remove(pos);
            changedColumns.add(column(pos));
            return;
        }
        if (sky) {
            columns.computeIfPresent(column(pos), (key, saved) -> saved.thenApply(values -> {
                Map<Integer, CubeSkyOcclusion> merged = new HashMap<>(values);
                merged.put(pos.getY(), capture.sky);
                return Map.copyOf(merged);
            }));
        }
        if (capture.blocks != null) {
            diskBlocks.put(pos, CompletableFuture.completedFuture(capture.blocks));
        }
        trim(diskBlocks);
    }

    /** A roof can affect loaded cubes far below it. Revoke before any subsequent world reads or packets. */
    public void changed(CubeAccess cube) {
        checkOwner();
        if (!closed) {
            invalidateColumn(column(cube.cc_getCubePos()));
            for (CubePos pos : targets) {
                if (nearColumn(pos, cube.cc_getCubePos()) && Math.abs(pos.getY() - cube.cc_getCubePos().getY()) <= 1) {
                    urgent.add(pos);
                }
            }
        }
    }

    private void invalidateColumn(CubePos column) {
        versions.merge(column, 1L, Math::addExact);
        skyIndexes.remove(column);
        for (CubePos pos : targets) {
            if (nearColumn(pos, column)) {
                dirty.add(pos);
                retry.remove(pos);
                if (views.remove(pos) != null) {
                    changedPublication(pos);
                }
            }
        }
    }

    private void captureSome() {
        var budget = new CaptureBudget();
        for (CubePos priority : orderedWork()) {
            if (needsJob(priority)) {
                visitMissing(priority, budget);
                if (budget.full()) {
                    return;
                }
            }
        }
    }

    private static long columnKey(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    /** Only the nine halo columns can contribute terrain or a roof to this job. */
    private boolean visitMissing(CubePos center, @Nullable CaptureBudget budget) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var column = sourceColumns.get(columnKey(center.getX() + dx, center.getZ() + dz));
                if (column != null && !captureColumn(center, column, budget)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean captureColumn(CubePos center, Set<CubePos> column, @Nullable CaptureBudget budget) {
        for (CubePos pos : column) {
            if (needsCapture(center, pos)) {
                if (budget == null || budget.full()) {
                    return false;
                }
                if (captures.size() >= MAX_SNAPSHOTS && !captures.containsKey(pos)) {
                    captures.keySet().removeIf(cached -> !nearColumn(center, cached));
                    if (captures.size() >= MAX_SNAPSHOTS) {
                        failed(center, new IllegalStateException("Lighting source columns exceed the snapshot limit"));
                        return false;
                    }
                }
                captures.put(pos, captureSource(center, sources.get(pos).cube));
                budget.count++;
            }
        }
        return true;
    }

    private Capture captureSource(CubePos priority, CubeAccess cube) {
        // Distant heights need only their small sky summary, never a full 32^3 block copy.
        CubeLightBlockSnapshot blocks = nearCube(priority, cube.cc_getCubePos()) ? CubeLightBlockSnapshot.capture(cube) : null;
        return new Capture(blocks, sky ? cube.cc_snapshotSkyOcclusion() : null);
    }

    private boolean needsCapture(CubePos center, CubePos pos) {
        Capture capture = captures.get(pos);
        return nearCube(center, pos) ? capture == null || capture.blocks == null : sky && nearColumn(center, pos) && capture == null;
    }

    private void startNext() {
        for (CubePos pos : orderedWork()) {
            if (jobs.size() >= MAX_JOBS) {
                return;
            }
            if (needsJob(pos) && ready(pos)) {
                start(pos);
            }
        }
    }

    private List<CubePos> orderedWork() {
        if (urgent.isEmpty()) {
            return targets;
        }
        Set<CubePos> order = new LinkedHashSet<>(urgent);
        order.addAll(targets);
        return List.copyOf(order);
    }

    private boolean needsJob(CubePos pos) {
        return dirty.contains(pos) && !jobs.containsKey(pos) && tick >= retry.getOrDefault(pos, 0L);
    }

    private boolean ready(CubePos center) {
        return sources.containsKey(center) && visitMissing(center, null);
    }

    private void start(CubePos center) {
        try {
            CubeLightBounds bounds = halo(center);
            Map<CubePos, Long> dependencies = new HashMap<>();
            Map<CubePos, CompletableFuture<CubeWorldSkyColumn>> skyInputs = new HashMap<>();
            Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> blocks = new HashMap<>();
            for (CubePos pos : bounds.positions()) {
                CubePos column = column(pos);
                dependencies.put(column, versions.getOrDefault(column, 0L));
                if (sky) {
                    skyInputs.computeIfAbsent(column, key -> skyIndexes.computeIfAbsent(key, this::skyIndex));
                }
                Capture capture = captures.get(pos);
                blocks.put(pos, capture == null || capture.blocks == null ? diskBlocks.computeIfAbsent(pos, storage::cube)
                        : CompletableFuture.completedFuture(capture.blocks));
            }
            List<CompletableFuture<?>> inputs = new ArrayList<>(blocks.values());
            inputs.addAll(skyInputs.values());
            CompletableFuture<CubeLightView> result = CompletableFuture.allOf(inputs.toArray(CompletableFuture[]::new))
                    .thenApplyAsync(ignored -> solve(center, blocks, skyInputs), worker);
            jobs.put(center, new Job(center, sources.get(center), Map.copyOf(dependencies), result));
            trim(columns);
            trim(diskBlocks);
            trim(skyIndexes);
        } catch (RuntimeException exception) {
            failed(center, exception);
        }
    }

    private CompletableFuture<CubeWorldSkyColumn> skyIndex(CubePos column) {
        Map<Integer, CubeSkyOcclusion> overlay = new HashMap<>();
        for (CubePos pos : sourceColumns.getOrDefault(columnKey(column.getX(), column.getZ()), Set.of())) {
            Capture capture = captures.get(pos);
            if (capture != null) {
                overlay.put(pos.getY(), capture.sky);
            }
        }
        return columns.computeIfAbsent(column, storage::column).thenApplyAsync(saved -> {
            Map<Integer, CubeSkyOcclusion> merged = new HashMap<>(saved);
            merged.putAll(overlay);
            return new CubeWorldSkyColumn((x, z) -> storage.baselineHeight(seed, x, z), column.getX(), column.getZ(), merged,
                    storage.deepSky(column));
        }, worker);
    }

    private CubeLightView solve(
            CubePos center, Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> blocks,
            Map<CubePos, CompletableFuture<CubeWorldSkyColumn>> skyInputs
    ) {
        Map<CubePos, CubeLightBlockSnapshot> states = new HashMap<>();
        blocks.forEach((pos, future) -> states.put(pos, future.join()));
        Map<CubePos, CubeWorldSkyColumn> skyColumns = new HashMap<>();
        skyInputs.forEach((pos, future) -> skyColumns.put(pos, future.join()));
        return CubeWorldLightSolver.solve(center, states, (x, z) -> {
            CubeWorldSkyColumn column = skyColumns.get(CubePos.of(Coords.blockToCube(x), 0, Coords.blockToCube(z)));
            return column == null ? Integer.MAX_VALUE : column.edge(x, z);
        });
    }

    private void finish(Job finished) {
        if (!finished.result.isDone()) {
            return;
        }
        jobs.remove(finished.pos);
        try {
            CubeLightView view = finished.result.join();
            if (!finished.source.equals(sources.get(finished.pos)) || !current(finished.dependencies) || !targets.contains(finished.pos)) {
                return;
            }
            views.put(finished.pos, new Entry(finished.source, view));
            dirty.remove(finished.pos);
            urgent.remove(finished.pos);
            failure = null;
            changedPublication(finished.pos);
        } catch (RuntimeException exception) {
            failed(finished.pos, exception);
        }
    }

    private boolean current(Map<CubePos, Long> dependencies) {
        return dependencies.entrySet().stream().allMatch(entry -> entry.getValue().equals(versions.getOrDefault(entry.getKey(), 0L)));
    }

    private void failed(CubePos pos, RuntimeException exception) {
        failure = exception.toString();
        retry.put(pos, tick + RETRY_TICKS);
        // A transient read failure must not remain cached forever.
        columns.entrySet().removeIf(entry -> entry.getValue().isCompletedExceptionally());
        diskBlocks.entrySet().removeIf(entry -> entry.getValue().isCompletedExceptionally());
        skyIndexes.entrySet().removeIf(entry -> entry.getValue().isCompletedExceptionally());
    }

    private void changedPublication(CubePos pos) {
        epoch = Math.incrementExact(epoch);
        epochs.put(pos, epoch);
        notifications.put(pos, epoch);
    }

    private void notifySome() {
        if (tick < notificationRetry) {
            return;
        }
        int count = 0;
        for (CubePos pos : new ArrayList<>(notifications.keySet())) {
            if (count++ >= NOTIFICATIONS_PER_TICK) {
                return;
            }
            try {
                listener.changed(pos, notifications.get(pos));
                notifications.remove(pos);
            } catch (RuntimeException exception) {
                failure = exception.toString();
                notificationRetry = tick + RETRY_TICKS;
                return;
            }
        }
    }

    public @Nullable CubeLightView view(CubePos pos) {
        checkOwner();
        Entry entry = views.get(pos);
        return entry != null && entry.source.cube.cc_getLightRevision() == entry.source.revision ? entry.view : null;
    }

    public CubeLightPacketSnapshot capture(LevelCube cube) {
        checkOwner();
        Entry entry = views.get(cube.cc_getCubePos());
        CubeLightView view = entry != null && entry.source.cube == cube ? view(cube.cc_getCubePos()) : null;
        return new CubeLightPacketSnapshot(epochs.getOrDefault(cube.cc_getCubePos(), 0L), view, true);
    }

    public boolean republish(CubePos pos) {
        checkOwner();
        if (view(pos) == null) {
            return false;
        }
        for (CubePos candidate : targets) {
            if (nearColumn(pos, candidate) && view(candidate) != null) {
                changedPublication(candidate);
            }
        }
        return true;
    }

    public long epoch() {
        return epoch;
    }

    public CubeLightStatus status() {
        checkOwner();
        CubeLightStatus.Phase phase = !jobs.isEmpty() ? CubeLightStatus.Phase.RUNNING
                : dirty.isEmpty() ? CubeLightStatus.Phase.CURRENT : failure != null ? CubeLightStatus.Phase.RETRY_WAIT : CubeLightStatus.Phase.DIRTY;
        return new CubeLightStatus(phase, null, failure == null ? 0 : 1, 0, failure, !notifications.isEmpty(), null);
    }

    @Override public void close() {
        closed = true;
        jobs.clear();
        views.clear();
        sources.clear();
        sourceColumns.clear();
        captures.clear();
        columns.clear();
        diskBlocks.clear();
        skyIndexes.clear();
        urgent.clear();
        notifications.clear();
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("World lighting must be accessed on its owner thread");
        }
    }

    private static <K, V> void trim(Map<K, V> cache) {
        var iterator = cache.keySet().iterator();
        while (cache.size() > DISK_CACHE_SIZE && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public static CubeLightBounds halo(CubePos center) {
        int min = -CubePos.MAX_COORDINATE_VALUE - 1;
        int max = CubePos.MAX_COORDINATE_VALUE;
        return new CubeLightBounds(CubePos.of(Math.max(min, center.getX() - 1), Math.max(min, center.getY() - 1), Math.max(min, center.getZ() - 1)),
                CubePos.of(Math.min(max, center.getX() + 1), Math.min(max, center.getY() + 1), Math.min(max, center.getZ() + 1)));
    }

    private static boolean nearColumn(CubePos left, CubePos right) {
        return Math.abs(left.getX() - right.getX()) <= 1 && Math.abs(left.getZ() - right.getZ()) <= 1;
    }

    private static boolean nearCube(CubePos left, CubePos right) {
        return nearColumn(left, right) && Math.abs(left.getY() - right.getY()) <= 1;
    }

    private static CubePos column(CubePos pos) {
        return CubePos.of(pos.getX(), 0, pos.getZ());
    }

    public interface Storage {
        default @Nullable IntFunction<CubeSkyOcclusion> deepSky(CubePos column) {
            return null;
        }

        default int baselineHeight(long seed, int x, int z) {
            return CubeTerrainGenerator.surface(seed, x, z);
        }

        CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos column);

        CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos);
    }

    @FunctionalInterface
    public interface Listener {
        void changed(CubePos pos, long epoch);
    }

    private static final class CaptureBudget {
        private final long deadline = System.nanoTime() + CAPTURE_NANOS;
        private int count;

        private boolean full() {
            return count >= CAPTURES_PER_TICK || count > 0 && System.nanoTime() >= deadline;
        }
    }

    private record Stamp(CubeAccess cube, long revision) {}

    private record Capture(@Nullable CubeLightBlockSnapshot blocks, @Nullable CubeSkyOcclusion sky) {}

    private record Entry(Stamp source, CubeLightView view) {}

    private record Job(CubePos pos, Stamp source, Map<CubePos, Long> dependencies, CompletableFuture<CubeLightView> result) {}
}
