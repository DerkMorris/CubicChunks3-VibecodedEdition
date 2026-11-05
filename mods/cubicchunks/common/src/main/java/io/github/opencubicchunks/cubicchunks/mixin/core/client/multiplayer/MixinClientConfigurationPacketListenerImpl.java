package io.github.opencubicchunks.cubicchunks.mixin.core.client.multiplayer;

import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reset before every configuration phase, including reconnects after an aborted login. */
@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class MixinClientConfigurationPacketListenerImpl {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_resetWorldMode(CallbackInfo ci) {
        CubicWorldMode.clientOverworld = false;
    }
}
