package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.ticks.CubicLevelTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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

/** Real vanilla schedulers transformed by the production mixin; persistence also goes through the production file store. */
class TestCubeScheduledTicks extends BaseTest {
    @TempDir
    Path directory;

    private static <T> CubicLevelTicks<T> cubic(LevelTicks<T> ticks) {
        return (CubicLevelTicks<T>) ticks;
    }

    private static <T> LevelTicks<T> queue() {
        LevelTicks<T> ticks = new LevelTicks<>(key -> false);
        cubic(ticks).cc_configure(key -> true);
        return ticks;
    }

    private static <T> ScheduledTick<T> scheduled(T type, BlockPos pos, long time, long order) {
        return new ScheduledTick<>(type, pos, time, TickPriority.NORMAL, order);
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void separatesVerticalCubesAndRoutesBothHorizontalSections(int height) {
        LevelTicks<Block> ticks = queue();
        BlockPos lower = new BlockPos(0, height - 1, 0);
        BlockPos upper = new BlockPos(31, height, 31);
        cubic(ticks).cc_addContainer(CubePos.from(lower), new LevelChunkTicks<>());
        cubic(ticks).cc_addContainer(CubePos.from(upper), new LevelChunkTicks<>());
        ticks.schedule(scheduled(Blocks.REPEATER, lower, 4, 1));
        ticks.schedule(scheduled(Blocks.REPEATER, upper, 4, 2));
        ticks.schedule(scheduled(Blocks.REPEATER, lower, 1, 3));
        assertEquals(2, ticks.count(), "Duplicate position/type must retain vanilla deduplication");
        assertTrue(ticks.hasScheduledTick(lower, Blocks.REPEATER));
        assertTrue(ticks.hasScheduledTick(upper, Blocks.REPEATER));
        List<BlockPos> ran = new ArrayList<>();
        ticks.tick(3, 10, (pos, block) -> ran.add(pos));
        assertTrue(ran.isEmpty());
        ticks.tick(4, 10, (pos, block) -> ran.add(pos));
        assertEquals(List.of(lower, upper), ran);
        assertEquals(0, ticks.count());
    }

    @Test
    void preservesPriorityBudgetAndDefersTicksAddedByCallbacks() {
        LevelTicks<Block> ticks = queue();
        BlockPos low = new BlockPos(2, -32000, 2);
        BlockPos high = new BlockPos(2, 32000, 2);
        cubic(ticks).cc_addContainer(CubePos.from(low), new LevelChunkTicks<>());
        cubic(ticks).cc_addContainer(CubePos.from(high), new LevelChunkTicks<>());
        ticks.schedule(scheduled(Blocks.REPEATER, low, 1, 0));
        ticks.schedule(new ScheduledTick<>(Blocks.OBSERVER, high, 1, TickPriority.HIGH, 1));
        List<BlockPos> ran = new ArrayList<>();
        ticks.tick(1, 1, (pos, block) -> {
            ran.add(pos);
            ticks.schedule(scheduled(Blocks.REDSTONE_TORCH, pos.above(), 1, 2));
        });
        assertEquals(List.of(high), ran);
        assertEquals(2, ticks.count());
        ticks.tick(2, 10, (pos, block) -> ran.add(pos));
        assertEquals(List.of(high, low, high.above()), ran);
    }

    @Test
    void simulationGateAndUnloadSuspendOnlyTheAffectedCube() {
        LevelTicks<Fluid> ticks = queue();
        Set<Long> enabled = new HashSet<>();
        cubic(ticks).cc_configure(enabled::contains);
        BlockPos lower = new BlockPos(1, -32000, 1);
        BlockPos upper = new BlockPos(1, 32000, 1);
        LevelChunkTicks<Fluid> lowerContainer = new LevelChunkTicks<>();
        cubic(ticks).cc_addContainer(CubePos.from(lower), lowerContainer);
        cubic(ticks).cc_addContainer(CubePos.from(upper), new LevelChunkTicks<>());
        ticks.schedule(scheduled(Fluids.WATER, lower, 5, 0));
        ticks.schedule(scheduled(Fluids.LAVA, upper, 5, 1));
        enabled.add(CubePos.from(upper).asLong());
        List<BlockPos> ran = new ArrayList<>();
        ticks.tick(5, 10, (pos, fluid) -> ran.add(pos));
        assertEquals(List.of(upper), ran);
        cubic(ticks).cc_removeContainer(CubePos.from(lower));
        enabled.add(CubePos.from(lower).asLong());
        ticks.tick(6, 10, (pos, fluid) -> ran.add(pos));
        assertEquals(List.of(upper), ran);
        // Unloaded containers retain their pending work; registration must restore its next due time.
        cubic(ticks).cc_addContainer(CubePos.from(lower), lowerContainer);
        ticks.tick(7, 10, (pos, fluid) -> ran.add(pos));
        assertEquals(List.of(upper, lower), ran);
    }

    @Test
    void cloneAndClearUseAllThreeCoordinatesAndKeepOtherLayers() {
        LevelTicks<Block> ticks = queue();
        BlockPos original = new BlockPos(31, -32001, 31);
        BlockPos nextLayer = original.above();
        Vec3i offset = new Vec3i(1, 64001, 1);
        BlockPos destination = original.offset(offset);
        for (BlockPos pos : List.of(original, nextLayer, destination)) {
            cubic(ticks).cc_addContainer(CubePos.from(pos), new LevelChunkTicks<>());
        }
        ticks.schedule(scheduled(Blocks.REPEATER, original, 10, 0));
        ticks.schedule(scheduled(Blocks.REPEATER, nextLayer, 10, 1));
        BoundingBox box = new BoundingBox(original);
        ticks.copyArea(box, offset);
        assertTrue(ticks.hasScheduledTick(destination, Blocks.REPEATER));
        ticks.clearArea(box);
        assertFalse(ticks.hasScheduledTick(original, Blocks.REPEATER));
        List<BlockPos> ran = new ArrayList<>();
        ticks.tick(10, 10, (pos, block) -> ran.add(pos));
        assertEquals(2, ran.size());
        assertEquals(Set.of(nextLayer, destination), Set.copyOf(ran));
    }

    @Test
    void columnRegistrationCannotReplaceCubeZeroAndVanillaQueuesStillWork() {
        BlockPos pos = BlockPos.ZERO;
        LevelTicks<Block> ticks = queue();
        cubic(ticks).cc_addContainer(CubePos.from(pos), new LevelChunkTicks<>());
        ticks.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
        ticks.schedule(scheduled(Blocks.REPEATER, pos, 1, 0));
        ticks.removeContainer(new ChunkPos(0, 0));
        assertTrue(ticks.hasScheduledTick(pos, Blocks.REPEATER));
        LevelTicks<Block> vanilla = new LevelTicks<>(key -> true);
        vanilla.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
        vanilla.schedule(scheduled(Blocks.REPEATER, pos, 1, 0));
        List<BlockPos> ran = new ArrayList<>();
        vanilla.tick(1, 1, (blockPos, block) -> ran.add(blockPos));
        assertEquals(List.of(pos), ran);
        vanilla.removeContainer(new ChunkPos(0, 0));
        assertEquals(0, vanilla.count());
    }

    @Test
    void outOfRangeTicksCannotAliasTheOppositeWorldBoundary() {
        LevelTicks<Block> ticks = queue();
        BlockPos bottom = new BlockPos(0, CubicBlockBounds.MIN, 0);
        BlockPos top = new BlockPos(0, CubicBlockBounds.MAX, 0);
        cubic(ticks).cc_addContainer(CubePos.from(bottom), new LevelChunkTicks<>());
        cubic(ticks).cc_addContainer(CubePos.from(top), new LevelChunkTicks<>());
        ticks.schedule(scheduled(Blocks.REPEATER, top.above(), 1, 0));
        ticks.schedule(scheduled(Blocks.REPEATER, bottom.below(), 1, 1));
        assertEquals(0, ticks.count());
        assertFalse(ticks.hasScheduledTick(top.above(), Blocks.REPEATER));
        assertFalse(ticks.hasScheduledTick(bottom.below(), Blocks.REPEATER));
        ticks.schedule(scheduled(Blocks.REPEATER, top, 1, 2));
        ticks.schedule(scheduled(Blocks.REPEATER, bottom, 1, 3));
        List<BlockPos> ran = new ArrayList<>();
        ticks.tick(1, 10, (pos, block) -> ran.add(pos));
        assertEquals(List.of(top, bottom), ran);
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void blockAndFluidTicksResumeAfterCubeDiskReopen(int height) throws Exception {
        ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        LevelTicks<Block> blocks = queue();
        LevelTicks<Fluid> fluids = queue();
        when(level.getBlockTicks()).thenReturn(blocks);
        when(level.getFluidTicks()).thenReturn(fluids);
        when(level.getGameTime()).thenReturn(100L);
        BlockPos pos = new BlockPos(31, height, -1);
        CubePos cubePos = CubePos.from(pos);
        LevelCube cube = new LevelCube(level, cubePos);
        cube.registerTickContainerInLevel(level);
        blocks.schedule(scheduled(Blocks.REPEATER, pos, 110, 1));
        fluids.schedule(scheduled(Fluids.WATER, pos, 120, 2));
        try (CubeStorage store = new CubeStorage(directory)) {
            store.write(cubePos, SerializableCubeData.copyOf(level, cube).write()).join();
        }
        cube.unregisterTickContainerFromLevel(level);
        assertEquals(0, blocks.count());
        assertEquals(0, fluids.count());
        LevelCube reopened;
        try (CubeStorage store = new CubeStorage(directory)) {
            reopened = (LevelCube) ((ImposterProtoCube) SerializableCubeData.parse(store.read(cubePos).join().orElseThrow(), cubePos).read(level))
                    .cc_getWrappedClo();
        }
        reopened.unpackTicks(1000);
        reopened.registerTickContainerInLevel(level);
        List<String> ran = new ArrayList<>();
        blocks.tick(1009, 10, (p, block) -> ran.add("block"));
        fluids.tick(1019, 10, (p, fluid) -> ran.add("fluid"));
        assertTrue(ran.isEmpty());
        blocks.tick(1010, 10, (p, block) -> {
            assertEquals(pos, p);
            assertEquals(Blocks.REPEATER, block);
            ran.add("block");
        });
        fluids.tick(1020, 10, (p, fluid) -> {
            assertEquals(pos, p);
            assertEquals(Fluids.WATER, fluid);
            ran.add("fluid");
        });
        assertEquals(List.of("block", "fluid"), ran);
    }
}
