package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk.storage;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiStorage;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubicPoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Intercepts inherited lifecycle methods only for a cubic PoiManager. Other SectionStorage users remain native. */
@Mixin(SectionStorage.class)
public abstract class MixinPoiSectionStorage {
    @Unique
    private @Nullable CubePoiStorage cc_poi() {
        return this instanceof CubicPoiManager manager ? manager.cc_cubePoi() : null;
    }

    @Inject(method = "flushAll", at = @At("HEAD"), cancellable = true)
    private void cc_flushAll(CallbackInfo ci) {
        var cubes = cc_poi();
        if (cubes != null) {
            cubes.flushAll();
            ci.cancel();
        }
    }

    @Inject(method = "hasWork", at = @At("HEAD"), cancellable = true)
    private void cc_hasWork(CallbackInfoReturnable<Boolean> cir) {
        var cubes = cc_poi();
        if (cubes != null) {
            cir.setReturnValue(cubes.hasWork());
        }
    }

    @Inject(method = "prefetch", at = @At("HEAD"), cancellable = true)
    private void cc_prefetch(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (cc_poi() != null) {
            // Column callers have no Y. Cube loading reconciles its own POI explicitly.
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void cc_flush(ChunkPos pos, CallbackInfo ci) {
        var cubes = cc_poi();
        if (cubes != null) {
            cubes.flushAll();
            ci.cancel();
        }
    }

    @WrapMethod(method = "close")
    private void cc_close(Operation<Void> original) throws IOException {
        try (CubePoiStorage cubes = cc_poi()) {
            original.call();
        }
    }
}
