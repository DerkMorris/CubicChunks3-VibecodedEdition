package io.github.opencubicchunks.cubicchunks.server.level.progress;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Adds a vertical status view to the vanilla loading view without retaining obsolete progress listeners. */
public final class CubeLoadStatusView implements ChunkLoadStatusView, StoringCloProgressListener, CanBeCubic {
    private final MinecraftServer server;
    private final ChunkLoadStatusView columns;
    private volatile @Nullable Focus focus;

    public CubeLoadStatusView(MinecraftServer server, ChunkLoadStatusView columns) {
        this.server = server;
        this.columns = columns;
    }

    @Override public void moveTo(ResourceKey<Level> dimension, ChunkPos center) {
        columns.moveTo(dimension, center);
        ServerLevel level = server.getLevel(dimension);
        // The vanilla focus callback supplies horizontal coordinates only. Use the world's respawn
        // height until a cubic-aware caller supplies the complete position through moveToCube.
        focus = level == null ? null
                : new Focus(level, Coords.sectionToCube(center.x()), Coords.blockToCube(level.getRespawnData().pos().getY()),
                        Coords.sectionToCube(center.z()));
    }

    public void moveToCube(ResourceKey<Level> dimension, int cubeX, int cubeY, int cubeZ) {
        columns.moveTo(dimension, new ChunkPos(Coords.cubeToSection(cubeX, 0), Coords.cubeToSection(cubeZ, 0)));
        ServerLevel level = server.getLevel(dimension);
        focus = level == null ? null : new Focus(level, cubeX, cubeY, cubeZ);
    }

    @Override public @Nullable ChunkStatus get(int chunkX, int chunkZ) {
        return columns.get(chunkX, chunkZ);
    }

    @Override public int radius() {
        return columns.radius();
    }

    @Override public boolean cc_isCubic() {
        Focus current = focus;
        return current != null && ((CanBeCubic) current.level()).cc_isCubic();
    }

    @Override public @Nullable ChunkStatus cc_getStatus(int cubeX, int cubeY, int cubeZ) {
        Focus current = focus;
        if (current == null || !((CanBeCubic) current.level()).cc_isCubic()) {
            return null;
        }
        int cubeRadius = Coords.sectionToCubeCeil(radius());
        long position = CloPos.cubeAsLong(current.x() + cubeX - cubeRadius, current.y() + cubeY - cubeRadius, current.z() + cubeZ - cubeRadius);
        // Like the vanilla status view, query the already-published holder status without loading a cube.
        return current.level().getChunkSource().chunkMap.getLatestStatus(position);
    }

    private record Focus(ServerLevel level, int x, int y, int z) {}
}
