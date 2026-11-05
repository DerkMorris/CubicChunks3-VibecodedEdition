package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import net.minecraft.core.Holder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Build ordinary generator inputs in private chunks. Features can read and write the neighboring chunks in their stage. */
final class BiomeGenerationTile {
    private static final int BIOME_BORDER = 3;
    static final int WIDTH = 4;
    private final ServerLevel level;
    private final LoadingCache<ChunkPos, UndecoratedColumn> biomes;
    private final Cache<ChunkPos, UndecoratedColumn> terrain;
    private final NativeStructureLayout structures;
    private final @Nullable DeepCaveTerrain deep;
    private final int startX;
    private final int startZ;
    private final Map<ChunkPos, HolderChunk> chunks = new HashMap<>();

    BiomeGenerationTile(
            ServerLevel level, ChunkPos tile, LoadingCache<ChunkPos, UndecoratedColumn> biomes, Cache<ChunkPos, UndecoratedColumn> terrain,
            NativeStructureLayout structures, @Nullable DeepCaveTerrain deep
    ) {
        this.level = level;
        this.terrain = terrain;
        this.structures = structures;
        this.deep = deep;
        this.biomes = biomes;
        startX = tile.x() * WIDTH;
        startZ = tile.z() * WIDTH;
    }

    Map<ChunkPos, NativeBiomeColumn> generate() {
        forEach(BIOME_BORDER, (x, z) -> {
            var pos = new ChunkPos(x, z);
            chunks.put(pos, withStarts(biomes.getUnchecked(pos).copy(level)));
        });
        forEach(2, this::buildTerrain);
        forEach(1, this::decorate);
        Map<ChunkPos, NativeBiomeColumn> result = new HashMap<>();
        forEach(0, (x, z) -> result.put(new ChunkPos(x, z), new NativeBiomeColumn(level, chunk(x, z))));
        return Map.copyOf(result);
    }

    private void buildTerrain(int x, int z) {
        var pos = new ChunkPos(x, z);
        try {
            var input = chunk(x, z);
            structures.installReferences(input);
            var pristine = terrain.get(pos, () -> generateTerrain(x, z));
            var copy = pristine.copy(level);
            copy.setAllStarts(input.getAllStarts());
            copy.setAllReferences(input.getAllReferences());
            chunks.put(pos, new HolderChunk(copy));
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        }
    }

    private UndecoratedColumn generateTerrain(int x, int z) {
        var chunk = chunk(x, z);
        var region = region(chunk, ChunkStatus.TERRAIN);
        var source = level.getChunkSource();
        Set<Holder<Biome>> biomes = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunk(x + dx, z + dz).collectBiomesInPalette(biomes);
            }
        }
        source.getGenerator().buildTerrain(chunk, Blender.empty(), source.randomState(), level.structureManager().forWorldGenRegion(region),
                region.getBiomeManager(), region, biomes).join();
        if (deep != null) {
            deep.blendFloor(chunk);
            deep.ores.applyToNativeTerrain(chunk);
        }
        Heightmap.primeHeightmaps(chunk, ChunkStatus.FINAL_HEIGHTMAPS);
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        return new UndecoratedColumn(level, chunk);
    }

    private void decorate(int x, int z) {
        var chunk = chunk(x, z);
        var region = region(chunk, ChunkStatus.FEATURES);
        level.getChunkSource().getGenerator().applyBiomeDecoration(region, chunk, level.structureManager().forWorldGenRegion(region));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
    }

    private WorldGenRegion region(ProtoChunk chunk, ChunkStatus status) {
        var step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
        var pos = chunk.getPos();
        var cache = StaticCache2D.<GenerationChunkHolder>create(pos.x(), pos.z(), step.directDependencies().getRadius(),
                (x, z) -> chunks.computeIfAbsent(new ChunkPos(x, z), key -> {
                    var neighbor = new ProtoChunk(key, UpgradeData.EMPTY, LevelHeightAccessor.create(level.getMinY(), level.getHeight()),
                            level.palettedContainerFactory(), null);
                    neighbor.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
                    return withStarts(neighbor);
                }));
        return new BiomeGenerationRegion(level, cache, step, chunk, deep == null ? null : deep.ores);
    }

    private HolderChunk withStarts(ProtoChunk chunk) {
        structures.installStarts(chunk);
        return new HolderChunk(chunk);
    }

    private ProtoChunk chunk(int x, int z) {
        return chunks.get(new ChunkPos(x, z)).chunk;
    }

    private void forEach(int border, Visit visit) {
        for (int x = startX - border; x < startX + WIDTH + border; x++) {
            for (int z = startZ - border; z < startZ + WIDTH + border; z++) {
                visit.at(x, z);
            }
        }
    }

    private interface Visit {
        void at(int x, int z);
    }

    private static final class HolderChunk extends GenerationChunkHolder {
        private final ProtoChunk chunk;

        private HolderChunk(ProtoChunk chunk) {
            super(chunk.getPos());
            this.chunk = chunk;
        }

        @Override public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk.getPersistedStatus().isOrAfter(status) ? chunk : null;
        }

        @Override protected void addSaveDependency(CompletableFuture<?> future) {}

        @Override public int getQueueLevel() {
            return 0;
        }

        @Override public int getTicketLevel() {
            return 0;
        }
    }
}
