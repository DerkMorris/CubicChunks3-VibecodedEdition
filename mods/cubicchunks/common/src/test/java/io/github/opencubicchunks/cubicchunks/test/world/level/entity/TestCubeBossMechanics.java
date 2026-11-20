package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.CubeVillagerTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.EntityHitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Boss logic is native; explosion dispatch is observed here, while terrain explosion execution is covered by the live probe. */
class TestCubeBossMechanics extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void witherChargeExplosionProjectileDamageAndPersistenceKeepFullHeight(int y) throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, y);
            WitherBoss wither = EntityTypes.WITHER.create(f.level, EntitySpawnReason.LOAD);
            assertNotNull(wither);
            wither.snapTo(2.5, y - 1, 2.5);
            wither.makeInvulnerable();
            assertFalse(wither.hurtServer(f.level, f.level.damageSources().generic(), 20));
            assertEquals(100, wither.getHealth());
            for (int tick = 1; tick <= 220; tick++) {
                wither.tickCount = tick;
                invoke(WitherBoss.class, wither, "customServerAiStep", ServerLevel.class, f.level);
            }
            assertEquals(0, wither.getInvulnerableTicks());
            assertEquals(300, wither.getHealth());
            verify(f.level, times(1)).explode(wither, wither.getX(), wither.getEyeY(), wither.getZ(), 7, false, Level.ExplosionInteraction.MOB);
            var target = f.world.pig(y + 3);
            target.snapTo(10.5, y + 3, 2.5);
            List<Entity> spawned = new ArrayList<>();
            when(f.level.addFreshEntity(any())).thenAnswer(i -> spawned.add(i.getArgument(0)));
            wither.performRangedAttack(target, 1);
            assertEquals(1, spawned.size());
            WitherSkull skull = assertInstanceOf(WitherSkull.class, spawned.getFirst());
            assertSame(wither, skull.getOwner());
            assertEquals(y + 2, skull.getY(), 0.001);
            assertTrue(skull.getDeltaMovement().x > 0, "Native skull must aim towards its target");
            CubePos cube = CubePos.from(wither.blockPosition());
            wither.setInvulnerableTicks(42);
            wither.setHealth(117);
            WitherBoss restored = (WitherBoss) CubeEntityData.load(f.level, cube, CubeEntityData.save(cube, List.of(wither))).getFirst();
            assertEquals(42, restored.getInvulnerableTicks());
            assertEquals(117, restored.getHealth());
            assertEquals(wither.position(), restored.position());
            assertTrue(restored.isPowered());
            float health = target.getHealth();
            invoke(WitherSkull.class, skull, "onHitEntity", EntityHitResult.class, new EntityHitResult(target));
            assertTrue(target.getHealth() < health, "Native Wither skull impact must apply damage");
            assertTrue(target.hasEffect(net.minecraft.world.effect.MobEffects.WITHER));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void witherDamageTriggersLocalBlockBreakingAndPoweredArrowImmunity(int y) throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, y);
            WitherBoss wither = EntityTypes.WITHER.create(f.level, EntitySpawnReason.LOAD);
            assertNotNull(wither);
            wither.snapTo(2.5, y - 1, 2.5);
            BlockPos stone = new BlockPos(3, y, 2);
            BlockPos bedrock = stone.above();
            f.world.block(stone, Blocks.STONE.defaultBlockState());
            f.world.block(bedrock, Blocks.BEDROCK.defaultBlockState());
            when(f.level.destroyBlock(any(), eq(true), same(wither)))
                    .thenAnswer(i -> f.level.setBlockAndUpdate(i.getArgument(0), Blocks.AIR.defaultBlockState()));
            assertFalse(wither.hurtServer(f.level, f.level.damageSources().drown(), 10));
            assertFalse(wither.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER, 200)));
            assertTrue(wither.hurtServer(f.level, f.level.damageSources().generic(), 10));
            for (int tick = 1; tick <= 20; tick++) {
                wither.tickCount = tick;
                invoke(WitherBoss.class, wither, "customServerAiStep", ServerLevel.class, f.level);
            }
            assertTrue(f.level.getBlockState(stone).isAir());
            assertTrue(f.level.getBlockState(bedrock).is(Blocks.BEDROCK));
            wither.setHealth(149);
            var arrow = EntityTypes.ARROW.create(f.level, EntitySpawnReason.LOAD);
            assertNotNull(arrow);
            assertFalse(wither.hurtServer(f.level, f.level.damageSources().arrow(arrow, null), 30));
            assertEquals(149, wither.getHealth());
            when(f.level.getDifficulty()).thenReturn(Difficulty.PEACEFUL);
            wither.checkDespawn();
            assertTrue(wither.isRemoved());
        }
    }

    @Test
    void vanillaEndDragonRetainsFlightNodesPartsDamageAndDeathCallbacks() throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, 96);
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            CubeMobTestWorld.setField(Level.class, f.level, "cc_isCubic", false);
            when(f.level.dimension()).thenReturn(Level.END);
            when(f.level.getHeightmapPos(any(), any())).thenAnswer(i -> ((BlockPos) i.getArgument(1)).atY(64));
            f.level.getGameRules().set(GameRules.MOB_DROPS, false, null);
            EnderDragon dragon = EntityTypes.ENDER_DRAGON.create(f.level, EntitySpawnReason.LOAD);
            assertNotNull(dragon);
            dragon.snapTo(0, 100, 0);
            int from = dragon.findClosestNode();
            int to = dragon.findClosestNode(40, 80, 0);
            var path = dragon.findPath(from, to, null);
            assertNotNull(path);
            assertTrue(path.getNodeCount() > 0);
            for (int i = 0; i < path.getNodeCount(); i++) {
                assertTrue(path.getNode(i).y >= 73);
            }
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
            for (int i = 0; i < 5; i++) {
                dragon.aiStep();
            }
            assertEquals(8, dragon.getSubEntities().length);
            for (var part : dragon.getSubEntities()) {
                assertTrue(part.position().distanceTo(dragon.position()) < 20);
            }
            var explosion = f.level.damageSources().explosion(null, null);
            float before = dragon.getHealth();
            assertTrue(dragon.hurt(f.level, dragon.head, explosion, 10));
            assertTrue(dragon.getHealth() < before);
            var fight = mock(EnderDragonFight.class);
            dragon.setDragonFight(fight);
            dragon.setHealth(0);
            for (int i = 0; i < 200; i++) {
                invoke(EnderDragon.class, dragon, "tickDeath");
            }
            assertEquals(200, dragon.dragonDeathTime);
            assertTrue(dragon.isRemoved());
            verify(fight, times(200)).updateDragon(dragon);
            verify(fight).setDragonKilled(dragon);
        }
    }

    @Test
    void nativeEndFightPersistentStateRoundTrips() {
        EnderDragonFight fight = EnderDragonFight.createDefault();
        var saved = EnderDragonFight.CODEC.encodeStart(NbtOps.INSTANCE, fight).getOrThrow();
        var restored = EnderDragonFight.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(saved, EnderDragonFight.CODEC.encodeStart(NbtOps.INSTANCE, restored).getOrThrow());
        assertFalse(restored.hasPreviouslyKilledDragon());
    }

    private static void setup(CubeVillagerTestWorld f, int y) {
        f.world.floor(y - 1);
        when(f.level.getDifficulty()).thenReturn(Difficulty.NORMAL);
        DamageSources sources = new DamageSources(f.level.registryAccess());
        when(f.level.damageSources()).thenReturn(sources);
        when(f.level.getNearbyEntities(eq(LivingEntity.class), any(), any(), any())).thenReturn(List.of());
    }

    private static Object invoke(Class<?> type, Object target, String name, Class<?> argumentType, Object argument) throws Exception {
        var method = type.getDeclaredMethod(name, argumentType);
        method.setAccessible(true);
        return method.invoke(target, argument);
    }

    private static Object invoke(Class<?> type, Object target, String name) throws Exception {
        var method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
