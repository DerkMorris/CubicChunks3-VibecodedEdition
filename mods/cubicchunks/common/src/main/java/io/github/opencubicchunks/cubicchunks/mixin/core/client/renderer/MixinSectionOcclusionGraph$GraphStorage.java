package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$GraphStorage")
public class MixinSectionOcclusionGraph$GraphStorage {
    /**
     * Wrap reading of the vertical section count when constructing {@link Octree} to ensure that the Octree does not have special-cased Y axis
     * behavior (as
     * occurs in vanilla worlds due to the height limit)
     */
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;sectionCount()I"))
    private int cc_onInit_constructOctree_getMinY(ViewArea instance, Operation<Integer> original) {
        if (((CanBeCubic) instance).cc_isCubic()) {
            // Passing MAX_VALUE for the vertical section count ensures that the Y axis is not special-cased and behaves the same
            // as the X and Z axes.
            return Integer.MAX_VALUE;
        }
        return original.call(instance);
    }
}
