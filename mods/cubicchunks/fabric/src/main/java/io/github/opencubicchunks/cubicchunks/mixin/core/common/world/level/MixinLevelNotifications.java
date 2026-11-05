package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The loader determines where block notification runs; cube status must be checked there. */
@Mixin(Level.class)
public abstract class MixinLevelNotifications {
    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getFullStatus()Lnet/minecraft/server/level/FullChunkStatus;"))
    private FullChunkStatus cc_notificationStatus(LevelChunk chunk, Operation<FullChunkStatus> original, @Local(argsOnly = true) BlockPos pos) {
        var level = (CubicLevel) this;
        return ((io.github.opencubicchunks.cubicchunks.CanBeCubic) this).cc_isCubic() ? level.cc_getCubeAt(pos).getFullStatus()
                : original.call(chunk);
    }
}
