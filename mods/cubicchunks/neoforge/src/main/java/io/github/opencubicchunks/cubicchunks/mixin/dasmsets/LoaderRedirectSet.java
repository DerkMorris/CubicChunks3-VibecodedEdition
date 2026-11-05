package io.github.opencubicchunks.cubicchunks.mixin.dasmsets;

import io.github.notstirred.dasm.api.annotations.redirect.redirects.*;
import io.github.notstirred.dasm.api.annotations.redirect.sets.*;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.platform.neoforge.CubeLoadEvent;
import io.github.opencubicchunks.cubicchunks.platform.neoforge.LoaderHooks;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import net.minecraft.server.level.*;

public interface LoaderRedirectSet {
    @RedirectSet
    interface Global {
        @InterOwnerContainer(from = @Ref(net.neoforged.neoforge.event.EventHooks.class), to = @Ref(LoaderHooks.class))
        class Events {
            @MethodRedirect("fireChunkTicketLevelUpdated(Lnet/minecraft/server/level/ServerLevel;JIILnet/minecraft/server/level/ChunkHolder;)V")
            public static native void fireChunkTicketLevelUpdated(ServerLevel level, long pos, int oldLevel, int newLevel, ChunkHolder holder);
        }
    }

    @RedirectSet
    interface Cube {
        @TypeRedirect(from = @Ref(net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.class), to = @Ref(io.github.opencubicchunks.cubicchunks.platform.neoforge.CubeAuxiliaryLightManager.class))
        class AuxiliaryLights {}

        @TypeRedirect(from = @Ref(net.neoforged.neoforge.event.level.ChunkEvent.Load.class), to = @Ref(CubeLoadEvent.class))
        class CubeLoad {}
    }

    @RedirectSet
    interface Clo {
        @InterOwnerContainer(from = @Ref(net.neoforged.neoforge.event.EventHooks.class), to = @Ref(LoaderHooks.class))
        class Events {
            @MethodRedirect("fireChunkWatch(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/server/level/ServerLevel;)V")
            public static native void fireChunkWatch(ServerPlayer player, LevelClo clo, ServerLevel level);

            @MethodRedirect("fireChunkUnWatch(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/ServerLevel;)V")
            public static native void fireChunkUnWatch(ServerPlayer player, CloPos pos, ServerLevel level);
        }
    }
}
