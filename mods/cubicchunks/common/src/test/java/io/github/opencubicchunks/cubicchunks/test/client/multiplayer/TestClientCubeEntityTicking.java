package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientEntityManager;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientLevel;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TestClientCubeEntityTicking extends BaseTest {
    @Test
    void cubeLoadBeforeOrAfterSpawnStartsNativeTicksAndDropReloadRemovalStopThem() throws Exception {
        Fixture f = new Fixture(true);
        Mob first = spy(f.mob(EntityTypes.PIG, 32000000));
        doNothing().when(first).tick();
        f.manager.addEntity(first);
        f.level.tickEntities();
        assertEquals(0, first.tickCount);
        CubePos pos = CubePos.from(first.blockPosition());
        f.install(pos);
        assertTrue(f.ticks.contains(first));
        f.level.tickEntities();
        assertEquals(1, first.tickCount);
        Mob second = f.mob(EntityTypes.COW, 32000000);
        f.manager.addEntity(second);
        assertTrue(f.ticks.contains(second));
        f.install(pos); // same-object relight snapshot cannot stop/restart entities
        assertTrue(f.ticks.contains(first));
        f.cache.cc_drop(pos);
        assertFalse(f.ticks.contains(first));
        assertFalse(f.ticks.contains(second));
        f.install(pos);
        first.discard();
        assertFalse(f.ticks.contains(first));
        f.cache.cc_drop(pos);
        f.install(pos);
        assertFalse(f.ticks.contains(first));
        assertTrue(f.ticks.contains(second));
        assertEquals(1, f.manager.count());
    }

    @Test
    void fullHeightMovementDoesNotAliasNativeSectionKeysOrTickOtherVerticalCubes() throws Exception {
        Fixture f = new Fixture(true);
        Mob low = f.mob(EntityTypes.PIG, -32000000);
        Mob high = f.mob(EntityTypes.COW, -32000000 + (1 << 24));
        assertEquals(SectionPos.asLong(low.blockPosition()), SectionPos.asLong(high.blockPosition()));
        f.manager.addEntity(low);
        f.manager.addEntity(high);
        f.install(CubePos.from(low.blockPosition()));
        assertTrue(f.ticks.contains(low));
        assertFalse(f.ticks.contains(high));
        low.snapTo(high.position());
        assertFalse(f.ticks.contains(low));
        f.install(CubePos.from(high.blockPosition()));
        assertTrue(f.ticks.contains(low));
        assertTrue(f.ticks.contains(high));
        high.snapTo(high.position().add(0, 32, 0));
        assertFalse(f.ticks.contains(high));
        assertTrue(f.ticks.contains(low));
    }

    @Test
    void changingViewCenterRadiusAndRingEvictionRefreshTickMembership() throws Exception {
        Fixture f = new Fixture(true);
        f.cache.cc_updateViewRadius(5);
        Mob mob = f.mob(EntityTypes.PIG, 32000000);
        CubePos pos = CubePos.from(mob.blockPosition());
        f.install(pos);
        f.manager.addEntity(mob);
        f.cache.cc_updateViewCenter(pos.getX() + 7, pos.getY(), pos.getZ());
        assertTrue(f.ticks.contains(mob));
        f.cache.cc_updateViewRadius(2);
        assertFalse(f.ticks.contains(mob));
        f.install(pos);
        assertTrue(f.ticks.contains(mob));
        f.cache.cc_updateViewCenter(pos.getX(), pos.getY() + 20, pos.getZ());
        assertFalse(f.ticks.contains(mob));
        f.cache.cc_updateViewCenter(pos.getX(), pos.getY(), pos.getZ());
        assertTrue(f.ticks.contains(mob));
        f.install(CubePos.of(pos.getX(), pos.getY() + 11, pos.getZ())); // radius 5 => same ring slot
        f.cache.cc_updateViewCenter(pos.getX(), pos.getY(), pos.getZ());
        assertFalse(f.ticks.contains(mob));
    }

    @Test
    void nativeFreezeAndPassengerRulesRemainInForce() throws Exception {
        Fixture f = new Fixture(true);
        Mob vehicle = spy(f.mob(EntityTypes.PIG, 32000000));
        Mob passenger = spy(f.mob(EntityTypes.COW, 32000000));
        doNothing().when(vehicle).tick();
        doNothing().when(passenger).rideTick();
        f.install(CubePos.from(vehicle.blockPosition()));
        f.manager.addEntity(vehicle);
        f.manager.addEntity(passenger);
        assertTrue(passenger.startRiding(vehicle, true, false));
        f.level.tickEntities();
        assertEquals(1, vehicle.tickCount);
        assertEquals(1, passenger.tickCount);
        when(f.rate.isEntityFrozen(vehicle)).thenReturn(true);
        f.level.tickEntities();
        assertEquals(1, vehicle.tickCount);
        assertEquals(1, passenger.tickCount);
    }

    @Test
    void alwaysTickingEntitiesAndOrdinaryColumnLifecycleKeepNativeBehavior() throws Exception {
        Fixture cubic = new Fixture(true);
        Mob always = spy(cubic.mob(EntityTypes.PIG, 64));
        when(always.isAlwaysTicking()).thenReturn(true);
        cubic.manager.addEntity(always);
        assertTrue(cubic.ticks.contains(always));
        Mob ordinaryMob = cubic.mob(EntityTypes.COW, 64);
        cubic.manager.addEntity(ordinaryMob);
        cubic.manager.startTicking(ordinaryMob.chunkPosition());
        assertFalse(cubic.ticks.contains(ordinaryMob), "A column packet cannot activate an unavailable cubic height");
        cubic.manager.stopTicking(always.chunkPosition());
        assertTrue(cubic.ticks.contains(always));
        always.discard();
        assertFalse(cubic.ticks.contains(always));
        Fixture vanilla = new Fixture(false);
        Mob mob = vanilla.mob(EntityTypes.PIG, 64);
        vanilla.manager.addEntity(mob);
        assertFalse(vanilla.ticks.contains(mob));
        vanilla.manager.startTicking(mob.chunkPosition());
        assertTrue(vanilla.ticks.contains(mob));
        vanilla.manager.stopTicking(mob.chunkPosition());
        assertFalse(vanilla.ticks.contains(mob));
    }

    @Test
    void actualClientMobTicksAdvanceInterpolationAnimationAndDamageCountdownAtBothHeightSigns() throws Exception {
        for (int y : new int[] { 32000000, -32000000 }) {
            Fixture f = new Fixture(true);
            List<Mob> mobs = new ArrayList<>();
            for (EntityType<? extends Mob> type : List.of(EntityTypes.PIG, EntityTypes.COW, EntityTypes.ZOMBIE, EntityTypes.ENDERMAN, EntityTypes.BAT,
                    EntityTypes.COD)) {
                Mob mob = f.mob(type, y + 8);
                mob.setNoGravity(true);
                mob.setOnGround(true);
                mobs.add(mob);
                f.manager.addEntity(mob);
            }
            f.install(CubePos.from(mobs.getFirst().blockPosition()));
            for (Mob mob : mobs) {
                mob.moveOrInterpolateTo(new Vec3(8.5, y + 8, 2.5), 45, 10);
                mob.hurtTime = 10;
                mob.hurtDuration = 10;
            }
            for (int tick = 0; tick < 12; tick++) {
                f.level.tickEntities();
            }
            for (Mob mob : mobs) {
                assertEquals(12, mob.tickCount, mob.getType().toString());
                assertEquals(8.5, mob.getX(), 0.01, "Queued movement must reach " + mob.getType() + " at " + mob.position());
                assertFalse(mob.isInterpolating());
                assertEquals(0, mob.hurtTime, "The red damage overlay must expire");
            }
            assertTrue(mobs.getFirst().walkAnimation.position() > 0, "Walking animation must advance with interpolated movement");
        }
    }

    private static final class Fixture {
        private final ClientLevel level = Misc.mockLevelWithPalettes(ClientLevel.class);
        private final EntityTickList ticks = new EntityTickList();
        private final TickRateManager rate = mock();
        private final TransientEntitySectionManager<Entity> manager;
        private final ClientCubeCache cache;
        private int nextId;

        private Fixture(boolean cubic) throws Exception {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
            setField(Level.class, level, "cc_isCubic", cubic);
            setField(Level.class, level, "random", RandomSource.create(1));
            when(level.getRandom()).thenReturn(RandomSource.create(1));
            when(level.isClientSide()).thenReturn(true);
            when(level.getRelativeTickSpeed()).thenReturn(1.0F);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getNextEntityId()).thenAnswer(invocation -> ++nextId);
            when(level.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
            when(level.getFluidState(any())).thenReturn(Blocks.AIR.defaultBlockState().getFluidState());
            setField(ClientLevel.class, level, "minecraft", mock(Minecraft.class));
            setField(ClientLevel.class, level, "tickingEntities", ticks);
            setField(ClientLevel.class, level, "tickRateManager", rate);
            var constructor = Class.forName("net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks").getDeclaredConstructor(ClientLevel.class);
            constructor.setAccessible(true);
            manager = new TransientEntitySectionManager<>(Entity.class, (LevelCallback<Entity>) constructor.newInstance(level));
            setField(ClientLevel.class, level, "entityStorage", manager);
            ClientChunkCache source = new ClientChunkCache(level, 2);
            cache = (ClientCubeCache) source;
            setField(ClientLevel.class, level, "chunkSource", source);
            when(level.getChunkSource()).thenReturn(source);
            when(((CubicClientLevel) level).cc_getCubeSource()).thenReturn(cache);
            if (cubic) {
                ((CubicClientEntityManager) manager)
                        .cc_configureCubeTicks(pos -> cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null);
            }
            doCallRealMethod().when((CubicClientLevel) level).cc_onCubeLoaded(any());
            doCallRealMethod().when((CubicClientLevel) level).cc_onCubeUnloaded(any());
            doCallRealMethod().when((CubicClientLevel) level).cc_refreshEntityTicks();
            doCallRealMethod().when(level).tickEntities();
            doCallRealMethod().when(level).tickNonPassenger(any());
            doCallRealMethod().when(level).guardEntityTick(any(), any());
        }

        private Mob mob(EntityType<? extends Mob> type, int y) {
            Mob mob = type.create(level, EntitySpawnReason.LOAD);
            mob.snapTo(2.5, y, 2.5);
            return mob;
        }

        private void install(CubePos pos) {
            cache.cc_updateViewCenter(pos.getX(), pos.getY(), pos.getZ());
            var data = new CCClientboundLevelCubePacketData(new LevelCube(level, pos));
            var buffer = data.getReadBuffer();
            try {
                cache.cc_replaceWithPacketData(pos.getX(), pos.getY(), pos.getZ(), buffer, data.getHeightmaps(), ignored -> {});
            } finally {
                buffer.release();
            }
        }
    }
}
