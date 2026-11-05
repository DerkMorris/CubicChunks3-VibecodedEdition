package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ClientCubeLightState;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class TestClientCubeLightState extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-2, -800000, 3);
    private static final CubePos NEIGHBOR = CubePos.of(-1, -800000, 3);

    @Test
    void relightingRetainsDisplayButRejectsStalePublicationsAndStillAcceptsActualDarkness() throws Exception {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        var state = fixture.state;
        CubeLightView lit = CubeLightView.fromExact(POSITION, TestCubeLightView.filled(7), TestCubeLightView.filled(15));
        assertNull(state.displayView(POSITION), "An initial cube has no invented light");
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 7, lit, state.generation()));
        cube.setBlockState(POSITION.asBlockPos(), Blocks.STONE.defaultBlockState());
        state.invalidateLocalAt(POSITION);
        state.invalidateScoped(POSITION, 8);
        for (int tick = 0; tick < 40; tick++) {
            assertNull(state.view(POSITION));
            assertSame(lit, state.displayView(POSITION));
            state.pollRefresh();
        }
        assertSame(lit, CompletableFuture.supplyAsync(() -> state.displayView(POSITION)).get(5, TimeUnit.SECONDS));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 7, exact(POSITION), state.generation()));
        state.forget(POSITION); // Full block replacement happens before its queued lighting callback.
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 8, null, state.generation()));
        assertNull(state.view(POSITION));
        assertSame(lit, state.displayView(POSITION));
        CubeLightView dark = exact(POSITION);
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 9, dark, state.generation()));
        assertSame(dark, state.view(POSITION));
        assertSame(dark, state.displayView(POSITION), "A real zero-light result must replace the old bright field");
    }

    @Test
    void displayCannotLeakAcrossEvictionReplacementOrAnotherClientWorld() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        var state = fixture.state;
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 3, exact(POSITION), state.generation()));
        fixture.cubes.remove(POSITION);
        state.remove(POSITION);
        assertNull(state.displayView(POSITION));
        LevelCube replacement = fixture.add(POSITION);
        assertNull(state.displayView(POSITION));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 3, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(replacement, replacement.cc_getLightRevision(), 4, exact(POSITION), state.generation()));
        fixture.add(POSITION);
        assertNull(state.displayView(POSITION), "Even a missed eviction callback must not expose another cube instance's light");
        assertNull(new ClientCubeLightState(fixture.cubes::get, pos -> {}).displayView(POSITION));
    }

    @Test
    void readinessRevocationDoesNotRebuildUnchangedDisplayedGeometry() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubeLightView view = exact(POSITION);
        assertTrue(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 1, view, fixture.state.generation()));
        fixture.changed.clear();
        fixture.state.invalidateLocalAt(POSITION);
        fixture.state.invalidateScoped(POSITION, 2);
        fixture.state.forget(POSITION);
        assertNull(fixture.state.view(POSITION));
        assertSame(view, fixture.state.displayView(POSITION));
        assertTrue(fixture.changed.isEmpty());
        assertTrue(fixture.state.applyScoped(cube, cube.cc_getLightRevision(), 3, view, fixture.state.generation()));
        assertEquals(List.of(POSITION), fixture.changed);
    }

    @Test
    void legacyInvalidationAlsoRetainsDisplayUntilCubeRemoval() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubeLightView light = exact(POSITION);
        assertTrue(fixture.apply(cube, 3, light));
        fixture.state.invalidate(4);
        fixture.state.invalidateLocal();
        fixture.state.invalidateCache();
        assertNull(fixture.state.view(POSITION));
        assertSame(light, fixture.state.displayView(POSITION));
        fixture.state.remove(POSITION);
        assertNull(fixture.state.displayView(POSITION));
    }

    @Test
    void scopedPredictionRecoveryUsesEditedColumnRatherThanAnEdgeNeighbor() {
        Fixture fixture = new Fixture();
        CubePos edited = CubePos.of(0, 1000000, 0);
        fixture.add(edited);
        CubePos left = CubePos.of(-1, 1000000, 0);
        LevelCube cube = fixture.add(left);
        var state = fixture.state;
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 3, exact(left), state.generation()));
        state.invalidateLocalAt(edited);
        for (int tick = 1; tick < 20; tick++) {
            assertNull(state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(edited, 3), state.pollRefresh());
    }

    @Test
    void queuedScopedLightIsRevokedByRemoteRoofEditAndEvictionDoesNotClearOthers() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubePos far = CubePos.of(20, 1000000, 30);
        LevelCube distant = fixture.add(far);
        var state = fixture.state;
        state.expectScoped(cube, 7);
        state.expectScoped(distant, 8);
        long generation = state.generation();
        state.invalidateLocalAt(CubePos.of(POSITION.getX(), 1000000, POSITION.getZ()));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 7, exact(POSITION), generation));
        assertTrue(state.applyScoped(distant, distant.cc_getLightRevision(), 8, exact(far), generation));
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 9, exact(POSITION), generation));
        fixture.cubes.remove(POSITION);
        state.remove(POSITION);
        assertNotNull(state.view(far));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 9, exact(POSITION), generation));
    }

    @Test
    void scopedEpochsKeepUnrelatedCubesAndRejectLateCallbacksAfterRoofChange() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        CubePos far = CubePos.of(10, 1000000, 10);
        LevelCube distant = fixture.add(far);
        var state = fixture.state;
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 2, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(distant, distant.cc_getLightRevision(), 9, exact(far), state.generation()));
        assertNotNull(state.view(POSITION));
        state.invalidateScoped(POSITION, 10);
        assertNull(state.view(POSITION));
        assertNotNull(state.view(far));
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 2, exact(POSITION), state.generation()));
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 10, exact(POSITION), state.generation()));
        state.invalidateLocalAt(POSITION);
        assertFalse(state.applyScoped(cube, cube.cc_getLightRevision(), 10, exact(POSITION), state.generation()));
        assertNotNull(state.view(far));
        assertTrue(state.applyScoped(cube, cube.cc_getLightRevision(), 11, exact(POSITION), state.generation()));
    }

    @Test
    void sameEpochSnapshotsKeepBothCubesAndAreReadableByAWorker() throws Exception {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        fixture.state.invalidate(4);
        CubeLightView light = exact(POSITION);
        assertTrue(fixture.apply(cube, 4, light));
        assertTrue(fixture.apply(neighbor, 4, exact(NEIGHBOR)));
        assertSame(light, CompletableFuture.supplyAsync(() -> fixture.state.view(POSITION)).get(5, TimeUnit.SECONDS));
        assertTrue(fixture.state.view(NEIGHBOR).isReady());
        assertFalse(cube.isLightCorrect());
    }

    @Test
    void oldAndDuplicateRevocationsCannotRemoveANewerPublication() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidate(4);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        long generation = fixture.state.generation();
        fixture.state.invalidate(3);
        fixture.state.invalidate(4);
        assertEquals(generation, fixture.state.generation());
        assertTrue(fixture.state.view(POSITION).isReady());
        fixture.state.invalidate(5);
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 4, exact(POSITION)));
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
    }

    @Test
    void queuedCallbacksCannotCrossRevocationOrLocalBlockChanges() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        long generation = fixture.state.generation();
        fixture.state.invalidate(4);
        assertFalse(fixture.state.apply(cube, cube.cc_getLightRevision(), 4, exact(POSITION), generation));
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 4, exact(POSITION)));
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
    }

    @Test
    void sameObjectReplacementAndDifferentInstanceRejectQueuedLight() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        long revision = cube.cc_getLightRevision();
        cube.cc_invalidateHeightmaps();
        assertFalse(fixture.state.apply(cube, revision, 2, exact(POSITION), fixture.state.generation()));
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        fixture.add(POSITION);
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 3, exact(POSITION)));
    }

    @Test
    void unloadingOrMutatingAnInstalledCubeNeverReturnsItsOldView() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        assertTrue(fixture.apply(cube, 1, exact(POSITION)));
        cube.setBlockState(POSITION.asBlockPos(16, 16, 16), Blocks.STONE.defaultBlockState());
        assertNull(fixture.state.view(POSITION));
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        fixture.cubes.remove(POSITION);
        assertNull(fixture.state.view(POSITION));
        assertFalse(fixture.apply(cube, 2, exact(POSITION)));
    }

    @Test
    void fullReplacementClearsOwnLightWithoutDroppingOtherSameBatchCubes() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        assertTrue(fixture.apply(neighbor, 2, exact(NEIGHBOR)));
        fixture.state.forget(POSITION);
        cube.cc_invalidateHeightmaps();
        assertNull(fixture.state.view(POSITION));
        assertTrue(fixture.state.view(NEIGHBOR).isReady());
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        assertFalse(fixture.apply(cube, 2, null));
        assertNull(fixture.state.view(POSITION));
        fixture.state.invalidateLocal();
        assertNull(fixture.state.view(NEIGHBOR));
        assertTrue(fixture.changed.containsAll(List.of(POSITION, NEIGHBOR)));
    }

    @Test
    void higherEpochReplacesAllOldEntriesAndMalformedInputIsRejected() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        assertTrue(fixture.apply(cube, 2, exact(POSITION)));
        assertTrue(fixture.apply(neighbor, 3, exact(NEIGHBOR)));
        assertNull(fixture.state.view(POSITION));
        assertThrows(IllegalArgumentException.class, () -> fixture.state.invalidate(-1));
        assertThrows(IllegalArgumentException.class, () -> fixture.apply(cube, -1, exact(POSITION)));
        assertThrows(IllegalArgumentException.class, () -> fixture.apply(cube, 3, exact(NEIGHBOR)));
    }

    @Test
    void refreshBackoffIsBoundedAndRepeatedInvalidationsDoNotRestartIt() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        List<Integer> requestedAt = new ArrayList<>();
        for (int tick = 1; tick <= 1000; tick++) {
            fixture.state.invalidateLocal();
            ClientCubeLightState.RefreshRequest request = fixture.state.pollRefresh();
            if (request != null) {
                requestedAt.add(tick);
                assertEquals(POSITION, request.pos());
                assertEquals(4, request.observedEpoch());
            }
        }
        assertEquals(List.of(20, 60, 140, 300, 500), requestedAt);
        assertNull(fixture.state.view(POSITION));
    }

    @Test
    void serverRevocationAndUnavailableSnapshotsPreserveRecoveryUntilCompleteAnchorArrives() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        fixture.state.invalidate(5);
        assertFalse(fixture.apply(cube, 5, null));
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 5), fixture.state.pollRefresh());
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void evictionRecoversAStillCachedAnchorAndSameEpochSnapshotCancelsRetries() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        LevelCube neighbor = fixture.add(NEIGHBOR);
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        assertTrue(fixture.apply(neighbor, 4, exact(NEIGHBOR)));
        fixture.cubes.remove(POSITION);
        fixture.state.invalidateCache();
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(NEIGHBOR, 4), fixture.state.pollRefresh());
        assertTrue(fixture.apply(neighbor, 4, exact(NEIGHBOR)));
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void absentPublicationAndLostAnchorCannotGenerateRequests() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidateLocal();
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertTrue(fixture.apply(cube, 4, exact(POSITION)));
        fixture.state.invalidateLocal();
        fixture.cubes.remove(POSITION);
        fixture.state.invalidateCache();
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        LevelCube replacement = fixture.add(POSITION);
        assertTrue(fixture.apply(replacement, 5, exact(POSITION)));
        fixture.state.invalidateLocal();
        for (int tick = 1; tick < 20; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 5), fixture.state.pollRefresh());
    }

    @Test
    void firstQueuedPublicationRetainsRecoveryAfterLocalChangeWithoutExposingLight() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidate(4);
        long generation = fixture.state.generation();
        fixture.state.expect(cube, 4);
        assertEquals(generation, fixture.state.generation());
        assertNull(fixture.state.view(POSITION));
        for (int tick = 1; tick < 20; tick++) {
            fixture.state.expect(cube, 4);
            assertNull(fixture.state.pollRefresh());
        }
        fixture.state.invalidateLocal();
        assertFalse(fixture.state.apply(cube, cube.cc_getLightRevision(), 4, exact(POSITION), generation));
        assertEquals(new ClientCubeLightState.RefreshRequest(POSITION, 4), fixture.state.pollRefresh());
        fixture.state.invalidate(5);
        assertTrue(fixture.apply(cube, 5, exact(POSITION)));
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
    }

    @Test
    void staleEpochAndReplacedCubeCannotStartRefreshExpectation() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.add(POSITION);
        fixture.state.invalidate(4);
        fixture.state.expect(cube, 3);
        fixture.add(POSITION);
        fixture.state.expect(cube, 4);
        for (int tick = 0; tick < 1000; tick++) {
            assertNull(fixture.state.pollRefresh());
        }
        assertThrows(IllegalArgumentException.class, () -> fixture.state.expect(cube, -1));
    }

    private static CubeLightView exact(CubePos pos) {
        return CubeLightView.fromExact(pos, new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE],
                new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE]);
    }

    private static final class Fixture {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final Map<CubePos, CubeAccess> cubes = new HashMap<>();
        private final List<CubePos> changed = new ArrayList<>();
        private final ClientCubeLightState state = new ClientCubeLightState(this.cubes::get, this.changed::add);

        private LevelCube add(CubePos pos) {
            LevelCube cube = new LevelCube(this.level, pos);
            this.cubes.put(pos, cube);
            return cube;
        }

        private boolean apply(LevelCube cube, long epoch, CubeLightView view) {
            return this.state.apply(cube, cube.cc_getLightRevision(), epoch, view, this.state.generation());
        }
    }
}
