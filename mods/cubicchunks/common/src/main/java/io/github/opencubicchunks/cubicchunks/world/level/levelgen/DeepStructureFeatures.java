package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FossilFeature;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Native underground features with actual-height origins instead of vanilla's bounded placement height providers. */
public final class DeepStructureFeatures {
    private static final int BAND_HEIGHT = 384;
    private static final int CACHED_CELLS = 512;
    private static final int CACHED_FEATURES = 512;
    private static final int DUNGEON_ATTEMPTS = 14;
    private static final int DUNGEON_RADIUS = 6;
    private static final int DUNGEON_BELOW = -3;
    private static final int DUNGEON_ABOVE = 6;
    private static final int GEODE_RARITY = 24;
    private static final int FOSSIL_RARITY = 64;
    private static final int FOSSIL_DEPTH = 64;
    private static final int NEIGHBOR_MARGIN = 2;
    private final ServerLevel level;
    private final BiomeResolver biomes;
    private final DeepStructureRegion.SectionProvider terrain;
    private final int nativeMinY;
    private final long seed;
    private final Map<String, Specification> specifications = new LinkedHashMap<>();
    private final LoadingCache<Cell, List<Candidate>> cells;
    private final LoadingCache<Candidate, Optional<DeepStructureResult>> features;
    private int horizontalReach;
    private int verticalReach;

    public DeepStructureFeatures(ServerLevel level, BiomeResolver biomes, DeepStructureRegion.SectionProvider terrain, int nativeMinY) {
        this.level = level;
        this.biomes = biomes;
        this.terrain = terrain;
        this.nativeMinY = nativeMinY;
        seed = level.getSeed();
        register("monster_room", DUNGEON_ATTEMPTS, 1, "monster_room", "monster_room_deep");
        register("amethyst_geode", 1, GEODE_RARITY, "amethyst_geode");
        register("fossil_coal", 1, FOSSIL_RARITY, "fossil_upper");
        register("fossil_diamonds", 1, FOSSIL_RARITY, "fossil_lower");
        cells = CacheBuilder.newBuilder().maximumSize(CACHED_CELLS).build(CacheLoader.from(this::candidatesIn));
        features = CacheBuilder.newBuilder().maximumSize(CACHED_FEATURES).build(CacheLoader.from(this::generate));
    }

    /** Structure conflicts are checked against whole feature envelopes before any output can be published. */
    public List<DeepStructureResult> intersecting(BoundingBox query, Predicate<BoundingBox> conflictsWithStructure) {
        List<DeepStructureResult> result = new ArrayList<>();
        for (Candidate candidate : candidates(query)) {
            var cached = features.getIfPresent(candidate);
            if (cached != null && cached.map(DeepStructureResult::isEmpty).orElse(true)) {
                continue;
            }
            if (cached == null && !eligible(candidate)) {
                features.put(candidate, Optional.empty());
                continue;
            }
            var bounds = bounds(candidate);
            if (!conflictsWithStructure.test(bounds)) {
                (cached == null ? features.getUnchecked(candidate) : cached).filter(value -> !value.isEmpty()).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    /** A stable ordering makes overlapping feature writes independent of which neighboring cube was requested first. */
    List<Candidate> candidates(BoundingBox query) {
        List<Candidate> result = new ArrayList<>();
        if (query.minY() >= nativeMinY || specifications.isEmpty()) {
            return result;
        }
        int minBand = Math.floorDiv(query.minY() - verticalReach - nativeMinY, BAND_HEIGHT);
        int maxBand = Math.min(-1, Math.floorDiv(query.maxY() + verticalReach - nativeMinY, BAND_HEIGHT));
        for (int band = minBand; band <= maxBand; band++) {
            for (int x = SectionPos.blockToSectionCoord(query.minX() - horizontalReach);
                    x <= SectionPos.blockToSectionCoord(query.maxX() + horizontalReach); x++) {
                for (int z = SectionPos.blockToSectionCoord(query.minZ() - horizontalReach);
                        z <= SectionPos.blockToSectionCoord(query.maxZ() + horizontalReach); z++) {
                    for (Candidate candidate : cells.getUnchecked(new Cell(x, band, z))) {
                        var bounds = bounds(candidate);
                        if (bounds.maxY() < nativeMinY && representable(bounds) && bounds.intersects(query)) {
                            result.add(candidate);
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private void register(String name, int attempts, int rarity, String... placements) {
        Feature feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(Identifier.withDefaultNamespace(name));
        if (feature == null) {
            return;
        }
        List<PlacedFeature> placed = new ArrayList<>();
        for (String placement : placements) {
            var value = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
                    .getValue(Identifier.withDefaultNamespace(placement));
            if (value != null && value.feature().value() == feature) {
                placed.add(value);
            }
        }
        if (placed.isEmpty()) {
            return;
        }
        Specification specification;
        if (feature instanceof MonsterRoomFeature) {
            // Native rooms extend at most four blocks horizontally; include neighbor shape reads and writes.
            specification = new Specification(feature, placed, attempts, rarity, DUNGEON_RADIUS, DUNGEON_BELOW, DUNGEON_ABOVE);
        } else if (feature instanceof GeodeFeature geode) {
            int radius = Math.max(Math.abs(geode.minGenOffset()), Math.abs(geode.maxGenOffset())) + NEIGHBOR_MARGIN;
            specification = new Specification(feature, placed, attempts, rarity, radius, geode.minGenOffset() - NEIGHBOR_MARGIN,
                    geode.maxGenOffset() + NEIGHBOR_MARGIN);
        } else if (feature instanceof FossilFeature fossil) {
            specification = fossilSpecification(fossil, placed, attempts, rarity);
        } else {
            // A datapack can replace a named definition with an unrelated feature whose footprint is unknown.
            return;
        }
        specifications.put(name, specification);
        horizontalReach = Math.max(horizontalReach, specification.radius());
        verticalReach = Math.max(verticalReach, Math.max(-specification.below() + SectionPos.SECTION_SIZE - 1, specification.above()));
    }

    private Specification fossilSpecification(FossilFeature fossil, List<PlacedFeature> placed, int attempts, int rarity) {
        int radius = 0;
        int height = 0;
        for (var template : fossil.fossilStructures()) {
            var size = level.getStructureTemplateManager().getOrCreate(template).getSize();
            radius = Math.max(radius, Math.max(size.getX(), size.getZ()));
            height = Math.max(height, size.getY());
        }
        for (var template : fossil.overlayStructures()) {
            var size = level.getStructureTemplateManager().getOrCreate(template).getSize();
            radius = Math.max(radius, Math.max(size.getX(), size.getZ()));
            height = Math.max(height, size.getY());
        }
        return new Specification(fossil, placed, attempts, rarity, radius + NEIGHBOR_MARGIN, -FOSSIL_DEPTH, height + NEIGHBOR_MARGIN);
    }

    private List<Candidate> candidatesIn(Cell cell) {
        List<Candidate> result = new ArrayList<>();
        specifications.forEach((name, specification) -> {
            var random = RandomSource.create(mix(seed ^ name.hashCode(), cell.x(), cell.band(), cell.z()));
            for (int attempt = 0; attempt < specification.attempts(); attempt++) {
                if (random.nextInt(specification.rarity()) != 0) {
                    continue;
                }
                int x = SectionPos.sectionToBlockCoord(cell.x()) + random.nextInt(SectionPos.SECTION_SIZE);
                int y = nativeMinY + cell.band() * BAND_HEIGHT + random.nextInt(BAND_HEIGHT);
                int z = SectionPos.sectionToBlockCoord(cell.z()) + random.nextInt(SectionPos.SECTION_SIZE);
                result.add(new Candidate(name, new BlockPos(x, y, z), random.nextLong()));
            }
        });
        return List.copyOf(result);
    }

    private boolean eligible(Candidate candidate) {
        Specification specification = specifications.get(candidate.feature());
        var origin = candidate.origin();
        var biome = biomes.getNoiseBiome(QuartPos.fromBlock(origin.getX()), QuartPos.fromBlock(origin.getY()), QuartPos.fromBlock(origin.getZ()));
        var generation = level.getChunkSource().getGenerator().getBiomeGenerationSettings(biome);
        return specification.placed().stream().anyMatch(generation::hasFeature);
    }

    private Optional<DeepStructureResult> generate(Candidate candidate) {
        Specification specification = specifications.get(candidate.feature());
        var origin = candidate.origin();
        var bounds = bounds(candidate);
        int minY = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(bounds.minY()));
        int height = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(bounds.maxY()) + 1) - minY;
        var region = specification.feature() instanceof FossilFeature
                ? new FossilRegion(level, bounds, minY, height, candidate.seed(), biomes, terrain, origin.getY())
                : new DeepStructureRegion(level, bounds, minY, height, candidate.seed(), biomes, terrain);
        // False includes native placement rejection (e.g. a dungeon without a cave opening); cache it as well as successes.
        return region.placeFeature(specification.feature(), origin, candidate.seed()) ? Optional.of(region.finish()) : Optional.empty();
    }

    BoundingBox bounds(Candidate candidate) {
        var specification = specifications.get(candidate.feature());
        var origin = candidate.origin();
        int minY = origin.getY() + specification.below();
        if (specification.feature() instanceof FossilFeature) {
            minY = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(minY));
        }
        return new BoundingBox(origin.getX() - specification.radius(), minY,
                origin.getZ() - specification.radius(), origin.getX() + specification.radius(), origin.getY() + specification.above(),
                origin.getZ() + specification.radius());
    }

    private static boolean representable(BoundingBox bounds) {
        return CubicBlockBounds.contains(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()))
                && CubicBlockBounds.contains(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Full-coordinate identity; no packed Y or native placement seed that omits height.
    private static long mix(long seed, int x, int y, int z) {
        long hash = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xD1B54A32D192ED03L ^ z * 0x94D049BB133111EBL;
        hash = (hash ^ hash >>> 30) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ hash >>> 27) * 0x94D049BB133111EBL;
        return hash ^ hash >>> 31;
    }

    record Candidate(String feature, BlockPos origin, long seed) {}

    private record Cell(int x, int band, int z) {}

    private record Specification(Feature feature, List<PlacedFeature> placed, int attempts, int rarity, int radius, int below, int above) {}

    /** Native fossils bury below a real local terrain sample, never below an artificial vertical-band ceiling. */
    private static final class FossilRegion extends DeepStructureRegion {
        private final int ceiling;

        private FossilRegion(ServerLevel level, BoundingBox bounds, int minY, int height, long seed, BiomeResolver biomes,
                SectionProvider terrain, int ceiling) {
            super(level, bounds, minY, height, seed, biomes, terrain);
            this.ceiling = ceiling;
        }

        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            var at = new BlockPos.MutableBlockPos(x, ceiling, z);
            for (int y = ceiling; y >= getMinY(); y--) {
                if (type.isOpaque().test(getBlockState(at.setY(y)))) {
                    return y + 1;
                }
            }
            return getMinY();
        }
    }
}
