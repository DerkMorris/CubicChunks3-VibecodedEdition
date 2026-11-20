package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.block.piston;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep native piston limits and reactions, replacing only the dimension's vertical coordinate bounds. */
@Mixin(PistonBaseBlock.class)
public abstract class MixinPistonBaseBlock {
    @WrapOperation(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int cc_pistonMinimum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }

    @WrapOperation(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private static int cc_pistonMaximum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MAX : original.call(level);
    }
}
