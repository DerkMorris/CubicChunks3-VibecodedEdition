package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightArrival extends BaseTest {
    private static final CubePos TARGET = CubePos.of(0, -8, 0);
    private static final CubePos NEIGHBOR = CubePos.of(1, -8, 0);
    private static final CubePos ROOF = CubePos.of(0, 2, 0);
    private static final CubePos DEEP_ROOF = CubePos.of(0, -4, 0);

    @Test
    void moreThan128ResolvedFallbacksRemainAvailableForExactLiveArrivalComparison() throws ReflectiveOperationException {
        for (boolean changed : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            try (var manager = fixture.manager(false)) {
                fixture.prepare(manager);
                fixture.finish(manager);
                var original = manager.view(TARGET);
                for (int index = 1; index <= 6; index++) {
                    CubePos pos = CubePos.of(index * 4, TARGET.getY(), 0);
                    fixture.sources.put(pos, fixture.cube(pos));
                    fixture.targets.add(pos);
                }
                fixture.prepare(manager);
                fixture.finish(manager);
                var cacheField = CubeWorldLightManager.class.getDeclaredField("diskBlocks");
                cacheField.setAccessible(true);
                Map<?, ?> cached = (Map<?, ?>) cacheField.get(manager);
                assertTrue(cached.size() > 128);
                assertTrue(cached.containsKey(NEIGHBOR));
                int reads = fixture.storageReads;
                LevelCube added = fixture.cube(NEIGHBOR);
                if (changed) {
                    added.setBlockState(NEIGHBOR.asBlockPos(0, 16, 16), Blocks.GLOWSTONE.defaultBlockState());
                }
                fixture.sources.put(NEIGHBOR, added);
                manager.tick(fixture.sources, fixture.targets);
                if (changed) {
                    assertNull(manager.view(TARGET));
                } else {
                    assertSame(original, manager.view(TARGET));
                }
                assertEquals(reads, fixture.storageReads, "Comparing completed fallback data must not request it again");
                assertFalse(cached.containsKey(NEIGHBOR), "A retained live snapshot replaces the extra fallback-cache reference");
                fixture.prepare(manager);
                fixture.finish(manager);
                assertEquals(changed ? 14 : 0, manager.view(TARGET).sample(LightLayer.BLOCK, TARGET.asBlockPos(31, 16, 16)).orElseThrow().lower());
            }
        }
    }

    @Test
    void resolvedDeepBaselinePreservesPublishedAndQueuedResultsWithoutGeneratingAgain() {
        for (boolean publishFirst : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            fixture.deep = true;
            fixture.floor = 0;
            LevelCube roof = fixture.cube(DEEP_ROOF);
            roof.setBlockState(DEEP_ROOF.asBlockPos(16, 16, 16), Blocks.STONE.defaultBlockState());
            fixture.generatedSky.put(DEEP_ROOF, roof.cc_snapshotSkyOcclusion());
            try (var manager = fixture.manager(true)) {
                fixture.prepare(manager);
                fixture.completeSkyIndexes();
                if (publishFirst) {
                    fixture.finish(manager);
                }
                var original = manager.view(TARGET);
                int reads = fixture.deepReads;
                int submissions = fixture.submissions;
                assertTrue(reads > 0);
                fixture.sources.put(DEEP_ROOF, roof);
                manager.tick(fixture.sources, fixture.targets);
                if (publishFirst) {
                    assertSame(original, manager.view(TARGET));
                }
                fixture.finish(manager);
                assertNotNull(manager.view(TARGET));
                assertEquals(reads, fixture.deepReads);
                assertEquals(submissions, fixture.submissions);
            }
        }
    }

    @Test
    void changedResolvedDeepBaselineStillRevokesTheDistantResult() {
        Fixture fixture = new Fixture();
        fixture.deep = true;
        fixture.floor = 0;
        LevelCube roof = fixture.cube(DEEP_ROOF);
        roof.setBlockState(DEEP_ROOF.asBlockPos(16, 16, 16), Blocks.STONE.defaultBlockState());
        fixture.generatedSky.put(DEEP_ROOF, roof.cc_snapshotSkyOcclusion());
        try (var manager = fixture.manager(true)) {
            fixture.prepare(manager);
            fixture.finish(manager);
            assertEquals(14, manager.view(TARGET).sample(LightLayer.SKY, TARGET.asBlockPos(16, 16, 16)).orElseThrow().lower());
            fixture.sources.put(DEEP_ROOF, fixture.cube(DEEP_ROOF));
            manager.tick(fixture.sources, fixture.targets);
            assertNull(manager.view(TARGET));
            fixture.prepare(manager);
            fixture.finish(manager);
            assertEquals(15, manager.view(TARGET).sample(LightLayer.SKY, TARGET.asBlockPos(16, 16, 16)).orElseThrow().lower());
        }
    }

    @Test
    void anUnlitRefreshAnchorCanRepublishCurrentNeighborsWithoutReadingOrSolving() {
        Fixture fixture = new Fixture();
        fixture.sources.put(ROOF, fixture.cube(ROOF));
        try (var manager = fixture.manager(false)) {
            fixture.prepare(manager);
            fixture.finish(manager);
            var original = manager.view(TARGET);
            assertNotNull(original);
            assertNull(manager.view(ROOF));
            int submissions = fixture.submissions;
            int reads = fixture.storageReads;
            long epoch = manager.epoch();
            assertTrue(manager.republish(ROOF));
            assertEquals(epoch + 1, manager.epoch());
            assertSame(original, manager.view(TARGET));
            assertEquals(submissions, fixture.submissions);
            assertEquals(reads, fixture.storageReads);
            assertTrue(fixture.work.isEmpty());
        }
    }

    @Test
    void refreshWithoutAnyCurrentNeighborReturnsFalseWithoutStartingMoreWork() {
        Fixture fixture = new Fixture();
        try (var manager = fixture.manager(false)) {
            fixture.prepare(manager);
            assertNull(manager.view(TARGET));
            int submissions = fixture.submissions;
            int reads = fixture.storageReads;
            long epoch = manager.epoch();
            assertFalse(manager.republish(ROOF));
            assertEquals(epoch, manager.epoch());
            assertEquals(submissions, fixture.submissions);
            assertEquals(reads, fixture.storageReads);
        }
    }

    @Test
    void identicalBlockArrivalPreservesPublishedAndQueuedNeighborResults() {
        for (boolean publishFirst : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            try (var manager = fixture.manager(false)) {
                fixture.prepare(manager);
                if (publishFirst) {
                    fixture.finish(manager);
                }
                var original = manager.view(TARGET);
                fixture.sources.put(NEIGHBOR, fixture.cube(NEIGHBOR));
                manager.tick(fixture.sources, fixture.targets);
                if (publishFirst) {
                    assertSame(original, manager.view(TARGET));
                }
                fixture.finish(manager);
                assertNotNull(manager.view(TARGET));
                assertEquals(1, fixture.submissions, "Identical arrival must retain the original immutable calculation");
            }
        }
    }

    @Test
    void changedBlockArrivalRevokesPublishedAndQueuedNeighborResults() {
        for (boolean publishFirst : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            try (var manager = fixture.manager(false)) {
                fixture.prepare(manager);
                if (publishFirst) {
                    fixture.finish(manager);
                }
                LevelCube added = fixture.cube(NEIGHBOR);
                added.setBlockState(NEIGHBOR.asBlockPos(0, 16, 16), Blocks.GLOWSTONE.defaultBlockState());
                fixture.sources.put(NEIGHBOR, added);
                manager.tick(fixture.sources, fixture.targets);
                assertNull(manager.view(TARGET));
                fixture.finish(manager);
                fixture.prepare(manager);
                fixture.finish(manager);
                assertEquals(14, manager.view(TARGET).sample(LightLayer.BLOCK, TARGET.asBlockPos(31, 16, 16)).orElseThrow().lower());
            }
        }
    }

    @Test
    void identicalSkyArrivalAtAnotherHeightPreservesPublishedAndQueuedResults() {
        for (boolean publishFirst : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            LevelCube roof = fixture.roof();
            fixture.savedRoof(roof);
            try (var manager = fixture.manager(true)) {
                fixture.prepare(manager);
                fixture.completeSkyIndexes();
                if (publishFirst) {
                    fixture.finish(manager);
                }
                var original = manager.view(TARGET);
                int submissions = fixture.submissions;
                fixture.sources.put(ROOF, roof);
                manager.tick(fixture.sources, fixture.targets);
                if (publishFirst) {
                    assertSame(original, manager.view(TARGET));
                }
                fixture.finish(manager);
                assertNotNull(manager.view(TARGET));
                assertEquals(submissions, fixture.submissions, "Matching saved sky must retain both the index and its queued calculation");
            }
        }
    }

    @Test
    void roofHoleOnArrivalInvalidatesTheDistantSkyResult() {
        Fixture fixture = new Fixture();
        fixture.savedRoof(fixture.roof());
        try (var manager = fixture.manager(true)) {
            fixture.prepare(manager);
            fixture.finish(manager);
            assertEquals(14, manager.view(TARGET).sample(LightLayer.SKY, TARGET.asBlockPos(16, 16, 16)).orElseThrow().lower());
            fixture.sources.put(ROOF, fixture.cube(ROOF));
            manager.tick(fixture.sources, fixture.targets);
            assertNull(manager.view(TARGET));
            fixture.prepare(manager);
            fixture.finish(manager);
            assertEquals(15, manager.view(TARGET).sample(LightLayer.SKY, TARGET.asBlockPos(16, 16, 16)).orElseThrow().lower());
        }
    }

    @Test
    void equivalentBoundaryLeafStateOnArrivalPreservesDistantLight() {
        Fixture fixture = new Fixture();
        LevelCube roof = fixture.cube(ROOF);
        var leaf = ROOF.asBlockPos(16, 31, 16);
        roof.setBlockState(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 7));
        fixture.savedRoof(roof);
        roof.setBlockState(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
        try (var manager = fixture.manager(true)) {
            fixture.prepare(manager);
            fixture.finish(manager);
            var original = manager.view(TARGET);
            assertNotNull(original);
            fixture.sources.put(ROOF, roof);
            manager.tick(fixture.sources, fixture.targets);
            assertSame(original, manager.view(TARGET));
        }
    }

    @Test
    void incompleteBlockBaselineCannotCertifyAnArrival() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        var pending = new CompletableFuture<CubeLightBlockSnapshot>();
        fixture.blocks.put(NEIGHBOR, pending);
        try (var manager = fixture.manager(false)) {
            fixture.prepare(manager);
            var jobs = CubeWorldLightManager.class.getDeclaredField("jobs");
            jobs.setAccessible(true);
            Object oldJob = ((Map<?, ?>) jobs.get(manager)).get(TARGET);
            assertNotNull(oldJob);
            fixture.sources.put(NEIGHBOR, fixture.cube(NEIGHBOR));
            manager.tick(fixture.sources, fixture.targets);
            assertNotSame(oldJob, ((Map<?, ?>) jobs.get(manager)).get(TARGET));
            fixture.prepare(manager);
            fixture.finish(manager);
            assertNotNull(manager.view(TARGET), "A current live capture can proceed without waiting for the obsolete fallback");
            assertFalse(pending.isDone());
            pending.complete(CubeLightBlockSnapshot.uniform(NEIGHBOR, Blocks.GLOWSTONE.defaultBlockState()));
            fixture.finish(manager);
            assertEquals(0, manager.view(TARGET).sample(LightLayer.BLOCK, TARGET.asBlockPos(31, 16, 16)).orElseThrow().lower());
        }
    }

    @Test
    void boundaryLightEquivalentStatesMatchButRoofAndShapeChangesDoNot() {
        Fixture fixture = new Fixture();
        LevelCube roof = fixture.cube(ROOF);
        var at = ROOF.asBlockPos(16, 31, 16);
        roof.setBlockState(at, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 7));
        var leaves = roof.cc_snapshotSkyOcclusion();
        roof.setBlockState(at, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
        assertFalse(leaves.sameOcclusion(roof.cc_snapshotSkyOcclusion()));
        assertTrue(leaves.equivalentOcclusion(roof.cc_snapshotSkyOcclusion()));
        roof.setBlockState(at, Blocks.GRASS_BLOCK.defaultBlockState());
        var grass = roof.cc_snapshotSkyOcclusion();
        roof.setBlockState(at, Blocks.DIRT.defaultBlockState());
        assertTrue(grass.equivalentOcclusion(roof.cc_snapshotSkyOcclusion()));
        roof.setBlockState(at, Blocks.AIR.defaultBlockState());
        assertFalse(grass.equivalentOcclusion(roof.cc_snapshotSkyOcclusion()));
        roof.setBlockState(at, Blocks.OAK_SLAB.defaultBlockState());
        assertFalse(grass.equivalentOcclusion(roof.cc_snapshotSkyOcclusion()));
    }

    @Test
    void moreThan128SkyColumnsRetainTheRoofProofForADeepFluidEdit() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        fixture.floor = 80;
        CubePos fluidPos = CubePos.of(0, -4, 0);
        LevelCube fluid = fixture.cube(fluidPos);
        fixture.sources.put(fluidPos, fluid);
        try (var manager = fixture.manager(true)) {
            fixture.prepare(manager);
            fixture.finish(manager);
            var original = manager.view(TARGET);
            assertNotNull(original);
            for (int index = 1; index <= 20; index++) {
                CubePos pos = CubePos.of(index * 4, TARGET.getY(), 0);
                fixture.sources.put(pos, fixture.cube(pos));
                fixture.targets.add(pos);
            }
            for (int batch = 0; batch < 5; batch++) {
                fixture.prepare(manager);
                fixture.finish(manager);
            }
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));
            var indexes = CubeWorldLightManager.class.getDeclaredField("skyIndexes");
            indexes.setAccessible(true);
            assertTrue(((Map<?, ?>) indexes.get(manager)).size() > 128);
            fluid.setBlockState(fluidPos.asBlockPos(8, 8, 8), Blocks.WATER.defaultBlockState());
            manager.changed(fluid, true);
            assertSame(original, manager.view(TARGET), "A retained higher roof proves this genuine fluid edit cannot change distant sky");
            fixture.prepare(manager);
            fixture.finish(manager);
            assertSame(original, manager.view(TARGET));
        }
    }

    private static final class Fixture {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final List<CubePos> targets = new ArrayList<>(List.of(TARGET));
        private final Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> blocks = new HashMap<>();
        private final Map<CubePos, Map<Integer, CubeSkyOcclusion>> columns = new HashMap<>();
        private final Map<CubePos, CubeSkyOcclusion> generatedSky = new HashMap<>();
        private final Queue<Runnable> work = new ArrayDeque<>();
        private int submissions;
        private int storageReads;
        private int floor = -1000;
        private boolean deep;
        private int deepReads;

        private Fixture() {
            sources.put(TARGET, cube(TARGET));
        }

        private LevelCube cube(CubePos pos) {
            return new LevelCube(level, pos);
        }

        private LevelCube roof() {
            LevelCube roof = cube(ROOF);
            roof.setBlockState(ROOF.asBlockPos(16, 16, 16), Blocks.STONE.defaultBlockState());
            return roof;
        }

        private void savedRoof(LevelCube roof) {
            columns.put(CubePos.of(ROOF.getX(), 0, ROOF.getZ()), Map.of(ROOF.getY(), roof.cc_snapshotSkyOcclusion()));
        }

        private CubeWorldLightManager manager(boolean sky) {
            return new CubeWorldLightManager(123, sky, task -> {
                submissions++;
                work.add(task);
            }, new CubeWorldLightManager.Storage() {
                @Override public IntFunction<CubeSkyOcclusion> deepSky(CubePos column) {
                    return deep ? y -> {
                        deepReads++;
                        return generatedSky.computeIfAbsent(CubePos.of(column.getX(), y, column.getZ()), Fixture.this::generatedSky);
                    } : null;
                }

                @Override public int baselineHeight(long seed, int x, int z) {
                    return floor;
                }

                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    storageReads++;
                    return CompletableFuture.completedFuture(columns.getOrDefault(pos, Map.of()));
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    storageReads++;
                    return blocks.computeIfAbsent(pos, key -> CompletableFuture.completedFuture(
                            CubeLightBlockSnapshot.uniform(key, Blocks.AIR.defaultBlockState())));
                }
            }, (pos, epoch) -> {});
        }

        private CubeSkyOcclusion generatedSky(CubePos pos) {
            LevelCube cube = cube(pos);
            if (pos.getY() <= -20) {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        cube.setBlockState(pos.asBlockPos(x, 16, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
            return cube.cc_snapshotSkyOcclusion();
        }

        private void prepare(CubeWorldLightManager manager) {
            for (int pass = 0; pass < 100; pass++) {
                manager.tick(sources, targets);
            }
        }

        private void completeSkyIndexes() {
            for (int index = 0; index < 9; index++) {
                work.remove().run();
            }
        }

        private void finish(CubeWorldLightManager manager) {
            while (!work.isEmpty()) {
                work.remove().run();
            }
            manager.tick(sources, targets);
        }
    }
}
