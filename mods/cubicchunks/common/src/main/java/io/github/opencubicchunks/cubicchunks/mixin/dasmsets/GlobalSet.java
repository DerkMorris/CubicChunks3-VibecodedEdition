package io.github.opencubicchunks.cubicchunks.mixin.dasmsets;

import io.github.notstirred.dasm.api.annotations.redirect.redirects.TypeRedirect;
import io.github.notstirred.dasm.api.annotations.redirect.sets.IntraOwnerContainer;
import io.github.notstirred.dasm.api.annotations.redirect.sets.RedirectSet;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CloStatusUpdateListener;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;

/**
 * Contains redirects that are applied to all DASM transforms. <br/>
 * <br/>
 * Redirects should only be added to this set if they are applicable in all
 * contexts.
 */
@RedirectSet
public interface GlobalSet extends LoaderRedirectSet.Global {
    @TypeRedirect(from = @Ref(ChunkStatusUpdateListener.class), to = @Ref(CloStatusUpdateListener.class))
    interface ChunkStatusUpdateListener_to_CloStatusUpdateListener_redirects {}

    @TypeRedirect(from = @Ref(ChunkTrackingView.class), to = @Ref(CloTrackingView.class))
    interface ChunkTrackingView_to_CloTrackingView_redirects {}

    @TypeRedirect(from = @Ref(ChunkTrackingView.Positioned.class), to = @Ref(CloTrackingView.Positioned.class))
    abstract class ChunkTrackingView$Positioned_to_CloTrackingView$Positioned_redirects {}

    @IntraOwnerContainer(@Ref(ChunkMap.class))
    class ChunkMap_redirects {}

    @IntraOwnerContainer(@Ref(ServerChunkCache.class))
    class ServerChunkCache_redirects {}
}
