package io.github.opencubicchunks.cubicchunks.test.world.level.poi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeVillagerTestWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromBlockMemory;
import net.minecraft.world.entity.ai.behavior.SleepInBed;
import net.minecraft.world.entity.ai.behavior.SocializeAtBell;
import net.minecraft.world.entity.ai.behavior.WakeUp;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeVillagerLife extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void nativeSleepWakeAndCooldownKeepBedAndBodyAtBothHeightSigns() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                BlockPos bed = new BlockPos(2, y, 2);
                f.world.block(bed, TestCubePoiManager.bed());
                f.world.block(bed.above(4096), TestCubePoiManager.bed());
                Villager body = f.villager(y);
                body.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(f.level.dimension(), bed));
                activity(f, body, Activity.REST, 1000);
                var sleep = new SleepInBed();
                assertTrue(sleep.tryStart(f.level, body, 1000));
                assertTrue(body.isSleeping());
                assertEquals(bed, body.getSleepingPos().orElseThrow());
                assertTrue(body.getY() > y && body.getY() < y + 1);
                assertTrue(f.level.getBlockState(bed).getValue(AbstractBedBlock.OCCUPIED));
                assertFalse(f.level.getBlockState(bed.above(4096)).getValue(AbstractBedBlock.OCCUPIED));
                assertEquals(1000L, body.getBrain().getMemory(MemoryModuleType.LAST_SLEPT).orElseThrow());
                sleep.tickOrStop(f.level, body, 1001);
                assertTrue(body.isSleeping());
                activity(f, body, Activity.IDLE, 1100);
                assertTrue(WakeUp.create().tryStart(f.level, body, 1100));
                assertFalse(body.isSleeping());
                assertEquals(1100L, body.getBrain().getMemory(MemoryModuleType.LAST_WOKEN).orElseThrow());
                assertEquals(y, body.getY(), 0.001);
                assertFalse(f.level.getBlockState(bed).getValue(AbstractBedBlock.OCCUPIED));
                f.time(1150);
                assertFalse(new SleepInBed().tryStart(f.level, body, 1150));
                body.snapTo(2.5, y, 2.5);
                activity(f, body, Activity.REST, 1201);
                assertTrue(new SleepInBed().tryStart(f.level, body, 1201));
                assertTrue(body.isSleeping());
            }
        }
    }

    @Test
    void nativeSleepRejectsOccupiedMissingWrongDimensionAndVerticallyDistantBeds() throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            int y = 32000008;
            f.world.floor(y);
            BlockPos bed = new BlockPos(2, y, 2);
            Villager body = f.villager(y);
            body.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(Level.OVERWORLD, bed));
            f.time(1000);
            assertFalse(new SleepInBed().tryStart(f.level, body, 1000));
            f.world.block(bed, TestCubePoiManager.bed().setValue(AbstractBedBlock.OCCUPIED, true));
            assertFalse(new SleepInBed().tryStart(f.level, body, 1000));
            f.world.block(bed, TestCubePoiManager.bed());
            body.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(Level.NETHER, bed));
            assertFalse(new SleepInBed().tryStart(f.level, body, 1000));
            f.world.block(bed.above(4096), TestCubePoiManager.bed());
            body.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(Level.OVERWORLD, bed.above(4096)));
            assertFalse(new SleepInBed().tryStart(f.level, body, 1000));
            assertFalse(body.isSleeping());
        }
    }

    @Test
    void nativeWorkRestocksWithCooldownAndDailyLimitAtBothHeightSigns() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                Villager body = f.villager(y);
                BlockPos job = new BlockPos(3, y, 2);
                f.world.block(job, Blocks.FLETCHING_TABLE.defaultBlockState());
                body.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(Level.OVERWORLD, job));
                MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 4, 1, 0);
                var offers = body.getOffers();
                offers.clear();
                offers.add(offer);
                offer.setToOutOfStock();
                WorkAtPoi work = new WorkAtPoi();
                f.time(1000);
                assertTrue(work.tryStart(f.level, body, 1000));
                assertEquals(0, offer.getUses());
                assertEquals(job, body.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElseThrow().currentBlockPosition());
                offer.setToOutOfStock();
                f.time(1100);
                assertFalse(work.tryStart(f.level, body, 1100));
                f.time(1400);
                assertTrue(work.tryStart(f.level, body, 1400));
                assertTrue(offer.isOutOfStock());
                f.time(3401);
                assertTrue(work.tryStart(f.level, body, 3401));
                assertFalse(offer.isOutOfStock());
                offer.setToOutOfStock();
                f.time(6000);
                assertTrue(work.tryStart(f.level, body, 6000));
                assertTrue(offer.isOutOfStock());
            }
        }
    }

    @Test
    void nativeWorkRejectsAnotherDimensionAndSamePackedPositionAtAnotherHeight() throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            int y = -32000024;
            f.world.floor(y);
            Villager body = f.villager(y);
            f.time(1000);
            body.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(Level.NETHER, body.blockPosition()));
            assertFalse(new WorkAtPoi().tryStart(f.level, body, 1000));
            body.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(Level.OVERWORLD, body.blockPosition().above(4096)));
            assertFalse(new WorkAtPoi().tryStart(f.level, body, 1000));
            assertTrue(body.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).isEmpty());
        }
    }

    @Test
    void farmerCraftsBreadAndUsesOnlyHisFullHeightComposter() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                BlockPos job = new BlockPos(3, y, 2);
                f.world.block(job, Blocks.COMPOSTER.defaultBlockState());
                f.world.block(job.above(4096), Blocks.COMPOSTER.defaultBlockState());
                Villager body = f.villager(y);
                body.getOffers().clear();
                body.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(Level.OVERWORLD, job));
                body.getInventory().addItem(new ItemStack(Items.WHEAT, 9));
                var seeds = new ItemStack(Items.WHEAT_SEEDS, 30);
                // Reloadable loot providers are outside this fixture; use a deterministic one-layer compostable item.
                seeds.set(net.minecraft.core.component.DataComponents.COMPOSTABLE, new net.minecraft.world.item.component.Compostable(
                        new net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt.Constant(1)));
                body.getInventory().addItem(seeds);
                f.time(1000);
                assertTrue(new WorkAtComposter().tryStart(f.level, body, 1000));
                assertEquals(3, body.getInventory().countItem(Items.BREAD));
                assertEquals(0, body.getInventory().countItem(Items.WHEAT));
                assertTrue(body.getInventory().countItem(Items.WHEAT_SEEDS) < 30);
                assertTrue(body.getInventory().countItem(Items.WHEAT_SEEDS) >= 10);
                assertTrue(f.level.getBlockState(job).getValue(ComposterBlock.LEVEL) > 0);
                assertEquals(0, f.level.getBlockState(job.above(4096)).getValue(ComposterBlock.LEVEL));
            }
        }
    }

    @Test
    void scheduleAndAllThreePoiWalkMemoriesKeepTheirHeight() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                Villager body = f.villager(y);
                var memories = List.of(MemoryModuleType.JOB_SITE, MemoryModuleType.MEETING_POINT, MemoryModuleType.HOME);
                var activities = List.of(Activity.WORK, Activity.MEET, Activity.REST);
                for (int index = 0; index < memories.size(); index++) {
                    BlockPos target = new BlockPos(8 + index, y, 2);
                    body.getBrain().setMemory(memories.get(index), GlobalPos.of(Level.OVERWORLD, target));
                    activity(f, body, activities.get(index), 1000 + index * 100);
                    assertTrue(SetWalkTargetFromBlockMemory.create(memories.get(index), 0.5F, 1, 100, 1200).tryStart(f.level, body, 1000));
                    assertEquals(target, body.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().getTarget().currentBlockPosition());
                    var path = body.getNavigation().createPath(target, 1);
                    assertNotNull(path);
                    assertTrue(path.canReach());
                    assertEquals(y, path.getEndNode().y);
                    body.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            }
        }
    }

    @Test
    void nativeSocializingUsesNearbyVillagerAndRejectsVerticallyAliasedMeetingPoint() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                Villager body = f.villager(y);
                Villager neighbor = f.villager(y);
                neighbor.snapTo(3.5, y, 2.5);
                Villager remote = f.villager(y + 4096);
                // Native visibility performs its own target and distance checks; the clear sight ray is a world service.
                when(f.level.clip(any(net.minecraft.world.level.ClipContext.class))).thenAnswer(i -> {
                    var ray = (net.minecraft.world.level.ClipContext) i.getArgument(0);
                    return net.minecraft.world.phys.BlockHitResult.miss(ray.getTo(), net.minecraft.core.Direction.UP,
                            BlockPos.containing(ray.getTo()));
                });
                body.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                        new NearestVisibleLivingEntities(f.level, body, List.of(remote, neighbor)));
                BlockPos bell = new BlockPos(2, y, 3);
                f.world.block(bell, Blocks.BELL.defaultBlockState());
                body.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(Level.OVERWORLD, bell));
                assertTrue(SocializeAtBell.create().tryStart(f.level, body, 1000));
                assertSame(neighbor, body.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).orElseThrow());
                assertEquals(y, body.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().getTarget().currentBlockPosition().getY());
                body.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
                body.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(Level.OVERWORLD, bell.above(4096)));
                assertFalse(SocializeAtBell.create().tryStart(f.level, body, 1001));
                assertTrue(body.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).isEmpty());
            }
        }
    }

    @Test
    void nativeSleepingStateAndThreePoiMemoriesSurviveCubeEntitySerialization() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                BlockPos bed = new BlockPos(2, y, 2);
                f.world.floor(y);
                f.world.block(bed, TestCubePoiManager.bed());
                var body = f.villager(y);
                body.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(Level.OVERWORLD, bed));
                body.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(Level.OVERWORLD, bed.east(4)));
                body.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(Level.OVERWORLD, bed.west(4)));
                assertTrue(body.startSleeping(bed));
                var key = io.github.opencubicchunks.cc_core.api.CubePos.from(bed);
                var data = io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityData.save(key, List.of(body));
                var restored = (Villager) io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityData.load(f.level, key, data).getFirst();
                assertEquals(body.getUUID(), restored.getUUID());
                assertEquals(body.position(), restored.position());
                assertEquals(bed, restored.getSleepingPos().orElseThrow());
                for (var memory : List.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE, MemoryModuleType.MEETING_POINT)) {
                    assertEquals(body.getBrain().getMemory(memory), restored.getBrain().getMemory(memory));
                }
                restored.stopSleeping();
                assertFalse(restored.isSleeping());
                assertEquals(y, restored.getY(), 0.001);
            }
        }
    }

    private static void activity(CubeVillagerTestWorld f, Villager body, Activity activity, long time) {
        f.time(time);
        f.schedule(activity);
        body.getBrain().updateActivityFromSchedule(f.level.environmentAttributes(), time, body.position());
        assertTrue(body.getBrain().isActive(activity));
    }
}
