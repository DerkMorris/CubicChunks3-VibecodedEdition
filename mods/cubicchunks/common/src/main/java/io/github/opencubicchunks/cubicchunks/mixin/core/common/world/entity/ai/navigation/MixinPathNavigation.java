package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.navigation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keep the native pathfinder, with supported Y and a finite local search domain. */
@Mixin(PathNavigation.class)
public class MixinPathNavigation {
    @Shadow @Final protected Level level;

    @WrapOperation(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int cc_minY(Level world, Operation<Integer> original) {
        return ((CanBeCubic) world).cc_isCubic() ? CubicBlockBounds.MIN : original.call(world);
    }

    @ModifyVariable(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), argsOnly = true)
    private float cc_boundedSearch(float length) {
        return ((CanBeCubic) level).cc_isCubic() ? Math.max(0, Math.min(Float.isFinite(length) ? length : 0, CubeMobBlockView.MAX_PATH_LENGTH))
                : length;
    }
}
