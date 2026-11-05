package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.navigation;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GoalUtils.class)
public class MixinGoalUtils {
    /** Reject unavailable wander candidates before downstream goal helpers read their blocks. */
    @Inject(method = "isOutsideLimits", at = @At("HEAD"), cancellable = true)
    private static void cc_loadedCandidate(BlockPos pos, PathfinderMob mob, CallbackInfoReturnable<Boolean> cir) {
        if (((CanBeCubic) mob.level()).cc_isCubic() && CubeMobBlockView.loadedCube(mob.level(), pos) == null) {
            cir.setReturnValue(true);
        }
    }
}
