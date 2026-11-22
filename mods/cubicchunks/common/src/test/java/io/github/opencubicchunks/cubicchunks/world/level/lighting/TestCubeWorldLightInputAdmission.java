package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightInputAdmission extends BaseTest {
    @Test
    void eightColdInputGroupsStartAlongsideTwentyFourReadyUpdates() {
        var level = mockLevelWithPalettes(Level.class);
        Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        List<CubePos> updates = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            CubePos center = CubePos.of(100 + index * 4, -8, 0);
            updates.add(center);
            for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
                sources.put(pos, new LevelCube(level, pos));
            }
        }
        Queue<Runnable> work = new ArrayDeque<>();
        Map<CubePos, CompletableFuture<CubeLightBlockSnapshot>> reads = new HashMap<>();
        Set<Integer> requestedGroups = new HashSet<>();
        try (var manager = new CubeWorldLightManager(123, false, task -> {
            work.add(task);
            assertTrue(work.size() <= 8, "Input admission must not raise the detached calculation concurrency");
        }, new CubeWorldLightManager.Storage() {
            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                throw new AssertionError("A block-only fixture must not request sky data");
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                assertEquals(0, Math.floorMod(pos.getX() - 102, 4), "Only the missing halo plane needs storage");
                int group = Math.floorDiv(pos.getX() - 102, 4);
                assertTrue(group >= 0 && group < 8, "Admission must not start speculative reads outside the selected cold targets");
                requestedGroups.add(group);
                return reads.computeIfAbsent(pos, ignored -> new CompletableFuture<>());
            }
        }, (pos, epoch) -> {})) {
            // Warm every live halo; owner capture is time-budgeted, so allow several ticks before running the queued solver work.
            for (int pass = 0; pass < 100; pass++) {
                manager.tick(sources, updates, Set.of());
            }
            drain(work);
            manager.tick(sources, updates, Set.of());
            assertTrue(updates.stream().allMatch(pos -> manager.view(pos) != null));
            assertTrue(reads.isEmpty());

            List<CubePos> cold = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                // These centers and all their live inputs were captured above. Only the +X halo plane is absent.
                cold.add(CubePos.of(updates.get(index).getX() + 1, -8, 0));
            }
            List<CubePos> targets = new ArrayList<>(cold);
            targets.addAll(updates);
            Set<CubePos> first = Set.copyOf(cold);
            updates.forEach(pos -> manager.changed(sources.get(pos)));
            manager.tick(sources, targets, first);

            assertEquals(8, requestedGroups.size(), "A ready-update backlog must let all eight closest cold input groups begin");
            assertEquals(72, reads.size(), "Each selected cold target owns one nine-cube missing plane");
            assertEquals(8, work.size(), "Blocked inputs leave all calculation workers available to ready updates");
            assertTrue(reads.values().stream().noneMatch(CompletableFuture::isDone));

            drain(work);
            manager.tick(sources, targets, first);
            assertEquals(24, updates.stream().filter(pos -> manager.view(pos) != null).count(),
                    "Eight pending groups must leave twenty-four prepared slots for ready updates");
            assertTrue(cold.stream().allMatch(pos -> manager.view(pos) == null));
            reads.forEach((pos, future) -> future.complete(CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState())));
            drain(work);
            manager.tick(sources, targets, first);
            assertTrue(targets.stream().allMatch(pos -> manager.view(pos) != null), "Both cold first deliveries and the remaining updates finish");
            assertEquals(72, reads.size());
            assertFalse(reads.values().stream().anyMatch(CompletableFuture::isCancelled));
            assertNull(manager.status().lastFailure());
        }
    }

    private static void drain(Queue<Runnable> work) {
        while (!work.isEmpty()) {
            work.remove().run();
        }
    }
}
