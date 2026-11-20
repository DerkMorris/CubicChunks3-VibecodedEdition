package io.github.opencubicchunks.cubicchunks.test.server.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.LookAt;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real native command method bodies and entity NBT creation; network teleport delivery and entity insertion are controlled sinks. */
class TestCubeNativeCommands extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeSummonCreatesAndRegistersEntityAtTheRequestedFullHeight(int y) throws Exception {
        try (CubeMobTestWorld world = new CubeMobTestWorld(directory.resolve("summon" + y))) {
            CommandSourceStack source = source(world.level);
            when(world.level.getDifficulty()).thenReturn(Difficulty.NORMAL);
            when(world.level.tryAddFreshEntityWithPassengers(any())).thenReturn(true);
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("NoGravity", true);
            Vec3 target = new Vec3(31.5, y + .25, -31.5);
            Entity spawned = SummonCommand.createEntity(source, EntityTypes.ARMOR_STAND.builtInRegistryHolder(), target, tag, false);
            assertEquals(target, spawned.position());
            assertTrue(spawned.isNoGravity(), "Native summon must still apply supplied entity NBT");
            assertFalse(tag.contains("id"), "Native command must retain its defensive copy of caller NBT");
            verify(world.level).tryAddFreshEntityWithPassengers(spawned);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeTeleportUsesTheDestinationModeAndRetainsMovementAndLookSideEffects(int y) throws Exception {
        ServerLevel origin = level(false);
        ServerLevel destination = level(true);
        CommandSourceStack source = source(origin);
        Entity entity = entity(origin);
        LookAt look = mock();
        teleport(source, entity, destination, new Vec3(31.5, y + .25, -31.5), Set.of(), look);
        verify(entity).teleportTo(destination, 31.5, y + .25, -31.5, Set.of(), 90, -90, true);
        verify(entity).setDeltaMovement(new Vec3(2, 0, 3));
        verify(entity).setOnGround(true);
        verify(look).perform(source, entity);
    }

    @Test
    void ordinaryDestinationRetainsNativeHeightCutoffEvenWhenTheSourceIsCubic() throws Exception {
        ServerLevel origin = level(true);
        ServerLevel destination = level(false);
        CommandSourceStack source = source(origin);
        Entity entity = entity(origin);
        for (int y : new int[] { 32000000, -32000000 }) {
            assertThrows(CommandSyntaxException.class, () -> teleport(source, entity, destination, new Vec3(0, y, 0), Set.of(), null));
        }
        teleport(source, entity, destination, new Vec3(0, 32000, 0), Set.of(), null);
        verify(entity).teleportTo(destination, 0, 32000, 0, Set.of(), 90, -90, true);
        assertThrows(CommandSyntaxException.class, () -> SummonCommand.createEntity(source(destination),
                EntityTypes.ARMOR_STAND.builtInRegistryHolder(), new Vec3(0, 32000000, 0), new CompoundTag(), false));
        verify(destination, never()).tryAddFreshEntityWithPassengers(any());
    }

    @Test
    void nativeHorizontalLimitsAndCubicPackingEdgesAreEnforcedBeforeAnyMutation() throws Exception {
        ServerLevel destination = level(true);
        CommandSourceStack source = source(destination);
        Entity entity = entity(destination);
        Vec3[] invalid = { new Vec3(30000000, 32000, 0), new Vec3(-30000001, 32000, 0), new Vec3(0, 32000, 30000000), new Vec3(0, 32000, -30000001),
            new Vec3(0, CubicBlockBounds.MAX + 1.0, 0), new Vec3(0, CubicBlockBounds.MIN - 1.0, 0) };
        for (Vec3 target : invalid) {
            assertThrows(CommandSyntaxException.class, () -> teleport(source, entity, destination, target, Set.of(), null));
            assertThrows(CommandSyntaxException.class,
                    () -> SummonCommand.createEntity(source, EntityTypes.ARMOR_STAND.builtInRegistryHolder(), target, new CompoundTag(), false));
        }
        verify(entity, never()).teleportTo(any(), anyDouble(), anyDouble(), anyDouble(), any(), anyFloat(), anyFloat(), anyBoolean());
        verify(destination, never()).tryAddFreshEntityWithPassengers(any());
        for (int edge : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX }) {
            teleport(source, entity, destination, new Vec3(-30000000, edge, 29999999), Set.of(), null);
            verify(entity).teleportTo(destination, -30000000, edge, 29999999, Set.of(), 90, -90, true);
        }
    }

    @Test
    void relativeTeleportStillPassesNativeDeltasAtExtremeHeight() throws Exception {
        ServerLevel level = level(true);
        Entity entity = entity(level);
        when(entity.getY()).thenReturn(32000000.0);
        when(entity.getX()).thenReturn(31.0);
        Set<Relative> relative = Set.of(Relative.X, Relative.Y);
        teleport(source(level), entity, level, new Vec3(32, 32000001, 4), relative, null);
        verify(entity).teleportTo(eq(level), eq(1.0), eq(1.0), eq(4.0), eq(relative), eq(90.0f), eq(-90.0f), eq(true));
    }

    private static ServerLevel level(boolean cubic) {
        ServerLevel level = mock();
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
        return level;
    }

    private static CommandSourceStack source(ServerLevel level) {
        CommandSourceStack source = mock();
        when(source.getLevel()).thenReturn(level);
        return source;
    }

    private static Entity entity(ServerLevel level) {
        Entity entity = mock();
        when(entity.level()).thenReturn(level);
        when(entity.getDeltaMovement()).thenReturn(new Vec3(2, 4, 3));
        when(entity.teleportTo(any(), anyDouble(), anyDouble(), anyDouble(), any(), anyFloat(), anyFloat(), anyBoolean())).thenReturn(true);
        return entity;
    }

    private static void teleport(
            CommandSourceStack source, Entity entity, ServerLevel destination, Vec3 position, Set<Relative> relative, LookAt look
    ) throws Exception {
        var method = TeleportCommand.class.getDeclaredMethod("performTeleport", CommandSourceStack.class, Entity.class, ServerLevel.class,
                double.class, double.class, double.class, Set.class, float.class, float.class, LookAt.class);
        method.setAccessible(true);
        try {
            method.invoke(null, source, entity, destination, position.x, position.y, position.z, relative, 450.0f, -450.0f, look);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(failure.getCause());
        }
    }
}
