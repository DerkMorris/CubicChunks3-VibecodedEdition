package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Tall blocks need room for the upper half within cubic build bounds, not the dimension's section-array bounds. */
@Mixin({ DoorBlock.class, DoublePlantBlock.class })
public abstract class MixinTallBlockPlacement {
    @WrapOperation(method = "getStateForPlacement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private int cc_tallBlockMaximum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MAX : original.call(level);
    }
}
