package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubePlayerSpawn;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Ready")
public abstract class MixinPrepareSpawnReady {
    @Shadow @Final private ServerLevel spawnLevel;
    @Shadow @Final private Vec3 spawnPosition;

    @WrapOperation(method = "keepAlive", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V"))
    private void cc_keepSpawnCubes(ServerChunkCache cache, TicketType type, ChunkPos pos, int radius, Operation<Void> original) {
        if (((CanBeCubic) spawnLevel).cc_isCubic()) {
            CubePlayerSpawn.keepAlive(spawnLevel, type, spawnPosition, radius);
        } else {
            original.call(cache, type, pos, radius);
        }
    }

    @WrapOperation(method = "spawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;waitForEntities(Lnet/minecraft/world/level/ChunkPos;I)V"))
    private void cc_waitForSpawnEntities(ServerLevel level, ChunkPos pos, int radius, Operation<Void> original) {
        if (((CanBeCubic) level).cc_isCubic()) {
            CubePlayerSpawn.waitForEntities(level, spawnPosition, radius);
        } else {
            original.call(level, pos, radius);
        }
    }
}
