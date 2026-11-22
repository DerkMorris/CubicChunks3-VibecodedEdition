package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicDeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium's task heap has ten bits per axis; use the same camera-relative origin for Y as for X and Z. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree", remap = false)
public abstract class MixinSodiumTaskCollectingTree extends SectionTree {
    @Unique private int cc_verticalOffset;

    protected MixinSodiumTaskCollectingTree(Viewport viewport, float buildDistance, int frame, CullType cullType, Level level) {
        super(viewport, buildDistance, frame, cullType, level);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_captureVerticalOrigin(Viewport viewport, float buildDistance, int frame, CullType cullType, Level level, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cc_verticalOffset = baseOffsetY - TaskCollectingTree.SECTION_Y_MIN;
        }
    }

    @ModifyExpressionValue(method = "addPendingSection", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;getChunkY()I"))
    private int cc_encodeRelativeHeight(int sectionY) {
        return sectionY - cc_verticalOffset;
    }

    @ModifyReturnValue(method = "getPendingTaskLists", at = @At("RETURN"))
    private DeferredTaskList cc_transferVerticalOrigin(DeferredTaskList tasks) {
        ((CubicDeferredTaskList) tasks).cc_setVerticalOffset(cc_verticalOffset);
        return tasks;
    }
}
