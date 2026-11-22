package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Start traversal at the actual camera cube even outside the vanilla dimension's section arrays. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public abstract class MixinSodiumOcclusionCuller {
    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinSectionY()I"))
    private int cc_minSectionY(Level level) {
        return ((CanBeCubic) level).cc_isCubic() ? SectionPos.blockToSectionCoord(CubicBlockBounds.MIN) : level.getMinSectionY();
    }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxSectionY()I"))
    private int cc_maxSectionY(Level level) {
        return ((CanBeCubic) level).cc_isCubic() ? SectionPos.blockToSectionCoord(CubicBlockBounds.MAX) : level.getMaxSectionY();
    }
}
