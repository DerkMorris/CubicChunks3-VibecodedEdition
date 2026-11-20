package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.entity.EntityCubePosGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native entity ticks, collision and ray targeting over real cubes; unrelated world services are mocked. */
class TestCubeEntityMechanics extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "dropped item gravity and expiry at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void droppedItemsFallAcrossVerticalCubeSeamTickAndExpire(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y - 1);
            ItemEntity item = new ItemEntity(world.level, 2.5, y + 2, 2.5, new ItemStack(Items.DIAMOND, 7), 0, 0, 0);
            item.setPickUpDelay(3);
            assertTrue(world.manager.addNewEntity(item));
            world.status(item, FullChunkStatus.ENTITY_TICKING);
            world.status(world.pig(y - 1), FullChunkStatus.ENTITY_TICKING);
            CubePos before = ((EntityCubePosGetter) item).cc_cubePosition();
            world.tick();
            assertTrue(item.hasPickUpDelay());
            for (int tick = 1; tick < 50; tick++) {
                world.tick();
            }
            assertFalse(item.hasPickUpDelay());
            assertEquals(50, item.getAge());
            assertEquals(y - 1, item.getY(), 0.01);
            assertEquals(before.getY() - 1, ((EntityCubePosGetter) item).cc_cubePosition().getY());
            assertEquals(7, item.getItem().getCount());
            assertFalse(item.isRemoved());
            world.status(item, FullChunkStatus.FULL);
            world.tick();
            assertEquals(50, item.getAge(), "Items must pause with an inactive cube");
            world.status(item, FullChunkStatus.ENTITY_TICKING);
            CubeMobTestWorld.setField(ItemEntity.class, item, "age", 5999);
            world.tick();
            assertTrue(item.isRemoved(), "Native five-minute expiry must still run at extended heights");
            assertFalse(world.manager.isLoaded(item.getUUID()));
        }
    }

    @ParameterizedTest(name = "projectile target query at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void projectileRayFindsNearestEntityAcrossCubeSeamAndIgnoresUnloadedTargets(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            nativeQueries(world);
            var source = EntityTypes.ARROW.create(world.level, EntitySpawnReason.LOAD);
            assertNotNull(source);
            source.snapTo(2.5, y - 2, 2.5);
            var near = world.pig(y + 1);
            var far = world.pig(y + 5);
            var alias = world.pig(y + (y > 0 ? -(1 << 24) : 1 << 24) + 1);
            for (Entity target : List.of(near, far, alias)) {
                assertTrue(world.manager.addNewEntity(target));
                world.status(target, FullChunkStatus.FULL);
            }
            Vec3 start = source.position();
            Vec3 end = start.add(0, 10, 0);
            AABB area = new AABB(start, end).inflate(1);
            var hit = ProjectileUtil.getEntityHitResult(world.level, source, start, end, area, entity -> true, 0);
            assertNotNull(hit);
            assertSame(near, hit.getEntity());
            near.discard();
            hit = ProjectileUtil.getEntityHitResult(world.level, source, start, end, area, entity -> true, 0);
            assertNotNull(hit);
            assertSame(far, hit.getEntity());
            world.status(far, FullChunkStatus.INACCESSIBLE);
            assertNull(ProjectileUtil.getEntityHitResult(world.level, source, start, end, area, entity -> true, 0));
        }
    }

    @ParameterizedTest(name = "enderman teleport safety at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void endermanTeleportFindsFloorAcrossCubeSeamAndRejectsWaterWallsAndMissingCubes(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y - 1);
            doCallRealMethod().when(world.level).noCollision(any(AABB.class));
            doCallRealMethod().when(world.level).noCollision(nullable(Entity.class), any(AABB.class));
            Enderman mob = EntityTypes.ENDERMAN.create(world.level, EntitySpawnReason.LOAD);
            assertNotNull(mob);
            mob.snapTo(2.5, y - 1, 2.5);
            assertTrue(mob.teleport(34.5, y + 2, 2.5));
            assertEquals(new Vec3(34.5, y - 1, 2.5), mob.position());
            Vec3 landed = mob.position();
            world.block(new BlockPos(12, y - 1, 2), Blocks.WATER.defaultBlockState());
            assertFalse(mob.teleport(12.5, y + 2, 2.5));
            assertEquals(landed, mob.position());
            world.block(new BlockPos(12, y - 1, 2), Blocks.AIR.defaultBlockState());
            world.block(new BlockPos(12, y + 1, 2), Blocks.STONE.defaultBlockState());
            assertFalse(mob.teleport(12.5, y, 2.5));
            assertEquals(landed, mob.position());
            assertFalse(mob.teleport(1000.5, y + 2, 2.5));
            assertEquals(landed, mob.position());
        }
    }

    private static void nativeQueries(CubeMobTestWorld world) throws Exception {
        var getter = ServerLevel.class.getDeclaredMethod("getEntities");
        getter.setAccessible(true);
        doCallRealMethod().when(world.level);
        getter.invoke(world.level);
        doCallRealMethod().when(world.level).getEntities(nullable(Entity.class), any(AABB.class), any());
        doCallRealMethod().when(world.level).getEntities(any(EntityTypeTest.class), any(AABB.class), any());
        when(world.level.dragonParts()).thenReturn(List.of());
    }

    @ParameterizedTest(name = "general living-entity teleport at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void generalRandomTeleportUsesLoadedCubeFloorAndForbiddenBlockPredicate(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y - 1);
            doCallRealMethod().when(world.level).noCollision(any(AABB.class));
            doCallRealMethod().when(world.level).noCollision(nullable(Entity.class), any(AABB.class));
            var mob = world.pig(y - 1);
            assertTrue(mob.getNavigation().moveTo(10.5, y - 1, 2.5, 1));
            assertFalse(mob.getNavigation().isDone());
            assertTrue(mob.randomTeleport(34.5, y + 2, 2.5, true, state -> false));
            assertEquals(new Vec3(34.5, y - 1, 2.5), mob.position());
            assertTrue(mob.getNavigation().isDone());
            verify(world.level).broadcastEntityEvent(mob, (byte) 46);
            Vec3 landed = mob.position();
            assertFalse(mob.randomTeleport(12.5, y + 2, 2.5, false, state -> state.is(Blocks.STONE)));
            assertEquals(landed, mob.position());
            world.block(new BlockPos(12, y - 1, 2), Blocks.COBWEB.defaultBlockState());
            assertFalse(mob.randomTeleport(12.5, y + 2, 2.5, false, state -> state.is(Blocks.COBWEB)));
            assertEquals(landed, mob.position());
            world.block(new BlockPos(12, y - 1, 2), Blocks.WATER.defaultBlockState());
            assertFalse(mob.randomTeleport(12.5, y + 2, 2.5, false, state -> false));
            assertEquals(landed, mob.position());
            assertFalse(mob.randomTeleport(1000.5, y + 2, 2.5, false, state -> false));
            assertEquals(landed, mob.position());
            if (y < -64) {
                when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
                when(world.level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
                assertFalse(mob.randomTeleport(34.5, y + 2, 2.5, false, state -> false),
                        "Ordinary dimensions retain their original minimum teleport height");
            }
        }
    }

    @ParameterizedTest(name = "falling block expiry at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void fallingBlockKeepsNativeLifetimeInsideCubicBounds(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y);
            var sand = EntityTypes.FALLING_BLOCK.create(world.level, EntitySpawnReason.LOAD);
            assertNotNull(sand);
            sand.snapTo(2.5, y + 10, 2.5);
            sand.setNoGravity(true);
            sand.dropItem = false;
            sand.time = 100;
            sand.tick();
            assertFalse(sand.isRemoved(), "Vanilla dimension height must not trigger the early out-of-world timeout");
            sand.time = 600;
            sand.tick();
            assertTrue(sand.isRemoved(), "The normal 600-tick lifetime still applies");
            when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
            var ordinary = EntityTypes.FALLING_BLOCK.create(world.level, EntitySpawnReason.LOAD);
            assertNotNull(ordinary);
            ordinary.snapTo(2.5, 32000, 2.5);
            ordinary.setNoGravity(true);
            ordinary.dropItem = false;
            ordinary.time = 100;
            ordinary.tick();
            assertTrue(ordinary.isRemoved(), "Ordinary worlds retain their original dimension timeout");
        }
    }
}
