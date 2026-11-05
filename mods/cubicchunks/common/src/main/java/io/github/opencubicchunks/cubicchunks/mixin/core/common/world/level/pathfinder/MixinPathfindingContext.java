package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.pathfinder;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.WalkNodeEvaluatorAccess;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The world's persistent PathTypeCache packs Y into 12 bits; never reuse those entries in a cubic query. */
@Mixin(PathfindingContext.class)
public class MixinPathfindingContext {
    @Unique
    private BlockGetter cc_blocks;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_fullPositionCache(CollisionGetter region, Mob mob, CallbackInfo ci) {
        if (((CanBeCubic) mob.level()).cc_isCubic()) {
            cc_blocks = region instanceof Level world ? new CubeMobBlockView(world) : region;
        }
    }

    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private void cc_pathType(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
        if (cc_blocks != null) {
            cir.setReturnValue(WalkNodeEvaluatorAccess.cc_pathType(cc_blocks, new BlockPos(x, y, z)));
        }
    }
}
