package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.ToIntBiFunction;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightRoofCertificate extends BaseTest {
    @Test
    void clippedRoofEditOutsideTheBlockFootprintStillRevokesPublishedAndQueuedCertificates() {
        for (boolean published : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(-8, 20);
            try (var manager = fixture.manager()) {
                fixture.capture(manager);
                if (published) {
                    fixture.publish(manager);
                }
                LevelCube roof = fixture.openRoof(16, 16);
                BlockPos edited = roof.cc_getCubePos().asBlockPos(16, fixture.roofY, 16);
                assertFalse(CubeLightBounds.solverIncludes(fixture.center, edited));
                manager.changedCell(roof, edited);
                assertNull(manager.view(fixture.center));
                if (!published) {
                    fixture.publish(manager);
                    assertNull(manager.view(fixture.center), "The old queued dark result depended on this roof above the voxel footprint");
                }
                fixture.capture(manager);
                fixture.skyInput.complete(Map.of());
                fixture.publish(manager);
                assertEquals(15, value(manager.view(fixture.center), LightLayer.SKY, fixture.center.asBlockPos(16, 16, 16)));
            }
        }
    }

    @Test
    void aRoofCellOutsideTheHorizontalFootprintPreservesPublishedAndQueuedCertificates() {
        for (boolean published : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(-8, 20);
            try (var manager = fixture.manager()) {
                fixture.capture(manager);
                if (published) {
                    fixture.publish(manager);
                }
                CubeLightView before = manager.view(fixture.center);
                LevelCube roof = fixture.openRoofAt(1, 0, 16, 16);
                BlockPos edited = roof.cc_getCubePos().asBlockPos(16, fixture.roofY, 16);
                manager.changedCell(roof, edited);
                fixture.publish(manager);
                assertNotNull(manager.view(fixture.center));
                if (published) {
                    assertSame(before, manager.view(fixture.center));
                }
                assertEquals(0, fixture.columnReads, "An edit outside the proven rays must not create global sky dependencies");
            }
        }
    }

    @Test
    void retiringALocalRoofForgetsThePreparationThatSkippedDistantSkySources() throws ReflectiveOperationException {
        Fixture fixture = new Fixture(-8, 20);
        CountingCube distant = new CountingCube(fixture.level, CubePos.of(0, 20, 0));
        fixture.sources.put(distant.cc_getCubePos(), distant);
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            var field = CubeWorldLightManager.class.getDeclaredField("fullyCaptured");
            field.setAccessible(true);
            Set<?> prepared = (Set<?>) field.get(manager);
            assertTrue(prepared.contains(fixture.center));
            assertEquals(0, distant.sectionReads, "The local roof lets preparation omit distant sky sources");
            fixture.sources.remove(CubePos.of(0, fixture.center.getY() + 1, 0));
            manager.tick(fixture.sources, List.of(fixture.center));
            var ready = CubeWorldLightManager.class.getDeclaredMethod("ready", CubePos.class);
            ready.setAccessible(true);
            assertFalse((boolean) ready.invoke(manager, fixture.center), "Without the live roof, the next preparation needs distant sky captures");
            assertFalse(prepared.contains(fixture.center), "An acknowledged unload must not retain the obsolete preparation shortcut");
            fixture.publish(manager);
            assertNotNull(manager.view(fixture.center), "The existing job still owns the exact immutable roof it originally captured");
        }
    }

    @Test
    void certifiedRoofMatchesCompleteSkyAndBlockSolverAtBothHeightSignsWithoutSurfaceReads() {
        for (int y : new int[] { -8, 1000000 }) {
            Fixture fixture = new Fixture(y, 20);
            BlockPos emitter = fixture.center.asBlockPos(8, 8, 8);
            fixture.cube(fixture.center).setBlockState(emitter, Blocks.GLOWSTONE.defaultBlockState());
            CountingCube distant = new CountingCube(fixture.level, CubePos.of(0, y + 10, 0));
            fixture.sources.put(distant.cc_getCubePos(), distant);
            try (var manager = fixture.manager()) {
                fixture.capture(manager);
                assertEquals(1, fixture.work.size(), "A certified solve is ready without starting sky-index tasks");
                fixture.publish(manager);
                CubeLightView actual = manager.view(fixture.center);
                assertNotNull(actual);
                fixture.assertExact(actual, (x, z) -> fixture.roofEdge());
                assertEquals(15, value(actual, LightLayer.BLOCK, emitter));
                assertEquals(0, fixture.columnReads);
                assertEquals(0, fixture.blockReads);
                assertEquals(0, distant.sectionReads, "A local certificate must not require captures at unrelated heights");
                assertTrue(!fixture.skyInput.isDone(), "The native surface may remain unavailable indefinitely");
            }
        }
    }

    @Test
    void missingUpperHaloCubeCannotCertifySkylight() {
        Fixture fixture = new Fixture(-8, 20);
        fixture.sources.remove(CubePos.of(1, fixture.center.getY() + 1, 1));
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            assertNull(manager.view(fixture.center));
            assertEquals(9, fixture.columnReads);
            assertTrue(fixture.work.isEmpty());
        }
    }

    @Test
    void holesImmediatelyOutsideTheSolverFootprintDoNotInvalidateTheCertificate() {
        Fixture fixture = new Fixture(-8, 20);
        for (int[] hole : new int[][] { { -1, 0, 16, 16 }, { 1, 0, 15, 16 }, { 0, -1, 16, 16 }, { 0, 1, 16, 15 } }) {
            fixture.openRoofAt(hole[0], hole[1], hole[2], hole[3]);
        }
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            fixture.publish(manager);
            CubeLightView actual = manager.view(fixture.center);
            assertNotNull(actual);
            assertEquals(0, fixture.columnReads, "The extra 17-block strips of neighbor cubes lie outside the 15-block solver halo");
            fixture.assertExact(actual, (x, z) -> x == -16 || x == 47 || z == -16 || z == 47 ? -1000 : fixture.roofEdge());
        }
    }

    @Test
    void holesInTheFirstInsideCellOnEveryFootprintEdgeRejectTheCertificate() {
        for (int[] hole : new int[][] { { -1, 0, 17, 16 }, { 1, 0, 14, 16 }, { 0, -1, 16, 17 }, { 0, 1, 16, 14 } }) {
            Fixture fixture = new Fixture(-8, 20);
            fixture.openRoofAt(hole[0], hole[1], hole[2], hole[3]);
            try (var manager = fixture.manager()) {
                fixture.capture(manager);
                assertNull(manager.view(fixture.center));
                assertEquals(9, fixture.columnReads, "Every direct-sky seed inside the solver footprint must remain accounted for");
                assertTrue(fixture.work.isEmpty());
            }
        }
    }

    @Test
    void oneRoofHoleWaitsForCompleteColumnsAndThenMatchesTheUnboundedSkySource() {
        Fixture fixture = new Fixture(-8, 20);
        fixture.openRoof(16, 16);
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            assertEquals(9, fixture.columnReads);
            assertNull(manager.view(fixture.center));
            fixture.skyInput.complete(Map.of());
            fixture.publish(manager);
            CubeLightView actual = manager.view(fixture.center);
            assertEquals(15, value(actual, LightLayer.SKY, fixture.center.asBlockPos(16, 8, 16)));
            fixture.assertExact(actual, (x, z) -> x == 16 && z == 16 ? -1000 : fixture.roofEdge());
        }
    }

    @Test
    void roofEdgeExactlyAtTheTopSolverCellDoesNotCertifyDarkSeeds() {
        Fixture fixture = new Fixture(-8, 13);
        assertEquals(fixture.center.maxCubeY() + 15, fixture.roofEdge());
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            assertEquals(9, fixture.columnReads);
            assertNull(manager.view(fixture.center));
            assertTrue(fixture.work.isEmpty());
        }
    }

    @Test
    void removingTheLocalRoofImmediatelyRevokesItsCertifiedPublication() {
        Fixture fixture = new Fixture(-8, 20);
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            fixture.publish(manager);
            assertNotNull(manager.view(fixture.center));
            LevelCube roof = fixture.openRoof(16, 16);
            manager.changed(roof, true);
            assertNull(manager.view(fixture.center));
            fixture.capture(manager);
            assertEquals(9, fixture.columnReads);
            assertNull(manager.view(fixture.center), "A revoked certificate must not substitute darkness while its actual sky inputs wait");
            fixture.skyInput.complete(Map.of());
            fixture.publish(manager);
            assertEquals(15, value(manager.view(fixture.center), LightLayer.SKY, fixture.center.asBlockPos(16, 8, 16)));
        }
    }

    @Test
    void distantColumnChangesPreserveCertifiedQueuedAndPublishedResults() {
        Fixture fixture = new Fixture(-8, 20);
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            CubePos far = CubePos.of(0, fixture.center.getY() + 10, 0);
            LevelCube distant = new LevelCube(fixture.level, far);
            fixture.sources.put(far, distant);
            distant.setBlockState(far.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
            manager.changed(distant, true);
            fixture.publish(manager);
            CubeLightView original = manager.view(fixture.center);
            assertNotNull(original, "A certified job depends on its block halo, not distant surface-column revisions");
            distant.setBlockState(far.asBlockPos(8, 8, 8), Blocks.AIR.defaultBlockState());
            manager.changed(distant, true);
            assertSame(original, manager.view(fixture.center));
            fixture.capture(manager);
            fixture.publish(manager);
            assertSame(original, manager.view(fixture.center));
            assertEquals(0, fixture.columnReads);
        }
    }

    @Test
    void aRoofMutationRejectsAnAlreadyQueuedCertifiedResult() {
        Fixture fixture = new Fixture(-8, 20);
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            LevelCube roof = fixture.openRoof(16, 16);
            manager.changed(roof, true);
            fixture.publish(manager);
            assertNull(manager.view(fixture.center), "The old all-dark calculation used a roof whose block revision has changed");
            fixture.capture(manager);
            assertEquals(9, fixture.columnReads);
            fixture.skyInput.complete(Map.of());
            fixture.publish(manager);
            assertEquals(15, value(manager.view(fixture.center), LightLayer.SKY, fixture.center.asBlockPos(16, 8, 16)));
        }
    }

    @Test
    void localEmitterRemovalRelightsCertifiedCubesWithoutAnySurfaceInput() {
        Fixture fixture = new Fixture(-8, 20);
        LevelCube center = fixture.cube(fixture.center);
        BlockPos emitter = fixture.center.asBlockPos(8, 8, 8);
        center.setBlockState(emitter, Blocks.GLOWSTONE.defaultBlockState());
        try (var manager = fixture.manager()) {
            fixture.capture(manager);
            fixture.publish(manager);
            assertEquals(15, value(manager.view(fixture.center), LightLayer.BLOCK, emitter));
            center.setBlockState(emitter, Blocks.AIR.defaultBlockState());
            manager.changed(center, true);
            assertNull(manager.view(fixture.center));
            fixture.capture(manager);
            fixture.publish(manager);
            assertEquals(0, value(manager.view(fixture.center), LightLayer.BLOCK, emitter));
            assertEquals(0, fixture.columnReads);
        }
    }

    private static int value(CubeLightView view, LightLayer layer, BlockPos pos) {
        return view.sample(layer, pos).orElseThrow().exactValue().orElseThrow();
    }

    private static final class CountingCube extends LevelCube {
        private int sectionReads;

        private CountingCube(Level level, CubePos pos) {
            super(level, pos);
        }

        @Override public LevelChunkSection[] getSections() {
            sectionReads++;
            return super.getSections();
        }
    }

    private static final class Fixture {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final Queue<Runnable> work = new ArrayDeque<>();
        private final CompletableFuture<Map<Integer, CubeSkyOcclusion>> skyInput = new CompletableFuture<>();
        private final CubePos center;
        private final int roofY;
        private int columnReads;
        private int blockReads;

        private Fixture(int cubeY, int roofY) {
            center = CubePos.of(0, cubeY, 0);
            this.roofY = roofY;
            for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
                LevelCube cube = new LevelCube(level, pos);
                sources.put(pos, cube);
                if (pos.getY() == cubeY + 1) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            cube.getSection(Coords.blockToIndex(x, roofY, z)).setBlockState(SectionPos.sectionRelative(x),
                                    SectionPos.sectionRelative(roofY), SectionPos.sectionRelative(z), Blocks.STONE.defaultBlockState());
                        }
                    }
                    cube.cc_invalidateHeightmaps();
                }
            }
        }

        private int roofEdge() {
            return center.minCubeY() + 32 + roofY + 1;
        }

        private LevelCube cube(CubePos pos) {
            return (LevelCube) sources.get(pos);
        }

        private LevelCube openRoof(int x, int z) {
            return openRoofAt(0, 0, x, z);
        }

        private LevelCube openRoofAt(int dx, int dz, int x, int z) {
            LevelCube roof = cube(CubePos.of(dx, center.getY() + 1, dz));
            roof.setBlockState(roof.cc_getCubePos().asBlockPos(x, roofY, z), Blocks.AIR.defaultBlockState());
            return roof;
        }

        private CubeWorldLightManager manager() {
            return new CubeWorldLightManager(123, true, work::add, new CubeWorldLightManager.Storage() {
                @Override public int baselineHeight(long seed, int x, int z) {
                    return -1000;
                }

                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    columnReads++;
                    return skyInput;
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    blockReads++;
                    return CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
                }
            }, (pos, epoch) -> {});
        }

        private void capture(CubeWorldLightManager manager) {
            for (int tick = 0; tick < 100; tick++) {
                manager.tick(sources, List.of(center));
            }
        }

        private void publish(CubeWorldLightManager manager) {
            while (!work.isEmpty()) {
                work.remove().run();
            }
            manager.tick(sources, List.of(center));
        }

        private void assertExact(CubeLightView actual, ToIntBiFunction<Integer, Integer> sky) {
            Map<CubePos, CubeLightBlockSnapshot> blocks = new HashMap<>();
            for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
                blocks.put(pos, CubeLightBlockSnapshot.capture(cube(pos)));
            }
            CubeLightView expected = CubeWorldLightSolver.solve(center, blocks, sky);
            for (LightLayer layer : LightLayer.values()) {
                for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                    assertArrayEquals(expected.packedLayer(layer, section).orElseThrow(), actual.packedLayer(layer, section).orElseThrow());
                }
            }
        }
    }
}
