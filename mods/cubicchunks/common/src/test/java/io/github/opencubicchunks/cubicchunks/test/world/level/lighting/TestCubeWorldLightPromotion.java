package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightPromotion extends BaseTest {
    private static final CubePos CENTER = CubePos.of(0, -8, 0);
    private static final CubePos NEIGHBOR = CubePos.of(1, -8, 0);
    private static final BlockPos EMITTER = NEIGHBOR.asBlockPos(0, 16, 16);
    private static final BlockPos SAMPLE = CENTER.asBlockPos(31, 16, 16);

    @Test
    void unchangedPromotionPreservesNeighborPublicationSnapshotAndBlockVersion() throws Exception {
        Fixture fixture = new Fixture();
        try (var manager = fixture.manager()) {
            fixture.publish(manager);
            var view = manager.view(CENTER);
            Object capture = entry(manager, "captures", NEIGHBOR);
            Object version = entry(manager, "blockVersions", NEIGHBOR);
            int submissions = fixture.submissions;
            fixture.promote();
            manager.tick(fixture.sources, fixture.targets);
            assertSame(view, manager.view(CENTER), "Promotion alone must not revoke unchanged neighboring light");
            assertSame(capture, entry(manager, "captures", NEIGHBOR), "The immutable block snapshot is still authoritative");
            assertEquals(version, entry(manager, "blockVersions", NEIGHBOR));
            assertEquals(submissions, fixture.submissions, "No replacement calculation is required");
        }
    }

    @Test
    void unchangedPromotionKeepsAnAlreadyQueuedNeighborCalculation() throws Exception {
        Fixture fixture = new Fixture();
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            Object job = entry(manager, "jobs", CENTER);
            Object capture = entry(manager, "captures", NEIGHBOR);
            Object version = entry(manager, "blockVersions", NEIGHBOR);
            assertNotNull(job);
            fixture.promote();
            manager.tick(fixture.sources, fixture.targets);
            assertSame(job, entry(manager, "jobs", CENTER));
            assertSame(capture, entry(manager, "captures", NEIGHBOR));
            assertEquals(version, entry(manager, "blockVersions", NEIGHBOR));
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(CENTER), "The original result remains valid after identity-only promotion");
            assertEquals(1, fixture.submissions);
        }
    }

    @Test
    void aPromotedTargetRebuildsItsOwnPublicationWithoutRevokingItsNeighbor() {
        Fixture fixture = new Fixture();
        fixture.targets.add(NEIGHBOR);
        for (CubePos pos : CubeWorldLightManager.halo(NEIGHBOR).positions()) {
            fixture.sources.computeIfAbsent(pos, key -> new LevelCube(fixture.level, key));
        }
        try (var manager = fixture.manager()) {
            fixture.publish(manager);
            var neighborView = manager.view(CENTER);
            assertNotNull(manager.view(NEIGHBOR));
            LevelCube promoted = fixture.promote();
            manager.tick(fixture.sources, fixture.targets);
            assertSame(neighborView, manager.view(CENTER));
            assertNull(manager.view(NEIGHBOR), "A publication bound to the old ProtoCube cannot be sent with the new LevelCube");
            assertNull(manager.capture(promoted).light());
            fixture.publish(manager);
            assertSame(neighborView, manager.view(CENTER));
            assertNotNull(manager.view(NEIGHBOR));
            assertSame(manager.view(NEIGHBOR), manager.capture(promoted).light());
        }
    }

    @Test
    void editingTheNewLevelCubeBeforeObservationRevokesViewsAndRejectsOldJobs() {
        assertMutationRejected(Mutation.NEW_LEVEL);
    }

    @Test
    void editingTheProtoCubeAfterCaptureRevokesViewsAndRejectsOldJobs() {
        assertMutationRejected(Mutation.OLD_PROTO);
    }

    @Test
    void differentSectionObjectsCannotPreserveViewsOrOldJobsEvenAtRevisionZero() {
        assertMutationRejected(Mutation.DIFFERENT_SECTIONS);
    }

    private static void assertMutationRejected(Mutation mutation) {
        for (boolean published : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            try (var manager = fixture.manager()) {
                if (published) {
                    fixture.publish(manager);
                } else {
                    fixture.capture(manager);
                }
                switch (mutation) {
                    case NEW_LEVEL -> {
                        LevelCube promoted = fixture.promote();
                        promoted.setBlockState(EMITTER, Blocks.GLOWSTONE.defaultBlockState());
                        assertTrue(promoted.cc_getLightRevision() > 0);
                    }
                    case OLD_PROTO -> {
                        fixture.proto.setBlockState(EMITTER, Blocks.GLOWSTONE.defaultBlockState());
                        assertEquals(0, fixture.promote().cc_getLightRevision());
                    }
                    case DIFFERENT_SECTIONS -> {
                        LevelCube replacement = new LevelCube(fixture.level, NEIGHBOR);
                        // Set up the replacement body directly so revision alone cannot distinguish it from a pristine promotion.
                        replacement.getSection(Coords.blockToIndex(EMITTER)).setBlockState(0, 0, 0, Blocks.GLOWSTONE.defaultBlockState());
                        assertEquals(0, replacement.cc_getLightRevision());
                        assertEquals(0, fixture.proto.cc_getLightRevision());
                        fixture.sources.put(NEIGHBOR, replacement);
                    }
                    default -> throw new AssertionError("Unexpected mutation: " + mutation);
                }
                manager.tick(fixture.sources, fixture.targets);
                assertNull(manager.view(CENTER), "Changed promotion must revoke a prior neighbor publication");
                if (!published) {
                    fixture.work.remove().run();
                    manager.tick(fixture.sources, fixture.targets);
                    assertNull(manager.view(CENTER), "The pre-mutation queued result must never publish");
                }
                fixture.publish(manager);
                assertEquals(14, manager.view(CENTER).sample(LightLayer.BLOCK, SAMPLE).orElseThrow().exactValue().orElseThrow(),
                        "The eventual replacement must use the changed neighboring emitter");
            }
        }
    }

    private static Object entry(CubeWorldLightManager manager, String name, CubePos pos) throws ReflectiveOperationException {
        var field = CubeWorldLightManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(manager)).get(pos);
    }

    private enum Mutation {
        NEW_LEVEL, OLD_PROTO, DIFFERENT_SECTIONS
    }

    private static final class Fixture {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final ProtoCube proto = new ProtoCube(NEIGHBOR, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final List<CubePos> targets = new ArrayList<>(List.of(CENTER));
        private final Queue<Runnable> work = new ArrayDeque<>();
        private int submissions;

        private Fixture() {
            for (CubePos pos : CubeWorldLightManager.halo(CENTER).positions()) {
                sources.put(pos, pos.equals(NEIGHBOR) ? proto : new LevelCube(level, pos));
            }
        }

        private LevelCube promote() {
            LevelCube promoted = new LevelCube(level, proto, null);
            sources.put(NEIGHBOR, promoted);
            return promoted;
        }

        private CubeWorldLightManager manager() {
            return new CubeWorldLightManager(123, false, task -> {
                submissions++;
                work.add(task);
            }, new CubeWorldLightManager.Storage() {
                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    throw new AssertionError("No skylight input is needed for this block-light promotion regression");
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    throw new AssertionError("The complete halo is already loaded");
                }
            }, (pos, epoch) -> {});
        }

        private void capture(CubeWorldLightManager manager) {
            for (int tick = 0; tick < 100; tick++) {
                manager.tick(sources, targets);
            }
        }

        private void drain() {
            while (!work.isEmpty()) {
                work.remove().run();
            }
        }

        private void publish(CubeWorldLightManager manager) {
            capture(manager);
            drain();
            manager.tick(sources, targets);
            assertNotNull(manager.view(CENTER));
        }
    }
}
