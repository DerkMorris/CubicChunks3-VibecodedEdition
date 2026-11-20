package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.portal;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PortalShape.class)
public abstract class MixinPortalShape {
    @WrapOperation(method = "calculateBottomLeft", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getMinY()I"))
    private static int cc_frameMinimum(BlockGetter level, Operation<Integer> original) {
        return level instanceof CanBeCubic cubic && cubic.cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }
}
