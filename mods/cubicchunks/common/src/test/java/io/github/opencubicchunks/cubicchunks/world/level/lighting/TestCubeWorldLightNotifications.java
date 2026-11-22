package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightNotifications extends BaseTest {
    @Test
    void initialBacklogDoesNotHideAQueuedUpdateAndUnusedQuotaServesInitials() throws ReflectiveOperationException {
        checkBacklog(true);
    }

    @Test
    void updateBacklogDoesNotHideAFirstDeliveryAndUnusedQuotaServesUpdates() throws ReflectiveOperationException {
        checkBacklog(false);
    }

    private static void checkBacklog(boolean initialBacklog) throws ReflectiveOperationException {
        List<CubePos> backlog = IntStream.range(0, 20).mapToObj(x -> CubePos.of(x, -8, 0)).toList();
        CubePos last = CubePos.of(20, -8, 0);
        List<CubePos> targets = new ArrayList<>(backlog);
        targets.add(last);
        Set<CubePos> firstDeliveries = initialBacklog ? Set.copyOf(backlog) : Set.of(last);
        List<Delivery> delivered = new ArrayList<>();
        try (var manager = new CubeWorldLightManager(123, false, task -> {
            throw new AssertionError("Notification dispatch must not schedule a lighting calculation");
        }, new CubeWorldLightManager.Storage() {
            @Override public int baselineHeight(long seed, int x, int z) {
                throw new AssertionError("Notification dispatch must not read terrain");
            }

            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                throw new AssertionError("Notification dispatch must not load sky columns");
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                throw new AssertionError("Notification dispatch must not load cube blocks");
            }
        }, (pos, epoch) -> delivered.add(new Delivery(pos, epoch)))) {
            manager.tick(Map.of(), targets, firstDeliveries);
            Map<CubePos, Long> pending = notifications(manager);
            // Seed pending publication notifications only; classification and dispatch run through the real owner tick.
            for (int index = 0; index < backlog.size(); index++) {
                pending.put(backlog.get(index), (long) index + 1);
            }
            pending.put(last, 900L);
            pending.put(last, 901L);

            manager.tick(Map.of(), targets, firstDeliveries);
            assertEquals(16, delivered.size(), "Both groups share the existing per-tick quota");
            assertEquals(5, pending.size());
            assertEquals(initialBacklog ? new Delivery(last, 901) : new Delivery(backlog.getFirst(), 1), delivered.getFirst());
            assertEquals(initialBacklog ? new Delivery(backlog.getFirst(), 1) : new Delivery(last, 901), delivered.get(1));
            assertEquals(backlog.subList(0, 15), delivered.stream().map(Delivery::pos).filter(pos -> !pos.equals(last)).toList(),
                    "Within-group FIFO and unused quota are preserved");

            manager.tick(Map.of(), targets, firstDeliveries);
            assertEquals(21, delivered.size(), "No initial delivery or update may be dropped");
            assertTrue(pending.isEmpty());
            assertEquals(new HashSet<>(targets), new HashSet<>(delivered.stream().map(Delivery::pos).toList()));
            assertEquals(backlog, delivered.stream().map(Delivery::pos).filter(pos -> !pos.equals(last)).toList());
            assertEquals(901, delivered.stream().filter(delivery -> delivery.pos.equals(last)).findFirst().orElseThrow().epoch);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<CubePos, Long> notifications(CubeWorldLightManager manager) throws ReflectiveOperationException {
        var field = CubeWorldLightManager.class.getDeclaredField("notifications");
        field.setAccessible(true);
        return (Map<CubePos, Long>) field.get(manager);
    }

    private record Delivery(CubePos pos, long epoch) {}
}
