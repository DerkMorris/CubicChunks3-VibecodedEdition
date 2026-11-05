package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockAndLightGetter.class)
public interface MixinSpawnLightReads {
    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private void cc_spawnBrightness(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        var context = CubeSpawnContext.current(this);
        if (context != null) {
            cir.setReturnValue(context.brightness(layer, pos));
        }
    }

    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void cc_spawnRawBrightness(BlockPos pos, int darkening, CallbackInfoReturnable<Integer> cir) {
        var context = CubeSpawnContext.current(this);
        if (context != null) {
            cir.setReturnValue(context.rawBrightness(pos, darkening));
        }
    }
}
