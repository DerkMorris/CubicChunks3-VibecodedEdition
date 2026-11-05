package io.github.opencubicchunks.cubicchunks.server.level;

import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

public interface ServerCubeCache extends CubeSource, CubeLightProvider {
    /** @see CubicChunkMap#cc_queryColumnSurface(Heightmap.Types, int, int, int, int) */
    CompletableFuture<ColumnSurfaceResult> cc_queryColumnSurface(Heightmap.Types type, int blockX, int blockZ, int minCubeY, int maxCubeY);

    /** @see CubicChunkMap#cc_querySkyLightColumn(int, int, int, int) */
    CompletableFuture<ColumnSkyLightResult> cc_querySkyLightColumn(int blockX, int blockZ, int minCubeY, int maxCubeY);

    /** @see CubicChunkMap#cc_rebuildCubeLighting(CubeLightBounds) */
    CompletableFuture<CubeLightUpdate> cc_rebuildCubeLighting(CubeLightBounds bounds);

    CompletableFuture<CubeLightStatus> cc_getCubeLightStatus();

    boolean cc_requestCubeLightRefresh(ServerPlayer player, CubePos pos, long observedEpoch);

    /** @see CubicChunkMap#cc_queryCubeLight(LightLayer, BlockPos) */
    CompletableFuture<CubeLightQuery> cc_queryCubeLight(LightLayer layer, BlockPos pos);

    /** @see CubicChunkMap#cc_captureCubeLight(LevelCube) */
    CubeLightPacketSnapshot cc_captureCubeLight(LevelCube cube);

    CompletableFuture<ChunkResult<CubeAccess>> cc_getCubeFuture(int pX, int pY, int pZ, ChunkStatus pChunkStatus, boolean pLoad);

    void cc_blockChanged(BlockPos pos);

    void cc_onLightUpdate(LightLayer pType, SectionPos pPos);

    void cc_addTicket(Ticket ticket, CloPos cloPos);

    void cc_addTicketWithRadius(TicketType ticket, CloPos cloPos, int radius);

    void cc_removeTicketWithRadius(TicketType ticket, CloPos cloPos, int radius);

    boolean cc_updateCloForced(CloPos pPos, boolean pAdd);
}
