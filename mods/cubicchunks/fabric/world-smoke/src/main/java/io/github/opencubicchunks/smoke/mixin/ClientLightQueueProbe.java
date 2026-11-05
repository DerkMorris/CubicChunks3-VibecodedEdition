package io.github.opencubicchunks.smoke.mixin;

import io.github.opencubicchunks.smoke.WorldLightDelay;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLightQueueProbe {
    @Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
    private void holdQueuedLightForContinuityCheck(CallbackInfo ci) {
        if (WorldLightDelay.active()) {
            ci.cancel();
        }
    }
}
