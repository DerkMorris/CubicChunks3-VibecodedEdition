package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.datafixers.util.Pair;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStructureStorage;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.SerializableCubeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

/** Seeded full-height owners of complete vanilla underground structure graphs. Cached starts are immutable snapshots. */
public final class DeepStructureLayout {
    public static final int BAND_HEIGHT = 512;
    private static final int CACHE_SIZE = 512;
    private static final int NATIVE_MIN_Y = -64;
    private static final int NATIVE_HEIGHT = 384;
    private static final int BAND_MARGIN = 16;
    private static final int VERTICAL_JITTER = 96;
    private static final int PORTAL_HALO = 32;
    private static final int CANDIDATE_REACH = 256;
    // A 16x16 chunk cell approximates vanilla's 0.004 mineshaft frequency. Strongholds remain substantially rarer.
    private static final int MINESHAFT_SPACING = 16;
    private static final int STRONGHOLD_SPACING = 64;
    private static final List<ResourceKey<Structure>> SUPPORTED = List.of(BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA,
            BuiltinStructures.STRONGHOLD, BuiltinStructures.ANCIENT_CITY, BuiltinStructures.TRIAL_CHAMBERS,
            BuiltinStructures.RUINED_PORTAL_STANDARD);
    private final ServerLevel level;
    private final BiomeResolver biomes;
    private final List<Kind> kinds;
    private final LoadingCache<Candidate, Optional<Start>> starts;
    private final Cache<CubePos, CompoundTag> owners = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build();
    private final Function<CubePos, CompletableFuture<Optional<CompoundTag>>> readOwner;
    private final ThreadLocal<Map<CubePos, Optional<CompoundTag>>> requestOwners = new ThreadLocal<>();

    public DeepStructureLayout(ServerLevel level, BiomeResolver biomes) {
        this(level, biomes, owner -> {
            // Detached generation fixtures can have no backing chunk map. Real ServerChunkCache instances always own one.
            var map = level.getChunkSource().chunkMap;
            return map == null ? CompletableFuture.completedFuture(Optional.empty())
                    : ((CubeStructureStorage) map).cc_read(CloPos.cube(owner));
        });
    }

    DeepStructureLayout(ServerLevel level, BiomeResolver biomes, Function<CubePos, CompletableFuture<Optional<CompoundTag>>> readOwner) {
        this.level = level;
        this.biomes = biomes;
        this.readOwner = readOwner;
        kinds = allowedKinds();
        starts = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build(CacheLoader.from(this::generate));
    }

    public int bandAt(int actualY) {
        return Math.floorDiv(actualY - level.getMinY(), BAND_HEIGHT);
    }

    public int bandMinY(int band) {
        return level.getMinY() + band * BAND_HEIGHT;
    }

    /** Repeated feature conflict queries share disk misses only for the lifetime of one terrain request. */
    OwnerReadScope ownerReadScope() {
        boolean outermost = requestOwners.get() == null;
        if (outermost) {
            requestOwners.set(new HashMap<>());
        }
        return new OwnerReadScope(outermost);
    }

    /** Publish loaded metadata before neighboring generation can consult this owner's previously saved layout. */
    public void rememberLoaded(CubeAccess cube) {
        if (!cube.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
            return;
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var context = StructurePieceSerializationContext.fromLevel(level);
        var snapshot = new CompoundTag();
        cube.getAllStarts().forEach((structure, start) -> snapshot.put(registry.getKey(structure).toString(),
                start.createTag(context, start.getChunkPos())));
        owners.put(cube.cc_getCubePos(), snapshot);
    }

    private Optional<Start> authoritative(Start generated) {
        var snapshot = ownerSnapshot(generated.owner);
        if (snapshot == null) {
            return Optional.of(generated);
        }
        return fromSnapshot(generated.owner, generated.structure, generated.seed, snapshot);
    }

    private Optional<Start> fromSnapshot(CubePos owner, Structure structure, long seed, CompoundTag snapshot) {
        String name = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure).toString();
        if (!snapshot.contains(name)) {
            return Optional.empty();
        }
        var tag = snapshot.getCompound(name).orElseThrow(() -> new IllegalStateException("Invalid saved deep structure " + name));
        var start = StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level), tag.copy(), level.getSeed());
        if (start == null || start.getStructure() != structure) {
            if (start == StructureStart.INVALID_START) {
                return Optional.empty();
            }
            throw new IllegalStateException("Cannot load saved deep structure " + name + " in " + owner);
        }
        if (!start.isValid()) {
            return Optional.empty();
        }
        if (start.getPieces().size() != tag.getList("Children").orElseThrow().size()) {
            throw new IllegalStateException("Saved deep structure pieces were dropped in " + owner);
        }
        return Optional.of(new Start(owner, structure, tag, start.getBoundingBox(), seed));
    }

    /** Resolve a referenced owner without loading its cube or terrain. Unavailable/corrupt metadata defers gameplay queries. */
    public @Nullable Start startForOwner(CubePos owner, Structure structure) {
        if (!supportsOwner(owner, structure)) {
            return null;
        }
        try {
            // Existing owner metadata is authoritative even after generation settings or biome eligibility change.
            var snapshot = ownerSnapshot(owner);
            if (snapshot != null) {
                return fromSnapshot(owner, structure, level.getSeed(), snapshot).orElse(null);
            }
            if (!level.structureManager().shouldGenerateStructures()) {
                return null;
            }
            for (var kind : kinds) {
                if (kind.holder.value() != structure) {
                    continue;
                }
                int size = kind.spacing * SectionPos.SECTION_SIZE;
                var candidate = candidate(kind, Math.floorDiv(owner.minCubeX(), size), bandAt(owner.minCubeY()),
                        Math.floorDiv(owner.minCubeZ(), size));
                if (!CubePos.from(new BlockPos(candidate.origin.getMiddleBlockX(), owner.minCubeY(), candidate.origin.getMiddleBlockZ()))
                        .equals(owner)) {
                    return null;
                }
                return starts.getUnchecked(candidate).filter(start -> start.owner.equals(owner)).flatMap(this::authoritative).orElse(null);
            }
        } catch (RuntimeException unavailable) {
            // Generation remains fail-fast; spawning/position checks must neither fabricate metadata nor crash on a failed owner read.
            return null;
        }
        return null;
    }

    private boolean supportsOwner(CubePos owner, Structure structure) {
        return level.dimension() == Level.OVERWORLD && owner.maxCubeY() < level.getMinY() && withinWorld(bounds(owner))
                && SUPPORTED.contains(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey(structure).orElse(null));
    }

    private @Nullable CompoundTag ownerSnapshot(CubePos owner) {
        var cached = owners.getIfPresent(owner);
        if (cached != null) {
            return cached;
        }
        var request = requestOwners.get();
        if (request != null) {
            var snapshot = request.get(owner);
            if (snapshot != null) {
                return snapshot.orElse(null);
            }
        }
        var saved = readOwner.apply(owner).join();
        if (saved.isPresent() && SerializableCubeData.parse(saved.get(), owner).chunkStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
            var snapshot = saved.get().getCompound("structures").orElseThrow().getCompound("starts").orElseThrow().copy();
            // A concurrently loaded owner is newer than a disk read that began before publication.
            owners.asMap().putIfAbsent(owner, snapshot);
        }
        var snapshot = owners.getIfPresent(owner);
        if (request != null && request.size() < CACHE_SIZE) {
            request.put(owner, Optional.ofNullable(snapshot));
        }
        // Missing/unfinished disk owners never outlive this request: a later save can establish authoritative empty starts.
        return snapshot;
    }

    private List<Kind> allowedKinds() {
        if (level.dimension() != Level.OVERWORLD || !level.structureManager().shouldGenerateStructures()) {
            return List.of();
        }
        Map<ResourceKey<Structure>, Kind> found = new HashMap<>();
        for (var set : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
            for (var entry : set.value().structures()) {
                var key = entry.structure().unwrapKey().orElse(null);
                int order = SUPPORTED.indexOf(key);
                if (order >= 0) {
                    int spacing = set.value().placement() instanceof RandomSpreadStructurePlacement placement ? placement.spacing()
                            : STRONGHOLD_SPACING;
                    if (key == BuiltinStructures.MINESHAFT || key == BuiltinStructures.MINESHAFT_MESA) {
                        spacing = MINESHAFT_SPACING;
                    }
                    // Even chunk origins cannot put two starts of the same type in a single 32-block owner cube.
                    spacing = Math.max(2, spacing + (spacing & 1));
                    found.putIfAbsent(key, new Kind(entry.structure(), spacing, order));
                }
            }
        }
        return found.values().stream().sorted(Comparator.comparingInt(Kind::order)).toList();
    }

    public List<Start> intersecting(BoundingBox actualBounds) {
        if (!level.structureManager().shouldGenerateStructures()) {
            return List.of();
        }
        var result = new ArrayList<Start>();
        for (var candidate : candidates(actualBounds, kind -> true)) {
            starts.getUnchecked(candidate).flatMap(this::authoritative).filter(start -> start.bounds.intersects(actualBounds)).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /** Cave parameter fading can reach across band boundaries; discover only the affected structures in the expanded area. */
    List<Start> caveStructuresNear(BoundingBox area, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Structure influence radius must be nonnegative");
        }
        if (!level.structureManager().shouldGenerateStructures()) {
            return List.of();
        }
        var expanded = new BoundingBox(clamp((long) area.minX() - radius), clamp((long) area.minY() - radius),
                clamp((long) area.minZ() - radius), clamp((long) area.maxX() + radius), clamp((long) area.maxY() + radius),
                clamp((long) area.maxZ() + radius));
        var result = new ArrayList<Start>();
        for (var candidate : candidates(expanded,
                kind -> kind.holder.is(BuiltinStructures.STRONGHOLD) || kind.holder.is(BuiltinStructures.TRIAL_CHAMBERS))) {
            starts.getUnchecked(candidate).flatMap(this::authoritative).filter(start -> start.bounds.intersects(expanded)).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /** Placement may extend supports through the band and portal material beyond the structure's main box. */
    public List<Start> covering(BoundingBox actualBounds) {
        if (!level.structureManager().shouldGenerateStructures()) {
            return List.of();
        }
        var result = new ArrayList<Start>();
        for (var candidate : candidates(actualBounds, kind -> true)) {
            starts.getUnchecked(candidate).flatMap(this::authoritative).filter(start -> start.bounds.inflatedBy(PORTAL_HALO)
                    .intersects(actualBounds.minX(), actualBounds.minZ(), actualBounds.maxX(), actualBounds.maxZ())).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public void installStarts(CubeAccess cube) {
        for (var start : intersecting(bounds(cube.cc_getCubePos()))) {
            if (start.owner.equals(cube.cc_getCubePos())) {
                cube.setStartForStructure(start.structure, start.copy(level));
            }
        }
    }

    public void installReferences(CubeAccess cube) {
        for (var start : intersecting(bounds(cube.cc_getCubePos()))) {
            cube.addReferenceForStructure(start.structure, start.owner.asLong());
        }
    }

    public @Nullable Pair<BlockPos, Holder<Structure>> findNearest(HolderSet<Structure> requested, BlockPos origin, int radius) {
        var start = findNearestStart(requested, origin, radius);
        return start == null ? null : Pair.of(start.bounds.getCenter(), level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .wrapAsHolder(start.structure));
    }

    public @Nullable Start findNearestStart(HolderSet<Structure> requested, BlockPos origin, int radius) {
        return findNearestStart(requested, origin, radius, start -> true);
    }

    /** The caller may exclude previously referenced owners without mutating the immutable generation cache. */
    public @Nullable Start findNearestStart(HolderSet<Structure> requested, BlockPos origin, int radius, Predicate<Start> eligible) {
        if (!level.structureManager().shouldGenerateStructures() || radius < 0) {
            return null;
        }
        long distance = (long) radius * SectionPos.SECTION_SIZE;
        var search = new BoundingBox(clamp(origin.getX() - distance), clamp(origin.getY() - distance), clamp(origin.getZ() - distance),
                clamp(origin.getX() + distance), clamp(origin.getY() + distance), clamp(origin.getZ() + distance));
        var ordered = candidates(search, kind -> requested.contains(kind.holder)).stream()
                .sorted(Comparator.comparingDouble(candidate -> lowerDistance(candidate, origin))).toList();
        double bestDistance = (double) distance * distance;
        Start best = null;
        for (var candidate : ordered) {
            if (lowerDistance(candidate, origin) > bestDistance) {
                break;
            }
            var optional = starts.getUnchecked(candidate).flatMap(this::authoritative);
            if (optional.isEmpty()) {
                continue;
            }
            var start = optional.get();
            double current = start.bounds.getCenter().distSqr(origin);
            if (current <= bestDistance && eligible.test(start)) {
                bestDistance = current;
                best = start;
            }
        }
        return best;
    }

    private List<Candidate> candidates(BoundingBox bounds, Predicate<Kind> filter) {
        if (kinds.isEmpty() || bounds.minY() >= level.getMinY()) {
            return List.of();
        }
        var result = new ArrayList<Candidate>();
        for (var kind : kinds) {
            if (!filter.test(kind)) {
                continue;
            }
            int size = kind.spacing * SectionPos.SECTION_SIZE;
            for (int band = bandAt(bounds.minY()); band <= Math.min(-1, bandAt(bounds.maxY())); band++) {
                for (int x = Math.floorDiv(bounds.minX() - CANDIDATE_REACH, size); x <= Math.floorDiv(bounds.maxX() + CANDIDATE_REACH, size); x++) {
                    for (int z = Math.floorDiv(bounds.minZ() - CANDIDATE_REACH, size); z <= Math.floorDiv(bounds.maxZ() + CANDIDATE_REACH, size); z++) {
                        var candidate = candidate(kind, x, band, z);
                        if (withinCandidateReach(candidate.origin, bounds)) {
                            result.add(candidate);
                        }
                    }
                }
            }
        }
        return result;
    }

    private double lowerDistance(Candidate candidate, BlockPos origin) {
        return squaredDistance(origin.getX(), candidate.origin.getMiddleBlockX() - CANDIDATE_REACH,
                candidate.origin.getMiddleBlockX() + CANDIDATE_REACH)
                + squaredDistance(origin.getY(), bandMinY(candidate.band), (long) bandMinY(candidate.band) + BAND_HEIGHT - 1)
                + squaredDistance(origin.getZ(), candidate.origin.getMiddleBlockZ() - CANDIDATE_REACH,
                        candidate.origin.getMiddleBlockZ() + CANDIDATE_REACH);
    }

    private Candidate candidate(Kind kind, int x, int band, int z) {
        long seed = candidateSeed(level.getSeed(), kind.order, x, band, z);
        var random = new XoroshiroRandomSource(seed);
        var origin = new ChunkPos(x * kind.spacing + 2 * random.nextInt(kind.spacing / 2),
                z * kind.spacing + 2 * random.nextInt(kind.spacing / 2));
        return new Candidate(kind, x, band, z, origin, seed);
    }

    private static boolean withinCandidateReach(ChunkPos origin, BoundingBox bounds) {
        return bounds.intersects(origin.getMiddleBlockX() - CANDIDATE_REACH, origin.getMiddleBlockZ() - CANDIDATE_REACH,
                origin.getMiddleBlockX() + CANDIDATE_REACH, origin.getMiddleBlockZ() + CANDIDATE_REACH);
    }

    private Optional<Start> generate(Candidate candidate) {
        if (candidate.kind.holder.is(BuiltinStructures.STRONGHOLD)) {
            // Vanilla stronghold layout uses mutable static weights and the imposed-piece selector.
            synchronized (StrongholdPieces.class) {
                return generateGraph(candidate);
            }
        }
        return generateGraph(candidate);
    }

    private Optional<Start> generateGraph(Candidate candidate) {
        long seed = candidate.seed;
        var random = new XoroshiroRandomSource(seed);
        // Keep the Y draw after the same two position draws used by cheap candidate discovery.
        random.nextInt(candidate.kind.spacing / 2);
        random.nextInt(candidate.kind.spacing / 2);
        var pos = candidate.origin;
        int base = bandMinY(candidate.band);
        int initialOffset = base + BAND_HEIGHT / 2 + QuartPos.SIZE * random.nextInt(2 * VERTICAL_JITTER / QuartPos.SIZE + 1) - VERTICAL_JITTER;
        var source = level.getChunkSource();
        var structure = candidate.kind.holder.value();
        var projected = new DeepStructureBiomeSource(source.getGenerator().getBiomeSource(), biomes, initialOffset);
        var climate = source.randomState().createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        var layoutRandom = new WorldgenRandom(new XoroshiroRandomSource(seed));
        layoutRandom.setLargeFeatureSeed(seed, pos.x(), pos.z());
        var context = new Structure.GenerationContext(level.registryAccess(), source.getGenerator(), projected, climate,
                projected.createResolver(climate), source.randomState(), level.getStructureTemplateManager(), layoutRandom, seed, pos,
                LevelHeightAccessor.create(NATIVE_MIN_Y, NATIVE_HEIGHT), structure.biomes()::contains);
        var stub = structure.findValidGenerationPoint(context);
        if (stub.isEmpty()) {
            return Optional.empty();
        }
        var builder = stub.get().getPiecesBuilder();
        if (builder.isEmpty()) {
            return Optional.empty();
        }
        return translateGraph(candidate, stub.get().position(), builder.build(), initialOffset);
    }

    private Optional<Start> translateGraph(Candidate candidate, BlockPos nativeAnchor, PiecesContainer container, int initialOffset) {
        var serialization = StructurePieceSerializationContext.fromLevel(level);
        boolean portal = candidate.kind.holder.is(BuiltinStructures.RUINED_PORTAL_STANDARD);
        if (portal && !isUndergroundPortal(container, serialization)) {
            return Optional.empty();
        }
        int base = bandMinY(candidate.band);
        var nativeBounds = container.calculateBoundingBox();
        int halo = portal ? PORTAL_HALO : 0;
        int minimumOffset = ceilQuart(base + BAND_MARGIN + halo - nativeBounds.minY());
        int maximumOffset = Math.floorDiv(base + BAND_HEIGHT - BAND_MARGIN - halo - 1 - nativeBounds.maxY(), QuartPos.SIZE) * QuartPos.SIZE;
        if (minimumOffset > maximumOffset) {
            return Optional.empty();
        }
        int offset = Math.clamp(initialOffset, minimumOffset, maximumOffset);
        var anchor = nativeAnchor.offset(0, offset, 0);
        var structure = candidate.kind.holder.value();
        if (!structure.biomes().contains(biomes.getNoiseBiome(QuartPos.fromBlock(anchor.getX()), QuartPos.fromBlock(anchor.getY()),
                QuartPos.fromBlock(anchor.getZ())))) {
            return Optional.empty();
        }
        movePieces(container, offset);
        var pos = candidate.origin;
        var start = new StructureStart(structure, pos, 0, container);
        var actualBounds = start.getBoundingBox();
        if (!fitsCandidateBand(candidate, actualBounds)) {
            return Optional.empty();
        }
        var owner = CubePos.from(new BlockPos(pos.getMiddleBlockX(), actualBounds.getCenter().getY(), pos.getMiddleBlockZ()));
        if (!actualBounds.intersects(bounds(owner))) {
            return Optional.empty();
        }
        return Optional.of(new Start(owner, structure, start.createTag(serialization, pos), actualBounds, candidate.seed));
    }

    private static boolean isUndergroundPortal(PiecesContainer container, StructurePieceSerializationContext serialization) {
        return container.pieces().stream().allMatch(piece -> piece instanceof RuinedPortalPiece
                && piece.createTag(serialization).getString("VerticalPlacement").orElse("").equals("underground"));
    }

    private static void movePieces(PiecesContainer container, int offset) {
        for (var piece : container.pieces()) {
            piece.move(0, offset, 0);
            if (piece instanceof PoolElementStructurePiece pool) {
                var junctions = pool.getJunctions().stream().map(junction -> new JigsawJunction(junction.getSourceX(),
                        junction.getSourceGroundY() + offset, junction.getSourceZ(), junction.getDeltaY(), junction.getDestProjection())).toList();
                pool.getJunctions().clear();
                junctions.forEach(pool::addJunction);
            }
        }
    }

    private boolean fitsCandidateBand(Candidate candidate, BoundingBox bounds) {
        int base = bandMinY(candidate.band);
        return withinWorld(bounds) && hasBoundedFootprint(candidate.origin, bounds) && bounds.minY() >= base
                && bounds.maxY() < base + BAND_HEIGHT && bounds.maxY() < level.getMinY();
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Full-coordinate avalanche; changing these constants changes newly generated layout.
    private static long candidateSeed(long seed, int kind, int x, int band, int z) {
        long value = seed ^ x * 0x9E3779B97F4A7C15L ^ band * 0xD1B54A32D192ED03L
                ^ z * 0x94D049BB133111EBL ^ (kind + 1L) * 0xDB4F0B9175AE2165L;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static int ceilQuart(int value) {
        return -Math.floorDiv(-value, QuartPos.SIZE) * QuartPos.SIZE;
    }

    private static boolean withinWorld(BoundingBox box) {
        return CubicBlockBounds.contains(new BlockPos(box.minX(), box.minY(), box.minZ()))
                && CubicBlockBounds.contains(new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
    }

    private static boolean hasBoundedFootprint(ChunkPos origin, BoundingBox bounds) {
        // The default vanilla graphs fit comfortably inside this bound. Reject oversized replacements rather than
        // silently missing their remote pieces during candidate discovery or cutting the graph at a tile edge.
        return bounds.minX() - PORTAL_HALO >= origin.getMiddleBlockX() - CANDIDATE_REACH
                && bounds.maxX() + PORTAL_HALO <= origin.getMiddleBlockX() + CANDIDATE_REACH
                && bounds.minZ() - PORTAL_HALO >= origin.getMiddleBlockZ() - CANDIDATE_REACH
                && bounds.maxZ() + PORTAL_HALO <= origin.getMiddleBlockZ() + CANDIDATE_REACH;
    }

    private static int clamp(long coordinate) {
        return (int) Math.clamp(coordinate, CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }

    private static double squaredDistance(long coordinate, long min, long max) {
        double difference = coordinate < min ? min - coordinate : coordinate > max ? coordinate - max : 0;
        return difference * difference;
    }

    private static BoundingBox bounds(CubePos pos) {
        return new BoundingBox(pos.minCubeX(), pos.minCubeY(), pos.minCubeZ(), pos.maxCubeX(), pos.maxCubeY(), pos.maxCubeZ());
    }

    private static BoundingBox copyBounds(BoundingBox bounds) {
        return new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    final class OwnerReadScope implements AutoCloseable {
        private final boolean outermost;

        private OwnerReadScope(boolean outermost) {
            this.outermost = outermost;
        }

        @Override public void close() {
            if (outermost) {
                requestOwners.remove();
            }
        }
    }

    private record Kind(Holder<Structure> holder, int spacing, int order) {}

    private record Candidate(Kind kind, int x, int band, int z, ChunkPos origin, long seed) {}

    public record Start(CubePos owner, Structure structure, CompoundTag tag, BoundingBox bounds, long seed) {
        public Start {
            tag = tag.copy();
            bounds = copyBounds(bounds);
        }

        @Override public CompoundTag tag() {
            return tag.copy();
        }

        @Override public BoundingBox bounds() {
            return copyBounds(bounds);
        }

        public StructureStart copy(ServerLevel level) {
            return java.util.Objects.requireNonNull(StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level), tag.copy(), seed));
        }
    }
}
