package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep native falling-block lifetimes while testing out-of-world expiry against the cubic build limits. */
@Mixin(FallingBlockEntity.class)
public abstract class MixinFallingBlockEntity {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int cc_fallingBlockMinimum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private int cc_fallingBlockMaximum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MAX : original.call(level);
    }
}
