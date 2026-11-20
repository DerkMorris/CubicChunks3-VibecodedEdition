package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.FallingBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Unsupported gravity blocks and archaeology blocks must be allowed to start falling at cubic depths. */
@Mixin({ FallingBlock.class, BrushableBlock.class })
public abstract class MixinFallingBlockStart {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private int cc_fallingSourceMinimum(ServerLevel level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }
}
