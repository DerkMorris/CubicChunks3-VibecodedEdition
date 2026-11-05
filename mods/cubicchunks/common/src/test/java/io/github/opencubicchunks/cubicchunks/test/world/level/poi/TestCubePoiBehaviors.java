package io.github.opencubicchunks.cubicchunks.test.world.level.poi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeSpawnTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.poi.PoiRetryKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.SetClosestHomeAsWalkTarget;
import net.minecraft.world.entity.ai.behavior.ValidateNearbyPoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestBedSensor;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubePoiBehaviors extends BaseTest {
    @TempDir
    Path directory;

    private static Villager villager(CubeSpawnTestWorld f, int y) {
        Villager villager = EntityTypes.VILLAGER.create(f.level, EntitySpawnReason.LOAD);
        villager.snapTo(2.5, y, 2.5);
        villager.setOnGround(true);
        when(f.level.getServer().getLevel(f.level.dimension())).thenReturn(f.level);
        return villager;
    }

    private static void acquire(CubeSpawnTestWorld f, Villager villager, BehaviorControl<PathfinderMob> behavior) {
        when(f.level.getGameTime()).thenReturn(100L);
        assertFalse(behavior.tryStart(f.level, villager, 100));
        when(f.level.getGameTime()).thenReturn(200L);
        assertTrue(behavior.tryStart(f.level, villager, 200));
    }

    @Test
    void nativeAcquireHomeUsesRealPathClaimsAndMemoryAtBothHeightSigns() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                BlockPos home = new BlockPos(6, y, 2);
                f.world.block(home, TestCubePoiManager.bed());
                var first = villager(f, y);
                acquire(f, first, AcquirePoi.create(type -> type.is(PoiTypes.HOME), MemoryModuleType.HOME, true, Optional.empty()));
                assertEquals(GlobalPos.of(f.level.dimension(), home), first.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow());
                assertEquals(1, f.poi.getCountInRange(type -> true, home, 0, PoiManager.Occupancy.IS_OCCUPIED));
                var other = villager(f, y);
                acquire(f, other, AcquirePoi.create(type -> type.is(PoiTypes.HOME), MemoryModuleType.HOME, true, Optional.empty()));
                assertTrue(other.getBrain().getMemory(MemoryModuleType.HOME).isEmpty());
                first.releasePoi(MemoryModuleType.HOME);
                assertEquals(1, f.poi.getCountInRange(type -> true, home, 0, PoiManager.Occupancy.HAS_SPACE));
            }
        }
    }

    @Test
    void nativeJobAcquisitionAssignsFarmerAndReleasesCorrectWorkstation() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                BlockPos job = new BlockPos(6, y, 2);
                f.world.block(job, Blocks.COMPOSTER.defaultBlockState());
                f.world.block(job.above(4096), Blocks.COMPOSTER.defaultBlockState());
                var body = villager(f, y);
                acquire(f, body, AcquirePoi.create(type -> type.is(PoiTypes.FARMER), MemoryModuleType.POTENTIAL_JOB_SITE, true, Optional.empty()));
                assertEquals(job, body.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).orElseThrow().pos());
                body.snapTo(5.5, y, 2.5);
                assertTrue(AssignProfessionFromJobSite.create().tryStart(f.level, body, 300));
                assertTrue(body.getVillagerData().profession().is(VillagerProfession.FARMER));
                assertEquals(job, body.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElseThrow().pos());
                body.releasePoi(MemoryModuleType.JOB_SITE);
                assertEquals(1, f.poi.getCountInRange(type -> true, job, 0, PoiManager.Occupancy.HAS_SPACE));
                assertEquals(1, f.poi.getCountInRange(type -> true, job.above(4096), 0, PoiManager.Occupancy.HAS_SPACE));
            }
        }
    }

    @Test
    void nativeMeetingAcquisitionRespectsBellCapacityAndFullHeight() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = -32000024;
            f.world.floor(y);
            BlockPos bell = new BlockPos(6, y, 2);
            f.world.block(bell, Blocks.BELL.defaultBlockState());
            var body = villager(f, y);
            acquire(f, body, AcquirePoi.create(type -> type.is(PoiTypes.MEETING), MemoryModuleType.MEETING_POINT, false, Optional.empty()));
            assertEquals(bell, body.getBrain().getMemory(MemoryModuleType.MEETING_POINT).orElseThrow().pos());
            int capacity = f.poi.getType(bell).orElseThrow().value().maxTickets();
            for (int i = 1; i < capacity; i++) {
                assertTrue(f.poi.take(type -> true, (type, point) -> true, bell, 0).isPresent());
            }
            assertTrue(f.poi.take(type -> true, (type, point) -> true, bell, 0).isEmpty());
            body.releasePoi(MemoryModuleType.MEETING_POINT);
            assertTrue(f.poi.take(type -> true, (type, point) -> true, bell, 0).isPresent());
        }
    }

    @Test
    void removedPoiInvalidatesNativeHomeMemoryWithoutTouchingAlias() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            f.world.floor(y);
            BlockPos home = new BlockPos(6, y, 2);
            f.world.block(home, TestCubePoiManager.bed());
            f.world.block(home.above(4096), TestCubePoiManager.bed());
            var body = villager(f, y);
            body.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(f.level.dimension(), home));
            f.world.block(home, Blocks.AIR.defaultBlockState());
            assertTrue(ValidateNearbyPoi.create(type -> type.is(PoiTypes.HOME), MemoryModuleType.HOME).tryStart(f.level, body, 1));
            assertTrue(body.getBrain().getMemory(MemoryModuleType.HOME).isEmpty());
            assertTrue(f.poi.existsAtPosition(PoiTypes.HOME, home.above(4096)));
        }
    }

    @Test
    void nativeHomeWalkAndBabyBedSensorRetainHighTargets() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeSpawnTestWorld(directory.resolve(Integer.toString(y)))) {
                f.world.floor(y);
                BlockPos home = new BlockPos(6, y, 2);
                f.world.block(home, TestCubePoiManager.bed());
                var body = villager(f, y);
                when(f.level.getGameTime()).thenReturn(100L);
                assertTrue(SetClosestHomeAsWalkTarget.create(0.5F).tryStart(f.level, body, 100));
                assertEquals(home, body.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().getTarget().currentBlockPosition());
                body.setAge(-100);
                var sensor = new NearestBedSensor();
                var tick = NearestBedSensor.class.getDeclaredMethod("doTick", net.minecraft.server.level.ServerLevel.class,
                        net.minecraft.world.entity.Mob.class);
                tick.setAccessible(true);
                tick.invoke(sensor, f.level, body);
                assertEquals(home, body.getBrain().getMemory(MemoryModuleType.NEAREST_BED).orElseThrow());
            }
        }
    }

    @Test
    void failedNativePoiAttemptDoesNotSuppressAnAliasedDestinationAfterTeleport() throws Exception {
        try (var f = new CubeSpawnTestWorld(directory)) {
            int y = 32000008;
            BlockPos blocked = new BlockPos(6, y, 2);
            f.world.block(blocked, TestCubePoiManager.bed());
            var body = villager(f, y);
            var random = org.mockito.Mockito.mock(net.minecraft.util.RandomSource.class);
            when(f.level.getRandom()).thenReturn(random);
            var behavior = AcquirePoi.create(type -> type.is(PoiTypes.HOME), MemoryModuleType.HOME, true, Optional.empty());
            acquire(f, body, behavior);
            assertTrue(body.getBrain().getMemory(MemoryModuleType.HOME).isEmpty());
            f.world.floor(y + 4096);
            f.world.block(blocked.above(4096), TestCubePoiManager.bed());
            body.snapTo(2.5, y + 4096, 2.5);
            body.setOnGround(true);
            when(f.level.getGameTime()).thenReturn(221L);
            assertTrue(behavior.tryStart(f.level, body, 221));
            assertEquals(blocked.above(4096), body.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow().pos());
        }
    }

    @Test
    void retryKeysSeparateAliasedPositionsAndSurviveCleanupOfExpiredEntries() {
        var objects = new PoiRetryKeys.Objects<String>();
        var times = new PoiRetryKeys.Times();
        BlockPos first = new BlockPos(5, 32000008, 5);
        BlockPos alias = first.above(4096);
        assertEquals(first.asLong(), alias.asLong());
        long key = objects.key(first);
        objects.put(key, "retained");
        long timeKey = times.key(first);
        times.put(timeKey, 100L);
        for (int i = 0; i < 600; i++) {
            objects.key(first.offset(i, 0, 1));
            times.key(first.offset(i, 0, 1));
        }
        assertEquals("retained", objects.get(objects.key(first)));
        assertEquals(100L, times.get(times.key(first)));
        assertNotEquals(key, objects.key(alias));
        assertNotEquals(timeKey, times.key(alias));
        objects.clear();
        times.clear();
        assertFalse(objects.containsKey(objects.key(first)));
        assertFalse(times.containsKey(times.key(first)));
    }
}
