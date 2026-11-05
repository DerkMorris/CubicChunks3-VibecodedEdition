package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.network;

import java.util.concurrent.CompletableFuture;

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

@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Preparing")
public abstract class MixinPrepareSpawnPreparing {
    @Shadow @Final private ServerLevel spawnLevel;
    @Shadow @Final private CompletableFuture<Vec3> spawnPosition;

    @WrapOperation(method = "lambda$tick$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;addTicketAndLoadWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<?> cc_loadSpawnCubes(
            ServerChunkCache cache, TicketType type, ChunkPos pos, int radius, Operation<CompletableFuture<?>> original
    ) {
        return ((CanBeCubic) spawnLevel).cc_isCubic() ? CubePlayerSpawn.load(spawnLevel, type, spawnPosition.join(), radius)
                : original.call(cache, type, pos, radius);
    }
}
