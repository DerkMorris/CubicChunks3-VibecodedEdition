package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.server.level.CubeLevel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ChunkLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.server.level.LoadingChunkTracker")
public abstract class MixinLoadingChunkTracker extends MixinChunkTracker {
    @Unique
    private final Long2IntOpenHashMap cc_unloadedCubeLevels = new Long2IntOpenHashMap();

    /** The graph still needs intermediate levels after the smaller cubic generation radius has released its holder. */
    @Inject(method = "getLevel", at = @At("HEAD"), cancellable = true)
    private void cc_retainPropagationLevel(long pos, CallbackInfoReturnable<Integer> cir) {
        if (cc_isCubic && cc_unloadedCubeLevels.containsKey(pos)) {
            cir.setReturnValue(cc_unloadedCubeLevels.get(pos));
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void cc_onSetLevel(long sectionPos, int level, CallbackInfo ci) {
        super.cc_onSetLevel(sectionPos, level);
        if (cc_isCubic && CloPos.isCube(sectionPos)) {
            if (level > CubeLevel.MAX_LEVEL && level <= ChunkLevel.MAX_LEVEL) {
                cc_unloadedCubeLevels.put(sectionPos, level);
            } else {
                cc_unloadedCubeLevels.remove(sectionPos);
            }
        }
    }
}
