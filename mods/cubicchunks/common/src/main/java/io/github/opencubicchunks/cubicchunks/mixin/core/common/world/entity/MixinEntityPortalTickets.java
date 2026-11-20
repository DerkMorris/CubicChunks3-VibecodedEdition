package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class MixinEntityPortalTickets {
    @WrapOperation(method = "placePortalTicket", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V"))
    private void cc_keepDestinationCube(
            ServerChunkCache source, TicketType type, ChunkPos column, int radius, Operation<Void> original,
            @Local(argsOnly = true) BlockPos destination
    ) {
        if (((CanBeCubic) ((Entity) (Object) this).level()).cc_isCubic()) {
            ((ServerCubeCache) source).cc_addTicketWithRadius(type, CloPos.cube(CubePos.from(destination)), radius);
        } else {
            original.call(source, type, column, radius);
        }
    }
}
