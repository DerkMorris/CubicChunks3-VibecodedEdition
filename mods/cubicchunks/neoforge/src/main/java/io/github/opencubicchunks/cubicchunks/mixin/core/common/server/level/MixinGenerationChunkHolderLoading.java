package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.GlobalSet;
import io.github.opencubicchunks.cubicchunks.server.level.GenerationCloHolder;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Preserve FML's reentrant-load shortcut independently for cubes and columns. */
@Dasm(value = GlobalSet.class, target = @Ref(GenerationChunkHolder.class))
@Mixin(GenerationChunkHolder.class)
public class MixinGenerationChunkHolderLoading {
    @Shadow public LevelChunk currentlyLoading;
    @AddFieldToSets(containers = ChunkToCubeSet.GenerationChunkHolder_redirects.class, field = "currentlyLoading:Lnet/minecraft/world/level/chunk/LevelChunk;")
    public LevelCube cc_currentlyLoadingCube;

    public LevelClo cc_getCurrentlyLoading() {
        return ((GenerationCloHolder) this).cc_getCloPos().isCube() ? cc_currentlyLoadingCube : (LevelClo) currentlyLoading;
    }

    public void cc_setCurrentlyLoading(LevelClo clo) {
        if (((GenerationCloHolder) this).cc_getCloPos().isCube())
            cc_currentlyLoadingCube = (LevelCube) clo;
        else
            currentlyLoading = (LevelChunk) clo;
    }
}
