package io.github.opencubicchunks.cubicchunks.mixin.access.common;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NaturalSpawner.class)
public interface NaturalSpawnerAccess {
    @Invoker("getMobForSpawn") static @Nullable Mob cc_createMob(ServerLevel level, EntityType<?> type) {
        throw new AssertionError();
    }

    @Invoker("isValidPositionForMob") static boolean cc_validMob(ServerLevel level, Mob mob, double distance) {
        throw new AssertionError();
    }
}
