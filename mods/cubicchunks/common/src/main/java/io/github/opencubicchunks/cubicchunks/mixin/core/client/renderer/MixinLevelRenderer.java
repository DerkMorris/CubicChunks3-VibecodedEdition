package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicLevelRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Forwards cube availability to the render-thread graph queue introduced for 26.3. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements CubicLevelRenderer {
    @Shadow public abstract SectionOcclusionGraph sectionOcclusionGraph();

    @Override public void cc_onCubeReadyToRender(CubePos cubePos) {
        ((CubicLevelRenderer) sectionOcclusionGraph()).cc_onCubeReadyToRender(cubePos);
    }

    @Override public void cc_onCubeUnloaded(CubePos cubePos) {
        ((CubicLevelRenderer) sectionOcclusionGraph()).cc_onCubeUnloaded(cubePos);
    }
}
