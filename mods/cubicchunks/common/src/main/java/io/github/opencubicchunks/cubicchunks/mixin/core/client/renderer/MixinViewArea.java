package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Uses 26.3's native section storage with a bounded vertical window that follows the camera. */
@Mixin(ViewArea.class)
public abstract class MixinViewArea implements CanBeCubic {
    @Shadow @Final private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;
    private boolean cc_isCubic;

    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/RotatingSectionStorage;<init>(IIILnet/minecraft/client/RotatingSectionStorage$ValueCreator;)V"))
    private void cc_boundVerticalStorage(Args args) {
        var level = Minecraft.getInstance().level;
        if (level != null && ((CanBeCubic) level).cc_isCubic()) {
            int radius = args.get(0);
            args.set(1, -radius);
            args.set(2, radius);
            cc_isCubic = true;
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_initializeStorage(CallbackInfo ci) {
        if (cc_isCubic) {
            ((MarkableAsCubic) sections).cc_setCubic();
        }
    }

    @Override public boolean cc_isCubic() {
        return cc_isCubic;
    }

    @Inject(method = "minY", at = @At("HEAD"), cancellable = true)
    private void cc_minY(CallbackInfoReturnable<Integer> cir) {
        if (cc_isCubic) {
            cir.setReturnValue(SectionPos.sectionToBlockCoord(sections.minY()));
        }
    }

    @Inject(method = "maxY", at = @At("HEAD"), cancellable = true)
    private void cc_maxY(CallbackInfoReturnable<Integer> cir) {
        if (cc_isCubic) {
            cir.setReturnValue(SectionPos.sectionToBlockCoord(sections.maxY() + 1) - 1);
        }
    }
}
