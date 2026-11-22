package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cubic levels need Sodium's sparse multi-forest; their nominal dimension height does not bound cube Y. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.tree.BaseBiForest", remap = false)
public abstract class MixinSodiumBaseBiForest {
    @Inject(method = "checkApplicable", at = @At("HEAD"), cancellable = true)
    private static void cc_useFullHeightForest(float buildDistance, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cir.setReturnValue(false);
        }
    }
}
