package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdStructure;

/** Complete native structure/feature outputs shared by live generation and detached lighting inputs. */
final class DeepStructureTerrain {
    private static final int CACHED_SECTIONS = 512;
    private static final int CACHED_STRUCTURES = 32;
    private static final int PLACEMENT_HALO = 32;
    private final ServerLevel level;
    private final BiomeResolver biomes;
    private final DeepStructureRegion.SectionProvider base;
    private final DeepStructureLayout layout;
    private final DeepStructureFeatures features;
    private final Cache<Origin, DeepStructureResult> structures = CacheBuilder.newBuilder().maximumSize(CACHED_STRUCTURES).build();
    private final LoadingCache<SectionPos, Section> sections = CacheBuilder.newBuilder().maximumSize(CACHED_SECTIONS)
            .build(CacheLoader.from(this::generate));

    DeepStructureTerrain(ServerLevel level, BiomeResolver biomes, DeepCaveTerrain base, DeepStructureLayout layout) {
        this.level = level;
        this.biomes = biomes;
        this.base = base::section;
        this.layout = layout;
        features = new DeepStructureFeatures(level, biomes, this.base, level.getMinY());
    }

    DeepStructureLayout layout() {
        return layout;
    }

    LevelChunkSection section(int sx, int sy, int sz) {
        return sections.getUnchecked(SectionPos.of(sx, sy, sz)).blocks();
    }

    void copyExtras(CubeAccess cube, int sectionIndex) {
        var pos = cube.cc_getCubePos();
        var section = SectionPos.of(Coords.cubeToSection(pos.getX(), Coords.indexToX(sectionIndex)),
                Coords.cubeToSection(pos.getY(), Coords.indexToY(sectionIndex)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(sectionIndex)));
        for (var result : sections.getUnchecked(section).results()) {
            result.copyExtras(cube, sectionIndex);
        }
    }

    private Section generate(SectionPos pos) {
        try (var ignored = layout.ownerReadScope()) {
            return generateWithinRequest(pos);
        }
    }

    private Section generateWithinRequest(SectionPos pos) {
        var bounds = new BoundingBox(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(), pos.maxBlockX(), pos.maxBlockY(), pos.maxBlockZ());
        var results = new ArrayList<DeepStructureResult>();
        for (var start : layout.covering(bounds)) {
            try {
                var result = structures.get(new Origin(start.owner(), start.structure()), () -> render(start));
                if (result.sections().contains(pos)) {
                    results.add(result);
                }
            } catch (ExecutionException failure) {
                throw new CompletionException(failure.getCause());
            }
        }
        // Natural geological features must not excavate or replace a generated structure.
        for (var result : features.intersecting(bounds, box -> !layout.covering(box).isEmpty())) {
            if (result.sections().contains(pos)) {
                results.add(result);
            }
        }
        var original = base.section(pos.x(), pos.y(), pos.z());
        if (results.isEmpty()) {
            return new Section(original, List.of());
        }
        var blocks = original.copy();
        for (var result : results) {
            result.apply(blocks, pos.x(), pos.y(), pos.z());
        }
        return new Section(blocks, results.stream().map(result -> result.forSection(pos)).toList());
    }

    private DeepStructureResult render(DeepStructureLayout.Start start) {
        // Postprocess the whole layout once. Chunk-at-a-time replays lose mutable piece state and consume different RNG streams.
        if (start.structure() instanceof StrongholdStructure) {
            synchronized (StrongholdStructure.class) {
                return renderNative(start);
            }
        }
        return renderNative(start);
    }

    private DeepStructureResult renderNative(DeepStructureLayout.Start start) {
        int minY = layout.bandMinY(layout.bandAt(start.owner().minCubeY()));
        var bounds = start.bounds();
        var writeBounds = new BoundingBox(bounds.minX() - PLACEMENT_HALO, minY, bounds.minZ() - PLACEMENT_HALO,
                bounds.maxX() + PLACEMENT_HALO, minY + DeepStructureLayout.BAND_HEIGHT - 1, bounds.maxZ() + PLACEMENT_HALO);
        var region = new DeepStructureRegion(level, writeBounds, minY, DeepStructureLayout.BAND_HEIGHT, start.seed(), biomes, base);
        var copy = start.copy(level);
        copy.placeInChunk(region, level.structureManager().forWorldGenRegion(region), level.getChunkSource().getGenerator(),
                RandomSource.create(start.seed()), writeBounds, copy.getChunkPos());
        return region.finish();
    }

    private record Origin(CubePos owner, Structure structure) {}

    private record Section(LevelChunkSection blocks, List<DeepStructureResult> results) {}
}
