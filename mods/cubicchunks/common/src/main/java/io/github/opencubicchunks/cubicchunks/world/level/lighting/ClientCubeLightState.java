package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

/** Immutable client publications, bound to the installed block snapshot rather than comparable server/client block counters. */
public final class ClientCubeLightState {
    private static final int[] REFRESH_RETRY_DELAYS = { 20, 40, 80, 160, 200 };
    private static final int MAX_REFRESH_COLUMNS = 64;
    private final Function<CubePos, CubeAccess> findCube;
    private final Consumer<CubePos> changed;
    private volatile State state = new State(0, -1, -1, 0, Map.of());
    private volatile Map<CubePos, Entry> displayed = Map.of();
    private @Nullable PendingRefresh refresh;
    private final Map<CubePos, PendingRefresh> scopedRefreshes = new LinkedHashMap<>();
    private int refreshCooldown;
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
        invalidateAffectedEntries(edited, true, null);
    }

    /** A completed cell edit can change direct sky only at the two vertical edges that touch it. */
    public synchronized void invalidateCell(BlockPos pos, BlockState previous) {
        CubePos edited = CubePos.from(pos);
        CubeAccess cube = findCube.apply(edited);
        if (cube != null && !LightEngine.hasDifferentLightProperties(previous, cube.getBlockState(pos))) {
            return;
        }
        boolean unchangedSky = hasUnchangedSkyEdges(pos, previous, cube);
        if (!unchangedSky && scoped && cube != null) {
            unchangedSky = hasAcceptedSkyProof(pos, cube) || hasLoadedRoofOnRay(pos, cube);
        }
        invalidateAffectedEntries(edited, !unchangedSky, pos);
    }

    private boolean hasUnchangedSkyEdges(BlockPos pos, BlockState previous, @Nullable CubeAccess cube) {
        CubeAccess above = findCube.apply(CubePos.from(pos.above()));
        CubeAccess below = findCube.apply(CubePos.from(pos.below()));
        if (cube == null || above == null || below == null) {
            return false;
        }
        BlockState current = cube.getBlockState(pos);
        BlockState upper = above.getBlockState(pos.above());
        BlockState lower = below.getBlockState(pos.below());
        return CubeSkyOcclusion.isEdgeOccluded(upper, previous) == CubeSkyOcclusion.isEdgeOccluded(upper, current)
                && CubeSkyOcclusion.isEdgeOccluded(previous, lower) == CubeSkyOcclusion.isEdgeOccluded(current, lower);
    }

    /** Direct sky is seeded at 15 and propagation never decreases it: an exact lower value proves a higher blocking edge. */
    private boolean hasAcceptedSkyProof(BlockPos edited, CubeAccess changedCube) {
        BlockPos above = edited.above();
        CubePos aboveCube = CubePos.from(above);
        if (acceptedSkyBelowMaximum(above, state.entries.get(aboveCube), changedCube)) {
            return true;
        }
        CubePos column = changedCube.cc_getCubePos();
        for (var entry : state.entries.entrySet()) {
            CubePos candidate = entry.getKey();
            if (higherSameColumn(column, candidate) && candidate.minCubeY() > above.getY()
                    && acceptedSkyBelowMaximum(new BlockPos(edited.getX(), candidate.minCubeY(), edited.getZ()), entry.getValue(), changedCube)) {
                return true;
            }
        }
        return false;
    }

    private boolean acceptedSkyBelowMaximum(BlockPos sample, @Nullable Entry entry, CubeAccess changedCube) {
        if (entry == null || findCube.apply(entry.cube.cc_getCubePos()) != entry.cube) {
            return false;
        }
        long revision = entry.cube.cc_getLightRevision();
        // The section already contains this one edit. Other accepted cubes must still match their exact installed revision.
        if (entry.cube == changedCube ? revision == 0 || entry.revision != revision - 1 : entry.revision != revision) {
            return false;
        }
        return entry.view.sample(LightLayer.SKY, sample).map(value -> value.upper() < LightEngine.MAX_LEVEL).orElse(false);
    }

    /** Only the edited ray can change its highest sky edge; an unaffected higher internal edge is a sufficient proof. */
    private boolean hasLoadedRoofOnRay(BlockPos edited, CubeAccess current) {
        if (hasHigherInternalEdge(edited, current)) {
            return true;
        }
        CubePos center = current.cc_getCubePos();
        for (CubePos candidate : installedEpochs.keySet()) {
            if (higherSameColumn(center, candidate) && hasHigherInternalEdge(edited, findCube.apply(candidate))) {
                return true;
            }
        }
        for (CubePos candidate : expectedEpochs.keySet()) {
            if (!installedEpochs.containsKey(candidate) && higherSameColumn(center, candidate)
                    && hasHigherInternalEdge(edited, findCube.apply(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static boolean higherSameColumn(CubePos center, CubePos candidate) {
        return candidate.getX() == center.getX() && candidate.getZ() == center.getZ() && candidate.getY() > center.getY();
    }

    private static boolean hasHigherInternalEdge(BlockPos edited, @Nullable CubeAccess cube) {
        if (cube == null) {
            return false;
        }
        CubePos pos = cube.cc_getCubePos();
        int lowestUpperY = Math.max(pos.minCubeY() + 1, edited.getY() + 2);
        int top = pos.maxCubeY();
        if (lowestUpperY > top) {
            return false;
        }
        // Scan at most 31 edges on this ray instead of rebuilding all 1024 sky summaries after every fluid edit.
        BlockState upper = cube.getBlockState(new BlockPos(edited.getX(), top, edited.getZ()));
        for (int y = top; y >= lowestUpperY; y--) {
            BlockState lower = cube.getBlockState(new BlockPos(edited.getX(), y - 1, edited.getZ()));
            if (CubeSkyOcclusion.isEdgeOccluded(upper, lower)) {
                return true;
            }
            upper = lower;
        }
        return false;
    }

    private void invalidateAffectedEntries(CubePos edited, boolean columnSkyChanged, @Nullable BlockPos changedCell) {
        if (!scoped) {
            invalidateLocal();
            return;
        }
        Map<CubePos, Long> known = new HashMap<>(installedEpochs);
        expectedEpochs.forEach((pos, epoch) -> known.merge(pos, epoch, Math::max));
        CubePos anchor = null;
        for (CubePos pos : known.keySet()) {
            if (affectedByEdit(pos, edited, columnSkyChanged, changedCell)) {
                revoked.merge(pos, Math.addExact(known.get(pos), 1), Math::max);
                forget(pos);
                if (anchor == null && findCube.apply(pos) != null) {
                    anchor = pos;
                }
            }
        }
        if (anchor != null) {
            // Center recovery on the edit, otherwise an edge anchor would miss the opposite neighboring column.
            retargetRefresh(findCube.apply(edited) == null ? anchor : edited);
        }
    }

    private static boolean affectedByEdit(CubePos pos, CubePos edited, boolean columnSkyChanged, @Nullable BlockPos changedCell) {
        if (columnSkyChanged) {
            return nearColumn(pos, edited);
        }
        if (changedCell != null) {
            return CubeLightBounds.solverIncludes(pos, changedCell);
        }
        return nearColumn(pos, edited) && Math.abs(pos.getY() - edited.getY()) <= 1;
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
        finishScopedRefresh(pos, false);
        // Identity/revision checks reject callbacks for the removed cube without invalidating unrelated entries.
    }

    /** Remember a complete packet waiting in the light queue without publishing its light or retaining the packet. */
    public synchronized void expect(CubeAccess cube, long epoch) {
        validateEpoch(epoch);
        CubePos pos = Objects.requireNonNull(cube, "cube").cc_getCubePos();
        if (!scoped && epoch < this.state.epoch || this.findCube.apply(pos) != cube) {
            return;
        }
        if (scoped) {
            queueScopedRefresh(pos, false);
        } else if (this.refresh == null || this.findCube.apply(this.refresh.pos) == null) {
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
        if (scoped) {
            finishScopedRefresh(pos, true);
            return;
        }
        if (this.refresh != null && this.refresh.pos.equals(pos)) {
            this.refresh = null;
        }
    }

    private void retargetRefresh(CubePos pos) {
        if (scoped) {
            queueScopedRefresh(pos, true);
            return;
        }
        if (refresh == null || refresh.attempts >= REFRESH_RETRY_DELAYS.length || findCube.apply(refresh.pos) == null || !nearColumn(refresh.pos, pos)) {
            resetRefresh(pos);
        } else {
            // Repeated edits may move the recovery anchor, but cannot postpone its next bounded retry forever.
            refresh.pos = pos;
        }
    }

    private void resetRefresh(CubePos pos) {
        int remaining = refresh == null ? REFRESH_RETRY_DELAYS[0] : Math.max(0, Math.min(refresh.remainingTicks, REFRESH_RETRY_DELAYS[0]));
        refresh = new PendingRefresh(pos);
        refresh.remainingTicks = remaining;
    }

    private void queueScopedRefresh(CubePos pos, boolean explicitEdit) {
        CubePos column = refreshColumn(pos);
        PendingRefresh pending = scopedRefreshes.get(column);
        if (pending != null) {
            pending.pos = pos;
            if (explicitEdit && pending.attempts >= REFRESH_RETRY_DELAYS.length) {
                pending.attempts = 0;
                pending.remainingTicks = Math.min(pending.remainingTicks, REFRESH_RETRY_DELAYS[0]);
            }
            return;
        }
        if (makeRefreshRoom(explicitEdit)) {
            scopedRefreshes.put(column, new PendingRefresh(pos));
        }
    }

    private boolean makeRefreshRoom(boolean explicitEdit) {
        scopedRefreshes.values().removeIf(pending -> findCube.apply(pending.pos) == null);
        if (scopedRefreshes.size() < MAX_REFRESH_COLUMNS) {
            return true;
        }
        if (explicitEdit) {
            var iterator = scopedRefreshes.values().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().attempts >= REFRESH_RETRY_DELAYS.length) {
                    iterator.remove();
                    return true;
                }
            }
        }
        return false;
    }

    private void finishScopedRefresh(CubePos pos, boolean progress) {
        CubePos column = refreshColumn(pos);
        PendingRefresh pending = scopedRefreshes.get(column);
        if (pending == null || !pending.pos.equals(pos)) {
            return;
        }
        CubePos missing = missingRefreshInColumn(pos);
        scopedRefreshes.remove(column);
        if (missing != null) {
            pending.pos = missing;
            scopedRefreshes.put(column, pending);
        }
        rememberMissingRefreshNeighbors(pos, revoked.keySet());
        rememberMissingRefreshNeighbors(pos, expectedEpochs.keySet());
        if (progress) {
            // A successful response can wake another waiting column without replenishing its retry budget.
            scopedRefreshes.values().forEach(waiting -> waiting.remainingTicks = Math.min(waiting.remainingTicks, REFRESH_RETRY_DELAYS[0]));
        }
    }

    private @Nullable CubePos missingRefreshInColumn(CubePos completed) {
        CubePos revokedNeighbor = missingRefreshInColumn(completed, revoked.keySet());
        return revokedNeighbor != null ? revokedNeighbor : missingRefreshInColumn(completed, expectedEpochs.keySet());
    }

    private @Nullable CubePos missingRefreshInColumn(CubePos completed, Iterable<CubePos> candidates) {
        for (CubePos missing : candidates) {
            if (!missing.equals(completed) && refreshColumn(missing).equals(refreshColumn(completed))
                    && findCube.apply(missing) != null && view(missing) == null) {
                return missing;
            }
        }
        return null;
    }

    private void rememberMissingRefreshNeighbors(CubePos completed, Iterable<CubePos> candidates) {
        for (CubePos missing : candidates) {
            if (!missing.equals(completed) && nearColumn(missing, completed) && findCube.apply(missing) != null && view(missing) == null) {
                queueScopedRefresh(missing, false);
            }
        }
    }

    private static CubePos refreshColumn(CubePos pos) {
        return CubePos.of(pos.getX(), 0, pos.getZ());
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
        scopedRefreshes.values().removeIf(pending -> findCube.apply(pending.pos) == null);
        rememberRefresh(previous);
        this.state = new State(previous.epoch, previous.lastInvalidation, previous.localRevocation, previous.generation + 1, Map.of());
    }

    /** Called once per client tick; bounded column retries share one request cooldown and retain no world snapshots. */
    public synchronized @Nullable RefreshRequest pollRefresh() {
        if (refreshCooldown > 0) {
            refreshCooldown--;
        }
        if (scoped) {
            return pollScopedRefresh();
        }
        PendingRefresh pending = this.refresh;
        if (pending == null) {
            return null;
        }
        if (this.findCube.apply(pending.pos) == null) {
            this.refresh = null;
            return null;
        }
        if (pending.remainingTicks > 0) {
            pending.remainingTicks--;
        }
        if (pending.attempts >= REFRESH_RETRY_DELAYS.length || pending.remainingTicks > 0 || refreshCooldown > 0) {
            return null;
        }
        pending.attempts++;
        refreshCooldown = REFRESH_RETRY_DELAYS[0];
        if (pending.attempts < REFRESH_RETRY_DELAYS.length) {
            pending.remainingTicks = REFRESH_RETRY_DELAYS[pending.attempts];
        }
        // Server revocation alone does not restore light; retain retries using the newest observed epoch.
        return new RefreshRequest(pending.pos, this.state.epoch);
    }

    private @Nullable RefreshRequest pollScopedRefresh() {
        scopedRefreshes.values().removeIf(pending -> findCube.apply(pending.pos) == null);
        for (PendingRefresh pending : scopedRefreshes.values()) {
            if (pending.remainingTicks > 0) {
                pending.remainingTicks--;
            }
        }
        if (refreshCooldown > 0) {
            return null;
        }
        var iterator = scopedRefreshes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingRefresh pending = entry.getValue();
            if (pending.attempts < REFRESH_RETRY_DELAYS.length && pending.remainingTicks == 0) {
                iterator.remove();
                scopedRefreshes.put(entry.getKey(), pending);
                pending.attempts++;
                refreshCooldown = REFRESH_RETRY_DELAYS[0];
                if (pending.attempts < REFRESH_RETRY_DELAYS.length) {
                    pending.remainingTicks = REFRESH_RETRY_DELAYS[pending.attempts];
                }
                return new RefreshRequest(pending.pos, state.epoch);
            }
        }
        return null;
    }

    private void rememberRefresh(State previous) {
        if (scoped) {
            previous.entries.forEach((pos, entry) -> {
                if (findCube.apply(pos) == entry.cube) {
                    queueScopedRefresh(pos, false);
                }
            });
            return;
        }
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
        private CubePos pos;
        private int attempts;
        private int remainingTicks = REFRESH_RETRY_DELAYS[0];

        private PendingRefresh(CubePos pos) {
            this.pos = pos;
        }
    }

    private record Entry(CubeAccess cube, long revision, CubeLightView view) {}

    private record State(long epoch, long lastInvalidation, long localRevocation, long generation, Map<CubePos, Entry> entries) {}
}
