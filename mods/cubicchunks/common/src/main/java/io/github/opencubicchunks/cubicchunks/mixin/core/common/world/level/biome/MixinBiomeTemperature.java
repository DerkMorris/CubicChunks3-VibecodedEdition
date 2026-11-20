package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.biome;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class MixinBiomeTemperature {
    private static final int PACKED_Y_MIN = -2048;
    private static final int PACKED_Y_MAX = 2047;

    @Shadow private float getHeightAdjustedTemperature(BlockPos pos, int seaLevel) {
        throw new IllegalStateException();
    }

    @Inject(method = "getTemperature", at = @At("HEAD"), cancellable = true)
    private void cc_fullHeightTemperature(BlockPos pos, int seaLevel, CallbackInfoReturnable<Float> cir) {
        if (pos.getY() < PACKED_Y_MIN || pos.getY() > PACKED_Y_MAX) {
            // Vanilla's BlockPos.asLong cache aliases Y every 4096 blocks. Native dimension positions keep the native cache.
            cir.setReturnValue(getHeightAdjustedTemperature(pos, seaLevel));
        }
    }
}
