package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native consumption-effect body and living-entity teleport, with controlled random choices and real cube floors. */
class TestCubeConsumeTeleport extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "chorus consumption effect at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeConsumeEffectKeepsTeleportNearSubjectAndRetainsSuccessEffects(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y);
            when(world.level.getLogicalHeight()).thenReturn(384);
            doCallRealMethod().when(world.level).noCollision(any(AABB.class));
            doCallRealMethod().when(world.level).noCollision(nullable(Entity.class), any(AABB.class));
            var subject = world.pig(y);
            Vec3 before = subject.position();
            var random = mock(RandomSource.class);
            when(random.nextDouble()).thenReturn(0.75, 0.75, 0.5);
            CubeMobTestWorld.setField(Entity.class, subject, "random", random);
            subject.fallDistance = 25;
            subject.setIgnoreFallDamageFromCurrentImpulse(true, before);

            assertTrue(new TeleportRandomlyConsumeEffect().apply(world.level, new ItemStack(Items.CHORUS_FRUIT), subject));

            assertEquals(new Vec3(6.5, y, 2.5), subject.position(), "The consume effect must not clamp a cubic target into the vanilla height band");
            assertEquals(0.0, subject.fallDistance);
            assertFalse(subject.isIgnoringFallDamageFromCurrentImpulse());
            verify(random, times(3)).nextDouble();
            verify(world.level).gameEvent(eq(GameEvent.TELEPORT), eq(before), any(GameEvent.Context.class));
            verify(world.level).playSound(nullable(Entity.class), eq(6.5), eq((double) y), eq(2.5), eq(SoundEvents.CHORUS_FRUIT_TELEPORT),
                    eq(SoundSource.PLAYERS));
            verify(world.level).levelEvent(eq(2017), eq(BlockPos.containing(before)), anyInt());
            assertEquals(-64, world.level.getMinY());
            assertEquals(384, world.level.getLogicalHeight());
        }
    }

    @Test
    void consumedTeleportTargetsClampToCubicBoundsAndRetainSixteenFailedAttempts() {
        checkFailedTargets(true, CubicBlockBounds.MAX - 1.0, Math.nextDown(1.0), CubicBlockBounds.MAX);
        checkFailedTargets(true, CubicBlockBounds.MIN + 1.0, 0.0, CubicBlockBounds.MIN);
    }

    @Test
    void ordinaryConsumeEffectRetainsDimensionLogicalHeightClamping() {
        checkFailedTargets(false, 32000, 0.5, 319);
        checkFailedTargets(false, -32000, 0.5, -64);
    }

    private static void checkFailedTargets(boolean cubic, double startY, double randomValue, double expectedY) {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
        when(level.getLogicalHeight()).thenReturn(384);
        var subject = mock(LivingEntity.class);
        var random = mock(RandomSource.class);
        when(subject.getY()).thenReturn(startY);
        when(subject.getRandom()).thenReturn(random);
        when(subject.position()).thenReturn(new Vec3(0, startY, 0));
        when(random.nextDouble()).thenReturn(randomValue);

        assertFalse(new TeleportRandomlyConsumeEffect().apply(level, new ItemStack(Items.CHORUS_FRUIT), subject));

        verify(subject, times(16)).randomTeleport(anyDouble(), eq(expectedY), anyDouble(), eq(true), eq(BlockTags.CONSUMABLE_DOES_NOT_TELEPORT_TO));
        verify(random, times(48)).nextDouble();
        verify(subject, never()).resetFallDistance();
        verify(subject, never()).resetCurrentImpulseContext();
        assertEquals(-64, level.getMinY());
        assertEquals(384, level.getLogicalHeight());
    }
}
