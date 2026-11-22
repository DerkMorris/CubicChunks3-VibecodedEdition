package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LightEngine;

/** Automatic per-cube lighting. Only immutable snapshots and disk data cross to the calculation worker. */
public final class CubeWorldLightManager implements AutoCloseable {
    private static final int CAPTURES_PER_TICK = 32;
    private static final long CAPTURE_NANOS = 4_000_000;
    private static final int MAX_JOBS = 32;
    private static final int MAX_RUNNING_JOBS = 8;
    private static final int INPUT_WAIT_SLOTS = MAX_RUNNING_JOBS;
    private static final int SKY_PREFETCH_LIMIT = 8;
    private static final int NOTIFICATIONS_PER_TICK = 16;
    private static final int MAX_SNAPSHOTS = 16384;
    private static final int SKY_CACHE_SIZE = 512;
    private static final int RETRY_TICKS = 40;
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("cubicchunks.smoke.lightDiagnostics");
    private final Thread owner = Thread.currentThread();
    private final long seed;
    private final boolean sky;
    private final int maxSnapshots;
    private final Executor worker;
    private final CubeLightCalculationExecutor calculations;
    private final Storage storage;
    private final Listener listener;
    private final Map<CubePos, Stamp> sources = new LinkedHashMap<>();
    private final Long2ObjectOpenHashMap<Set<CubePos>> sourceColumns = new Long2ObjectOpenHashMap<>();
    private final Map<CubePos, Capture> captures = new LinkedHashMap<>(256, 0.75F, true);
    private final Map<CubePos, Entry> views = new HashMap<>();
    private final Set<CubePos> dirty = new LinkedHashSet<>();
    private final Map<CubePos, Long> versions = new HashMap<>();
    private final Map<CubePos, Long> blockVersions = new HashMap<>();
    private final Map<CubePos, Stamp> invalidatedSources = new HashMap<>();
    private final Map<CubePos, Long> epochs = new HashMap<>();
    private final Map<CubePos, Long> notifications = new LinkedHashMap<>();
    private final Map<CubePos, Long> retry = new HashMap<>();
    private final Map<CubePos, CompletableFuture<Map<Integer, CubeSkyOcclusion>>> columns = new LinkedHashMap<>();
    private final Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> diskBlocks = new LinkedHashMap<>();
    private final Map<CubePos, CompletableFuture<CubeWorldSkyColumn>> skyIndexes = new LinkedHashMap<>();
    private final Set<CompletableFuture<CubeWorldSkyColumn>> skyPrefetchPending = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<CubePos, Job> jobs = new LinkedHashMap<>();
    private final Set<CubePos> urgent = new LinkedHashSet<>();
    private final Set<CubePos> fullyCaptured = new HashSet<>();
    private final @Nullable Map<String, Long> diagnosticTotals = DIAGNOSTICS ? new LinkedHashMap<>() : null;
    private final @Nullable Map<String, Long> diagnosticTick = DIAGNOSTICS ? new LinkedHashMap<>() : null;
    private List<CubePos> targets = List.of();
    private Set<CubePos> targetSet = Set.of();
    private Set<CubePos> firstDeliveries = Set.of();
    private @Nullable String failure;
    private long epoch;
    private long tick;
    private long notificationRetry;
    private boolean closed;

    public CubeWorldLightManager(long seed, boolean sky, Executor worker, Storage storage, Listener listener) {
        this(seed, sky, worker, storage, listener, MAX_SNAPSHOTS);
    }

    CubeWorldLightManager(long seed, boolean sky, Executor worker, Storage storage, Listener listener, int maxSnapshots) {
        if (maxSnapshots < 1 || maxSnapshots > MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("Lighting snapshot capacity must be between 1 and " + MAX_SNAPSHOTS);
        }
        this.seed = seed;
        this.sky = sky;
        this.maxSnapshots = maxSnapshots;
        this.worker = worker;
        this.calculations = new CubeLightCalculationExecutor(worker, MAX_RUNNING_JOBS, MAX_JOBS);
        this.storage = storage;
        this.listener = listener;
    }

    public void tick(Map<CubePos, CubeAccess> available, List<CubePos> orderedTargets) {
        checkOwner();
        if (closed) {
            return;
        }
        tick(available, orderedTargets, orderedTargets.stream().filter(pos -> !epochs.containsKey(pos)).collect(java.util.stream.Collectors.toSet()));
    }

    /** The caller keeps a cube in firstDeliveries until its body has been sent to every tracking player. */
    public void tick(Map<CubePos, CubeAccess> available, List<CubePos> orderedTargets, Set<CubePos> firstDeliveries) {
        checkOwner();
        if (closed) {
            return;
        }
        tick++;
        if (diagnosticTick != null) {
            diagnosticTick.clear();
            diagnosticTick.put("tick", tick);
        }
        diagnosticAdd("ticks", 1);
        targets = List.copyOf(orderedTargets);
        targetSet = new HashSet<>(targets);
        this.firstDeliveries = Set.copyOf(firstDeliveries);
        observe(available);
        views.keySet().retainAll(targetSet);
        epochs.keySet().retainAll(targetSet);
        notifications.keySet().retainAll(targetSet);
        retry.keySet().retainAll(targetSet);
        dirty.retainAll(targetSet);
        urgent.retainAll(targetSet);
        fullyCaptured.retainAll(targetSet);
        targets.stream().filter(pos -> !views.containsKey(pos)).forEach(dirty::add);
        Set<CubePos> neededVersions = new HashSet<>();
        sourceColumns.keySet().forEach((long key) -> neededVersions.add(CubePos.of(ChunkPos.getX(key), 0, ChunkPos.getZ(key))));
        jobs.values().forEach(job -> neededVersions.addAll(job.dependencies.keySet()));
        versions.keySet().retainAll(neededVersions);
        Set<CubePos> neededBlockVersions = new HashSet<>(sources.keySet());
        jobs.values().forEach(job -> neededBlockVersions.addAll(job.blockDependencies.keySet()));
        blockVersions.keySet().retainAll(neededBlockVersions);
        retireObsoleteWaitingJobs(targetSet);
        captureSome();
        prefetchSkyInputs();
        for (Job job : new ArrayList<>(jobs.values())) {
            finish(job);
        }
        startNext(prioritizeWaitingJobs());
        notifySome();
    }

    private void observe(Map<CubePos, CubeAccess> available) {
        Set<CubePos> changedColumns = new HashSet<>();
        for (CubePos pos : new ArrayList<>(sources.keySet())) {
            if (!available.containsKey(pos)) {
                retireSource(pos, changedColumns);
            }
        }
        available.forEach((pos, cube) -> observeSource(pos, cube, changedColumns));
        invalidatedSources.clear();
        changedColumns.forEach(this::invalidateColumn);
    }

    private void observeSource(CubePos pos, CubeAccess cube, Set<CubePos> changedColumns) {
        Stamp previous = sources.get(pos);
        if (previous == null || previous.cube != cube || previous.revision != cube.cc_getLightRevision()) {
            diagnosticAdd(previous == null ? "sourceAdded" : previous.cube != cube ? "sourceReplaced" : "sourceRevision", 1);
            if (previous == null) {
                sourceColumns.computeIfAbsent(columnKey(pos.getX(), pos.getZ()), ignored -> new LinkedHashSet<>()).add(pos);
            }
            Stamp current = new Stamp(cube, cube.cc_getLightRevision());
            sources.put(pos, current);
            if (!reusePromotion(pos, previous, cube)) {
                if (previous == null) {
                    observeAddedSource(pos, cube, current, changedColumns);
                } else {
                    observeChangedSource(pos, cube, current, changedColumns);
                }
            }
        }
    }

    private void observeAddedSource(CubePos pos, CubeAccess cube, Stamp current, Set<CubePos> changedColumns) {
        forgetCapturedColumn(pos);
        CompletableFuture<CubeLightBlockSnapshot> savedBlocks = diskBlocks.get(pos);
        CubeSkyOcclusion savedSky = sky ? arrivalSky(pos) : null;
        boolean compareBlocks = complete(savedBlocks);
        Capture captured = captureArrival(cube, compareBlocks, savedSky != null);
        boolean sameBlocks = compareBlocks && captured.blocks.sameStates(savedBlocks.join());
        boolean sameSky = savedSky != null && savedSky.equivalentOcclusion(captured.sky);
        retainArrival(pos, captured, sameBlocks, sameSky);
        if (!current.equals(invalidatedSources.get(pos))) {
            invalidateArrival(pos, sameBlocks, sameSky, changedColumns);
        }
    }

    private Capture captureArrival(CubeAccess cube, boolean compareBlocks, boolean compareSky) {
        long started = diagnosticClock();
        CubeLightBlockSnapshot blocks = compareBlocks ? CubeLightBlockSnapshot.capture(cube) : null;
        CubeSkyOcclusion currentSky = sky && (compareBlocks || compareSky) ? cube.cc_snapshotSkyOcclusion() : null;
        diagnosticElapsed("arrivalSnapshotNanos", started);
        return new Capture(blocks, currentSky);
    }

    private void retainArrival(CubePos pos, Capture captured, boolean sameBlocks, boolean sameSky) {
        boolean retained = (captured.blocks != null || captured.sky != null) && captures.size() < maxSnapshots;
        if (retained) {
            captures.put(pos, captured);
        }
        if (!sameBlocks || retained) {
            diskBlocks.remove(pos);
        }
        if (sameBlocks) {
            diagnosticAdd("sourceAddedBlocksReused", 1);
        }
        if (sameSky) {
            diagnosticAdd("sourceAddedSkyReused", 1);
        }
    }

    private void invalidateArrival(CubePos pos, boolean sameBlocks, boolean sameSky, Set<CubePos> changedColumns) {
        if (!sameBlocks) {
            diagnosticAdd("sourceAddedBlockInvalidations", 1);
            invalidateBlocks(pos);
        }
        if (sky && !coveredByRoof(pos) && !sameSky) {
            diagnosticAdd("sourceAddedColumnInvalidations", 1);
            changedColumns.add(column(pos));
        }
    }

    private @Nullable CubeSkyOcclusion arrivalSky(CubePos pos) {
        CompletableFuture<Map<Integer, CubeSkyOcclusion>> savedColumn = columns.get(column(pos));
        CubeSkyOcclusion saved = complete(savedColumn) ? savedColumn.join().get(pos.getY()) : null;
        if (saved != null) {
            return saved;
        }
        CompletableFuture<CubeWorldSkyColumn> index = skyIndexes.get(column(pos));
        CubeSkyOcclusion resolved = complete(index) ? index.join().source(pos.getY()) : null;
        if (resolved != null) {
            diagnosticAdd("sourceAddedResolvedSkyBaseline", 1);
        }
        return resolved;
    }

    private boolean reusePromotion(CubePos pos, @Nullable Stamp previous, CubeAccess cube) {
        if (previous == null || !unmodifiedPromotion(previous, cube)) {
            return false;
        }
        diagnosticAdd("promotionReused", 1);
        // A prior target's own result still carries the old instance stamp. Its neighbors have identical block inputs.
        if (views.containsKey(pos) || jobs.containsKey(pos)) {
            invalidateTarget(pos);
        }
        return true;
    }

    private static boolean unmodifiedPromotion(Stamp previous, CubeAccess cube) {
        if (!(previous.cube instanceof ProtoCube) || !(cube instanceof LevelCube) || cube.cc_getLightRevision() != 0
                || previous.cube.cc_getLightRevision() != previous.revision) {
            return false;
        }
        var before = previous.cube.getSections();
        var after = cube.getSections();
        if (before.length != after.length) {
            return false;
        }
        for (int index = 0; index < before.length; index++) {
            if (before[index] != after[index]) {
                return false;
            }
        }
        return true;
    }

    private void observeChangedSource(CubePos pos, CubeAccess cube, Stamp current, Set<CubePos> changedColumns) {
        // A clipped edit can preserve a neighbor's light while replacing the full cached source that its next job still needs.
        halo(pos).positions().forEach(fullyCaptured::remove);
        Capture previousCapture = captures.remove(pos);
        diskBlocks.remove(pos);
        refreshSkyCapture(pos, cube, previousCapture);
        if (!current.equals(invalidatedSources.get(pos))) {
            diagnosticAdd("observedBlockInvalidations", 1);
            invalidateBlocks(pos);
            if (sky && !coveredByRoof(pos) && !unchangedSky(previousCapture, cube)) {
                diagnosticAdd("observedColumnInvalidations", 1);
                changedColumns.add(column(pos));
            }
        }
    }

    private void refreshSkyCapture(CubePos pos, CubeAccess cube, @Nullable Capture previous) {
        if (sky && previous != null && previous.sky != null) {
            // Preserve a current comparison baseline between ticks, even if this cube's blocks are not needed for the next job.
            captures.put(pos, new Capture(null, cube.cc_snapshotSkyOcclusion()));
        }
    }

    private static boolean unchangedSky(@Nullable Capture previous, CubeAccess cube) {
        return previous != null && previous.sky != null && previous.sky.equivalentOcclusion(cube.cc_snapshotSkyOcclusion());
    }

    /** A change below every sky edge cannot alter that index, including either face shared with this cube. */
    private boolean coveredByRoof(CubePos pos) {
        CompletableFuture<CubeWorldSkyColumn> index = skyIndexes.get(column(pos));
        return complete(index) && index.join().lowestEdge() > pos.maxCubeY() + 1;
    }

    /** Sources leave this set only after unload saves are acknowledged. Their unchanged light inputs remain authoritative. */
    private void retireSource(CubePos pos, Set<CubePos> changedColumns) {
        forgetCapturedColumn(pos);
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
            invalidateBlocks(pos);
            if (sky && !coveredByRoof(pos)) {
                changedColumns.add(column(pos));
            }
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
        trim(diskBlocks, maxSnapshots);
    }

    /** A roof can affect loaded cubes far below it. Revoke before any subsequent world reads or packets. */
    public void changed(CubeAccess cube) {
        changed(cube, false);
    }

    /** Only completed single-cell edits may be captured here; bulk invalidations must wait for observe(). */
    public void changed(CubeAccess cube, boolean mutationComplete) {
        checkOwner();
        if (!closed) {
            diagnosticAdd(mutationComplete ? "completedCellCallbacks" : "bulkCallbacks", 1);
            CubePos changed = cube.cc_getCubePos();
            invalidateBlocks(changed);
            if (sky && !coveredByRoof(changed) && !(mutationComplete && unchangedSky(captures.get(changed), cube))) {
                diagnosticAdd("callbackColumnInvalidations", 1);
                invalidateColumn(column(changed));
            }
            // Bulk section replacement also calls here before writing. Capture only in the next owner tick.
            invalidatedSources.put(changed, new Stamp(cube, cube.cc_getLightRevision()));
            for (CubePos pos : halo(changed).positions()) {
                if (targetSet.contains(pos)) {
                    urgent.add(pos);
                }
            }
        }
    }

    /** A completed cell edit invalidates only solver footprints and local-roof proofs that actually depend on it. */
    public void changedCell(CubeAccess cube, BlockPos cell) {
        checkOwner();
        if (closed) {
            return;
        }
        CubePos changed = cube.cc_getCubePos();
        if (!changed.equals(CubePos.from(cell))) {
            throw new IllegalArgumentException("Changed cell is outside its source cube");
        }
        diagnosticAdd("completedCellCallbacks", 1);
        diagnosticAdd("clippedCellCallbacks", 1);
        boolean sameSky = !sky || unchangedSky(captures.get(changed), cube);
        diagnosticAdd("viewsRevokedByBlocks", invalidateCellTargets(changed, cell, sameSky));
        if (sky && !sameSky && !coveredByRoof(changed)) {
            diagnosticAdd("callbackColumnInvalidations", 1);
            invalidateColumn(column(changed));
        }
        // Existing neighboring jobs may safely retain their full old snapshot when this cell is outside their read footprint.
        invalidatedSources.put(changed, new Stamp(cube, cube.cc_getLightRevision()));
    }

    private int invalidateCellTargets(CubePos changed, BlockPos cell, boolean sameSky) {
        int revoked = 0;
        for (CubePos target : halo(changed).positions()) {
            if (targetSet.contains(target) && (CubeLightBounds.solverIncludes(target, cell) || !sameSky && usesChangedRoof(target, cell))) {
                if (invalidateTarget(target)) {
                    revoked++;
                }
                urgent.add(target);
            }
        }
        return revoked;
    }

    private boolean usesChangedRoof(CubePos target, BlockPos cell) {
        Entry entry = views.get(target);
        Job job = jobs.get(target);
        if (!(entry != null && entry.skyOccluded || job != null && job.skyOccluded)) {
            return false;
        }
        int halo = LightEngine.MAX_LEVEL;
        return Coords.blockToCube(cell.getY()) == target.getY() + 1 && cell.getY() > target.maxCubeY() + halo
                && cell.getX() >= target.minCubeX() - halo && cell.getX() <= target.maxCubeX() + halo
                && cell.getZ() >= target.minCubeZ() - halo && cell.getZ() <= target.maxCubeZ() + halo;
    }

    private void invalidateBlocks(CubePos source) {
        diagnosticAdd("invalidateBlocks", 1);
        blockVersions.merge(source, 1L, Math::addExact);
        int revoked = 0;
        for (CubePos pos : halo(source).positions()) {
            if (targetSet.contains(pos) && invalidateTarget(pos)) {
                revoked++;
            }
        }
        diagnosticAdd("viewsRevokedByBlocks", revoked);
    }

    private void invalidateColumn(CubePos column) {
        diagnosticAdd("invalidateColumns", 1);
        versions.merge(column, 1L, Math::addExact);
        skyIndexes.remove(column);
        int revoked = 0;
        for (CubePos pos : targets) {
            Entry entry = views.get(pos);
            Job job = jobs.get(pos);
            boolean independent = entry != null ? entry.skyOccluded : job != null && job.skyOccluded;
            if (nearColumn(pos, column) && !independent) {
                if (invalidateTarget(pos)) {
                    revoked++;
                }
            }
        }
        diagnosticAdd("viewsRevokedBySky", revoked);
    }

    private boolean invalidateTarget(CubePos pos) {
        Job job = jobs.get(pos);
        if (job != null) {
            job.invalidated = true;
        }
        fullyCaptured.remove(pos);
        dirty.add(pos);
        retry.remove(pos);
        if (views.remove(pos) != null) {
            diagnosticAdd("viewsRevoked", 1);
            changedPublication(pos);
            return true;
        }
        return false;
    }

    private void captureSome() {
        long started = diagnosticClock();
        var budget = new CaptureBudget();
        int prepared = 0;
        long orderStarted = diagnosticClock();
        List<CubePos> order = orderedWork();
        diagnosticElapsed("captureOrderNanos", orderStarted);
        protectNextBatch(order, budget);
        for (CubePos priority : order) {
            if (needsJob(priority) && sources.containsKey(priority) && !fullyCaptured.contains(priority)) {
                diagnosticAdd("preparedTargets", 1);
                int before = budget.count;
                boolean captured = visitMissing(priority, budget);
                if (captured) {
                    fullyCaptured.add(priority);
                    diagnosticAdd("preparedReady", 1);
                }
                // Already captured targets cost no snapshot capacity, regardless of whether their inputs are ready.
                // In particular, repeated ready updates must not hide uncaptured first deliveries farther away.
                if (budget.count != before) {
                    prepared++;
                }
                if (prepared >= MAX_JOBS || budget.full()) {
                    captureDiagnostics(budget, prepared, started);
                    return;
                }
            }
        }
        captureDiagnostics(budget, prepared, started);
    }

    private void protectNextBatch(List<CubePos> order, CaptureBudget budget) {
        int protectedTargets = 0;
        for (CubePos pos : order) {
            if (needsJob(pos) && sources.containsKey(pos)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        budget.protectedColumns.add(columnKey(pos.getX() + dx, pos.getZ() + dz));
                    }
                }
                if (++protectedTargets >= MAX_JOBS) {
                    return;
                }
            }
        }
    }

    /** Prepared targets can initialize their sky inputs before a calculation slot becomes available. */
    private void prefetchSkyInputs() {
        if (!sky) {
            return;
        }
        long started = diagnosticClock();
        skyPrefetchPending.removeIf(CompletableFuture::isDone);
        int attempted = 0;
        for (CubePos pos : orderedWork()) {
            if (attempted >= SKY_PREFETCH_LIMIT || skyPrefetchPending.size() >= SKY_PREFETCH_LIMIT) {
                break;
            }
            if (needsSkyPrefetch(pos)) {
                attempted += prefetchSkyColumns(pos, SKY_PREFETCH_LIMIT - attempted);
            }
        }
        diagnosticAdd("skyPrefetchAttempts", attempted);
        diagnosticAdd("skyPrefetchPending", skyPrefetchPending.size());
        diagnosticElapsed("skyPrefetchNanos", started);
    }

    private boolean needsSkyPrefetch(CubePos pos) {
        if (!needsJob(pos) || !fullyCaptured.contains(pos)) {
            return false;
        }
        // Most targets share completed indexes. Avoid walking their captured source columns again.
        boolean missing = haloSkyColumns(pos).stream().anyMatch(column -> !skyIndexes.containsKey(column));
        return missing && ready(pos) && !hasLocalSkyRoof(pos);
    }

    private int prefetchSkyColumns(CubePos center, int remaining) {
        int attempted = 0;
        for (CubePos column : haloSkyColumns(center)) {
            if (!skyIndexes.containsKey(column)) {
                attempted++;
                CompletableFuture<CubeWorldSkyColumn> future;
                try {
                    future = skyIndex(column);
                } catch (RuntimeException exception) {
                    failed(center, exception);
                    break;
                }
                skyIndexes.put(column, future);
                if (!future.isDone()) {
                    // Invalidating or evicting a cache key does not finish its already dispatched work.
                    skyPrefetchPending.add(future);
                }
                diagnosticAdd("skyPrefetched", 1);
                trim(columns, SKY_CACHE_SIZE);
                trim(skyIndexes, SKY_CACHE_SIZE);
                if (attempted >= remaining || skyPrefetchPending.size() >= SKY_PREFETCH_LIMIT) {
                    break;
                }
            }
        }
        return attempted;
    }

    private static List<CubePos> haloSkyColumns(CubePos center) {
        CubeLightBounds bounds = halo(center);
        List<CubePos> columns = new ArrayList<>();
        for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
            for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
                columns.add(CubePos.of(x, 0, z));
            }
        }
        return columns;
    }

    private void captureDiagnostics(CaptureBudget budget, int prepared, long started) {
        if (!DIAGNOSTICS) {
            return;
        }
        diagnosticElapsed("captureNanos", started);
        if (budget.count >= CAPTURES_PER_TICK) {
            diagnosticAdd("captureStopCount", 1);
        } else if (budget.full()) {
            diagnosticAdd("captureStopTime", 1);
        } else {
            diagnosticAdd(prepared >= MAX_JOBS ? "captureStopBatch" : "captureStopExhausted", 1);
        }
    }

    private static long columnKey(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    /** Only the nine halo columns can contribute terrain or a roof to this job. */
    private boolean visitMissing(CubePos center, @Nullable CaptureBudget budget) {
        // A local roof can prove all sky seeds dark without requiring any native surface-column inputs.
        for (CubePos pos : halo(center).positions()) {
            if (sources.containsKey(pos) && !captureOne(center, pos, budget)) {
                return false;
            }
        }
        if (!sky || hasLocalSkyRoof(center)) {
            return true;
        }
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
            if (!captureOne(center, pos, budget)) {
                return false;
            }
        }
        return true;
    }

    private boolean captureOne(CubePos center, CubePos pos, @Nullable CaptureBudget budget) {
        if (!needsCapture(center, pos)) {
            return true;
        }
        if (budget == null || budget.full()) {
            return false;
        }
        if (captures.size() >= maxSnapshots && !captures.containsKey(pos)) {
            if (!evictCapture(center, budget)) {
                failed(center, new IllegalStateException("Lighting source columns exceed the snapshot limit"));
                return false;
            }
        }
        if (budget.count == 0) {
            diagnosticAdd("captureTicksWithWork", 1);
            diagnosticElapsed("beforeFirstCaptureNanos", budget.started);
        }
        long started = diagnosticClock();
        Capture capture = captureSource(center, sources.get(pos).cube);
        captures.put(pos, capture);
        diagnosticElapsed("snapshotNanos", started);
        diagnosticAdd("captured", 1);
        diagnosticAdd(capture.blocks == null ? "capturedSkyOnly" : "capturedBlocks", 1);
        budget.count++;
        return true;
    }

    private boolean evictCapture(CubePos center, CaptureBudget budget) {
        CubePos fallback = null;
        for (CubePos cached : captures.keySet()) {
            if (nearColumn(center, cached)) {
                continue;
            }
            if (fallback == null) {
                fallback = cached;
            }
            if (!budget.protectedColumns.contains(columnKey(cached.getX(), cached.getZ()))) {
                captures.remove(cached);
                forgetCapturedColumn(cached);
                diagnosticAdd("capturesEvicted", 1);
                return true;
            }
        }
        // The next batch is a preference, not a permanent reservation that can stop all forward progress.
        if (fallback != null) {
            captures.remove(fallback);
            forgetCapturedColumn(fallback);
            diagnosticAdd("capturesEvictedProtected", 1);
            return true;
        }
        return false;
    }

    private void forgetCapturedColumn(CubePos source) {
        fullyCaptured.removeIf(pos -> nearColumn(pos, source));
    }

    /** The nine upper halo cubes cover every X/Z cell of the solver's 62-wide footprint. */
    private boolean hasLocalSkyRoof(CubePos center) {
        if (center.getY() == CubePos.MAX_COORDINATE_VALUE) {
            return false;
        }
        int top = center.maxCubeY() + LightEngine.MAX_LEVEL;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Capture capture = captures.get(CubePos.of(center.getX() + dx, center.getY() + 1, center.getZ() + dz));
                if (!roofCoversHalo(capture, top, dx, dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean roofCoversHalo(@Nullable Capture capture, int top, int dx, int dz) {
        if (capture == null || capture.sky == null) {
            return false;
        }
        int minX = dx < 0 ? CubicConstants.DIAMETER_IN_BLOCKS - LightEngine.MAX_LEVEL : 0;
        int minZ = dz < 0 ? CubicConstants.DIAMETER_IN_BLOCKS - LightEngine.MAX_LEVEL : 0;
        int maxX = dx > 0 ? LightEngine.MAX_LEVEL : CubicConstants.DIAMETER_IN_BLOCKS;
        int maxZ = dz > 0 ? LightEngine.MAX_LEVEL : CubicConstants.DIAMETER_IN_BLOCKS;
        return capture.sky.blocksSkyAbove(top, minX, minZ, maxX, maxZ);
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

    private void startNext(List<CubePos> preferred) {
        for (CubePos pos : preferred) {
            if (jobs.size() >= MAX_JOBS) {
                return;
            }
            if (needsJob(pos) && ready(pos)) {
                start(pos);
            }
        }
    }

    private void retireObsoleteWaitingJobs(Set<CubePos> targetSet) {
        jobs.values().removeIf(job -> (!targetSet.contains(job.pos) || !job.source.equals(sources.get(job.pos)) || !current(job))
                && retireWaiting(job));
    }

    private boolean retireWaiting(Job job) {
        boolean retired = job.retireWaiting();
        if (retired) {
            diagnosticAdd("waitingRetired", 1);
        }
        return retired;
    }

    /** Pending terrain reads keep a small share of capacity without blocking calculations whose inputs already exist. */
    private List<CubePos> prioritizeWaitingJobs() {
        int available = MAX_JOBS - (int) jobs.values().stream().filter(Job::readyToCalculate).count();
        List<CubePos> readyFirst = new ArrayList<>();
        List<CubePos> readyUpdates = new ArrayList<>();
        List<CubePos> waitingInputs = new ArrayList<>();
        collectWaitingCandidates(available, readyFirst, readyUpdates, waitingInputs);
        List<CubePos> readyInputs = interleave(readyFirst, readyUpdates, available);
        List<CubePos> preferred = new ArrayList<>(available);
        int waitingCount = Math.min(Math.min(INPUT_WAIT_SLOTS, available), waitingInputs.size());
        int readyCount = Math.min(available - waitingCount, readyInputs.size());
        waitingCount = Math.min(MAX_RUNNING_JOBS, Math.min(available - readyCount, waitingInputs.size()));
        preferred.addAll(waitingInputs.subList(0, waitingCount));
        preferred.addAll(readyInputs.subList(0, readyCount));
        jobs.values().removeIf(job -> !job.readyToCalculate() && !preferred.contains(job.pos) && retireWaiting(job));
        return preferred;
    }

    private void collectWaitingCandidates(int available, List<CubePos> readyFirst, List<CubePos> readyUpdates, List<CubePos> waitingInputs) {
        for (CubePos pos : orderedWork()) {
            if (readyFirst.size() >= available && readyUpdates.size() >= available && waitingInputs.size() >= available) {
                break;
            }
            Job job = jobs.get(pos);
            if (waitingCandidate(pos, job)) {
                List<CubePos> group = inputsReady(pos, job) ? firstDeliveries.contains(pos) ? readyFirst : readyUpdates : waitingInputs;
                if (group.size() < available) {
                    group.add(pos);
                }
            }
        }
    }

    private static List<CubePos> interleave(List<CubePos> first, List<CubePos> second, int limit) {
        List<CubePos> result = new ArrayList<>(limit);
        for (int index = 0; result.size() < limit && (index < first.size() || index < second.size()); index++) {
            if (index < first.size()) {
                result.add(first.get(index));
            }
            if (result.size() < limit && index < second.size()) {
                result.add(second.get(index));
            }
        }
        return result;
    }

    private boolean waitingCandidate(CubePos pos, @Nullable Job job) {
        return (job == null || !job.readyToCalculate()) && tick >= retry.getOrDefault(pos, 0L) && (job != null || ready(pos));
    }

    private boolean inputsReady(CubePos center, @Nullable Job job) {
        if (job != null) {
            // These immutable inputs remain valid even when their shared cache entries have been evicted.
            return complete(job.inputs);
        }
        boolean needsSky = sky && !hasLocalSkyRoof(center);
        for (CubePos pos : halo(center).positions()) {
            if (needsSky && !complete(skyIndexes.get(column(pos)))) {
                return false;
            }
            Capture capture = captures.get(pos);
            if ((capture == null || capture.blocks == null) && !complete(diskBlocks.get(pos))) {
                return false;
            }
        }
        return true;
    }

    private static boolean complete(@Nullable CompletableFuture<?> future) {
        return future != null && future.isDone() && !future.isCompletedExceptionally();
    }

    private List<CubePos> orderedWork() {
        long started = diagnosticClock();
        List<CubePos> pending = targets.stream().filter(dirty::contains).toList();
        List<CubePos> first = pending.stream().filter(firstDeliveries::contains).toList();
        List<CubePos> updates = pending.stream().filter(pos -> !firstDeliveries.contains(pos)).toList();
        var edits = updates.stream().filter(urgent::contains).iterator();
        Set<CubePos> updateOrder = new LinkedHashSet<>();
        // Both update streams follow current player distance; edits retain priority within the relight capacity.
        for (CubePos pos : updates) {
            updateOrder.add(pos);
            if (edits.hasNext()) {
                updateOrder.add(edits.next());
            }
        }
        var firstIterator = first.iterator();
        var updateIterator = updateOrder.iterator();
        List<CubePos> order = new ArrayList<>(pending.size());
        // Repeated nearby relights must leave capacity for the first body delivery, including previously revoked publications.
        while (firstIterator.hasNext() || updateIterator.hasNext()) {
            if (firstIterator.hasNext()) {
                order.add(firstIterator.next());
            }
            if (updateIterator.hasNext()) {
                order.add(updateIterator.next());
            }
        }
        diagnosticElapsed("orderNanos", started);
        return order;
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
            Map<CubePos, Long> blockDependencies = new HashMap<>();
            Map<CubePos, CompletableFuture<CubeWorldSkyColumn>> skyInputs = new HashMap<>();
            Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> blocks = new HashMap<>();
            boolean skyOccluded = sky && hasLocalSkyRoof(center);
            for (CubePos pos : bounds.positions()) {
                CubePos column = column(pos);
                blockDependencies.put(pos, blockVersions.getOrDefault(pos, 0L));
                if (sky && !skyOccluded) {
                    dependencies.put(column, versions.getOrDefault(column, 0L));
                    skyInputs.computeIfAbsent(column, key -> skyIndexes.computeIfAbsent(key, this::skyIndex));
                }
                Capture capture = captures.get(pos);
                blocks.put(pos, capture == null || capture.blocks == null ? diskBlocks.computeIfAbsent(pos, storage::cube)
                        : CompletableFuture.completedFuture(capture.blocks));
            }
            List<CompletableFuture<?>> inputs = new ArrayList<>(blocks.values());
            inputs.addAll(skyInputs.values());
            jobs.put(center, new Job(center, sources.get(center), Map.copyOf(dependencies), Map.copyOf(blockDependencies), skyOccluded,
                    CompletableFuture.allOf(inputs.toArray(CompletableFuture[]::new)), () -> solve(center, blocks, skyInputs), calculations));
            diagnosticAdd("jobsStarted", 1);
            trim(columns, SKY_CACHE_SIZE);
            trim(diskBlocks, maxSnapshots);
            trim(skyIndexes, SKY_CACHE_SIZE);
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
            if (!finished.source.equals(sources.get(finished.pos)) || !current(finished) || !targetSet.contains(finished.pos)) {
                diagnosticAdd("staleDropped", 1);
                return;
            }
            views.put(finished.pos, new Entry(finished.source, view, finished.skyOccluded));
            diagnosticAdd("published", 1);
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

    private boolean current(Job job) {
        return !job.invalidated && current(job.dependencies) && job.blockDependencies.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(blockVersions.getOrDefault(entry.getKey(), 0L)));
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
        for (CubePos pos : notificationOrder()) {
            try {
                listener.changed(pos, notifications.get(pos));
                diagnosticAdd(firstDeliveries.contains(pos) ? "initialNotifications" : "updateNotifications", 1);
                notifications.remove(pos);
            } catch (RuntimeException exception) {
                failure = exception.toString();
                notificationRetry = tick + RETRY_TICKS;
                return;
            }
        }
    }

    private List<CubePos> notificationOrder() {
        List<CubePos> first = new ArrayList<>();
        List<CubePos> updates = new ArrayList<>();
        for (CubePos pos : notifications.keySet()) {
            List<CubePos> group = firstDeliveries.contains(pos) ? first : updates;
            if (group.size() < NOTIFICATIONS_PER_TICK) {
                group.add(pos);
            }
            if (first.size() == NOTIFICATIONS_PER_TICK && updates.size() == NOTIFICATIONS_PER_TICK) {
                break;
            }
        }
        return interleave(updates, first, NOTIFICATIONS_PER_TICK);
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
        boolean published = false;
        for (CubePos candidate : targets) {
            if (nearColumn(pos, candidate) && view(candidate) != null) {
                changedPublication(candidate);
                published = true;
            }
        }
        return published;
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
        jobs.values().forEach(Job::retireWaiting);
        jobs.clear();
        views.clear();
        sources.clear();
        invalidatedSources.clear();
        blockVersions.clear();
        sourceColumns.clear();
        captures.clear();
        fullyCaptured.clear();
        columns.clear();
        diskBlocks.clear();
        skyIndexes.clear();
        skyPrefetchPending.clear();
        urgent.clear();
        notifications.clear();
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("World lighting must be accessed on its owner thread");
        }
    }

    private static long diagnosticClock() {
        return DIAGNOSTICS ? System.nanoTime() : 0;
    }

    private void diagnosticElapsed(String name, long started) {
        if (DIAGNOSTICS) {
            diagnosticAdd(name, System.nanoTime() - started);
        }
    }

    private void diagnosticAdd(String name, long value) {
        if (diagnosticTotals != null) {
            diagnosticTotals.merge(name, value, Long::sum);
            diagnosticTick.merge(name, value, Long::sum);
        }
    }

    private static <K, V> void trim(Map<K, V> cache, int limit) {
        var iterator = cache.keySet().iterator();
        while (cache.size() > limit && iterator.hasNext()) {
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
        private final long started = System.nanoTime();
        private final long deadline = started + CAPTURE_NANOS;
        private final Set<Long> protectedColumns = new HashSet<>();
        private int count;

        private boolean full() {
            return count >= CAPTURES_PER_TICK || count > 0 && System.nanoTime() >= deadline;
        }
    }

    private record Stamp(CubeAccess cube, long revision) {}

    private record Capture(@Nullable CubeLightBlockSnapshot blocks, @Nullable CubeSkyOcclusion sky) {}

    private record Entry(Stamp source, CubeLightView view, boolean skyOccluded) {}

    private static final class Job {
        private static final int WAITING = 0;
        private static final int RUNNING = 1;
        private static final int RETIRED = 2;
        private final CubePos pos;
        private final Stamp source;
        private final Map<CubePos, Long> dependencies;
        private final Map<CubePos, Long> blockDependencies;
        private final boolean skyOccluded;
        private boolean invalidated;
        private final CompletableFuture<Void> inputs;
        private final AtomicInteger state = new AtomicInteger(WAITING);
        private final CompletableFuture<CubeLightView> result;

        private Job(CubePos pos, Stamp source, Map<CubePos, Long> dependencies, Map<CubePos, Long> blockDependencies, boolean skyOccluded,
                CompletableFuture<Void> inputs,
                Supplier<CubeLightView> calculation, CubeLightCalculationExecutor calculations) {
            this.pos = pos;
            this.source = source;
            this.dependencies = dependencies;
            this.blockDependencies = blockDependencies;
            this.skyOccluded = skyOccluded;
            this.inputs = inputs;
            result = new CompletableFuture<>();
            inputs.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    calculations.execute(() -> {
                        if (!state.compareAndSet(WAITING, RUNNING)) {
                            result.cancel(false);
                            return;
                        }
                        try {
                            result.complete(calculation.get());
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                    }, () -> state.get() == WAITING, result::completeExceptionally);
                }
            });
        }

        private boolean running() {
            return state.get() == RUNNING;
        }

        private boolean readyToCalculate() {
            // Keep queued calculations as well as running ones: repeatedly replacing them would grow the executor queue.
            return running() || complete(inputs);
        }

        private boolean retireWaiting() {
            if (!state.compareAndSet(WAITING, RETIRED)) {
                return false;
            }
            // Shared terrain inputs continue. A calculation that already started keeps its slot until finish().
            result.cancel(false);
            return true;
        }
    }
}
