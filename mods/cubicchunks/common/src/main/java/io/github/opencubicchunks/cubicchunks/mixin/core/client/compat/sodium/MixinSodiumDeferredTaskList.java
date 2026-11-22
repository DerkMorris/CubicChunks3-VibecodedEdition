package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cubicchunks.client.renderer.CubicDeferredTaskList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Restores the camera-relative Y used when this particular heap snapshot was captured. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList", remap = false)
public abstract class MixinSodiumDeferredTaskList implements CubicDeferredTaskList {
    @Unique private int cc_verticalOffset;

    @Override public void cc_setVerticalOffset(int offset) {
        cc_verticalOffset = offset;
    }

    @ModifyArg(method = "dequeueNextSectionPos", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong(III)J"), index = 1)
    private int cc_decodeRelativeHeight(int sectionY) {
        return sectionY + cc_verticalOffset;
    }
}
