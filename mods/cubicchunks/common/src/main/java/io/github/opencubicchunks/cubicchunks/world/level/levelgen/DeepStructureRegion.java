package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Detached native placement area. Only sections actually touched by a piece or feature copy their base terrain. */
public class DeepStructureRegion extends WorldGenRegion {
    private static final int READ_BORDER = 1;
    private final State state;
    private final Map<Entity, UUID> entityIds = new IdentityHashMap<>();
    private int nextEntity;

    DeepStructureRegion(ServerLevel level, BoundingBox writeBounds, int bandMinY, int bandHeight, long seed, BiomeResolver biomes,
            SectionProvider base) {
        this(level, new State(level, writeBounds, bandMinY, bandHeight, seed, biomes, base));
    }

    private DeepStructureRegion(ServerLevel level, State state) {
        super(level, state.holders, state.step, state.center);
        this.state = state;
    }

    @Override public int getMinY() {
        return state == null ? super.getMinY() : state.bounds.getMinY();
    }

    @Override public int getHeight() {
        return state == null ? super.getHeight() : state.bounds.getHeight();
    }

    @Override public long getSeed() {
        return state == null ? super.getSeed() : state.seed;
    }

    @Override public RandomSource getRandom() {
        return state == null ? super.getRandom() : state.random;
    }

    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return state == null ? super.getUncachedNoiseBiome(x, y, z) : state.biomes.getNoiseBiome(x, y, z);
    }

    @Override public boolean isOldChunkAround(ChunkPos pos, int radius) {
        return false;
    }

    @Override public boolean ensureCanWrite(BlockPos pos) {
        return state.writeBounds.isInside(pos) && state.bounds.isInsideBuildHeight(pos);
    }

    @Override public boolean addFreshEntity(Entity entity) {
        if (!ensureCanWrite(entity.blockPosition())) {
            return false;
        }
        entity.getSelfAndPassengers().forEach(value -> value.setUUID(entityIds.computeIfAbsent(value, ignored -> {
            long index = nextEntity++;
            return new UUID(mix(state.entitySeed, index), mix(~state.entitySeed, index));
        })));
        return super.addFreshEntity(entity);
    }

    boolean placeFeature(Feature feature, BlockPos origin, long seed) {
        return feature.place(this, getLevel().getChunkSource().getGenerator(), RandomSource.create(seed), origin);
    }

    DeepStructureResult finish() {
        return DeepStructureResult.capture(getLevel(), state.chunks.values());
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Stable UUID words from the placement identity and entity's canonical generation order.
    private static long mix(long seed, long index) {
        long value = seed + index * 0x9E3779B97F4A7C15L;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    @FunctionalInterface
    interface SectionProvider {
        LevelChunkSection section(int sx, int sy, int sz);
    }

    private static final class State {
        private final LevelHeightAccessor bounds;
        private final BoundingBox writeBounds;
        private final long seed;
        private final long entitySeed;
        private final RandomSource random;
        private final BiomeResolver biomes;
        private final Map<ChunkPos, StagingChunk> chunks = new HashMap<>();
        private final StaticCache2D<GenerationChunkHolder> holders;
        private final ChunkStep step;
        private final StagingChunk center;

        private State(ServerLevel level, BoundingBox writeBounds, int minY, int height, long seed, BiomeResolver biomes, SectionProvider base) {
            if (Math.floorMod(minY, SectionPos.SECTION_SIZE) != 0 || height <= 0 || Math.floorMod(height, SectionPos.SECTION_SIZE) != 0) {
                throw new IllegalArgumentException("Detached structure height must be a positive, section-aligned band");
            }
            bounds = LevelHeightAccessor.create(minY, height);
            this.writeBounds = new BoundingBox(writeBounds.minX(), writeBounds.minY(), writeBounds.minZ(), writeBounds.maxX(), writeBounds.maxY(),
                    writeBounds.maxZ());
            this.seed = seed;
            entitySeed = mix(mix(mix(seed, writeBounds.minX()), writeBounds.minY()), writeBounds.minZ());
            random = RandomSource.create(seed);
            this.biomes = biomes;
            var origin = ChunkPos.containing(writeBounds.getCenter());
            int radius = Math.max(Math.max(origin.x() - SectionPos.blockToSectionCoord(writeBounds.minX()),
                    SectionPos.blockToSectionCoord(writeBounds.maxX()) - origin.x()),
                    Math.max(origin.z() - SectionPos.blockToSectionCoord(writeBounds.minZ()),
                            SectionPos.blockToSectionCoord(writeBounds.maxZ()) - origin.z())) + READ_BORDER;
            var requirements = ImmutableList.<ChunkStatus>builder();
            for (int i = 0; i <= radius; i++) {
                requirements.add(ChunkStatus.TERRAIN);
            }
            var dependencies = new ChunkDependencies(requirements.build());
            step = new ChunkStep(ChunkStatus.FEATURES, dependencies, dependencies, radius,
                    ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).task());
            holders = StaticCache2D.create(origin.x(), origin.z(), radius, (x, z) -> {
                var pos = new ChunkPos(x, z);
                var chunk = new StagingChunk(level, pos, bounds, biomes, base);
                chunks.put(pos, chunk);
                return new HolderChunk(chunk);
            });
            center = chunks.get(origin);
        }
    }

    static final class StagingChunk extends ProtoChunk {
        private final SectionProvider base;
        private final BiomeResolver biomes;
        private final boolean[] initialized;
        final Map<BlockPos, BlockState> writes = new HashMap<>();

        private StagingChunk(ServerLevel level, ChunkPos pos, LevelHeightAccessor bounds, BiomeResolver biomes, SectionProvider base) {
            super(pos, UpgradeData.EMPTY, bounds, level.palettedContainerFactory(), null);
            this.base = base;
            this.biomes = biomes;
            initialized = new boolean[getSectionsCount()];
            setPersistedStatus(ChunkStatus.TERRAIN);
        }

        @Override public LevelChunkSection getSection(int index) {
            if (!initialized[index]) {
                sections[index] = base.section(getPos().x(), getSectionYFromSectionIndex(index), getPos().z()).copy();
                initialized[index] = true;
            }
            return sections[index];
        }

        @Override public LevelChunkSection[] getSections() {
            if (initialized != null) {
                for (int index = 0; index < sections.length; index++) {
                    getSection(index);
                }
            }
            return sections;
        }

        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return biomes.getNoiseBiome(x, y, z);
        }

        @Override public BlockState setBlockState(BlockPos pos, BlockState value, int flags) {
            if (isOutsideBuildHeight(pos)) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            var previous = getSection(getSectionIndex(pos.getY())).setBlockState(SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), value, false);
            writes.put(pos.immutable(), value);
            return previous;
        }

        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            var pos = new BlockPos.MutableBlockPos(getPos().getMinBlockX() + x, getMaxY(), getPos().getMinBlockZ() + z);
            for (int y = getMaxY(); y >= getMinY(); y--) {
                if (type.isOpaque().test(getBlockState(pos.setY(y)))) {
                    return y;
                }
            }
            return getMinY() - 1;
        }
    }

    private static final class HolderChunk extends GenerationChunkHolder {
        private final ProtoChunk chunk;

        private HolderChunk(ProtoChunk chunk) {
            super(chunk.getPos());
            this.chunk = chunk;
        }

        @Override public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk;
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
