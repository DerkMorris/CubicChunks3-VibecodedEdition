package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightCellInvalidation extends BaseTest {
    private static final CubePos SOURCE = CubePos.of(-2, -8, -3);

    @Test
    void aPreparedUnchangedNeighborWithoutAJobRecapturesAReplacedSourceSnapshot() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        List<CubePos> targets = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            CubePos pos = CubePos.of(100 + index * 4, SOURCE.getY(), SOURCE.getZ());
            fixture.sources.put(pos, new LevelCube(fixture.level, pos));
            targets.add(pos);
        }
        CubePos prepared = CubePos.of(SOURCE.getX() + 1, SOURCE.getY(), SOURCE.getZ());
        targets.add(prepared);
        try (var manager = fixture.manager()) {
            for (int pass = 0; pass < 100; pass++) {
                manager.tick(fixture.sources, targets);
            }
            var jobs = CubeWorldLightManager.class.getDeclaredField("jobs");
            jobs.setAccessible(true);
            assertEquals(32, ((Map<?, ?>) jobs.get(manager)).size());
            assertFalse(((Map<?, ?>) jobs.get(manager)).containsKey(prepared));
            var memo = CubeWorldLightManager.class.getDeclaredField("fullyCaptured");
            memo.setAccessible(true);
            assertTrue(((Set<?>) memo.get(manager)).contains(prepared));
            BlockPos edited = SOURCE.asBlockPos(16, 16, 16);
            assertFalse(CubeLightBounds.solverIncludes(prepared, edited));
            fixture.source().setBlockState(edited, Blocks.GLOWSTONE.defaultBlockState());
            manager.changedCell(fixture.source(), edited);
            manager.tick(fixture.sources, targets);
            var captures = CubeWorldLightManager.class.getDeclaredField("captures");
            captures.setAccessible(true);
            assertNotNull(((Map<?, ?>) captures.get(manager)).get(SOURCE),
                    "Retaining the old light does not mean retaining the removed full source snapshot");
            for (int batch = 0; batch < 3; batch++) {
                while (!fixture.work.isEmpty()) {
                    fixture.work.remove().run();
                }
                manager.tick(fixture.sources, targets);
            }
            assertNotNull(manager.view(prepared), "The prepared neighbor must start once the occupied job slots become available");
        }
    }

    @Test
    void exactFootprintsRetainUnrelatedPublishedAndQueuedResultsAcrossEveryAxis() {
        int[][] cases = { { 15, 15, 15, 1 }, { 16, 16, 16, 1 }, { 14, 15, 15, 2 }, { 14, 14, 15, 4 },
            { 14, 14, 14, 8 }, { 17, 17, 17, 8 }, { 31, 31, 31, 8 }, { 0, 0, 0, 8 } };
        for (int[] cell : cases) {
            for (boolean published : new boolean[] { false, true }) {
                Fixture fixture = new Fixture();
                try (var manager = fixture.manager()) {
                    fixture.prepare(manager);
                    if (published) {
                        fixture.publish(manager);
                    }
                    Map<CubePos, CubeLightView> before = new HashMap<>();
                    fixture.targets.forEach(pos -> before.put(pos, manager.view(pos)));
                    BlockPos edited = SOURCE.asBlockPos(cell[0], cell[1], cell[2]);
                    fixture.source().setBlockState(edited, Blocks.GLOWSTONE.defaultBlockState());
                    manager.changedCell(fixture.source(), edited);
                    if (!published) {
                        fixture.publish(manager);
                    }
                    int affected = 0;
                    for (CubePos target : fixture.targets) {
                        if (CubeLightBounds.solverIncludes(target, edited)) {
                            affected++;
                            assertNull(manager.view(target), "Every old result that reads this voxel must be revoked");
                        } else if (published) {
                            assertSame(before.get(target), manager.view(target), "Other footprints retain the same publication");
                        } else {
                            assertNotNull(manager.view(target), "An old queued result remains valid when it never reads the changed cell");
                        }
                    }
                    assertEquals(cell[3], affected);
                    fixture.prepare(manager);
                    fixture.publish(manager);
                    assertEquals(15, value(manager.view(SOURCE), edited));
                }
            }
        }
    }

    @Test
    void editsBeforeObservationAccumulateTheirAffectedQueuedJobs() {
        Fixture fixture = new Fixture();
        try (var manager = fixture.manager()) {
            fixture.prepare(manager);
            BlockPos first = SOURCE.asBlockPos(0, 0, 0);
            BlockPos second = SOURCE.asBlockPos(31, 31, 31);
            for (BlockPos edited : List.of(first, second)) {
                fixture.source().setBlockState(edited, Blocks.GLOWSTONE.defaultBlockState());
                manager.changedCell(fixture.source(), edited);
            }
            fixture.publish(manager);
            int rejected = 0;
            for (CubePos target : fixture.targets) {
                if (CubeLightBounds.solverIncludes(target, first) || CubeLightBounds.solverIncludes(target, second)) {
                    rejected++;
                    assertNull(manager.view(target));
                } else {
                    assertNotNull(manager.view(target));
                }
            }
            assertEquals(15, rejected);
            fixture.prepare(manager);
            fixture.publish(manager);
            assertEquals(15, value(manager.view(SOURCE), first));
            assertEquals(15, value(manager.view(SOURCE), second));
        }
    }

    @Test
    void bulkInvalidationAfterAClippedEditStillRejectsTheEntire27CubeHalo() {
        for (boolean published : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            try (var manager = fixture.manager()) {
                fixture.prepare(manager);
                if (published) {
                    fixture.publish(manager);
                }
                BlockPos edited = SOURCE.asBlockPos(16, 16, 16);
                fixture.source().setBlockState(edited, Blocks.GLOWSTONE.defaultBlockState());
                manager.changedCell(fixture.source(), edited);
                manager.changed(fixture.source(), false);
                if (!published) {
                    fixture.publish(manager);
                }
                fixture.targets.forEach(pos -> assertNull(manager.view(pos)));
            }
        }
    }

    @Test
    void anEmitterOutsideTheNeighborFootprintPreservesItButAnEdgeEmitterRelightsItExactly() {
        Fixture fixture = new Fixture();
        CubePos neighbor = CubePos.of(SOURCE.getX() + 1, SOURCE.getY(), SOURCE.getZ());
        try (var manager = fixture.manager()) {
            fixture.prepare(manager);
            fixture.publish(manager);
            var before = manager.view(neighbor);
            BlockPos middle = SOURCE.asBlockPos(16, 16, 16);
            fixture.source().setBlockState(middle, Blocks.GLOWSTONE.defaultBlockState());
            manager.changedCell(fixture.source(), middle);
            assertSame(before, manager.view(neighbor));
            fixture.prepare(manager);
            fixture.publish(manager);
            assertSame(before, manager.view(neighbor));
            assertEquals(0, value(before, neighbor.asBlockPos(0, 16, 16)));
            BlockPos edge = SOURCE.asBlockPos(31, 16, 16);
            fixture.source().setBlockState(edge, Blocks.GLOWSTONE.defaultBlockState());
            manager.changedCell(fixture.source(), edge);
            assertNull(manager.view(neighbor));
            fixture.prepare(manager);
            fixture.publish(manager);
            assertEquals(14, value(manager.view(neighbor), neighbor.asBlockPos(0, 16, 16)));
        }
    }

    @Test
    void supportedWorldEdgesClampTheCandidateHaloWithoutWrapping() {
        int min = -CubePos.MAX_COORDINATE_VALUE - 1;
        int max = CubePos.MAX_COORDINATE_VALUE;
        CubePos low = CubePos.of(min, min, min);
        CubePos high = CubePos.of(max, max, max);
        assertEquals(1, affected(low, low.asBlockPos(0, 0, 0)));
        assertEquals(8, affected(low, low.asBlockPos(31, 31, 31)));
        assertEquals(1, affected(high, high.asBlockPos(31, 31, 31)));
        assertEquals(8, affected(high, high.asBlockPos(0, 0, 0)));
    }

    private static long affected(CubePos source, BlockPos edited) {
        return CubeWorldLightManager.halo(source).positions().stream().filter(pos -> CubeLightBounds.solverIncludes(pos, edited)).count();
    }

    private static int value(CubeLightView view, BlockPos pos) {
        return view.sample(LightLayer.BLOCK, pos).orElseThrow().exactValue().orElseThrow();
    }

    private static final class Fixture {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final List<CubePos> targets = CubeWorldLightManager.halo(SOURCE).positions();
        private final Queue<Runnable> work = new ArrayDeque<>();

        private Fixture() {
            targets.forEach(pos -> sources.put(pos, new LevelCube(level, pos)));
        }

        private LevelCube source() {
            return (LevelCube) sources.get(SOURCE);
        }

        private CubeWorldLightManager manager() {
            return new CubeWorldLightManager(123, false, work::add, new CubeWorldLightManager.Storage() {
                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    return CompletableFuture.completedFuture(Map.of());
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    return CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
                }
            }, (pos, epoch) -> {});
        }

        private void prepare(CubeWorldLightManager manager) {
            for (int pass = 0; pass < 100; pass++) {
                manager.tick(sources, targets);
            }
        }

        private void publish(CubeWorldLightManager manager) {
            while (!work.isEmpty()) {
                work.remove().run();
            }
            manager.tick(sources, targets);
        }
    }
}
