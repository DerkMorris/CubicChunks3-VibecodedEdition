package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightScheduling extends BaseTest {
    @Test
    void boundedBatchPublishesEightCubesTogetherInsteadOfOnePerTick() {
        Fixture fixture = new Fixture(12);
        try (var manager = fixture.manager(false)) {
            fixture.capture(manager);
            assertEquals(8, fixture.work.size(), "Bound worker concurrency even while new targets keep arriving");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(8, fixture.targets.stream().filter(pos -> manager.view(pos) != null).count());
            assertEquals(4, fixture.work.size());
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));
        }
    }

    @Test
    void verticalBatchSharesSkyIndexesAndEditsInvalidateOnlyTheChangedIndex() {
        Fixture fixture = new Fixture(4);
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            assertEquals(9, fixture.work.size(), "The same nine sky indexes serve every vertical target");
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(13, fixture.submissions.get(), "Nine sky indexes plus four cube solves");
            assertTrue(fixture.targets.stream().allMatch(pos -> manager.view(pos) != null));
            CubePos top = fixture.targets.getLast();
            LevelCube roof = (LevelCube) fixture.sources.get(top);
            roof.setBlockState(top.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
            manager.changed(roof);
            assertNull(manager.view(fixture.targets.getFirst()));
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(18, fixture.submissions.get(), "Rebuild one sky index and four dependent cubes");
            assertEquals(14, manager.view(fixture.targets.getFirst()).sample(LightLayer.SKY, fixture.targets.getFirst().asBlockPos(8, 8, 8))
                    .orElseThrow().exactValue().orElseThrow());
        }
    }

    @Test
    void changedCubeTakesPriorityOverAnExistingVerticalRelightBacklog() {
        Fixture fixture = new Fixture(12);
        try (var manager = fixture.manager(false)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            CubePos last = fixture.targets.getLast();
            LevelCube edited = (LevelCube) fixture.sources.get(last);
            edited.setBlockState(last.asBlockPos(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState());
            manager.changed(edited);
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertNotNull(manager.view(last), "An edit at the end of the distance order must join the first relight batch");
            assertEquals(15, manager.view(last).sample(LightLayer.BLOCK, last.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow());
            assertTrue(fixture.targets.stream().anyMatch(pos -> manager.view(pos) == null), "The background backlog still exists");
        }
    }

    @Test
    void unchangedUnloadKeepsPublicationsAndRetainsSavedRoofWhenAnotherEditRebuildsTheIndex() {
        Fixture fixture = new Fixture(4);
        CubePos high = fixture.targets.getLast();
        ((LevelCube) fixture.sources.get(high)).setBlockState(high.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            CubePos low = fixture.targets.getFirst();
            var before = manager.view(low);
            assertNotNull(before);
            fixture.sources.remove(high);
            fixture.targets.remove(high);
            manager.tick(fixture.sources, fixture.targets);
            assertSame(before, manager.view(low), "Unloading an unchanged, saved input must not darken or relight its column");
            assertTrue(fixture.work.isEmpty());
            LevelCube edited = (LevelCube) fixture.sources.get(low);
            edited.setBlockState(low.asBlockPos(2, 2, 2), Blocks.GLOWSTONE.defaultBlockState());
            manager.changed(edited);
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(14, manager.view(low).sample(LightLayer.SKY, low.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow(),
                    "The acknowledged roof remains in the column foundation after live capture retirement");
        }
    }

    @Test
    void unloadWithAnUnobservedEditDiscardsTheOldFoundation() {
        Fixture fixture = new Fixture(4);
        CubePos high = fixture.targets.getLast();
        LevelCube roof = (LevelCube) fixture.sources.get(high);
        roof.setBlockState(high.asBlockPos(8, 8, 8), Blocks.STONE.defaultBlockState());
        try (var manager = fixture.manager(true)) {
            fixture.capture(manager);
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            roof.setBlockState(high.asBlockPos(8, 8, 8), Blocks.AIR.defaultBlockState());
            fixture.sources.remove(high);
            fixture.targets.remove(high);
            manager.tick(fixture.sources, fixture.targets);
            CubePos low = fixture.targets.getFirst();
            assertNull(manager.view(low));
            fixture.drain();
            manager.tick(fixture.sources, fixture.targets);
            assertEquals(15, manager.view(low).sample(LightLayer.SKY, low.asBlockPos(8, 8, 8)).orElseThrow().exactValue().orElseThrow());
        }
    }

    @Test
    void generatedSnapshotsMatchAuthoritativeTerrainIncludingSurfaceAndBothHeightSigns() {
        for (int y : new int[] { -1000000, 2, 3, 1000000 }) {
            CubePos pos = CubePos.of(-2, y, 4);
            CubeLightBlockSnapshot snapshot = CubeLightBlockSnapshot.generated(pos, 246813579);
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    for (int dy = 0; dy < 32; dy++) {
                        assertEquals(CubeTerrainGenerator.state(246813579, pos.minCubeX() + x, pos.minCubeY() + dy, pos.minCubeZ() + z),
                                snapshot.state(x, dy, z));
                    }
                }
            }
        }
    }

    private static final class Fixture {
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final List<CubePos> targets = new ArrayList<>();
        private final Queue<Runnable> work = new ArrayDeque<>();
        private final AtomicInteger submissions = new AtomicInteger();
        private final Map<CubePos, CubeLightBlockSnapshot> disk = new HashMap<>();

        private Fixture(int count) {
            Level level = mockLevelWithPalettes(Level.class);
            for (int y = 0; y < count; y++) {
                CubePos pos = CubePos.of(0, 1000000 + y, 0);
                targets.add(pos);
                sources.put(pos, new LevelCube(level, pos));
            }
        }

        private CubeWorldLightManager manager(boolean sky) {
            return new CubeWorldLightManager(123, sky, task -> {
                submissions.incrementAndGet();
                work.add(task);
            }, new CubeWorldLightManager.Storage() {
                @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                    return CompletableFuture.completedFuture(Map.of());
                }

                @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                    return CompletableFuture.completedFuture(disk.computeIfAbsent(pos, key -> CubeLightBlockSnapshot.generated(key, 123)));
                }
            }, (pos, epoch) -> {});
        }

        private void capture(CubeWorldLightManager manager) {
            // Owner-thread capture has a time budget. Do not make the test depend on machine speed or JIT warm-up.
            for (int pass = 0; pass < 100; pass++) {
                manager.tick(sources, targets);
            }
        }

        private void drain() {
            while (!work.isEmpty()) {
                work.remove().run();
            }
        }
    }
}
