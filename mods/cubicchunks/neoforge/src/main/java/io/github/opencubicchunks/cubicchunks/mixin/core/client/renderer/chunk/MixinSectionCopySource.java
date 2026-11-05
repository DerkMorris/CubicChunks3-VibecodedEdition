package io.github.opencubicchunks.cubicchunks.mixin.core.client.renderer.chunk;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ClientChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.platform.neoforge.SectionCopySource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Dasm(value = ClientChunkToCubeSet.class, target = @Ref(SectionCopy.class))
@Mixin(SectionCopy.class)
public class MixinSectionCopySource implements SectionCopySource {
    @Shadow @Final private LevelChunk wrapped;

    @AddFieldToSets(containers = ClientChunkToCubeSet.SectionCopy_redirects.class, field = "wrapped:Lnet/minecraft/world/level/chunk/LevelChunk;")
    private LevelCube cc_wrappedCube;

    @Override public BlockGetter cc_getSource() {
        // NeoForge exposes auxiliary light through the level's column managers.
        return cc_wrappedCube != null ? cc_wrappedCube.getLevel() : wrapped;
    }
}
