package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.lighting;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLayerLightListener;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
public class MixinLevelLightEngine {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;

    @Unique
    private @Nullable CubeLightProvider cc_lightProvider;
    @Unique
    private @Nullable CubeLayerLightListener cc_blockListener;
    @Unique
    private @Nullable CubeLayerLightListener cc_skyListener;
    @Unique
    private boolean cc_hasBlockLight;
    @Unique
    private boolean cc_hasSkyLight;

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V", at = @At("RETURN"))
    private void cc_captureCubeLightProvider(LightChunkGetter source, boolean block, boolean sky, CallbackInfo ci) {
        this.cc_hasBlockLight = block;
        this.cc_hasSkyLight = sky;
        if (source instanceof CubeLightProvider provider) {
            this.cc_lightProvider = provider;
            this.cc_blockListener = block ? new CubeLayerLightListener(provider, LightLayer.BLOCK) : null;
            this.cc_skyListener = sky ? new CubeLayerLightListener(provider, LightLayer.SKY) : null;
        }
    }

    @Unique
    private boolean cc_isCubicLighting() {
        return this.levelHeightAccessor instanceof CanBeCubic level && level.cc_isCubic();
    }

    @Inject(method = "getLayerListener", at = @At("HEAD"), cancellable = true)
    private void cc_onGetLayerListener(LightLayer layer, CallbackInfoReturnable<LayerLightEventListener> cir) {
        if (this.cc_isCubicLighting()) {
            CubeLayerLightListener listener = layer == LightLayer.BLOCK ? this.cc_blockListener : this.cc_skyListener;
            cir.setReturnValue(listener == null ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : listener);
        }
    }

    @Inject(method = "lightOnInColumn", at = @At("HEAD"), cancellable = true)
    private void cc_onLightOnInColumn(long columnPos, CallbackInfoReturnable<Boolean> cir) {
        if (this.cc_isCubicLighting()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void cc_onGetRawBrightness(BlockPos blockPos, int amount, CallbackInfoReturnable<Integer> cir) {
        if (this.cc_isCubicLighting()) {
            CubePos cube = CubePos.of(Coords.blockToCube(blockPos.getX()), Coords.blockToCube(blockPos.getY()), Coords.blockToCube(blockPos.getZ()));
            CubeLightView view = this.cc_lightProvider == null ? null : this.cc_lightProvider.cc_getVisibleCubeLightView(cube);
            cir.setReturnValue(view == null ? 0 : view.rawBrightness(blockPos, amount, this.cc_hasBlockLight, this.cc_hasSkyLight).orElse(0));
        }
    }
}
