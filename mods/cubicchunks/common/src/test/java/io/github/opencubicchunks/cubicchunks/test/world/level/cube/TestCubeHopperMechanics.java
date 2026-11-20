package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeVillagerTestWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native container lookup, transfer, cooldown and cube-bound tickers; unrelated world services are mocked. */
class TestCubeHopperMechanics extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeHopperTransfersIntoChestAcrossCubeBoundaryWithoutLosingItems(int y) throws Exception {
        try (var fixture = new CubeVillagerTestWorld(directory)) {
            BlockPos pos = setup(fixture, y);
            var hopper = assertInstanceOf(HopperBlockEntity.class, fixture.level.getBlockEntity(pos));
            var chest = assertInstanceOf(ChestBlockEntity.class, fixture.level.getBlockEntity(pos.below()));
            hopper.setItem(0, new ItemStack(Items.DIAMOND, 3));

            tick(fixture, 1);
            assertEquals(2, hopper.getItem(0).getCount());
            assertEquals(1, chest.getItem(0).getCount());
            tick(fixture, 7);
            assertEquals(1, chest.getItem(0).getCount(), "Native eight-tick cooldown must remain in effect");
            tick(fixture, 9);
            assertEquals(0, hopper.getItem(0).getCount());
            assertEquals(3, chest.getItem(0).getCount());
            assertEquals(Items.DIAMOND, chest.getItem(0).getItem());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void disabledHopperRetainsItemsAndResumesWithNativeBlockState(int y) throws Exception {
        try (var fixture = new CubeVillagerTestWorld(directory)) {
            BlockPos pos = setup(fixture, y);
            var hopper = assertInstanceOf(HopperBlockEntity.class, fixture.level.getBlockEntity(pos));
            var chest = assertInstanceOf(ChestBlockEntity.class, fixture.level.getBlockEntity(pos.below()));
            hopper.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
            fixture.world.block(pos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.ENABLED, false));
            tick(fixture, 17);
            assertEquals(2, hopper.getItem(0).getCount());
            assertEquals(0, chest.getItem(0).getCount());
            fixture.world.block(pos, Blocks.HOPPER.defaultBlockState());
            tick(fixture, 1);
            assertEquals(1, hopper.getItem(0).getCount());
            assertEquals(1, chest.getItem(0).getCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000 })
    void inactiveCubeAndFrozenWorldPauseNativeContainerTicker(int y) throws Exception {
        try (var fixture = new CubeVillagerTestWorld(directory)) {
            BlockPos pos = setup(fixture, y);
            var hopper = assertInstanceOf(HopperBlockEntity.class, fixture.level.getBlockEntity(pos));
            var chest = assertInstanceOf(ChestBlockEntity.class, fixture.level.getBlockEntity(pos.below()));
            hopper.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
            fixture.world.cube(pos).setFullStatus(() -> FullChunkStatus.FULL);
            tick(fixture, 17);
            assertEquals(0, chest.getItem(0).getCount());
            fixture.world.cube(pos).setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
            when(fixture.level.tickRateManager().runsNormally()).thenReturn(false);
            tick(fixture, 17);
            assertEquals(0, chest.getItem(0).getCount());
            when(fixture.level.tickRateManager().runsNormally()).thenReturn(true);
            tick(fixture, 1);
            assertEquals(1, chest.getItem(0).getCount());
            assertEquals(1, hopper.getItem(0).getCount());
        }
    }

    private static BlockPos setup(CubeVillagerTestWorld fixture, int y) throws Exception {
        setField(Level.class, fixture.level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, fixture.level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        doCallRealMethod().when(fixture.level).addBlockEntityTicker(any());
        doCallRealMethod().when(fixture.level).tickBlockEntities();
        doCallRealMethod().when(fixture.level).shouldTickBlocksAt(any(BlockPos.class));
        var cubic = (CubicServerLevel) fixture.level;
        doCallRealMethod().when(cubic).cc_areCubeEntitiesLoaded(any());
        when(fixture.world.distance.inBlockTickingRange(anyLong())).thenReturn(true);
        when(fixture.level.tickRateManager().runsNormally()).thenReturn(true);
        when(fixture.level.getEntities(nullable(Entity.class), any(AABB.class), any())).thenReturn(List.of());
        when(fixture.level.getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class), any())).thenReturn(List.of());
        BlockPos pos = new BlockPos(2, Coords.cubeToMinBlock(Coords.blockToCube(y)), 2);
        for (BlockPos block : new BlockPos[] { pos, pos.below() }) {
            fixture.world.cube(block).setLoaded(true);
            cubic.cc_onCubeEntityStatus(CubePos.from(block), FullChunkStatus.BLOCK_TICKING);
        }
        fixture.world.block(pos.below(), Blocks.CHEST.defaultBlockState());
        fixture.world.block(pos, Blocks.HOPPER.defaultBlockState());
        return pos;
    }

    private static void tick(CubeVillagerTestWorld fixture, int count) {
        for (int step = 0; step < count; step++) {
            fixture.time(fixture.level.getGameTime() + 1);
            fixture.level.tickBlockEntities();
        }
    }
}
