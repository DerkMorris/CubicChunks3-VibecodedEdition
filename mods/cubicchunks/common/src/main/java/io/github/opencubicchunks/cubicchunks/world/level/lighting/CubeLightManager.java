package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Publishes one bounded, experimental lighting result. Capture, lookup and validation run on the owning thread;
 * workers see only detached block snapshots. Closing is also safe from the shutdown thread.
 */
public final class CubeLightManager implements AutoCloseable {
    private static final int RETRY_BASE_TICKS = 20;
    private static final int MAX_FAILURES = 5;
    private static final int MAX_NOTIFICATION_POSITIONS = CubeLightBounds.MAX_CUBES * 2;
    private final Function<CubePos, Source> findSource;
    private final BooleanSupplier isOwnerThread;
    private final Executor worker;
    private final Executor owner;
    private final ChangeListener changes;
    private @Nullable Pending inFlight;
    private @Nullable Publication publication;
    private @Nullable CubeLightBounds activeBounds;
    private List<Dependency> watched = List.of();
    private boolean dirty;
    private boolean ticking;
    private boolean notifying;
    private int consecutiveFailures;
    private long ticks;
    private long retryAt;
    private @Nullable String lastFailure;
    private @Nullable Notification notification;
    private long notifyAt;
    private @Nullable String notificationFailure;
    private boolean stale;
    private boolean closed;
    private long epoch;

    public CubeLightManager(Function<CubePos, Source> findSource, BooleanSupplier isOwnerThread, Executor worker, Executor owner) {
        this(findSource, isOwnerThread, worker, owner, (epoch, affected) -> {});
    }

    public CubeLightManager(
            Function<CubePos, Source> findSource, BooleanSupplier isOwnerThread, Executor worker, Executor owner, ChangeListener changes
    ) {
        this.findSource = Objects.requireNonNull(findSource, "findSource");
        this.isOwnerThread = Objects.requireNonNull(isOwnerThread, "isOwnerThread");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.changes = Objects.requireNonNull(changes, "changes");
    }

    /** Admit a bounded domain only after successful capture. Its future describes this one attempt, not automatic retries. */
    public synchronized CompletableFuture<CubeLightUpdate> rebuild(CubeLightBounds bounds) {
        requireOwnerThread();
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cube light manager is closed"));
        }
        if (this.inFlight != null || this.notifying) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cube light rebuild is already running"));
        }
        try {
            Objects.requireNonNull(bounds, "bounds");
            if (this.notification != null && this.activeBounds != null && !this.activeBounds.equals(bounds)) {
                throw new IllegalStateException("Cannot retarget while a publication notification is pending");
            }
            Pending pending = captureInputs(bounds);
            this.activeBounds = bounds;
            this.watched = pending.dependencies;
            resetFailures();
            start(pending);
            return pending.result;
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Start at most one automatic attempt per owner tick. Reads and invalidation never start workers. */
    public synchronized void tick() {
        requireOwnerThread();
        if (this.closed || this.ticking || this.notifying) {
            return;
        }
        this.ticking = true;
        try {
            this.ticks = Math.incrementExact(this.ticks);
            validate();
            if (canStartAutomatic()) {
                try {
                    Pending pending = captureInputs(this.activeBounds);
                    this.watched = pending.dependencies;
                    start(pending);
                } catch (RuntimeException | Error failure) {
                    calculationFailed(failure);
                }
            }
            deliverNotification();
        } finally {
            this.ticking = false;
        }
    }

    private boolean canStartAutomatic() {
        return !this.closed && this.activeBounds != null && this.dirty && this.inFlight == null && this.consecutiveFailures < MAX_FAILURES
                && this.ticks >= this.retryAt;
    }

    /** Coalesce a manual refresh into the next eligible tick without changing the active domain or bypassing failure backoff. */
    public synchronized boolean requestRefresh(CubePos pos) {
        requireOwnerThread();
        Objects.requireNonNull(pos, "pos");
        if (this.closed || this.activeBounds == null || !this.activeBounds.contains(pos)) {
            return false;
        }
        this.dirty = true;
        revoke();
        return true;
    }

    /** Recover a revoked client snapshot using current complete views; no block recapture or voxel calculation is required. */
    public synchronized boolean republishCurrent(CubePos anchor) {
        requireOwnerThread();
        Objects.requireNonNull(anchor, "anchor");
        validate();
        if (this.closed || this.notifying || this.inFlight != null || this.dirty || this.notification != null || this.publication == null) {
            return false;
        }
        CubeLightView view = this.publication.views.get(anchor);
        if (view == null || !view.isReady()) {
            return false;
        }
        changed(knownPositions());
        return true;
    }

    public synchronized CubeLightStatus status() {
        requireOwnerThread();
        validate();
        int retry = this.consecutiveFailures >= MAX_FAILURES ? 0 : (int) Math.max(0, this.retryAt - this.ticks);
        return new CubeLightStatus(phase(), this.activeBounds, this.consecutiveFailures, retry, this.lastFailure, this.notification != null,
                this.notificationFailure);
    }

    private CubeLightStatus.Phase phase() {
        if (this.closed) {
            return CubeLightStatus.Phase.CLOSED;
        }
        if (this.inFlight != null) {
            return CubeLightStatus.Phase.RUNNING;
        }
        if (this.consecutiveFailures >= MAX_FAILURES) {
            return CubeLightStatus.Phase.FAILED;
        }
        if (this.consecutiveFailures > 0 && this.ticks < this.retryAt) {
            return CubeLightStatus.Phase.RETRY_WAIT;
        }
        if (this.dirty) {
            return CubeLightStatus.Phase.DIRTY;
        }
        return this.publication == null ? CubeLightStatus.Phase.IDLE : CubeLightStatus.Phase.CURRENT;
    }

    private void start(Pending pending) {
        Set<CubePos> affected = new HashSet<>(knownPositions());
        affected.addAll(pending.knownPositions());
        this.inFlight = pending;
        this.publication = null;
        this.stale = false;
        this.dirty = false;
        changed(affected);
        if (this.inFlight != pending || this.closed) {
            return;
        }
        try {
            this.worker.execute(() -> solve(pending));
        } catch (RuntimeException | Error failure) {
            fail(pending, failure);
        }
    }

    /** Revalidate every dependency, including absent cubes, before exposing a current sample. */
    public synchronized CubeLightQuery query(LightLayer layer, BlockPos pos) {
        requireOwnerThread();
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(pos, "pos");
        validate();
        if (this.publication == null) {
            return new CubeLightQuery(this.stale ? CubeLightQuery.State.STALE : CubeLightQuery.State.UNAVAILABLE, Optional.empty());
        }
        CubePos cube = CubePos.of(Coords.blockToCube(pos.getX()), Coords.blockToCube(pos.getY()), Coords.blockToCube(pos.getZ()));
        CubeLightView view = this.publication.views.get(cube);
        Optional<LightRange> sample = view == null ? Optional.empty() : view.sample(layer, pos);
        return new CubeLightQuery(sample.isPresent() ? CubeLightQuery.State.CURRENT : CubeLightQuery.State.UNAVAILABLE, sample);
    }

    /** Owner-thread read for vanilla adapters. The immutable view can be retained, but must be reacquired for current-world reads. */
    public synchronized @Nullable CubeLightView view(CubePos pos) {
        requireOwnerThread();
        validate();
        return this.publication == null ? null : this.publication.views.get(Objects.requireNonNull(pos, "pos"));
    }

    /** Export light only for the exact active instance whose block data will be serialized in the same owner-thread operation. */
    public synchronized CubeLightPacketSnapshot capture(LevelCube cube) {
        requireOwnerThread();
        Objects.requireNonNull(cube, "cube");
        validate();
        CubeLightView view = this.publication == null ? null : this.publication.views.get(cube.cc_getCubePos());
        if (view != null && view.isReady()) {
            for (Dependency dependency : this.publication.dependencies) {
                if (dependency.source != null && dependency.source.cube == cube) {
                    return new CubeLightPacketSnapshot(this.epoch, view);
                }
            }
        }
        return new CubeLightPacketSnapshot(this.epoch, null);
    }

    /** Observe all domain positions, including absent ones, without scheduling work or retaining new block snapshots. */
    public synchronized void validate() {
        requireOwnerThread();
        if (this.closed || this.activeBounds == null) {
            return;
        }
        try {
            List<Dependency> current = observe(this.activeBounds);
            if (!sameDependencies(this.watched, current)) {
                this.watched = current;
                this.dirty = true;
                resetFailures();
                revoke();
            }
        } catch (RuntimeException | Error failure) {
            revoke();
            if (this.consecutiveFailures == 0) {
                calculationFailed(failure);
            }
        }
    }

    private void revoke() {
        if (this.publication != null) {
            Set<CubePos> affected = knownPositions();
            this.publication = null;
            this.stale = true;
            changed(affected);
        }
    }

    public synchronized long epoch() {
        return this.epoch;
    }

    @Override public synchronized void close() {
        if (this.closed) {
            return;
        }
        Set<CubePos> affected = new HashSet<>(knownPositions());
        if (this.inFlight != null) {
            affected.addAll(this.inFlight.knownPositions());
        }
        this.closed = true;
        this.publication = null;
        this.stale = false;
        this.dirty = false;
        this.activeBounds = null;
        this.watched = List.of();
        if (this.inFlight != null) {
            fail(this.inFlight, new IllegalStateException("Cube light manager is closed"));
        }
        changed(affected);
        this.notification = null;
        this.notificationFailure = null;
        resetFailures();
    }

    private Set<CubePos> knownPositions() {
        return this.publication == null ? Set.of() : this.publication.views.keySet();
    }

    private void changed(Set<CubePos> affected) {
        this.epoch = Math.incrementExact(this.epoch);
        Set<CubePos> combined = new HashSet<>(affected);
        if (this.notification != null) {
            combined.addAll(this.notification.affected);
        }
        if (combined.size() > MAX_NOTIFICATION_POSITIONS) {
            throw new IllegalStateException("Publication notification exceeds its bounded domain union");
        }
        this.notification = new Notification(this.epoch, Set.copyOf(combined));
        deliverNotification();
    }

    private void deliverNotification() {
        Notification next = this.notification;
        if (next == null || this.notifying || (!this.closed && this.ticks < this.notifyAt)) {
            return;
        }
        this.notifying = true;
        try {
            this.changes.onChanged(next.epoch, next.affected);
            if (this.notification == next) {
                this.notification = null;
            }
            this.notificationFailure = null;
            this.notifyAt = this.ticks;
        } catch (RuntimeException | Error failure) {
            this.notificationFailure = describe(failure);
            this.notifyAt = this.ticks + RETRY_BASE_TICKS;
        } finally {
            this.notifying = false;
        }
    }

    private Pending captureInputs(CubeLightBounds bounds) {
        List<Dependency> dependencies = observe(bounds);
        Map<CubePos, CubeLightBlockSnapshot> snapshots = new HashMap<>();
        for (Dependency dependency : dependencies) {
            if (dependency.source != null) {
                CubeAccess cube = dependency.source.cube;
                if (!cube.cc_getCubePos().equals(dependency.pos) || dependency.status != ChunkStatus.FULL) {
                    throw new IllegalArgumentException("Lighting sources must be FULL cubes at the requested position: " + dependency.pos);
                }
                snapshots.put(dependency.pos, CubeLightBlockSnapshot.capture(cube));
            }
        }
        return new Pending(bounds, dependencies, Map.copyOf(snapshots));
    }

    private List<Dependency> observe(CubeLightBounds bounds) {
        List<Dependency> dependencies = new ArrayList<>();
        for (CubePos pos : bounds.positions()) {
            Source source = source(pos);
            dependencies.add(source == null ? new Dependency(pos, null, 0, null)
                    : new Dependency(pos, source, source.cube.cc_getLightRevision(), source.cube.getPersistedStatus()));
        }
        return List.copyOf(dependencies);
    }

    private @Nullable Source source(CubePos pos) {
        Source source = this.findSource.apply(pos);
        if (source != null && source.cube instanceof ImposterProtoCube imposter) {
            return new Source(source.holder, (CubeAccess) imposter.cc_getWrappedClo());
        }
        return source;
    }

    private static boolean sameDependencies(List<Dependency> previous, List<Dependency> current) {
        if (previous.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            if (!matches(previous.get(index), current.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Dependency previous, Dependency current) {
        if (!previous.pos.equals(current.pos)) {
            return false;
        }
        if (previous.source == null || current.source == null) {
            return previous.source == current.source;
        }
        return previous.source.holder == current.source.holder && previous.source.cube == current.source.cube && previous.revision == current.revision
                && previous.status == current.status;
    }

    private void solve(Pending pending) {
        Map<CubePos, CubeLightBlockSnapshot> snapshots;
        synchronized (this) {
            if (this.inFlight != pending) {
                return;
            }
            snapshots = pending.snapshots;
            pending.snapshots = Map.of();
        }
        Map<CubePos, CubeLightView> views = null;
        Throwable failure = null;
        try {
            CubeLightSolution solution = BoundedCubeLightSolver.solve(pending.bounds, snapshots, CubeLightBoundary.UNKNOWN);
            Map<CubePos, CubeLightView> extracted = new HashMap<>();
            for (CubePos pos : snapshots.keySet()) {
                extracted.put(pos, CubeLightView.fromSolution(solution, pos).orElseThrow());
            }
            views = Map.copyOf(extracted);
        } catch (Throwable caught) {
            failure = caught;
        }
        dispatch(pending, views, failure);
    }

    private void dispatch(Pending pending, @Nullable Map<CubePos, CubeLightView> views, @Nullable Throwable failure) {
        try {
            this.owner.execute(() -> finish(pending, views, failure));
        } catch (RuntimeException | Error rejected) {
            fail(pending, rejected);
        }
    }

    private synchronized void finish(Pending pending, @Nullable Map<CubePos, CubeLightView> views, @Nullable Throwable failure) {
        if (this.inFlight != pending) {
            return;
        }
        try {
            requireOwnerThread();
            if (failure != null) {
                fail(pending, failure);
                return;
            }
            List<Dependency> currentDependencies = observe(pending.bounds);
            boolean current = sameDependencies(pending.dependencies, currentDependencies);
            this.watched = currentDependencies;
            this.publication = current ? new Publication(Objects.requireNonNull(views, "views"), pending.dependencies) : null;
            this.stale = !current;
            this.dirty |= !current;
            this.inFlight = null;
            resetFailures();
            changed(pending.knownPositions());
            pending.result.complete(current ? CubeLightUpdate.PUBLISHED : CubeLightUpdate.STALE);
        } catch (Throwable caught) {
            fail(pending, caught);
            pending.result.completeExceptionally(caught);
        }
    }

    private synchronized void fail(Pending pending, Throwable failure) {
        if (this.inFlight == pending) {
            this.inFlight = null;
            pending.snapshots = Map.of();
            if (!this.closed) {
                calculationFailed(failure);
            }
            pending.result.completeExceptionally(failure);
        }
    }

    private void calculationFailed(Throwable failure) {
        this.dirty = true;
        this.consecutiveFailures = Math.min(MAX_FAILURES, this.consecutiveFailures + 1);
        this.lastFailure = describe(failure);
        this.retryAt = this.ticks + ((long) RETRY_BASE_TICKS << (this.consecutiveFailures - 1));
    }

    private void resetFailures() {
        this.consecutiveFailures = 0;
        this.retryAt = this.ticks;
        this.lastFailure = null;
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }

    private void requireOwnerThread() {
        if (!this.isOwnerThread.getAsBoolean()) {
            throw new IllegalStateException("Cube light capture and queries require the owning thread");
        }
    }

    /** Identity of the active holder and its FULL cube. Providers return null for missing or non-FULL positions. */
    public record Source(Object holder, CubeAccess cube) {
        public Source {
            Objects.requireNonNull(holder, "holder");
            Objects.requireNonNull(cube, "cube");
        }
    }

    /** Invoked after state changes; callbacks may reenter read/export methods. Close may notify from the shutdown thread. */
    @FunctionalInterface
    public interface ChangeListener {
        void onChanged(long epoch, Set<CubePos> affected);
    }

    private record Dependency(CubePos pos, @Nullable Source source, long revision, @Nullable ChunkStatus status) {}

    private record Notification(long epoch, Set<CubePos> affected) {}

    private record Publication(Map<CubePos, CubeLightView> views, List<Dependency> dependencies) {}

    private static final class Pending {
        private final CubeLightBounds bounds;
        private final List<Dependency> dependencies;
        private Map<CubePos, CubeLightBlockSnapshot> snapshots;
        private final CompletableFuture<CubeLightUpdate> result = new CompletableFuture<>();

        private Pending(CubeLightBounds bounds, List<Dependency> dependencies, Map<CubePos, CubeLightBlockSnapshot> snapshots) {
            this.bounds = bounds;
            this.dependencies = dependencies;
            this.snapshots = snapshots;
        }

        private Set<CubePos> knownPositions() {
            Set<CubePos> positions = new HashSet<>();
            for (Dependency dependency : this.dependencies) {
                if (dependency.source != null) {
                    positions.add(dependency.pos);
                }
            }
            return Set.copyOf(positions);
        }
    }
}
