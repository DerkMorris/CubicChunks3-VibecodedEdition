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
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** One immutable decorated terrain source per world for new cubes and the lighting engine's missing neighbors. */
public final class NativeBiomeTerrain {
    private static final int TERRAIN_COLUMNS = 256;
    private static final int CACHE_SIZE = 32;
    // Native terrain can itself dispatch work to Minecraft's background pool.
    private static final Executor WORKER = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("CC biome generation ", 0).factory());
    private final LoadingCache<ChunkPos, UndecoratedColumn> biomes;
    private final Cache<ChunkPos, UndecoratedColumn> terrain = CacheBuilder.newBuilder().maximumSize(TERRAIN_COLUMNS).build();
    private final ServerLevel level;
    private final NativeStructureLayout structures;
    private final @Nullable DeepCaveTerrain deep;
    private final Executor generationExecutor;
    private final Map<ChunkPos, CompletableFuture<Map<ChunkPos, NativeBiomeColumn>>> tiles = new LinkedHashMap<>(16, .75f, true);

    public NativeBiomeTerrain(ServerLevel level) {
        this(level, WORKER);
    }

    public NativeBiomeTerrain(ServerLevel level, Executor generationExecutor) {
        this.level = level;
        structures = new NativeStructureLayout(level);
        var settings = level.getLevelData() instanceof CubicWorldData data ? data.cc_deepCaveSettings() : DeepCaveSettings.CLASSIC;
        deep = settings.enabled() || !settings.oreRules().isEmpty() ? new DeepCaveTerrain(level.getSeed(), settings, level.palettedContainerFactory())
                : null;
        this.generationExecutor = generationExecutor;
        biomes = CacheBuilder.newBuilder().maximumSize(TERRAIN_COLUMNS).build(CacheLoader.from(this::generateBiomes));
    }

    private UndecoratedColumn generateBiomes(ChunkPos pos) {
        var source = level.getChunkSource();
        var resolver = source.getGenerator().getBiomeSource().createUncachedResolver(source.randomState());
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(level.getMinY(), level.getHeight()),
                level.palettedContainerFactory(), null);
        chunk.fillBiomesFromNoise(resolver);
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        return new UndecoratedColumn(level, chunk);
    }

    public NativeStructureLayout structures() {
        return structures;
    }

    public int baseFloor() {
        return level.getMinY() - 1;
    }

    private synchronized CompletableFuture<Map<ChunkPos, NativeBiomeColumn>> tile(CubePos pos) {
        var key = new ChunkPos(Math.floorDiv(Coords.cubeToSection(pos.getX(), 0), BiomeGenerationTile.WIDTH),
                Math.floorDiv(Coords.cubeToSection(pos.getZ(), 0), BiomeGenerationTile.WIDTH));
        var cached = tiles.get(key);
        if (cached != null) {
            return cached;
        }
        var future = CompletableFuture.supplyAsync(() -> new BiomeGenerationTile(level, key, biomes, terrain, structures, deep).generate(),
                generationExecutor);
        tiles.put(key, future);
        future.whenComplete((result, error) -> finishTile(key, future, error));
        return future;
    }

    private synchronized void finishTile(ChunkPos key, CompletableFuture<?> future, Throwable failure) {
        if (failure != null) {
            tiles.remove(key, future);
        }
        var iterator = tiles.values().iterator();
        while (tiles.size() > CACHE_SIZE && iterator.hasNext()) {
            if (iterator.next().isDone()) {
                iterator.remove();
            }
        }
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
                fillStone(section);
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
            fillStone(cube.getSections()[i]);
        } else {
            var generated = deepSection(cube.cc_getCubePos(), i);
            cube.getSections()[i] = new LevelChunkSection(generated.getStates().copy(), cube.getSections()[i].getBiomes());
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
            fillStone(empty);
        }
        return empty;
    }

    static void fillStone(LevelChunkSection section) {
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState(), false);
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
            var state = pos.maxCubeY() < level.getMinY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
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
}
