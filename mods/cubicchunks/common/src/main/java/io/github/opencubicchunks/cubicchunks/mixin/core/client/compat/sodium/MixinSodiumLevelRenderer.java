package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cubicchunks.client.renderer.CubicLevelRenderer;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicRenderingBackend;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelRenderer.class)
public abstract class MixinSodiumLevelRenderer implements CubicRenderingBackend {
    @Override public CubicLevelRenderer cc_cubeRenderer() {
        return (CubicLevelRenderer) ((LevelRendererExtension) this).sodium$getWorldRenderer();
    }
}
