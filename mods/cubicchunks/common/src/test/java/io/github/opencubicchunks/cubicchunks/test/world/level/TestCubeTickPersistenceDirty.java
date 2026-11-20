package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeStorageBarrier;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeSaveQueue;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.ticks.CubicLevelTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Persistence must observe accepted, consumed and aging work even when no block state changes. */
class TestCubeTickPersistenceDirty extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void acceptedBlockAndFluidTicksDirtyOnlyTheirOwningCube(int height) {
        Fixture f = new Fixture(height);
        LevelCube other = new LevelCube(f.level, CubePos.from(f.pos.above(4096)));
        other.registerTickContainerInLevel(f.level);
        clean(f.cube);
        clean(other);
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        assertTrue(f.cube.isUnsaved());
        assertFalse(other.isUnsaved());
        clean(f.cube);
        f.fluids.schedule(tick(Fluids.WATER, f.pos, 220));
        assertTrue(f.cube.isUnsaved());
        assertFalse(other.isUnsaved());
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void pollAndAreaClearDirtyQueuesWithoutAWorldBlockMutation(int height) {
        Fixture f = new Fixture(height);
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        clean(f.cube);
        ((LevelChunkTicks<Block>) f.cube.getBlockTicks()).poll();
        assertTrue(f.cube.isUnsaved(), "Consuming a saved tick must not resurrect it on reopen");
        f.fluids.schedule(tick(Fluids.WATER, f.pos, 220));
        clean(f.cube);
        f.fluids.clearArea(new BoundingBox(f.pos));
        assertTrue(f.cube.isUnsaved());
        assertEquals(0, f.fluids.count());
    }

    @Test
    void rejectedDuplicatesEmptyPollsAndUnmatchedClearsDoNotDirtyCube() {
        Fixture f = new Fixture(32000);
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        clean(f.cube);
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 300));
        assertFalse(f.cube.isUnsaved());
        ((LevelChunkTicks<Fluid>) f.cube.getFluidTicks()).poll();
        f.blocks.clearArea(new BoundingBox(f.pos.above()));
        assertFalse(f.cube.isUnsaved());
    }

    @Test
    void queueChangesAfterSnapshotKeepCubeDirtyForTheNextSave() {
        Fixture f = new Fixture(-32000);
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        assertTrue(f.cube.tryMarkSaved());
        var first = SerializableCubeData.copyOf(f.level, f.cube);
        f.blocks.schedule(tick(Blocks.OBSERVER, f.pos.above(), 240));
        assertTrue(f.cube.isUnsaved());
        LevelCube snapshot = (LevelCube) ((ImposterProtoCube) first.read(f.level)).cc_getWrappedClo();
        assertEquals(1, snapshot.getBlockTicks().count(), "The earlier snapshot must remain immutable");
        assertEquals(2, f.cube.getBlockTicks().count());
        assertTrue(f.cube.tryMarkSaved(), "A successful older write must not erase the newer dirty state");
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void elapsedDelayAndConsumedTicksSurviveSaveGatingAndDiskReopen(int height) throws Exception {
        Fixture f = new Fixture(height);
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        saveDirty(f.level, f.cube);
        assertFalse(f.cube.isUnsaved());
        LevelCube empty = new LevelCube(f.level, CubePos.from(f.pos.above(4096)));
        empty.registerTickContainerInLevel(f.level);
        clean(empty);
        when(f.level.getGameTime()).thenReturn(110L);
        f.blocks.tick(110, 0, (pos, block) -> {
            throw new AssertionError("Nothing is due");
        });
        assertTrue(f.cube.isUnsaved(), "A saved relative delay must age while its queue remains loaded");
        assertFalse(empty.isUnsaved(), "Empty cubes must not be dirtied by clock progression");
        saveDirty(f.level, f.cube);
        f.cube.unregisterTickContainerFromLevel(f.level);
        LevelCube reopened = read(f.level, f.cube.cc_getCubePos());
        reopened.unpackTicks(1000);
        reopened.registerTickContainerInLevel(f.level);
        clean(reopened);
        List<BlockPos> ran = new ArrayList<>();
        f.blocks.tick(1089, 10, (pos, block) -> ran.add(pos));
        assertTrue(ran.isEmpty());
        f.blocks.tick(1090, 10, (pos, block) -> ran.add(pos));
        assertEquals(List.of(f.pos), ran, "Reopen must retain 90 remaining ticks, not the original 100");
        assertTrue(reopened.isUnsaved());
        when(f.level.getGameTime()).thenReturn(1090L);
        saveDirty(f.level, reopened);
        reopened.unregisterTickContainerFromLevel(f.level);
        LevelCube consumed = read(f.level, reopened.cc_getCubePos());
        consumed.unpackTicks(2000);
        assertEquals(0, consumed.getBlockTicks().count(), "The consumed tick must not return after a second reopen");
    }

    @Test
    void ordinaryUnconfiguredTickContainersKeepNativeQueueOperations() {
        LevelChunkTicks<Block> ticks = new LevelChunkTicks<>();
        var scheduled = tick(Blocks.REPEATER, BlockPos.ZERO, 5);
        ticks.schedule(scheduled);
        assertEquals(scheduled, ticks.poll());
        assertEquals(0, ticks.count());
        ticks.schedule(scheduled);
        ticks.removeIf(tick -> true);
        assertEquals(0, ticks.count());
    }

    @Test
    void inaccessibleQueueCapturesFinalDelayOnceAndAllowsDelayedSaveToFinish() throws Exception {
        Fixture f = new Fixture(-32000);
        AtomicBoolean aging = new AtomicBoolean(true);
        ((CubicLevelTicks<Block>) f.blocks).cc_configure(key -> false, key -> aging.get());
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        saveDirty(f.level, f.cube);
        when(f.level.getGameTime()).thenReturn(110L);
        f.blocks.tick(110, 0, (pos, block) -> {
            throw new AssertionError("Execution remains disabled");
        });
        assertTrue(f.cube.isUnsaved(), "A FULL cube ages its delay even outside the simulation range");
        saveDirty(f.level, f.cube);

        aging.set(false);
        when(f.level.getGameTime()).thenReturn(111L);
        f.blocks.tick(111, 0, (pos, block) -> {});
        assertTrue(f.cube.isUnsaved(), "Entering unload captures one final elapsed delay");
        CubeSaveQueue<CubePos> saves = new CubeSaveQueue<>();
        CubePos key = f.cube.cc_getCubePos();
        try (CubeStorage storage = new CubeStorage(directory)) {
            try (CubeStorageBarrier barrier = new CubeStorageBarrier(storage)) {
                assertTrue(f.cube.tryMarkSaved());
                var snapshot = SerializableCubeData.copyOf(f.level, f.cube).write();
                assertTrue(saves.submit(key, snapshot, data -> storage.write(key, data), () -> {}, f.cube::markUnsaved));
                for (long time = 112; time <= 120; time++) {
                    when(f.level.getGameTime()).thenReturn(time);
                    f.blocks.tick(time, 0, (pos, block) -> {});
                    assertTrue(saves.contains(key), "The real file worker remains held");
                    assertFalse(f.cube.isUnsaved(), "Elapsed time must not restart an unload save");
                }
            }
            saves.awaitAll();
            assertFalse(saves.contains(key));
            assertFalse(f.cube.isUnsaved(), "The same clean/no-pending-write gate used by unload can now finish");
            f.cube.unregisterTickContainerFromLevel(f.level);
            LevelCube reopened = (LevelCube) ((ImposterProtoCube) SerializableCubeData.parse(storage.read(key).join().orElseThrow(), key)
                    .read(f.level)).cc_getWrappedClo();
            reopened.unpackTicks(1000);
            ((CubicLevelTicks<Block>) f.blocks).cc_configure(pos -> true);
            reopened.registerTickContainerInLevel(f.level);
            List<BlockPos> ran = new ArrayList<>();
            f.blocks.tick(1088, 10, (pos, block) -> ran.add(pos));
            assertTrue(ran.isEmpty());
            f.blocks.tick(1089, 10, (pos, block) -> ran.add(pos));
            assertEquals(List.of(f.pos), ran, "The final snapshot preserves 89 remaining ticks, before delayed I/O");
        }
    }

    @Test
    void frozenQueuesStillDirtyForRealMutationsAndResumeAgingWhenReactivated() {
        Fixture f = new Fixture(32000);
        AtomicBoolean aging = new AtomicBoolean(false);
        ((CubicLevelTicks<Block>) f.blocks).cc_configure(key -> false, key -> aging.get());
        f.blocks.schedule(tick(Blocks.REPEATER, f.pos, 200));
        f.blocks.tick(100, 0, (pos, block) -> {});
        clean(f.cube);
        f.blocks.tick(101, 0, (pos, block) -> {});
        assertFalse(f.cube.isUnsaved());
        f.blocks.schedule(tick(Blocks.OBSERVER, f.pos.above(), 220));
        assertTrue(f.cube.isUnsaved(), "Real scheduling after a snapshot must remain observable during unload");
        clean(f.cube);
        f.blocks.clearArea(new BoundingBox(f.pos));
        assertTrue(f.cube.isUnsaved(), "Real removals must remain observable during unload");
        clean(f.cube);
        f.blocks.tick(102, 0, (pos, block) -> {});
        assertFalse(f.cube.isUnsaved());

        aging.set(true);
        f.blocks.tick(103, 0, (pos, block) -> {});
        assertTrue(f.cube.isUnsaved(), "Revival resumes delay aging without enabling execution");
        clean(f.cube);
        aging.set(false);
        f.blocks.tick(104, 0, (pos, block) -> {});
        assertTrue(f.cube.isUnsaved(), "A subsequent unload receives its own final snapshot");
        clean(f.cube);
        f.blocks.tick(105, 0, (pos, block) -> {});
        assertFalse(f.cube.isUnsaved());
        f.cube.unregisterTickContainerFromLevel(f.level);
        f.cube.registerTickContainerInLevel(f.level);
        f.blocks.tick(106, 0, (pos, block) -> {});
        assertTrue(f.cube.isUnsaved(), "Container re-registration clears the old frozen state");
    }

    private void saveDirty(ServerLevel level, LevelCube cube) throws Exception {
        assertTrue(cube.isUnsaved());
        assertTrue(cube.tryMarkSaved());
        var data = SerializableCubeData.copyOf(level, cube).write();
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(cube.cc_getCubePos(), data).join();
        }
    }

    private LevelCube read(ServerLevel level, CubePos pos) throws Exception {
        try (CubeStorage storage = new CubeStorage(directory)) {
            return (LevelCube) ((ImposterProtoCube) SerializableCubeData.parse(storage.read(pos).join().orElseThrow(), pos).read(level))
                    .cc_getWrappedClo();
        }
    }

    private static void clean(LevelCube cube) {
        cube.tryMarkSaved();
        assertFalse(cube.isUnsaved());
    }

    private static <T> ScheduledTick<T> tick(T type, BlockPos pos, long due) {
        return new ScheduledTick<>(type, pos, due, TickPriority.NORMAL, 0);
    }

    private static final class Fixture {
        private final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        private final LevelTicks<Block> blocks = new LevelTicks<>(key -> false);
        private final LevelTicks<Fluid> fluids = new LevelTicks<>(key -> false);
        private final BlockPos pos;
        private final LevelCube cube;

        private Fixture(int height) {
            Misc.setupLevelHeight(level, -64, 384);
            when(level.getGameTime()).thenReturn(100L);
            when(level.getBlockTicks()).thenReturn(blocks);
            when(level.getFluidTicks()).thenReturn(fluids);
            ((CubicLevelTicks<Block>) blocks).cc_configure(key -> true);
            ((CubicLevelTicks<Fluid>) fluids).cc_configure(key -> true);
            pos = new BlockPos(31, height, -1);
            cube = new LevelCube(level, CubePos.from(pos));
            cube.registerTickContainerInLevel(level);
        }
    }
}
