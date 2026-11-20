package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.gameevent.vibrations;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.gameevent.CubeGameEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VibrationSystem.Ticker.class)
public interface MixinVibrationTicker {
    @Inject(method = "areAdjacentChunksTicking", at = @At("HEAD"), cancellable = true)
    private static void cc_neighbors(Level level, BlockPos position, CallbackInfoReturnable<Boolean> cir) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cir.setReturnValue(CubeGameEvents.adjacentSectionsTicking(level, position));
        }
    }
}
