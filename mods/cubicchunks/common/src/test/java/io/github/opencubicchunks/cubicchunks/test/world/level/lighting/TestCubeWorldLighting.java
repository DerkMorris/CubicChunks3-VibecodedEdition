package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightStorage;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldSkyColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeWorldLighting extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void blockLightCrossesFacesAtBothHeightSignsAndSourceRemovalRemovesIt() {
        for (int y : new int[] { 1000000, -1000000 }) {
            CubePos center = CubePos.of(-2, y, 3);
            BlockPos source = center.asBlockPos(-1, 16, 16);
            var blocks = snapshots(center, pos -> pos.equals(source) ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            CubeLightView view = CubeWorldLightSolver.solve(center, blocks, (x, z) -> Integer.MAX_VALUE);
            assertEquals(14, value(view, LightLayer.BLOCK, center.asBlockPos(0, 16, 16)));
            assertEquals(1, value(view, LightLayer.BLOCK, center.asBlockPos(13, 16, 16)));
            assertEquals(0, value(view, LightLayer.BLOCK, center.asBlockPos(14, 16, 16)));
            CubeLightView removed = CubeWorldLightSolver.solve(center, snapshots(center, pos -> Blocks.AIR.defaultBlockState()),
                    (x, z) -> Integer.MAX_VALUE);
            assertEquals(0, value(removed, LightLayer.BLOCK, center.asBlockPos(0, 16, 16)));
        }
    }

    @Test
    void skyCrossesVerticalCubeFacesAndRoofOpeningAttenuatesSidewaysAtBothSigns() {
        for (int y : new int[] { 1000000, -1000000 }) {
            CubePos center = CubePos.of(0, y, 0);
            int roof = center.maxCubeY() + 1;
            var blocks = snapshots(center, pos -> pos.getY() == roof && (pos.getX() != 16 || pos.getZ() != 16) ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState());
            CubeLightView view = CubeWorldLightSolver.solve(center, blocks, (x, z) -> x == 16 && z == 16 ? Integer.MIN_VALUE : roof + 1);
            assertEquals(15, value(view, LightLayer.SKY, center.asBlockPos(16, 0, 16)));
            assertEquals(14, value(view, LightLayer.SKY, center.asBlockPos(17, 10, 16)));
            var closed = snapshots(center, pos -> pos.getY() == roof ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            CubeLightView dark = CubeWorldLightSolver.solve(center, closed, (x, z) -> roof + 1);
            assertEquals(0, value(dark, LightLayer.SKY, center.asBlockPos(16, 31, 16)));
        }
    }

    @Test
    void skyIndexRetainsHighRoofAndRebuildsWhenItIsRemoved() {
        Level level = mockLevelWithPalettes(Level.class);
        CubePos high = CubePos.of(0, 1000000, 0);
        LevelCube roof = new LevelCube(level, high);
        roof.setBlockState(high.asBlockPos(2, 31, 2), Blocks.STONE.defaultBlockState());
        CubeWorldSkyColumn index = new CubeWorldSkyColumn(123, 0, 0, Map.of(high.getY(), roof.cc_snapshotSkyOcclusion()));
        assertEquals(high.maxCubeY() + 1, index.edge(2, 2));
        roof.setBlockState(high.asBlockPos(2, 31, 2), Blocks.AIR.defaultBlockState());
        CubeWorldSkyColumn removed = new CubeWorldSkyColumn(123, 0, 0, Map.of(high.getY(), roof.cc_snapshotSkyOcclusion()));
        assertTrue(removed.edge(2, 2) < 200);
    }

    @Test
    void managerRejectsInFlightEditsAndKeepsIndependentPublications() {
        Level level = mockLevelWithPalettes(Level.class);
        CubePos first = CubePos.of(0, 1000000, 0);
        CubePos other = CubePos.of(10, 1000000, 0);
        LevelCube a = new LevelCube(level, first);
        LevelCube b = new LevelCube(level, other);
        Map<CubePos, CubeAccess> sources = Map.of(first, a, other, b);
        Queue<Runnable> work = new ArrayDeque<>();
        var storage = new CubeWorldLightStorage(123, level.palettedContainerFactory(), pos -> CompletableFuture.completedFuture(Optional.empty()),
                pos -> CompletableFuture.completedFuture(new java.util.TreeMap<>()), work::add);
        try (var manager = new CubeWorldLightManager(123, true, work::add, storage, (pos, epoch) -> {})) {
            manager.tick(sources, List.of(first, other));
            assertNull(manager.view(first));
            a.setBlockState(first.asBlockPos(16, 16, 16), Blocks.GLOWSTONE.defaultBlockState());
            drain(work);
            manager.tick(sources, List.of(first, other));
            assertNull(manager.view(first), "The old result must be discarded before publication");
            for (int pass = 0; pass < 5; pass++) {
                drain(work);
                manager.tick(sources, List.of(first, other));
            }
            assertEquals(15, value(manager.view(first), LightLayer.BLOCK, first.asBlockPos(16, 16, 16)));
            assertNotNull(manager.view(other));
            a.setBlockState(first.asBlockPos(16, 16, 16), Blocks.AIR.defaultBlockState());
            manager.changed(a);
            assertNull(manager.view(first));
            assertNotNull(manager.view(other));
        }
    }

    @Test
    void reopenedStorageAccountsForUnloadedRoofFarAboveTarget() throws Exception {
        ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        CubePos target = CubePos.of(0, 1000000, 0);
        try (CubeStorage storage = new CubeStorage(directory)) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    CubePos pos = CubePos.of(x, target.getY() + 100, z);
                    LevelCube roof = new LevelCube(level, pos);
                    for (int dx = 0; dx < 32; dx++) {
                        for (int dz = 0; dz < 32; dz++) {
                            roof.setBlockState(pos.asBlockPos(dx, 16, dz), Blocks.STONE.defaultBlockState());
                        }
                    }
                    storage.write(pos, SerializableCubeData.copyOf(level, roof).write()).join();
                }
            }
        }
        try (CubeStorage storage = new CubeStorage(directory)) {
            var adapter = new CubeWorldLightStorage(123, level.palettedContainerFactory(), storage::read,
                    pos -> storage.readColumn(pos.getX(), pos.getZ(), -CubePos.MAX_COORDINATE_VALUE - 1, CubePos.MAX_COORDINATE_VALUE),
                    Runnable::run);
            try (var manager = new CubeWorldLightManager(123, true, Runnable::run, adapter, (pos, epoch) -> {})) {
                LevelCube cube = new LevelCube(level, target);
                Map<CubePos, CubeAccess> live = Map.of(target, cube);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (manager.view(target) == null && System.nanoTime() < deadline) {
                    manager.tick(live, List.of(target));
                    LockSupport.parkNanos(1000000);
                }
                assertNotNull(manager.view(target), () -> "Lighting did not complete: " + manager.status());
                assertEquals(0, value(manager.view(target), LightLayer.SKY, target.asBlockPos(16, 16, 16)));
            }
        }
    }

    private static void drain(Queue<Runnable> tasks) {
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
    }

    private static Map<CubePos, CubeLightBlockSnapshot> snapshots(CubePos center, Function<BlockPos, BlockState> source) {
        Map<CubePos, CubeLightBlockSnapshot> result = new HashMap<>();
        for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
            result.put(pos, CubeLightBlockSnapshot.create(pos, source));
        }
        return result;
    }

    private static int value(CubeLightView view, LightLayer layer, BlockPos pos) {
        return view.sample(layer, pos).orElseThrow().exactValue().orElseThrow();
    }
}
