package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntFunction;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.NativeBiomeTerrain;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Uses authoritative cube files. Only genuinely absent/un-generated terrain uses the current deterministic generator. */
public final class CubeWorldLightStorage implements CubeWorldLightManager.Storage {
    private final NativeBiomeTerrain terrain;
    private final long seed;
    private final PalettedContainerFactory palettes;
    private final Function<CubePos, CompletableFuture<Optional<CompoundTag>>> read;
    private final Function<CubePos, CompletableFuture<NavigableMap<Integer, CompoundTag>>> readColumn;
    private final Executor worker;

    public CubeWorldLightStorage(
            long seed, PalettedContainerFactory palettes, Function<CubePos, CompletableFuture<Optional<CompoundTag>>> read,
            Function<CubePos, CompletableFuture<NavigableMap<Integer, CompoundTag>>> readColumn, Executor worker
    ) {
        this(seed, palettes, read, readColumn, worker, null);
    }

    public CubeWorldLightStorage(
            long seed, PalettedContainerFactory palettes, Function<CubePos, CompletableFuture<Optional<CompoundTag>>> read,
            Function<CubePos, CompletableFuture<NavigableMap<Integer, CompoundTag>>> readColumn, Executor worker, NativeBiomeTerrain terrain
    ) {
        this.terrain = terrain;
        this.seed = seed;
        this.palettes = palettes;
        this.read = read;
        this.readColumn = readColumn;
        this.worker = worker;
    }

    @Override public @Nullable IntFunction<CubeSkyOcclusion> deepSky(CubePos column) {
        return terrain == null ? null : terrain.deepSky(column);
    }

    @Override public int baselineHeight(long worldSeed, int x, int z) {
        return terrain == null ? CubeWorldLightManager.Storage.super.baselineHeight(worldSeed, x, z) : terrain.baseFloor();
    }

    @Override public CompletableFuture<Map<Integer, CubeSkyOcclusion>> column(CubePos column) {
        var baseline = terrain == null ? CompletableFuture.completedFuture(Map.<Integer, CubeSkyOcclusion>of()) : terrain.sky(column);
        return readColumn.apply(column).thenCombineAsync(baseline, (tags, generated) -> {
            Map<Integer, CubeSkyOcclusion> result = new HashMap<>(generated);
            tags.forEach((y, tag) -> {
                SerializableCubeData data = SerializableCubeData.parse(tag, CubePos.of(column.getX(), y, column.getZ()));
                if (data.chunkStatus().isOrAfter(ChunkStatus.TERRAIN)) {
                    result.put(y, data.skyOcclusion(palettes));
                }
            });
            return Map.copyOf(result);
        }, worker);
    }

    @Override public CompletableFuture<CubeLightBlockSnapshot> cube(CubePos pos) {
        return read.apply(pos).thenComposeAsync(tag -> {
            if (tag.isPresent()) {
                SerializableCubeData data = SerializableCubeData.parse(tag.get(), pos);
                if (data.chunkStatus().isOrAfter(ChunkStatus.TERRAIN)) {
                    return CompletableFuture.completedFuture(data.lightBlocks(palettes));
                }
            }
            return terrain == null ? CompletableFuture.completedFuture(CubeLightBlockSnapshot.generated(pos, seed)) : terrain.blocks(pos);
        }, worker);
    }
}
