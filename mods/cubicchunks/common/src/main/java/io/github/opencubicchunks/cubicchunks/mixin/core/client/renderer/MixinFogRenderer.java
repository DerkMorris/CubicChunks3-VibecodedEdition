package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientWorldSettings;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicSkyRendering;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public abstract class MixinFogRenderer {
    @Inject(method = "computeFogColor", at = @At("TAIL"))
    private void cc_fadeUndergroundBackground(Camera camera, float partialTicks, ClientLevel level, int renderDistance,
                                            float darkenWorldAmount, Vector4f dest, CallbackInfo ci) {
        if (CubicSkyRendering.applies(level) && camera.getFluidInCamera() == FogType.NONE) {
            CubicSkyRendering.fadeBackground(dest, camera.position().y, CubicClientWorldSettings.heights(level).skyFadeStartY());
        }
    }
}
