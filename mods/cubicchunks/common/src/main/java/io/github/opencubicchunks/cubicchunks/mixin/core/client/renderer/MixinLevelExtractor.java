package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientWorldSettings;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicSkyRendering;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractor {
    @Shadow private ClientLevel level;
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "extract", at = @At("TAIL"))
    private void cc_extractHeightSettings(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
        if (CubicSkyRendering.applies(level)) {
            CubicSkyRendering.apply(levelRenderState, CubicClientWorldSettings.heights(level), camera.position().y);
        }
    }
}
