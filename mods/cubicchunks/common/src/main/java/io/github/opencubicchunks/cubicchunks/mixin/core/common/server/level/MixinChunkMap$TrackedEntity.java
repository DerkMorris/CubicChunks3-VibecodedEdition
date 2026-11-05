package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddTransformToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCloSet;
import io.github.opencubicchunks.cubicchunks.server.level.CubicChunkMap;
import io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Dasm(value = ChunkToCloSet.class, target = @Ref(ChunkMap.TrackedEntity.class))
@Mixin(ChunkMap.TrackedEntity.class)
public abstract class MixinChunkMap$TrackedEntity {
    @Shadow @Final Entity entity;

    // region [cc_updatePlayer dasm + mixin]
    @AddTransformToSets(ChunkToCloSet.ChunkMap$TrackedEntity_redirects.class)
    @TransformFromMethod("updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V")
    public native void cc_updatePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void cc_routeCubicTracking(ServerPlayer player, CallbackInfo ci) {
        if (((CanBeCubic) entity.level()).cc_isCubic()) {
            cc_updatePlayer(player);
            ci.cancel();
        }
    }

    @Dynamic @ModifyVariable(method = "cc_updatePlayer", at = @At("STORE"), ordinal = 1)
    private double cc_includeVerticalDistance(double horizontal, @Local(argsOnly = true) ServerPlayer player) {
        double dy = player.getY() - entity.getY();
        return horizontal + dy * dy;
    }

    @Dynamic @Redirect(method = "cc_updatePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean cc_updatePlayer_isChunkTracked(ChunkMap instance, ServerPlayer player, int x, int z) {
        CubePos pos = CubePos.from(entity.blockPosition());
        return entity.level() == player.level()
                && ((CubeEntityTrackingSender) player.connection.chunkSender).cc_hasSentEntityCube(player.level(), pos)
                && ((CubicChunkMap) instance).cc_isChunkTracked(player, pos.getX(), pos.getY(), pos.getZ());
    }
    // endregion
}
