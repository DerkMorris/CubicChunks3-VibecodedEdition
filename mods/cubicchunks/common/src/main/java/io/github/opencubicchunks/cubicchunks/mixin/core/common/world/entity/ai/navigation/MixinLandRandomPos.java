package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.navigation;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LandRandomPos.class)
public class MixinLandRandomPos {
    /** Wandering must not scan/load a whole vertical column while escaping solid terrain. */
    @Inject(method = "movePosUpOutOfSolid", at = @At("HEAD"), cancellable = true)
    private static void cc_localWanderTarget(PathfinderMob mob, BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        if (!((CanBeCubic) mob.level()).cc_isCubic()) {
            return;
        }
        var blocks = new CubeMobBlockView(mob.level());
        BlockPos current = pos;
        for (int step = 0; step <= CubeMobBlockView.MAX_PATH_LENGTH; step++) {
            if (CubeMobBlockView.loadedCube(mob.level(), current) == null) {
                break;
            }
            if (!blocks.getBlockState(current).isSolid()) {
                cir.setReturnValue(GoalUtils.isWater(mob, current) || GoalUtils.hasMalus(mob, current) ? null : current);
                return;
            }
            current = current.above();
        }
        cir.setReturnValue(null);
    }
}
