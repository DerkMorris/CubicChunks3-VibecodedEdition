package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.EventHooks;

/** Keep native column events restricted to columns: their positions and types cannot represent a cube. */
public final class LoaderHooks {
    private LoaderHooks() {}

    public static void fireChunkTicketLevelUpdated(ServerLevel level, long pos, int oldLevel, int newLevel, ChunkHolder holder) {
        if (CloPos.isChunk(pos))
            EventHooks.fireChunkTicketLevelUpdated(level, pos, oldLevel, newLevel, holder);
    }

    public static void fireChunkWatch(ServerPlayer player, LevelClo clo, ServerLevel level) {
        if (clo instanceof LevelChunk chunk)
            EventHooks.fireChunkWatch(player, chunk, level);
    }

    public static void fireChunkUnWatch(ServerPlayer player, CloPos pos, ServerLevel level) {
        if (pos.isChunk())
            EventHooks.fireChunkUnWatch(player, pos.chunkPos(), level);
    }
}
