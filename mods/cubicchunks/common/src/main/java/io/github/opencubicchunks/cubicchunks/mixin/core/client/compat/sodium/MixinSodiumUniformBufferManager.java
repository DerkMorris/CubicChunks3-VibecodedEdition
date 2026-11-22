package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Initial GPU storage scales with the resident window; Sodium can grow it when more regions arrive. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager", remap = false)
public abstract class MixinSodiumUniformBufferManager {
    // One center cube plus a boundary cube on either side of the radius.
    @Unique private static final int CC_WINDOW_PADDING_CUBES = 3;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSectionY()I"))
    private int cc_allocationMinSection(ClientLevel level) {
        return ((CanBeCubic) level).cc_isCubic() ? 0 : level.getMinSectionY();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSectionY()I"))
    private int cc_allocationMaxSection(ClientLevel level, ClientLevel unusedLevel, int renderDistance) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return level.getMaxSectionY();
        }
        int radius = Math.max(renderDistance, CubicChunks.config().getVerticalViewDistance());
        return (radius * 2 + CC_WINDOW_PADDING_CUBES) * CubicConstants.DIAMETER_IN_SECTIONS - 1;
    }
}
