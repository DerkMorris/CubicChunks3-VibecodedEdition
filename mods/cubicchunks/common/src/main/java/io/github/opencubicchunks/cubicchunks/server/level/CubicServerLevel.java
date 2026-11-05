package io.github.opencubicchunks.cubicchunks.server.level;

import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.annotation.UsedFromASM;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.NativeBiomeTerrain;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightQuery;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightStatus;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

public interface CubicServerLevel extends CubicLevel {
    NativeBiomeTerrain cc_nativeBiomeTerrain();

    void cc_tickClo(LevelClo cube, int randomTickSpeed);

    void cc_onCubeEntityStatus(CubePos pos, FullChunkStatus status);

    void cc_unloadCubeEntities(CubePos pos);

    boolean cc_tryUnloadCubeEntities(CubePos pos);

    boolean cc_areCubeEntitiesLoaded(CubePos pos);

    /** @see CubicChunkMap#cc_queryColumnSurface(Heightmap.Types, int, int, int, int) */
    CompletableFuture<ColumnSurfaceResult> cc_queryColumnSurface(Heightmap.Types type, int blockX, int blockZ, int minCubeY, int maxCubeY);

    /** @see CubicChunkMap#cc_querySkyLightColumn(int, int, int, int) */
    CompletableFuture<ColumnSkyLightResult> cc_querySkyLightColumn(int blockX, int blockZ, int minCubeY, int maxCubeY);

    /** @see CubicChunkMap#cc_rebuildCubeLighting(CubeLightBounds) */
    CompletableFuture<CubeLightUpdate> cc_rebuildCubeLighting(CubeLightBounds bounds);

    /** @see CubicChunkMap#cc_getCubeLightStatus() */
    CompletableFuture<CubeLightStatus> cc_getCubeLightStatus();

    /** @see CubicChunkMap#cc_queryCubeLight(LightLayer, BlockPos) */
    CompletableFuture<CubeLightQuery> cc_queryCubeLight(LightLayer layer, BlockPos pos);

    @UsedFromASM
    boolean isNaturalSpawningAllowed(CloPos cloPos);

    @UsedFromASM
    void invalidateCapabilities(CloPos cloPos);
}
