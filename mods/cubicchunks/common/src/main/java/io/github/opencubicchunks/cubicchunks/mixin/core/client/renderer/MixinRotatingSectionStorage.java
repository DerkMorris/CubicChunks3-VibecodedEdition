package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A ViewArea opts into a moving vertical interval; other users of RotatingSectionStorage keep vanilla bounds. */
@Mixin(RotatingSectionStorage.class)
public abstract class MixinRotatingSectionStorage implements MarkableAsCubic {
    @Shadow @Final private int radius;
    @Shadow @Final @Mutable private int minY;
    @Shadow @Final @Mutable private int maxY;
    @Shadow @Final private int sectionGridSizeY;
    @Unique
    private int cc_firstLayer;
    private boolean cc_isCubic;

    @Override public void cc_setCubic() {
        cc_isCubic = true;
    }

    @Override public boolean cc_isCubic() {
        return cc_isCubic;
    }

    @Inject(method = "repositionCenter", at = @At("HEAD"))
    private void cc_repositionVerticalWindow(SectionPos center, CallbackInfoReturnable<Boolean> cir) {
        if (cc_isCubic) {
            minY = center.y() - radius;
            maxY = center.y() + radius;
            cc_firstLayer = Math.floorMod(center.y(), sectionGridSizeY);
        }
    }

    /** Move only the layers that leave the window, preserving the slots for all overlapping sections. */
    @ModifyArg(method = "repositionCenter", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/RotatingSectionStorage;getSectionIndex(III)I"), index = 1)
    private int cc_rotateVerticalSlot(int localY) {
        int slot = localY + cc_firstLayer;
        return slot >= sectionGridSizeY ? slot - sectionGridSizeY : slot;
    }

    /** The graph worker can look up sections during a camera move. Derive the slot from the world Y alone. */
    @ModifyArg(method = "getValue(III)Lnet/minecraft/client/RotatingSectionStorage$Value;", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/RotatingSectionStorage;getSectionIndex(III)I"), index = 1)
    private int cc_lookupVerticalSlot(int localY, @Local(argsOnly = true, ordinal = 1) int sectionY) {
        return cc_isCubic ? Math.floorMod(sectionY + radius, sectionGridSizeY) : localY;
    }
}
