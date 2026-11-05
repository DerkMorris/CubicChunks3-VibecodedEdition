package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.cube;

import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelCube.class)
public abstract class MixinLevelCubePoi {
    @Inject(method = "setLoaded", at = @At("HEAD"))
    private void cc_loaded(boolean loaded, CallbackInfo ci) {
        if (loaded) {
            LevelCube cube = (LevelCube) (Object) this;
            var storage = CubePoiLifecycle.storage(cube.getLevel());
            if (storage != null) {
                storage.reconcile(cube);
            }
        }
    }
}
