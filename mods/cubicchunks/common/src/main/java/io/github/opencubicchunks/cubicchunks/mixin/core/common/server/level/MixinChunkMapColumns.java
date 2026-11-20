package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk.storage.MixinChunkStorage;
import io.github.opencubicchunks.cubicchunks.server.level.CloHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnCache;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMapColumns extends MixinChunkStorage implements CubeColumnSource {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Unique
    private CubeColumnCache cc_gameplayColumns;
    @Unique
    private long cc_columnSourceTick = Long.MIN_VALUE;
    @Unique
    private Map<CubePos, List<CubeAccess>> cc_columnSources = new HashMap<>();

    @Override public void cc_columnChanged(CubeAccess cube) {
        if (mainThreadExecutor.isSameThread()) {
            ((io.github.opencubicchunks.cubicchunks.server.level.CubeWeatherServer) level).cc_weatherColumnChanged(cube.cc_getCubePos());
        }
        if (cc_gameplayColumns != null && mainThreadExecutor.isSameThread()) {
            cc_gameplayColumns.changed(cube);
            var pos = cube.cc_getCubePos();
            var indexed = cc_columnSources.get(CubePos.of(pos.getX(), 0, pos.getZ()));
            if (indexed == null || indexed.stream().noneMatch(source -> source == cube)) {
                cc_columnSourceTick = Long.MIN_VALUE;
            }
        }
    }

    @Override public @Nullable CubeColumnSnapshot cc_column(int x, int z) {
        if (!((CanBeCubic) level).cc_isCubic() || !mainThreadExecutor.isSameThread() || !CubicBlockBounds.contains(x)
                || !CubicBlockBounds.contains(z)) {
            return null;
        }
        if (cc_gameplayColumns == null) {
            cc_gameplayColumns = new CubeColumnCache(level.getMinY(), level.getMaxY(), this::cc_loadGameplayColumn, level::getGameTime);
        }
        if (cc_columnSourceTick != level.getGameTime()) {
            cc_columnSourceTick = level.getGameTime();
            cc_columnSources.clear();
            cc_collectGameplayColumns(pendingUnloads.values(), cc_columnSources);
            cc_collectGameplayColumns(updatingChunkMap.values(), cc_columnSources);
        }
        return cc_gameplayColumns.get(x, z, cc_columnSources.getOrDefault(CubePos.of(Coords.blockToCube(x), 0, Coords.blockToCube(z)), List.of()));
    }

    @Unique
    private static void cc_collectGameplayColumns(Iterable<ChunkHolder> holders, Map<CubePos, List<CubeAccess>> sources) {
        for (ChunkHolder holder : holders) {
            var latest = ((CloHolder) holder).cc_getLatestClo();
            if (latest instanceof ImposterProtoCube imposter) {
                latest = imposter.cc_getWrappedClo();
            }
            if (latest instanceof CubeAccess cube) {
                var pos = cube.cc_getCubePos();
                sources.computeIfAbsent(CubePos.of(pos.getX(), 0, pos.getZ()), ignored -> new ArrayList<>()).add(cube);
            }
        }
    }

    @Unique
    private CompletableFuture<CubeColumnSnapshot> cc_loadGameplayColumn(CubePos column) {
        var palettes = level.palettedContainerFactory();
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        var generated = ((CubicServerLevel) level).cc_nativeBiomeTerrain().columnBlocks(column);
        return cc_readCubeColumn(column.getX(), column.getZ(), -CubePos.MAX_COORDINATE_VALUE - 1, CubePos.MAX_COORDINATE_VALUE)
                .thenCombineAsync(generated, (tags, baseline) -> {
                    if (tags.size() > CubeColumnCache.MAX_COLUMN_CUBES) {
                        throw new IllegalStateException("Gameplay column exceeds the sparse snapshot budget");
                    }
                    var blocks = new TreeMap<Integer, CubeColumnBlocks>(baseline);
                    var unknown = new HashSet<Integer>();
                    tags.forEach((y, tag) -> {
                        var data = SerializableCubeData.parse(tag, CubePos.of(column.getX(), y, column.getZ()));
                        var captured = data.columnBlocks(palettes);
                        if (data.chunkStatus().isOrAfter(ChunkStatus.TERRAIN)) {
                            blocks.put(y, captured);
                        } else if (!captured.isEmpty()) {
                            blocks.remove(y);
                            unknown.add(y);
                        }
                    });
                    return new CubeColumnSnapshot(column.getX(), column.getZ(), minY, maxY, blocks, unknown);
                }, Util.backgroundExecutor());
    }
}
