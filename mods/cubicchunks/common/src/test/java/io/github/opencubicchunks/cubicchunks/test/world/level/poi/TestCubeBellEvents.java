package io.github.opencubicchunks.cubicchunks.test.world.level.poi;

import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeVillagerTestWorld;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeBellEvents extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void nativeBellEventQueueNotifiesOnlyVillagersWithinFullThreeDimensionalRadius() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                BlockPos pos = new BlockPos(2, y, 2);
                f.world.block(pos, Blocks.BELL.defaultBlockState());
                var nearby = f.villager(y);
                var outside = f.villager(y + 33);
                var alias = f.villager(y + 4096);
                for (var body : java.util.List.of(nearby, outside, alias)) {
                    f.world.status(body, FullChunkStatus.ENTITY_TICKING);
                    assertTrue(f.world.manager.addNewEntity(body));
                }
                queries(f.level);
                var queue = events(f);
                var bell = (BellBlockEntity) f.level.getBlockEntity(pos);
                f.time(1000);
                bell.onHit(Direction.WEST);
                assertEquals(1, queue.size());
                runEvents(f.level);
                assertTrue(queue.isEmpty());
                assertTrue(bell.shaking);
                assertEquals(1000L, nearby.getBrain().getMemory(MemoryModuleType.HEARD_BELL_TIME).orElseThrow());
                assertTrue(outside.getBrain().getMemory(MemoryModuleType.HEARD_BELL_TIME).isEmpty());
                assertTrue(alias.getBrain().getMemory(MemoryModuleType.HEARD_BELL_TIME).isEmpty());
            }
        }
    }

    @Test
    void nativeQueueDefersInactiveCubeThenDispatchesAndDropsReplacedBlockEvents() throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            BlockPos pos = new BlockPos(2, 32000008, 2);
            f.world.block(pos, Blocks.BELL.defaultBlockState());
            var cube = f.world.cube(pos);
            var queue = events(f);
            cube.setFullStatus(() -> FullChunkStatus.FULL);
            f.level.blockEvent(pos, Blocks.BELL, 1, 2);
            runEvents(f.level);
            assertEquals(1, queue.size());
            cube.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
            runEvents(f.level);
            assertTrue(queue.isEmpty());
            assertTrue(((BellBlockEntity) f.level.getBlockEntity(pos)).shaking);
            f.level.blockEvent(pos, Blocks.BELL, 1, 2);
            f.world.block(pos, Blocks.STONE.defaultBlockState());
            runEvents(f.level);
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void tickingGateRequiresExactLoadedCubeAndExactThreeDimensionalTicket() throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            BlockPos pos = new BlockPos(2, -32000024, 2);
            f.world.cube(pos);
            f.world.cube(pos.above(4096));
            doCallRealMethod().when(f.level).shouldTickBlocksAt(any(BlockPos.class));
            when(f.world.distance.inBlockTickingRange(anyLong())).thenReturn(false);
            when(f.world.distance.inBlockTickingRange(CloPos.cube(CubePos.from(pos)).asLong())).thenReturn(true);
            assertTrue(f.level.shouldTickBlocksAt(pos));
            assertFalse(f.level.shouldTickBlocksAt(pos.above(4096)));
            f.world.cubes.remove(CubePos.from(pos));
            assertFalse(f.level.shouldTickBlocksAt(pos));
            assertFalse(f.level.shouldTickBlocksAt(new BlockPos(0, Integer.MAX_VALUE, 0)));
            when(((io.github.opencubicchunks.cubicchunks.CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            doCallRealMethod().when(f.level).shouldTickBlocksAt(anyLong());
            when(f.world.distance.inBlockTickingRange(net.minecraft.world.level.ChunkPos.pack(pos))).thenReturn(true);
            assertTrue(f.level.shouldTickBlocksAt(pos));
        }
    }

    @Test
    void nativeCubeBoundBellTickerWaitsForEntitiesAndStopsWithCubeStatusOrFrozenClock() throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            BlockPos pos = new BlockPos(2, 32000008, 2);
            var cube = f.world.cube(pos);
            var tickers = new ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity>();
            setField(net.minecraft.world.level.Level.class, f.level, "blockEntityTickers", tickers);
            setField(net.minecraft.world.level.Level.class, f.level, "pendingBlockEntityTickers", new ArrayList<>());
            doCallRealMethod().when(f.level).addBlockEntityTicker(any());
            doCallRealMethod().when(f.level).tickBlockEntities();
            doCallRealMethod().when(f.level).shouldTickBlocksAt(any(BlockPos.class));
            var cubicLevel = (io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel) f.level;
            doCallRealMethod().when(cubicLevel).cc_areCubeEntitiesLoaded(any());
            when(f.world.distance.inBlockTickingRange(anyLong())).thenReturn(true);
            when(f.level.tickRateManager().runsNormally()).thenReturn(true);
            cube.setLoaded(true);
            f.world.block(pos, Blocks.BELL.defaultBlockState());
            assertEquals(1, tickers.size());
            var bell = (BellBlockEntity) f.level.getBlockEntity(pos);
            assertTrue(bell.triggerEvent(1, 2));
            f.level.tickBlockEntities();
            assertEquals(0, bell.ticks);
            cubicLevel.cc_onCubeEntityStatus(CubePos.from(pos), FullChunkStatus.BLOCK_TICKING);
            f.level.tickBlockEntities();
            assertEquals(1, bell.ticks);
            cube.setFullStatus(() -> FullChunkStatus.FULL);
            f.level.tickBlockEntities();
            assertEquals(1, bell.ticks);
            cube.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
            when(f.level.tickRateManager().runsNormally()).thenReturn(false);
            f.level.tickBlockEntities();
            assertEquals(1, bell.ticks);
        }
    }

    private static ObjectLinkedOpenHashSet<BlockEventData> events(CubeVillagerTestWorld f) throws Exception {
        var queue = new ObjectLinkedOpenHashSet<BlockEventData>();
        setField(ServerLevel.class, f.level, "blockEvents", queue);
        setField(ServerLevel.class, f.level, "blockEventsToReschedule", new ArrayList<BlockEventData>());
        setField(ServerLevel.class, f.level, "server", f.level.getServer());
        doCallRealMethod().when(f.level).blockEvent(any(), any(), anyInt(), anyInt());
        doCallRealMethod().when(f.level).shouldTickBlocksAt(any(BlockPos.class));
        when(f.world.distance.inBlockTickingRange(anyLong())).thenReturn(true);
        return queue;
    }

    private static void runEvents(ServerLevel level) throws Exception {
        Method run = ServerLevel.class.getDeclaredMethod("runBlockEvents");
        run.setAccessible(true);
        run.invoke(level);
    }

    private static void queries(ServerLevel level) throws Exception {
        Method getter = ServerLevel.class.getDeclaredMethod("getEntities");
        getter.setAccessible(true);
        getter.invoke(doCallRealMethod().when(level));
        doCallRealMethod().when(level).getEntitiesOfClass(any(), any(AABB.class));
        doCallRealMethod().when(level).getEntitiesOfClass(any(), any(AABB.class), any());
        doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(AABB.class), any());
        doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(), any(), any());
        doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(), any(), any(), anyInt());
    }
}
