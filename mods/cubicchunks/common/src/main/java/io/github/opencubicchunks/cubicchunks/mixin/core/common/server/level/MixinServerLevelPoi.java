package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevelPoi {
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"), cancellable = true)
    private void cc_update(BlockPos pos, BlockState previous, BlockState current, CallbackInfo ci) {
        if (((CanBeCubic) this).cc_isCubic()) {
            CubePoiLifecycle.blockChanged((ServerLevel) (Object) this, pos, previous, current);
            ci.cancel();
        }
    }
}
