package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** One immutable decorated terrain source per world for new cubes and the lighting engine's missing neighbors. */
public final class NativeBiomeTerrain {
    private static final int TERRAIN_COLUMNS = 256;
    // A radius-eight cube view uses 81 tiles; its generation halo can use 121. Results accumulate lazily.
    private static final int CACHE_SIZE = 128;
    // Each tile waits for terrain on Minecraft's background pool. Bound concurrent tile memory while keeping that pool supplied.
    private static final int WORKERS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);
    private static final Executor WORKER = Executors.newFixedThreadPool(WORKERS, Thread.ofPlatform().daemon().name("CC biome generation ", 0).factory());
    private final LoadingCache<ChunkPos, UndecoratedColumn> biomes;
    private final Cache<ChunkPos, UndecoratedColumn> terrain = CacheBuilder.newBuilder().maximumSize(TERRAIN_COLUMNS).build();
    private final ServerLevel level;
    private final NativeStructureLayout structures;
    private final @Nullable DeepCaveTerrain deep;
    private final @Nullable DeepCaveBiomes deepBiomes;
    private final @Nullable DeepslateStrata strata;
    private final Executor generationExecutor;
    private final Map<ChunkPos, CompletableFuture<Map<ChunkPos, NativeBiomeColumn>>> tiles = new LinkedHashMap<>(16, .75f, true);
    private final @Nullable NativeTerrainDiagnostics diagnostics = NativeTerrainDiagnostics.createIfEnabled();

    public NativeBiomeTerrain(ServerLevel level) {
        this(level, WORKER);
    }

    public NativeBiomeTerrain(ServerLevel level, Executor generationExecutor) {
        this.level = level;
        structures = new NativeStructureLayout(level);
        var settings = level.getLevelData() instanceof CubicWorldData data ? data.cc_deepCaveSettings() : DeepCaveSettings.CLASSIC;
        strata = level.dimension() == Level.OVERWORLD ? new DeepslateStrata(level.getSeed(), settings.heights().deepslateStartY()) : null;
        var source = level.getChunkSource();
        deepBiomes = settings.enabled() && level.dimension() == Level.OVERWORLD
                ? new DeepCaveBiomes(level.getSeed(), level.getMinY(), source.getGenerator().getBiomeSource(), source.randomState()) : null;
        boolean variedRock = strata != null && (strata.custom() || level.getMinY() > DeepslateStrata.VANILLA_START - DeepslateStrata.TRANSITION);
        deep = settings.enabled() || !settings.oreRules().isEmpty() || variedRock
                ? new DeepCaveTerrain(level.getSeed(), settings, level.palettedContainerFactory(), strata, deepBiomes, level.getMinY()) : null;
        this.generationExecutor = generationExecutor;
        biomes = CacheBuilder.newBuilder().maximumSize(TERRAIN_COLUMNS).build(CacheLoader.from(this::generateBiomes));
    }

    private UndecoratedColumn generateBiomes(ChunkPos pos) {
        var source = level.getChunkSource();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(level.getMinY(), level.getHeight()),
                level.palettedContainerFactory(), null);
        fillNativeBiomes(chunk, source.getGenerator().getBiomeSource(), source.randomState());
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        return new UndecoratedColumn(level, chunk);
    }

    /** Sample bounded climate volumes with vanilla's pooled context, retaining the original chunk-fill biome query order. */
    static void fillNativeBiomes(ProtoChunk chunk, BiomeSource biomeSource, RandomState randomState) {
        var buffers = randomState.acquireDensityBufferPool();
        try {
            var context = SamplerContext.builder().enableCaches().useBufferArena(buffers).build();
            var sampler = randomState.createClimateSampler(context);
            var pos = chunk.getPos();
            var resolver = biomeSource.createResolverForChunk(sampler, QuartPos.fromBlock(pos.getMinBlockX()), QuartPos.fromBlock(chunk.getMinY()),
                    QuartPos.fromBlock(pos.getMinBlockZ()), QuartPos.fromBlock(SectionPos.SECTION_SIZE), QuartPos.fromBlock(chunk.getHeight()),
                    QuartPos.fromBlock(SectionPos.SECTION_SIZE));
            chunk.fillBiomesFromNoise(resolver);
        } finally {
            randomState.releaseDensityBufferPool(buffers);
        }
    }

    public NativeStructureLayout structures() {
        return structures;
    }

    /** Shared by the BIOMES stage, deep decoration and unloaded server biome queries. */
    public BiomeResolver biomeResolver() {
        if (deepBiomes != null) {
            return deepBiomes;
        }
        var source = level.getChunkSource();
        return source.getGenerator().getBiomeSource().createUncachedResolver(source.randomState());
    }

    public int baseFloor() {
        return level.getMinY() - 1;
    }

    private synchronized CompletableFuture<Map<ChunkPos, NativeBiomeColumn>> tile(CubePos pos) {
        var key = new ChunkPos(Math.floorDiv(Coords.cubeToSection(pos.getX(), 0), BiomeGenerationTile.WIDTH),
                Math.floorDiv(Coords.cubeToSection(pos.getZ(), 0), BiomeGenerationTile.WIDTH));
        var cached = tiles.get(key);
        if (diagnostics != null) {
            diagnostics.requested(cached != null);
        }
        if (cached != null) {
            return cached;
        }
        if (diagnostics != null) {
            diagnostics.created(key);
        }
        var future = CompletableFuture.supplyAsync(() -> {
            if (diagnostics != null) {
                diagnostics.started();
            }
            return new BiomeGenerationTile(level, key, biomes, terrain, structures, deep, strata).generate();
        }, generationExecutor);
        tiles.put(key, future);
        future.whenComplete((result, error) -> finishTile(key, future, error));
        return future;
    }

    private synchronized void finishTile(ChunkPos key, CompletableFuture<?> future, Throwable failure) {
        if (diagnostics != null) {
            diagnostics.finished(failure != null);
        }
        if (failure != null) {
            tiles.remove(key, future);
        }
        boolean pendingAboveLimit = diagnostics != null && tiles.values().stream().filter(value -> !value.isDone()).count() > CACHE_SIZE;
        // Pending shared futures are not retained results. Counting them would evict every newly completed tile while the queue is long.
        int completed = (int) tiles.values().stream().filter(CompletableFuture::isDone).count();
        var iterator = tiles.values().iterator();
        while (completed > CACHE_SIZE && iterator.hasNext()) {
            var candidate = iterator.next();
            if (candidate.isDone()) {
                iterator.remove();
                completed--;
                if (diagnostics != null) {
                    diagnostics.evicted(candidate == future, pendingAboveLimit);
                }
            }
        }
    }

    /** Called reflectively by the optional development probe, never by normal gameplay. */
    synchronized @Nullable Map<String, Object> diagnosticsSnapshot() {
        if (diagnostics == null) {
            return null;
        }
        int pending = (int) tiles.values().stream().filter(value -> !value.isDone()).count();
        return diagnostics.snapshot(pending, tiles.size() - pending);
    }

    public CompletableFuture<CubeAccess> fill(CubeAccess cube) {
        var pos = cube.cc_getCubePos();
        if (pos.minCubeY() > level.getMaxY()) {
            return CompletableFuture.completedFuture(cube);
        }
        if (pos.maxCubeY() < level.getMinY()) {
            if (deep != null) {
                return CompletableFuture.supplyAsync(() -> {
                    cube.cc_invalidateHeightmaps();
                    for (int i = 0; i < cube.getSections().length; i++) {
                        fillBelowFloor(cube, i);
                    }
                    cube.markUnsaved();
                    return cube;
                }, net.minecraft.util.Util.backgroundExecutor());
            }
            cube.cc_invalidateHeightmaps();
            for (LevelChunkSection section : cube.getSections()) {
                fillRock(section, belowFloorRock());
            }
            cube.markUnsaved();
            return CompletableFuture.completedFuture(cube);
        }
        return tile(pos).thenApply(columns -> {
            cube.cc_invalidateHeightmaps();
            for (int i = 0; i < cube.getSections().length; i++) {
                NativeBiomeColumn column = column(columns, pos, i);
                int sy = Coords.cubeToSection(pos.getY(), Coords.indexToY(i));
                if (sy >= level.getMinSectionY() && sy <= level.getMaxSectionY()) {
                    cube.getSections()[i] = column.section(sy).copy();
                    column.copyExtras(cube, sy, i);
                } else if (sy < level.getMinSectionY()) {
                    fillBelowFloor(cube, i);
                }
            }
            cube.markUnsaved();
            return cube;
        });
    }

    private void fillBelowFloor(CubeAccess cube, int i) {
        if (deep == null) {
            fillRock(cube.getSections()[i], belowFloorRock());
        } else {
            var generated = deepSection(cube.cc_getCubePos(), i);
            cube.getSections()[i] = deepBiomes == null
                    ? new LevelChunkSection(generated.getStates().copy(), cube.getSections()[i].getBiomes()) : generated.copy();
            copyDeepBlockEntities(cube, i);
        }
    }

    private static void copyDeepBlockEntities(CubeAccess cube, int i) {
        var section = cube.getSections()[i];
        if (!section.maybeHas(state -> state.hasBlockEntity())) {
            return;
        }
        var pos = cube.cc_getCubePos();
        int x0 = SectionPos.sectionToBlockCoord(Coords.cubeToSection(pos.getX(), Coords.indexToX(i)));
        int y0 = SectionPos.sectionToBlockCoord(Coords.cubeToSection(pos.getY(), Coords.indexToY(i)));
        int z0 = SectionPos.sectionToBlockCoord(Coords.cubeToSection(pos.getZ(), Coords.indexToZ(i)));
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    var state = section.getBlockState(x, y, z);
                    if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock block) {
                        var entity = block.newBlockEntity(new BlockPos(x0 + x, y0 + y, z0 + z), state);
                        if (entity != null) {
                            cube.setBlockEntity(entity);
                        }
                    }
                }
            }
        }
    }

    private LevelChunkSection deepSection(CubePos pos, int i) {
        return deep.section(Coords.cubeToSection(pos.getX(), Coords.indexToX(i)), Coords.cubeToSection(pos.getY(), Coords.indexToY(i)),
                Coords.cubeToSection(pos.getZ(), Coords.indexToZ(i)));
    }

    private LevelChunkSection section(NativeBiomeColumn column, int sy) {
        if (sy >= level.getMinSectionY() && sy <= level.getMaxSectionY()) {
            return column.section(sy);
        }
        var empty = new LevelChunkSection(level.palettedContainerFactory());
        if (sy < level.getMinSectionY()) {
            fillRock(empty, belowFloorRock());
        }
        return empty;
    }

    static void fillStone(LevelChunkSection section) {
        fillRock(section, Blocks.STONE);
    }

    private Block belowFloorRock() {
        return strata == null ? Blocks.STONE : Blocks.DEEPSLATE;
    }

    private static void fillRock(LevelChunkSection section, Block block) {
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    section.setBlockState(x, y, z, block.defaultBlockState(), false);
                }
            }
        }
    }

    private static NativeBiomeColumn column(Map<ChunkPos, NativeBiomeColumn> columns, CubePos pos, int i) {
        return columns.get(new ChunkPos(Coords.cubeToSection(pos.getX(), Coords.indexToX(i)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(i))));
    }

    private LevelChunkSection[] sections(Map<ChunkPos, NativeBiomeColumn> columns, CubePos pos) {
        var sections = new LevelChunkSection[CubicConstants.SECTION_COUNT];
        for (int i = 0; i < sections.length; i++) {
            int sy = Coords.cubeToSection(pos.getY(), Coords.indexToY(i));
            sections[i] = deep != null && sy < level.getMinSectionY() ? deepSection(pos, i) : section(column(columns, pos, i), sy);
        }
        return sections;
    }

    private LevelChunkSection[] deepSections(CubePos pos) {
        var sections = new LevelChunkSection[CubicConstants.SECTION_COUNT];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = deepSection(pos, i);
        }
        return sections;
    }

    public @Nullable IntFunction<CubeSkyOcclusion> deepSky(CubePos column) {
        if (deep == null) {
            return null;
        }
        return y -> {
            var pos = CubePos.of(column.getX(), y, column.getZ());
            return CubeSkyOcclusion.capture(pos, deepSections(pos));
        };
    }

    public CompletableFuture<CubeLightBlockSnapshot> blocks(CubePos pos) {
        if (deep != null && pos.maxCubeY() < level.getMinY()) {
            return CompletableFuture.supplyAsync(() -> CubeLightBlockSnapshot.capture(pos, deepSections(pos)),
                    net.minecraft.util.Util.backgroundExecutor());
        }
        if (pos.maxCubeY() < level.getMinY() || pos.minCubeY() > level.getMaxY()) {
            var state = pos.maxCubeY() < level.getMinY() ? belowFloorRock().defaultBlockState() : Blocks.AIR.defaultBlockState();
            return CompletableFuture.completedFuture(CubeLightBlockSnapshot.uniform(pos, state));
        }
        return tile(pos).thenApply(columns -> CubeLightBlockSnapshot.capture(pos, sections(columns, pos)));
    }

    public CompletableFuture<Map<Integer, CubeSkyOcclusion>> sky(CubePos column) {
        return tile(column).thenApplyAsync(columns -> {
            Map<Integer, CubeSkyOcclusion> result = new HashMap<>();
            for (int y = Coords.blockToCube(level.getMinY()); y <= Coords.blockToCube(level.getMaxY()); y++) {
                CubePos pos = CubePos.of(column.getX(), y, column.getZ());
                result.put(y, CubeSkyOcclusion.capture(pos, sections(columns, pos)));
            }
            return result;
        }, net.minecraft.util.Util.backgroundExecutor());
    }

    /** Detached paletted native surface band for sparse gameplay column queries. No world cubes are loaded. */
    public CompletableFuture<Map<Integer, io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks>> columnBlocks(
            CubePos column
    ) {
        return tile(column).thenApplyAsync(columns -> {
            Map<Integer, io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks> result = new HashMap<>();
            for (int y = Coords.blockToCube(level.getMinY()); y <= Coords.blockToCube(level.getMaxY()); y++) {
                CubePos pos = CubePos.of(column.getX(), y, column.getZ());
                result.put(y, new io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks(pos, sections(columns, pos)));
            }
            return result;
        }, net.minecraft.util.Util.backgroundExecutor());
    }
}
