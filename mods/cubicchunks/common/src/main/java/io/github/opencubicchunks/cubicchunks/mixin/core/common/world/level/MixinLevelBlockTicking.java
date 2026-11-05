package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class MixinLevelBlockTicking {
    /** Block events and block-entity tickers must check the exact cube, without loading a column. */
    @Inject(method = "shouldTickBlocksAt(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void cc_shouldTick(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level && ((CanBeCubic) level).cc_isCubic()) {
            if (!CubicBlockBounds.contains(pos)) {
                cir.setReturnValue(false);
                return;
            }
            CubePos key = CubePos.from(pos);
            var cube = ((CubeSource) level.getChunkSource()).cc_getCubeNow(key.getX(), key.getY(), key.getZ());
            cir.setReturnValue(cube != null && cube.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)
                    && level.getChunkSource().chunkMap.getDistanceManager().inBlockTickingRange(CloPos.cube(key).asLong()));
        }
    }
}
