package io.github.opencubicchunks.cubicchunks.mixin.dasmsets;

import io.github.notstirred.dasm.api.annotations.redirect.redirects.TypeRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.sets.IntraOwnerContainer;
import io.github.notstirred.dasm.api.annotations.redirect.sets.RedirectSet;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionCopy;

/** Client redirects are separate so common DASM configuration never resolves client-only classes on a dedicated server. */
@RedirectSet
public interface ClientChunkToCubeSet extends ChunkToCubeSet {
    @TypeRedirect(from = @Ref(ClientChunkCache.Storage.class), to = @Ref(ClientCubeCache.Storage.class))
    abstract class ClientChunkCache$Storage_to_ClientCubeCache$Storage_redirects {}

    @IntraOwnerContainer(@Ref(ClientChunkCache.class))
    class ClientChunkCache_redirects {}

    @IntraOwnerContainer(@Ref(SectionOcclusionGraph.class))
    class SectionOcclusionGraph_redirects {}

    @IntraOwnerContainer(@Ref(LevelRenderer.class))
    class LevelRenderer_redirects {}

    @IntraOwnerContainer(@Ref(SectionCopy.class))
    class SectionCopy_redirects {}
}
