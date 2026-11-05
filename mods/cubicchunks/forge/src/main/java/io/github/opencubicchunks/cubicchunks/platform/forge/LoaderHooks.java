package io.github.opencubicchunks.cubicchunks.platform.forge;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.ForgeEventFactory;

/** Keep native column events restricted to columns: their positions and types cannot represent a cube. */
public final class LoaderHooks {
    private LoaderHooks() {}

    public static void fireChunkTicketLevelUpdated(ServerLevel level, long pos, int oldLevel, int newLevel, ChunkHolder holder) {
        if (CloPos.isChunk(pos))
            ForgeEventFactory.fireChunkTicketLevelUpdated(level, pos, oldLevel, newLevel, holder);
    }

    public static void fireChunkWatch(ServerPlayer player, LevelClo clo, ServerLevel level) {
        if (clo instanceof LevelChunk chunk)
            ForgeEventFactory.fireChunkWatch(player, chunk, level);
    }

    public static void fireChunkUnWatch(ServerPlayer player, CloPos pos, ServerLevel level) {
        if (pos.isChunk())
            ForgeEventFactory.fireChunkUnWatch(player, pos.chunkPos(), level);
    }

    public static void onCubeLoad(CubeAccess cube, boolean fresh) {
        CubeLoadEvent.BUS.post(new CubeLoadEvent((LevelCube) cube, fresh));
    }
}
