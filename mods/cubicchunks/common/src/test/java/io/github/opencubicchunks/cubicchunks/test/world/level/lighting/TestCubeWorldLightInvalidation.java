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
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightInvalidation extends BaseTest {
    @Test
    void completedGrassEditPreservesDistantPublicationAndItsSkyIndex() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube surface = fixture.target(CubePos.of(0, 0, 0));
        BlockPos ground = surface.cc_getCubePos().asBlockPos(8, 8, 8);
        surface.setBlockState(ground, Blocks.DIRT.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            CubeLightView original = manager.view(deep.cc_getCubePos());
            assertNotNull(original);
            int submissions = fixture.submissions;
            surface.setBlockState(ground, Blocks.GRASS_BLOCK.defaultBlockState());
            manager.changed(surface, true);
            assertSame(original, manager.view(deep.cc_getCubePos()), "An identical sky summary must preserve distant exact light immediately");
            assertNull(manager.view(surface.cc_getCubePos()));
            fixture.settle(manager);
            assertSame(original, manager.view(deep.cc_getCubePos()));
            assertEquals(submissions + 1, fixture.submissions, "Only the changed cube needs another solve, with its existing sky indexes");
        }
    }

    @Test
    void repeatedCompletedGrassEditsRetainTheirSkyComparisonBaselineAcrossObservationTicks() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube surface = fixture.source(CubePos.of(0, 0, 0));
        BlockPos ground = surface.cc_getCubePos().asBlockPos(8, 8, 8);
        surface.setBlockState(ground, Blocks.DIRT.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            CubeLightView original = manager.view(deep.cc_getCubePos());
            int submissions = fixture.submissions;
            for (int edit = 0; edit < 20; edit++) {
                surface.setBlockState(ground, (edit % 2 == 0 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState());
                manager.changed(surface, true);
                assertSame(original, manager.view(deep.cc_getCubePos()), "Every completed edit has the same exact skylight occlusion");
                manager.tick(fixture.sources, fixture.targets);
                assertSame(original, manager.view(deep.cc_getCubePos()), "Observing a revision must retain its sky-only baseline for the next edit");
            }
            assertEquals(submissions, fixture.submissions, "An unchanged distant field needs neither another sky index nor another solve");
        }
    }

    @Test
    void completedGrassEditDoesNotCancelADistantCalculationAlreadyQueued() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube surface = fixture.target(CubePos.of(0, 0, 0));
        BlockPos ground = surface.cc_getCubePos().asBlockPos(8, 8, 8);
        surface.setBlockState(ground, Blocks.DIRT.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            assertEquals(9, fixture.work.size());
            for (int index = 0; index < 9; index++) {
                fixture.work.remove().run();
            }
            assertEquals(2, fixture.work.size(), "Both solves have immutable ready inputs but have not run");
            surface.setBlockState(ground, Blocks.GRASS_BLOCK.defaultBlockState());
            manager.changed(surface, true);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(deep.cc_getCubePos()), "Unchanged sky and a disjoint block halo keep the original result current");
            assertNull(manager.view(surface.cc_getCubePos()), "The changed cube still rejects its old block snapshot");
        }
    }

    @Test
    void newDeepSourceBelowAnUnchangedRoofPreservesOtherHeights() {
        Fixture fixture = new Fixture(80);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube higher = fixture.target(CubePos.of(0, -4, 0));
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            CubeLightView beforeDeep = manager.view(deep.cc_getCubePos());
            CubeLightView beforeHigher = manager.view(higher.cc_getCubePos());
            LevelCube added = fixture.target(CubePos.of(0, -12, 0));
            added.setBlockState(added.cc_getCubePos().asBlockPos(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState());
            manager.tick(fixture.sources, fixture.targets);
            assertSame(beforeDeep, manager.view(deep.cc_getCubePos()));
            assertSame(beforeHigher, manager.view(higher.cc_getCubePos()));
            fixture.settle(manager);
            assertSame(beforeDeep, manager.view(deep.cc_getCubePos()));
            assertEquals(15, value(manager.view(added.cc_getCubePos()), LightLayer.BLOCK, added.cc_getCubePos().asBlockPos(8, 8, 8)));
        }
    }

    @Test
    void emitterChangeRevokesNeighborHaloButPreservesDistantHeight() {
        Fixture fixture = new Fixture(80);
        LevelCube near = fixture.target(CubePos.of(0, -8, 0));
        LevelCube far = fixture.target(CubePos.of(0, -4, 0));
        LevelCube neighbor = fixture.source(CubePos.of(1, -8, 0));
        BlockPos emitter = neighbor.cc_getCubePos().asBlockPos(0, 16, 16);
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            CubeLightView distant = manager.view(far.cc_getCubePos());
            neighbor.setBlockState(emitter, Blocks.GLOWSTONE.defaultBlockState());
            manager.changed(neighbor, true);
            assertNull(manager.view(near.cc_getCubePos()));
            assertSame(distant, manager.view(far.cc_getCubePos()));
            fixture.settle(manager);
            assertEquals(14, value(manager.view(near.cc_getCubePos()), LightLayer.BLOCK, near.cc_getCubePos().asBlockPos(31, 16, 16)));
            assertSame(distant, manager.view(far.cc_getCubePos()));
        }
    }

    @Test
    void neighborBlockRevisionRejectsAnAlreadyQueuedResultWithoutSkylight() {
        Fixture fixture = new Fixture(80);
        LevelCube near = fixture.target(CubePos.of(0, -8, 0));
        LevelCube far = fixture.target(CubePos.of(0, -4, 0));
        LevelCube neighbor = fixture.source(CubePos.of(1, -8, 0));
        try (var manager = fixture.manager(false)) {
            fixture.capture(manager);
            neighbor.setBlockState(neighbor.cc_getCubePos().asBlockPos(0, 16, 16), Blocks.GLOWSTONE.defaultBlockState());
            manager.changed(neighbor, true);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNull(manager.view(near.cc_getCubePos()), "A neighbor edit must invalidate every old result that used its block states");
            assertNotNull(manager.view(far.cc_getCubePos()));
            fixture.settle(manager);
            assertEquals(14, value(manager.view(near.cc_getCubePos()), LightLayer.BLOCK, near.cc_getCubePos().asBlockPos(31, 16, 16)));
        }
    }

    @Test
    void completedRoofRemovalImmediatelyRevokesDistantLight() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube roof = fixture.source(CubePos.of(0, 0, 0));
        BlockPos obstruction = roof.cc_getCubePos().asBlockPos(8, 8, 8);
        roof.setBlockState(obstruction, Blocks.STONE.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            assertEquals(14, value(manager.view(deep.cc_getCubePos()), LightLayer.SKY, deep.cc_getCubePos().asBlockPos(8, 8, 8)));
            roof.setBlockState(obstruction, Blocks.AIR.defaultBlockState());
            manager.changed(roof, true);
            assertNull(manager.view(deep.cc_getCubePos()));
            fixture.settle(manager);
            assertEquals(15, value(manager.view(deep.cc_getCubePos()), LightLayer.SKY, deep.cc_getCubePos().asBlockPos(8, 8, 8)));
        }
    }

    @Test
    void bulkInvalidationDoesNotCaptureTheSectionsBeforeTheirReplacement() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        CountingCube roof = new CountingCube(fixture.level, CubePos.of(0, 0, 0));
        fixture.sources.put(roof.cc_getCubePos(), roof);
        BlockPos obstruction = roof.cc_getCubePos().asBlockPos(8, 8, 8);
        roof.setBlockState(obstruction, Blocks.STONE.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            roof.cc_invalidateHeightmaps();
            int reads = roof.sectionReads;
            manager.changed(roof, false);
            assertNull(manager.view(deep.cc_getCubePos()));
            assertEquals(reads, roof.sectionReads, "A pre-write callback must not repopulate sky caches with the old sections");
            roof.getSection(Coords.blockToIndex(obstruction)).setBlockState(SectionPos.sectionRelative(obstruction.getX()),
                    SectionPos.sectionRelative(obstruction.getY()), SectionPos.sectionRelative(obstruction.getZ()), Blocks.AIR.defaultBlockState());
            fixture.settle(manager);
            assertEquals(15, value(manager.view(deep.cc_getCubePos()), LightLayer.SKY, deep.cc_getCubePos().asBlockPos(8, 8, 8)));
        }
    }

    @Test
    void skyEdgeOnTheChangedCubesTopBoundaryIsNotAnIndependentRoof() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube roof = fixture.source(CubePos.of(0, 0, 0));
        roofPlane(roof, true);
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            BlockPos sample = deep.cc_getCubePos().asBlockPos(16, 8, 16);
            assertEquals(0, value(manager.view(deep.cc_getCubePos()), LightLayer.SKY, sample));
            roof.setBlockState(roof.cc_getCubePos().asBlockPos(16, 31, 16), Blocks.AIR.defaultBlockState());
            manager.changed(roof, true);
            assertNull(manager.view(deep.cc_getCubePos()), "The edge at maxY + 1 uses this cube's top face and must be invalidated");
            fixture.settle(manager);
            assertEquals(15, value(manager.view(deep.cc_getCubePos()), LightLayer.SKY, sample));
        }
    }

    @Test
    void shadowedNewSourceIsIncludedWhenTheHigherRoofIsRemoved() {
        Fixture fixture = new Fixture(-1000);
        LevelCube deep = fixture.target(CubePos.of(0, -8, 0));
        LevelCube highRoof = fixture.source(CubePos.of(0, 0, 0));
        roofPlane(highRoof, true);
        try (var manager = fixture.manager(true)) {
            fixture.settle(manager);
            CubeLightView original = manager.view(deep.cc_getCubePos());
            LevelCube lowRoof = fixture.source(CubePos.of(0, -4, 0));
            roofPlane(lowRoof, true);
            manager.tick(fixture.sources, fixture.targets);
            assertSame(original, manager.view(deep.cc_getCubePos()));
            roofPlane(highRoof, false);
            manager.changed(highRoof, true);
            assertNull(manager.view(deep.cc_getCubePos()));
            fixture.settle(manager);
            assertEquals(0, value(manager.view(deep.cc_getCubePos()), LightLayer.SKY, deep.cc_getCubePos().asBlockPos(16, 8, 16)),
                    "Rebuilding the index must use the new lower roof even though adding it did not invalidate the previous index");
        }
    }

    private static void roofPlane(LevelCube cube, boolean present) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                cube.setBlockState(cube.cc_getCubePos().asBlockPos(x, 31, z), (present ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
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
        private final List<CubePos> targets = new ArrayList<>();
        private final Queue<Runnable> work = new ArrayDeque<>();
        private final int baseline;
        private int submissions;

        private Fixture(int baseline) {
            this.baseline = baseline;
        }

        private LevelCube source(CubePos pos) {
            var cube = new LevelCube(level, pos);
            sources.put(pos, cube);
            return cube;
        }

        private LevelCube target(CubePos pos) {
            targets.add(pos);
            return source(pos);
        }

        private CubeWorldLightManager manager(boolean sky) {
            return new CubeWorldLightManager(123, sky, task -> {
                submissions++;
                work.add(task);
            }, new CubeWorldLightManager.Storage() {
                @Override public int baselineHeight(long seed, int x, int z) {
                    return baseline;
                }

                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    return CompletableFuture.completedFuture(Map.of());
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    return CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
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

        private void settle(CubeWorldLightManager manager) {
            for (int batch = 0; batch < 3; batch++) {
                capture(manager);
                drain();
                manager.tick(sources, targets);
            }
            assertTrue(targets.stream().allMatch(pos -> manager.view(pos) != null));
        }
    }
}
