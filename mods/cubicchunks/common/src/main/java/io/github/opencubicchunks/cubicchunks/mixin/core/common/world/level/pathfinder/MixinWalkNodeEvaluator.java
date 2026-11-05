package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.pathfinder;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator extends NodeEvaluator {
    /** Falling/swimming searches stop at the local region edge, including below vanilla min Y. */
    @WrapOperation(method = { "tryFindFirstNonWaterBelow",
        "tryFindFirstGroundNodeBelow" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int cc_localMinimum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? currentContext.level().getMinY() : original.call(level);
    }
}
