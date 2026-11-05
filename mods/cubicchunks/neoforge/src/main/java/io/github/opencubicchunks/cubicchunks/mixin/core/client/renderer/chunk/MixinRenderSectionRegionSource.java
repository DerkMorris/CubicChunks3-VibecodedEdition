package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer.chunk;

import io.github.opencubicchunks.cubicchunks.platform.neoforge.SectionCopySource;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionRegion.class)
public abstract class MixinRenderSectionRegionSource {
    @Shadow @Final private int minSectionY;

    @Shadow protected abstract SectionCopy getSection(int x, int y, int z);

    @Inject(method = "getAuxLightManager", at = @At("HEAD"), cancellable = true)
    private void cc_sourceLight(ChunkPos pos, CallbackInfoReturnable<AuxiliaryLightManager> cir) {
        // A cubic snapshot has no wrapped LevelChunk. Ask its actual block source instead.
        var source = ((SectionCopySource) getSection(pos.x(), minSectionY, pos.z())).cc_getSource();
        cir.setReturnValue(source.getAuxLightManager(pos));
    }
}
