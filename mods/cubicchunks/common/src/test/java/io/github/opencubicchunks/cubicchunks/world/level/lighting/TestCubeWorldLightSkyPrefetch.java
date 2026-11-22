package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeWorldLightSkyPrefetch extends BaseTest {
    private static final CubePos CENTER = CubePos.of(-2, -8, -3);

    @Test
    void incompleteLiveColumnCaptureCannotStartAnyPrefetchReads() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            fixture.addHalo(CENTER);
            for (int y = 10; y < 50; y++) {
                fixture.add(CubePos.of(CENTER.getX(), y, CENTER.getZ()));
            }
            fixture.initialize(List.of(CENTER), Set.of(CENTER));
            phase(fixture.manager, "captureSome");
            assertFalse(prepared(fixture.manager).contains(CENTER), "One capture budget cannot snapshot all 67 live sources");
            fixture.prefetch();
            assertTrue(fixture.reads.isEmpty());
            assertEquals(0, fixture.blockReads);
            assertTrue(fixture.work.isEmpty());
            assertNull(fixture.manager.view(CENTER));
        }
    }

    @Test
    void ninthColumnProgressesOnAnAlreadyPreparedTargetWithoutJobAdmission() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            fixture.addHalo(CENTER);
            fixture.initialize(List.of(CENTER), Set.of(CENTER));
            fixture.prepare();
            fixture.prefetch();
            assertEquals(8, fixture.reads.size());
            assertEquals(8, pending(fixture.manager).size());
            assertEquals(CubeLightStatus.Phase.DIRTY, fixture.manager.status().phase(), "Prefetch must not admit a calculation job");
            fixture.prefetch();
            assertEquals(8, fixture.reads.size(), "Pending futures consume the extra-work budget across passes");

            fixture.completeReads();
            fixture.drain();
            assertTrue(prepared(fixture.manager).contains(CENTER));
            fixture.prefetch();
            assertEquals(9, fixture.reads.size(), "Preparation memoization must not hide the ninth missing column");
            assertEquals(1, pending(fixture.manager).size());
            assertEquals(0, fixture.blockReads);
            fixture.completeReads();
            fixture.publish();
            assertEquals(15, fixture.manager.view(CENTER).sample(LightLayer.SKY, CENTER.asBlockPos(16, 16, 16)).orElseThrow().upper());
        }
    }

    @Test
    void invalidatedAndEvictedFuturesKeepTheirPermitsUntilActualCompletion() throws ReflectiveOperationException {
        for (boolean eviction : new boolean[] { false, true }) {
            try (var fixture = new Fixture()) {
                fixture.addHalo(CENTER);
                fixture.initialize(List.of(CENTER), Set.of(CENTER));
                fixture.prepare();
                fixture.prefetch();
                Set<CompletableFuture<CubeWorldSkyColumn>> issued = Collections.newSetFromMap(new IdentityHashMap<>());
                issued.addAll(indexes(fixture.manager).values());
                if (eviction) {
                    // Cache eviction releases references, not the asynchronous work represented by those references.
                    indexes(fixture.manager).clear();
                    columns(fixture.manager).clear();
                } else {
                    for (CubePos column : new ArrayList<>(indexes(fixture.manager).keySet())) {
                        fixture.manager.changed(fixture.sources.get(CubePos.of(column.getX(), CENTER.getY(), column.getZ())));
                    }
                    fixture.prepare();
                }
                assertTrue(indexes(fixture.manager).isEmpty());
                for (int pass = 0; pass < 12; pass++) {
                    fixture.prefetch();
                    issued.addAll(indexes(fixture.manager).values());
                    assertEquals(8, issued.stream().filter(future -> !future.isDone()).count());
                    assertEquals(8, pending(fixture.manager).size());
                }
                assertEquals(8, fixture.reads.size());
                fixture.reads.getFirst().future.complete(Map.of());
                fixture.prefetch();
                assertTrue(indexes(fixture.manager).isEmpty(), "Completing storage alone does not finish the queued index calculation");
                fixture.drain();
                fixture.prefetch();
                assertEquals(1, indexes(fixture.manager).size(), "Exactly one completed index releases one extra-work permit");
                issued.addAll(indexes(fixture.manager).values());
                assertEquals(8, issued.stream().filter(future -> !future.isDone()).count());
                assertTrue(issued.stream().noneMatch(CompletableFuture::isCancelled));
                assertEquals(0, fixture.blockReads);
            }
        }
    }

    @Test
    void aLatePrefetchCannotRestoreAnOverlayFromBeforeRoofRemoval() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            fixture.addHalo(CENTER);
            CubePos roofPos = CubePos.of(CENTER.getX(), CENTER.getY() + 4, CENTER.getZ());
            LevelCube roof = fixture.add(roofPos);
            BlockPos block = roofPos.asBlockPos(16, 16, 16);
            roof.setBlockState(block, Blocks.STONE.defaultBlockState());
            fixture.initialize(List.of(CENTER), Set.of(CENTER));
            fixture.prepare();
            fixture.prefetch();
            CubePos column = CubePos.of(CENTER.getX(), 0, CENTER.getZ());
            CompletableFuture<CubeWorldSkyColumn> old = indexes(fixture.manager).get(column);
            assertNotNull(old);
            roof.setBlockState(block, Blocks.AIR.defaultBlockState());
            fixture.manager.changed(roof, true);
            assertFalse(indexes(fixture.manager).containsKey(column));
            fixture.observe();
            fixture.prepare();
            fixture.completeReads();
            fixture.drain();
            assertTrue(old.join().edge(block.getX(), block.getZ()) > CENTER.maxCubeY(), "The abandoned immutable overlay still contains the old roof");
            assertFalse(indexes(fixture.manager).containsKey(column), "Old completion cannot reinstall an invalidated cache entry");

            fixture.prefetch();
            CompletableFuture<CubeWorldSkyColumn> current = indexes(fixture.manager).get(column);
            assertNotNull(current);
            assertNotSame(old, current);
            fixture.completeReads();
            fixture.publish();
            assertTrue(current.join().edge(block.getX(), block.getZ()) < CENTER.minCubeY());
            assertEquals(15, fixture.manager.view(CENTER).sample(LightLayer.SKY, CENTER.asBlockPos(16, 16, 16)).orElseThrow().upper());
            assertFalse(old.isCancelled());
        }
    }

    @Test
    void aCertifiedLocalRoofStillNeedsNoSkyOrBlockStorage() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            fixture.addHalo(CENTER);
            for (CubeAccess cube : fixture.sources.values()) {
                if (cube.cc_getCubePos().getY() == CENTER.getY() + 1) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            cube.getSection(Coords.blockToIndex(x, 20, z)).setBlockState(SectionPos.sectionRelative(x), 4,
                                    SectionPos.sectionRelative(z), Blocks.STONE.defaultBlockState());
                        }
                    }
                    cube.cc_invalidateHeightmaps();
                }
            }
            fixture.initialize(List.of(CENTER), Set.of(CENTER));
            fixture.prepare();
            fixture.prefetch();
            assertTrue(fixture.reads.isEmpty());
            assertTrue(pending(fixture.manager).isEmpty());
            fixture.publish();
            assertEquals(0, fixture.manager.view(CENTER).sample(LightLayer.SKY, CENTER.asBlockPos(16, 16, 16)).orElseThrow().upper());
            assertEquals(0, fixture.blockReads);
            assertTrue(fixture.reads.isEmpty());
        }
    }

    @Test
    void anEarlierUpdateBacklogCannotHidePreparedFirstDeliveryInputs() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            List<CubePos> targets = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                CubePos update = CubePos.of(100 + index * 4, -8, 0);
                targets.add(update);
                fixture.addHalo(update);
            }
            fixture.addHalo(CENTER);
            targets.add(CENTER);
            fixture.initialize(targets, Set.of(CENTER));
            fixture.prepare();
            fixture.prefetch();
            assertEquals(8, fixture.reads.size());
            assertTrue(fixture.reads.stream().allMatch(read -> Math.abs(read.pos.getX() - CENTER.getX()) <= 1
                    && Math.abs(read.pos.getZ() - CENTER.getZ()) <= 1), "The closest pending first delivery gets lookahead before update-only inputs");
            assertEquals(0, fixture.blockReads);
            assertEquals(CubeLightStatus.Phase.DIRTY, fixture.manager.status().phase());
        }
    }

    @Test
    void synchronousStorageFailuresConsumeThePerPassAttemptBudget() throws ReflectiveOperationException {
        try (var fixture = new Fixture()) {
            List<CubePos> targets = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                CubePos pos = CubePos.of(100 + index * 4, -8, 0);
                targets.add(pos);
                fixture.addHalo(pos);
            }
            fixture.initialize(targets, Set.copyOf(targets));
            fixture.prepare();
            fixture.failColumns = true;
            fixture.prefetch();
            assertEquals(8, fixture.columnAttempts, "Failed submissions must not allow an unbounded scan of cold targets");
            assertTrue(pending(fixture.manager).isEmpty());
            assertTrue(indexes(fixture.manager).isEmpty());
            assertTrue(fixture.work.isEmpty());
            assertTrue(fixture.publications.isEmpty());
            assertEquals(0, fixture.blockReads);
            assertNotNull(fixture.manager.status().lastFailure());
        }
    }

    private static void phase(CubeWorldLightManager manager, String name) throws ReflectiveOperationException {
        var method = CubeWorldLightManager.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(manager);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(CubeWorldLightManager manager, String name) throws ReflectiveOperationException {
        var field = CubeWorldLightManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(manager);
    }

    private static Set<CubePos> prepared(CubeWorldLightManager manager) throws ReflectiveOperationException {
        return field(manager, "fullyCaptured");
    }

    private static Map<CubePos, CompletableFuture<CubeWorldSkyColumn>> indexes(CubeWorldLightManager manager) throws ReflectiveOperationException {
        return field(manager, "skyIndexes");
    }

    private static Map<CubePos, CompletableFuture<Map<Integer, CubeSkyOcclusion>>> columns(CubeWorldLightManager manager)
            throws ReflectiveOperationException {
        return field(manager, "columns");
    }

    private static Set<CompletableFuture<CubeWorldSkyColumn>> pending(CubeWorldLightManager manager) throws ReflectiveOperationException {
        return field(manager, "skyPrefetchPending");
    }

    private record Read(CubePos pos, CompletableFuture<Map<Integer, CubeSkyOcclusion>> future) {}

    private static final class Fixture implements AutoCloseable {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final Map<CubePos, CubeAccess> sources = new LinkedHashMap<>();
        private final Queue<Runnable> work = new ArrayDeque<>();
        private final List<Read> reads = new ArrayList<>();
        private final List<CubePos> publications = new ArrayList<>();
        private List<CubePos> targets;
        private Set<CubePos> first;
        private int blockReads;
        private int columnAttempts;
        private boolean failColumns;
        private final CubeWorldLightManager manager = new CubeWorldLightManager(123, true, work::add, new CubeWorldLightManager.Storage() {
            @Override public int baselineHeight(long seed, int x, int z) {
                return -1000;
            }

            @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos pos) {
                columnAttempts++;
                if (failColumns) {
                    throw new IllegalStateException("Controlled synchronous storage failure");
                }
                var future = new CompletableFuture<Map<Integer, CubeSkyOcclusion>>();
                reads.add(new Read(pos, future));
                return future;
            }

            @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
                blockReads++;
                throw new AssertionError("These complete live halos must never request block storage");
            }
        }, (pos, epoch) -> publications.add(pos));

        private LevelCube add(CubePos pos) {
            LevelCube cube = new LevelCube(level, pos);
            sources.put(pos, cube);
            return cube;
        }

        private void addHalo(CubePos center) {
            CubeWorldLightManager.halo(center).positions().forEach(this::add);
        }

        private void initialize(List<CubePos> ordered, Set<CubePos> deliveries) throws ReflectiveOperationException {
            targets = ordered;
            first = deliveries;
            manager.tick(Map.of(), targets, first);
            observe();
        }

        private void observe() throws ReflectiveOperationException {
            var method = CubeWorldLightManager.class.getDeclaredMethod("observe", Map.class);
            method.setAccessible(true);
            method.invoke(manager, sources);
        }

        private void prepare() throws ReflectiveOperationException {
            // Drive real owner capture without admitting normal jobs, whose input requests have a separate budget.
            for (int pass = 0; pass < 100 && !prepared(manager).containsAll(targets); pass++) {
                phase(manager, "captureSome");
            }
            assertTrue(prepared(manager).containsAll(targets), "The bounded fixture must finish capturing on any machine");
        }

        private void prefetch() throws ReflectiveOperationException {
            phase(manager, "prefetchSkyInputs");
        }

        private void completeReads() {
            List.copyOf(reads).forEach(read -> read.future.complete(Map.of()));
        }

        private void drain() {
            while (!work.isEmpty()) {
                work.remove().run();
            }
        }

        private void publish() {
            for (int pass = 0; pass < 4; pass++) {
                drain();
                manager.tick(sources, targets, first);
                completeReads();
            }
            assertNotNull(manager.view(CENTER));
        }

        @Override public void close() {
            manager.close();
        }
    }
}
