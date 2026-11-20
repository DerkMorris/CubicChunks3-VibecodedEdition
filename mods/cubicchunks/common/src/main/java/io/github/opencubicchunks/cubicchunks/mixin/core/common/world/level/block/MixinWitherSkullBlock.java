package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherSkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the vanilla skull pattern and difficulty checks while allowing summoning below the ordinary world floor. */
@Mixin(WitherSkullBlock.class)
public abstract class MixinWitherSkullBlock {
    @WrapOperation(method = {
        "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V",
        "canSpawnMob" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int cc_witherMinimum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }
}
