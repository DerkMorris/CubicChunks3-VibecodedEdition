package io.github.opencubicchunks.cubicchunks.server.level;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
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
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

public interface CubicChunkMap extends CubeLightProvider {
    void cc_forEachBlockTickingClo(Consumer<LevelClo> consumer);

    /**
     * Queries inclusive whole-cube Y bounds at global block X/Z. Only FULL cubes establish known coverage.
     * Captures live state when the request reaches the server thread; later edits do not change that result.
     * Missing and unfinished cubes remain unknown, while corrupt data and exceeded storage limits fail the future.
     * Reconstruction requires the server thread, so callers must not block that thread waiting for the result.
     * Exceptional completion may originate on the I/O worker; callbacks that access the world must dispatch explicitly.
     */
    CompletableFuture<ColumnSurfaceResult> cc_queryColumnSurface(Heightmap.Types type, int blockX, int blockZ, int minCubeY, int maxCubeY);

    /**
     * Finds direct-skylight blocking edges with both endpoints inside the inclusive whole-cube Y range.
     * A clear range does not establish that sky enters above its upper bound, and this query does not propagate light.
     * Uses the same request-time capture, FULL-only coverage, failure and threading contract as
     * {@link #cc_queryColumnSurface(Heightmap.Types, int, int, int, int)}.
     */
    CompletableFuture<ColumnSkyLightResult> cc_querySkyLightColumn(int blockX, int blockZ, int minCubeY, int maxCubeY);

    /**
     * Captures loaded FULL cubes and computes bounded block/sky light on a worker. Missing neighbors remain unknown.
     * Only one rebuild may run at a time; invalid or busy requests fail. Publication requires unchanged dependencies.
     * An admitted domain is maintained automatically on world ticks; this future reports only the requested attempt.
     * Exact transient views may be read and sent to clients; this does not mark cubes globally light-correct or persist light.
     */
    CompletableFuture<CubeLightUpdate> cc_rebuildCubeLighting(CubeLightBounds bounds);

    /** Status of the selected domain, automatic retries, and deferred network publication. */
    CompletableFuture<CubeLightStatus> cc_getCubeLightStatus();

    /** Rate-limited recovery for a tracked, ready cube; cannot select bounds, load cubes, or start a solver. */
    boolean cc_requestCubeLightRefresh(ServerPlayer player, CubePos pos, long observedEpoch);

    /** Queries the current bounded publication after validating all its dependencies on the server thread. */
    CompletableFuture<CubeLightQuery> cc_queryCubeLight(LightLayer layer, BlockPos pos);

    /** Nonblocking owner-thread export, bound to the exact cube instance whose block data is being sent. */
    CubeLightPacketSnapshot cc_captureCubeLight(LevelCube cube);

    ChunkGenerationTask cc_scheduleGenerationTask(ChunkStatus chunkStatus, CubePos cubePos);

    void cc_onFullChunkStatusChange(CubePos cubePos, FullChunkStatus fullChunkStatus);

    boolean cc_isChunkTracked(ServerPlayer player, int x, int y, int z);

    void cc_onCubeTrackingChange(ServerPlayer player, CubePos pos, boolean sent);
}
