package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.server.level.GenerationCloHolder;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkLoadCounter.class)
public abstract class MixinChunkLoadCounter {
    /** The initial-load collection contains cubes as well as columns; a cube has no native ChunkPos. */
    @WrapOperation(method = { "lambda$track$0",
        "lambda$track$1" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ChunkPos;pack()J"))
    private static long cc_loadKey(ChunkPos ignored, Operation<Long> original, @Local(argsOnly = true) ChunkHolder holder) {
        return ((GenerationCloHolder) holder).cc_getCloPos().toLong();
    }
}
