package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable client publications, bound to the installed block snapshot rather than comparable server/client block counters. */
public final class ClientCubeLightState {
    private static final int[] REFRESH_RETRY_DELAYS = { 20, 40, 80, 160, 200 };
    private final Function<CubePos, CubeAccess> findCube;
    private final Consumer<CubePos> changed;
    private volatile State state = new State(0, -1, -1, 0, Map.of());
    private volatile Map<CubePos, Entry> displayed = Map.of();
    private @Nullable PendingRefresh refresh;
    private boolean scoped;
    private final Map<CubePos, Long> revoked = new HashMap<>();
    private final Map<CubePos, Long> installedEpochs = new HashMap<>();
    private final Map<CubePos, Long> expectedEpochs = new HashMap<>();

    public ClientCubeLightState(Function<CubePos, CubeAccess> findCube, Consumer<CubePos> changed) {
        this.findCube = Objects.requireNonNull(findCube, "findCube");
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    /** Render workers only read immutable entries and the active cache's atomic cube slots. */
    public @Nullable CubeLightView view(CubePos pos) {
        State current = this.state;
        Entry entry = current.entries.get(pos);
        if (entry == null || this.findCube.apply(pos) != entry.cube || entry.cube.cc_getLightRevision() != entry.revision) {
            return null;
        }
        return entry.view;
    }

    public long generation() {
        return this.state.generation;
    }

    /** Keep the last accepted light visible while this resident cube is being relit. It does not certify readiness. */
    public @Nullable CubeLightView displayView(CubePos pos) {
        Entry entry = displayed.get(pos);
        return entry != null && findCube.apply(pos) == entry.cube ? entry.view : null;
    }

    private void display(CubeAccess cube, long revision, @Nullable CubeLightView view) {
        if (view != null) {
            Map<CubePos, Entry> next = new HashMap<>(displayed);
            next.put(cube.cc_getCubePos(), new Entry(cube, revision, view));
            displayed = Map.copyOf(next);
            changed.accept(cube.cc_getCubePos());
        }
    }

    private void clearDisplay(CubePos pos) {
        if (displayed.containsKey(pos)) {
            Map<CubePos, Entry> next = new HashMap<>(displayed);
            next.remove(pos);
            displayed = Map.copyOf(next);
        }
    }

    /** Publish a complete provisional decrease/relight transaction; render workers never see the intermediate cleared field. */
    public synchronized void changeCell(BlockPos pos, BlockState previous) {
        Map<CubePos, CubeLightView> updates = ClientCubeLightPropagation.change(pos, previous, findCube, this::displayView);
        if (updates.isEmpty()) {
            return;
        }
        Map<CubePos, Entry> next = new HashMap<>(displayed);
        updates.forEach((at, view) -> {
            CubeAccess cube = findCube.apply(at);
            if (cube != null) {
                next.put(at, new Entry(cube, cube.cc_getLightRevision(), view));
            }
        });
        displayed = Map.copyOf(next);
        updates.keySet().forEach(changed);
    }

    /** Per-cube epochs let independent publications coexist without making the rest of the world dark. */
    public synchronized boolean applyScoped(CubeAccess cube, long revision, long epoch, @Nullable CubeLightView view, long generation) {
        validateEpoch(epoch);
        CubePos pos = cube.cc_getCubePos();
        if (view != null && (!view.pos().equals(pos) || !view.isReady())) {
            throw new IllegalArgumentException("Scoped light must match its complete block snapshot");
        }
        scoped = true;
        State previous = state;
        if (generation != previous.generation || findCube.apply(pos) != cube || cube.cc_getLightRevision() != revision
                || epoch < revoked.getOrDefault(pos, -1L) || epoch < installedEpochs.getOrDefault(pos, -1L)) {
            return false;
        }
        Map<CubePos, Entry> entries = new HashMap<>(previous.entries);
        entries.remove(pos);
        if (view != null) {
            entries.put(pos, new Entry(cube, revision, view));
            cancelMatchingRefresh(pos);
        }
        installedEpochs.put(pos, epoch);
        clearExpected(pos, epoch);
        state = new State(Math.max(previous.epoch, epoch), previous.lastInvalidation, previous.localRevocation, previous.generation,
                Map.copyOf(entries));
        display(cube, revision, view);
        return view != null;
    }

    public synchronized void invalidateScoped(CubePos pos, long epoch) {
        validateEpoch(epoch);
        scoped = true;
        if (findCube.apply(pos) == null || epoch < installedEpochs.getOrDefault(pos, -1L) || epoch <= revoked.getOrDefault(pos, -1L)) {
            return;
        }
        revoked.put(pos, epoch);
        forget(pos);
    }

    private void clearExpected(CubePos pos, long epoch) {
        expectedEpochs.computeIfPresent(pos, (key, pending) -> pending <= epoch ? null : pending);
    }

    public synchronized void invalidateLocalAt(CubePos edited) {
        if (!scoped) {
            invalidateLocal();
            return;
        }
        Map<CubePos, Long> known = new HashMap<>(installedEpochs);
        expectedEpochs.forEach((pos, epoch) -> known.merge(pos, epoch, Math::max));
        for (CubePos pos : known.keySet()) {
            if (nearColumn(pos, edited)) {
                revoked.merge(pos, Math.addExact(known.get(pos), 1), Math::max);
                forget(pos);
                if (refresh == null || !nearColumn(refresh.pos, edited)) {
                    refresh = new PendingRefresh(pos);
                }
            }
        }
        if (refresh != null && nearColumn(refresh.pos, edited) && findCube.apply(edited) != null) {
            // Center recovery on the edit, otherwise an edge anchor would miss the opposite neighboring column.
            refresh = new PendingRefresh(edited);
        }
    }

    public synchronized void remove(CubePos pos) {
        clearDisplay(pos);
        if (!scoped) {
            invalidateCache();
            return;
        }
        forget(pos);
        revoked.remove(pos);
        installedEpochs.remove(pos);
        expectedEpochs.remove(pos);
        // Identity/revision checks reject callbacks for the removed cube without invalidating unrelated entries.
    }

    /** Remember a complete packet waiting in the light queue without publishing its light or retaining the packet. */
    public synchronized void expect(CubeAccess cube, long epoch) {
        validateEpoch(epoch);
        CubePos pos = Objects.requireNonNull(cube, "cube").cc_getCubePos();
        if (!scoped && epoch < this.state.epoch || this.findCube.apply(pos) != cube) {
            return;
        }
        if (this.refresh == null || this.findCube.apply(this.refresh.pos) == null) {
            this.refresh = new PendingRefresh(pos);
        }
    }

    /** Also protect publications queued before their first light entry exists. */
    public synchronized void expectScoped(CubeAccess cube, long epoch) {
        validateEpoch(epoch);
        if (findCube.apply(cube.cc_getCubePos()) == cube) {
            scoped = true;
            expectedEpochs.merge(cube.cc_getCubePos(), epoch, Math::max);
            expect(cube, epoch);
        }
    }

    private static boolean nearColumn(CubePos left, CubePos right) {
        return Math.abs(left.getX() - right.getX()) <= 1 && Math.abs(left.getZ() - right.getZ()) <= 1;
    }

    /** Apply on the client thread after the matching block payload, checking again when a queued callback runs. */
    public synchronized boolean apply(CubeAccess cube, long revision, long epoch, @Nullable CubeLightView view, long generation) {
        validateEpoch(epoch);
        CubePos pos = Objects.requireNonNull(cube, "cube").cc_getCubePos();
        if (view != null && (!view.pos().equals(pos) || !view.isReady())) {
            throw new IllegalArgumentException("Client light must be complete and belong to its block snapshot");
        }
        State previous = this.state;
        if (!accepts(previous, cube, revision, epoch, generation)) {
            return false;
        }
        Map<CubePos, Entry> entries = epoch > previous.epoch ? new HashMap<>() : new HashMap<>(previous.entries);
        entries.remove(pos);
        if (view != null) {
            entries.put(pos, new Entry(cube, revision, view));
        }
        this.state = new State(epoch, previous.lastInvalidation, previous.localRevocation, previous.generation, Map.copyOf(entries));
        if (view != null) {
            cancelMatchingRefresh(pos);
        }
        display(cube, revision, view);
        return view != null;
    }

    private boolean accepts(State previous, CubeAccess cube, long revision, long epoch, long generation) {
        return generation == previous.generation && epoch >= previous.epoch && epoch > previous.localRevocation
                && this.findCube.apply(cube.cc_getCubePos()) == cube && cube.cc_getLightRevision() == revision;
    }

    private void cancelMatchingRefresh(CubePos pos) {
        if (this.refresh != null && this.refresh.pos.equals(pos)) {
            this.refresh = null;
        }
    }

    /** Reliable server revocation precedes snapshots belonging to this new epoch. Repeated/old revocations are harmless. */
    public synchronized void invalidate(long epoch) {
        validateEpoch(epoch);
        State previous = this.state;
        if (epoch < previous.epoch || epoch <= previous.lastInvalidation) {
            return;
        }
        this.state = new State(epoch, epoch, previous.localRevocation, previous.generation + 1, Map.of());
    }

    /** Block deltas and prediction revoke all dependent cubes until a newer server publication arrives. */
    public synchronized void invalidateLocal() {
        State previous = this.state;
        rememberRefresh(previous);
        this.state = new State(previous.epoch, previous.lastInvalidation, Math.max(previous.localRevocation, previous.epoch), previous.generation + 1,
                Map.of());
    }

    /** Eviction changes local availability, not server blocks; fresh full packets may restore the same server epoch. */
    public synchronized void invalidateCache() {
        State previous = this.state;
        rememberRefresh(previous);
        this.state = new State(previous.epoch, previous.lastInvalidation, previous.localRevocation, previous.generation + 1, Map.of());
    }

    /** Called once per connected client tick; one anchor and five bounded retries cannot accumulate world snapshots or requests. */
    public synchronized @Nullable RefreshRequest pollRefresh() {
        PendingRefresh pending = this.refresh;
        if (pending == null) {
            return null;
        }
        if (this.findCube.apply(pending.pos) == null) {
            this.refresh = null;
            return null;
        }
        if (pending.attempts >= REFRESH_RETRY_DELAYS.length || --pending.remainingTicks > 0) {
            return null;
        }
        pending.attempts++;
        if (pending.attempts < REFRESH_RETRY_DELAYS.length) {
            pending.remainingTicks = REFRESH_RETRY_DELAYS[pending.attempts];
        }
        // Server revocation alone does not restore light; retain retries using the newest observed epoch.
        return new RefreshRequest(pending.pos, this.state.epoch);
    }

    private void rememberRefresh(State previous) {
        if (this.refresh != null && this.findCube.apply(this.refresh.pos) != null) {
            return;
        }
        this.refresh = null;
        for (Map.Entry<CubePos, Entry> entry : previous.entries.entrySet()) {
            if (this.findCube.apply(entry.getKey()) == entry.getValue().cube) {
                this.refresh = new PendingRefresh(entry.getKey());
                return;
            }
        }
    }

    /** Full block packets replace their own entry; same-epoch packets for other cubes remain coherent. */
    public synchronized void forget(CubePos pos) {
        State previous = this.state;
        if (previous.entries.containsKey(pos)) {
            Map<CubePos, Entry> entries = new HashMap<>(previous.entries);
            entries.remove(pos);
            this.state = new State(previous.epoch, previous.lastInvalidation, previous.localRevocation, previous.generation, Map.copyOf(entries));
        }
    }

    private static void validateEpoch(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("Negative cube lighting epoch");
        }
    }

    public record RefreshRequest(CubePos pos, long observedEpoch) {}

    private static final class PendingRefresh {
        private final CubePos pos;
        private int attempts;
        private int remainingTicks = REFRESH_RETRY_DELAYS[0];

        private PendingRefresh(CubePos pos) {
            this.pos = pos;
        }
    }

    private record Entry(CubeAccess cube, long revision, CubeLightView view) {}

    private record State(long epoch, long lastInvalidation, long localRevocation, long generation, Map<CubePos, Entry> entries) {}
}
