package io.github.opencubicchunks.cubicchunks.mixin.core.client.gui.screens;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.gui.screens.CubicLevelLoadingScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public abstract class MixinLevelLoadingScreen {
    @Inject(method = "extractChunksForRendering", at = @At("HEAD"), cancellable = true)
    private static void cc_onExtractChunksForRendering(
            GuiGraphicsExtractor guiGraphics, int centerX, int centerY, int size, int spacing, ChunkLoadStatusView progressView, CallbackInfo ci
    ) {
        if (!(progressView instanceof CanBeCubic cubic) || !cubic.cc_isCubic()) {
            return;
        }
        ci.cancel();
        CubicLevelLoadingScreen.doRender(guiGraphics, progressView);
    }
}
