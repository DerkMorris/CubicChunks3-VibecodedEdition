package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeMobTicking extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void nativeTickListFollowsEntityTickingStatusOnlyAndStopsOnRemoval() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            Mob pig = f.pig(32000000);
            assertTrue(f.manager.addNewEntity(pig));
            for (var status : new FullChunkStatus[] { FullChunkStatus.FULL, FullChunkStatus.BLOCK_TICKING }) {
                f.status(pig, status);
                assertFalse(f.ticks.contains(pig));
                assertFalse(f.level.isPositionEntityTicking(pig.blockPosition()));
            }
            f.status(pig, FullChunkStatus.ENTITY_TICKING);
            f.status(pig, FullChunkStatus.ENTITY_TICKING);
            assertTrue(f.ticks.contains(pig));
            assertTrue(f.level.isPositionEntityTicking(pig.blockPosition()));
            f.status(pig, FullChunkStatus.FULL);
            assertFalse(f.ticks.contains(pig));
            f.status(pig, FullChunkStatus.ENTITY_TICKING);
            pig.discard();
            assertFalse(f.ticks.contains(pig));
            assertFalse(f.manager.isLoaded(pig.getUUID()));
        }
    }

    @Test
    void aliasedSectionHeightsHaveIndependentTicksAndMovementTransfersMembership() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            Mob first = f.pig(-32000000);
            Mob second = f.pig(-32000000 + (1 << 24));
            assertEquals(SectionPos.asLong(first.blockPosition()), SectionPos.asLong(second.blockPosition()));
            f.manager.addNewEntity(first);
            f.manager.addNewEntity(second);
            f.status(first, FullChunkStatus.ENTITY_TICKING);
            f.status(second, FullChunkStatus.FULL);
            assertTrue(f.ticks.contains(first));
            assertFalse(f.ticks.contains(second));
            first.snapTo(second.position());
            assertFalse(f.ticks.contains(first));
            f.status(second, FullChunkStatus.ENTITY_TICKING);
            assertTrue(f.ticks.contains(first));
            assertTrue(f.ticks.contains(second));
            first.snapTo(first.position().add(0, 32, 0));
            assertFalse(f.ticks.contains(first));
            assertTrue(f.ticks.contains(second));
        }
    }

    @Test
    void nativeLoopUsesCubeDistanceAndRespectsFreeze() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            Mob pig = spy(f.pig(-32000000));
            doNothing().when(pig).tick();
            f.manager.addNewEntity(pig);
            f.status(pig, FullChunkStatus.ENTITY_TICKING);
            long key = CloPos.cube(CubePos.from(pig.blockPosition())).toLong();
            when(f.distance.inEntityTickingRange(key)).thenReturn(false);
            f.tick();
            assertEquals(0, pig.tickCount);
            when(f.distance.inEntityTickingRange(key)).thenReturn(true);
            when(f.rate.isEntityFrozen(pig)).thenReturn(true);
            f.tick();
            assertEquals(0, pig.tickCount);
            when(f.rate.isEntityFrozen(pig)).thenReturn(false);
            f.tick();
            assertEquals(1, pig.tickCount);
        }
    }

    @Test
    void passengersTickOnceThroughTheirVehicleAndStopInInactiveCube() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            Mob vehicle = spy(f.pig(32000000));
            Mob passenger = spy(f.pig(32000000));
            doNothing().when(vehicle).tick();
            doNothing().when(passenger).rideTick();
            f.manager.addNewEntity(vehicle);
            f.manager.addNewEntity(passenger);
            assertTrue(passenger.startRiding(vehicle, true, false));
            f.status(vehicle, FullChunkStatus.ENTITY_TICKING);
            f.tick();
            assertEquals(1, vehicle.tickCount);
            assertEquals(1, passenger.tickCount);
            f.status(vehicle, FullChunkStatus.FULL);
            f.tick();
            assertEquals(1, passenger.tickCount);
        }
    }

    @Test
    void actualMobTickRunsGoalsNavigationAndMovementAtBothHeightSigns() throws Exception {
        for (int y : new int[] { 32000000, -32000000 }) {
            try (var f = new CubeMobTestWorld(directory.resolve(Integer.toString(y)))) {
                f.floor(y);
                Mob pig = f.pig(y);
                AtomicInteger goals = new AtomicInteger();
                pig.getGoalSelector().addGoal(0, new Goal() {
                    @Override public boolean canUse() {
                        return true;
                    }

                    @Override public boolean requiresUpdateEveryTick() {
                        return true;
                    }

                    @Override public void tick() {
                        goals.incrementAndGet();
                    }
                });
                f.manager.addNewEntity(pig);
                f.status(pig, FullChunkStatus.ENTITY_TICKING);
                assertTrue(pig.getNavigation().moveTo(12.5, y, 2.5, 1));
                for (int tick = 0; tick < 20; tick++) {
                    f.tick();
                }
                assertEquals(20, pig.tickCount);
                assertTrue(goals.get() > 0);
                assertTrue(pig.getX() > 3, "native movement control should advance the mob");
                assertEquals(y, pig.getY(), 0.01, "the cube floor must stop gravity");
                assertFalse(pig.isRemoved());
                int before = goals.get();
                pig.setNoAi(true);
                f.tick();
                assertEquals(before, goals.get());
                f.status(pig, FullChunkStatus.FULL);
                f.tick();
                assertEquals(21, pig.tickCount);
            }
        }
    }

    @Test
    void voidCheckUsesSupportedMinimumAndOrdinaryWorldKeepsVanillaLimit() throws Exception {
        try (var f = new CubeMobTestWorld(directory)) {
            Entity entity = f.pig(CubicBlockBounds.MIN);
            float health = ((Mob) entity).getHealth();
            entity.checkBelowWorld();
            assertEquals(health, ((Mob) entity).getHealth());
            // The protected void callback is tested with a plain entity, whose default behavior discards it.
            Entity marker = net.minecraft.world.entity.EntityTypes.ITEM.create(f.level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
            marker.snapTo(0, CubicBlockBounds.MIN - 65.0, 0);
            marker.checkBelowWorld();
            assertTrue(marker.isRemoved());
            when(((io.github.opencubicchunks.cubicchunks.CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            Entity ordinary = net.minecraft.world.entity.EntityTypes.ITEM.create(f.level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
            ordinary.snapTo(0, -32000000, 0);
            ordinary.checkBelowWorld();
            assertTrue(ordinary.isRemoved());
        }
    }
}
