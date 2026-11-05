package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import io.github.opencubicchunks.cc_core.api.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;

/** Loaded cube references use CubePos keys, never vanilla ChunkPos or packed block Y. Missing starts defer spawning. */
public final class CubeStructureSpawns {
    private static final int MAX_REFERENCES = 1024;

    private CubeStructureSpawns() {}

    public static Optional<List<StructureStart>> starts(ServerLevel level, BlockPos pos, Predicate<Structure> filter) {
        var cube = CubeMobBlockView.loadedCube(level, pos);
        if (cube == null) {
            return Optional.empty();
        }
        Set<Structure> types = new LinkedHashSet<>(cube.getAllReferences().keySet());
        types.addAll(cube.getAllStarts().keySet());
        Set<StructureStart> result = new LinkedHashSet<>();
        int remaining = MAX_REFERENCES;
        for (Structure type : types) {
            if (!filter.test(type)) {
                continue;
            }
            var local = cube.getStartForStructure(type);
            if (local != null && local.isValid()) {
                result.add(local);
            }
            var references = cube.getReferencesForStructure(type);
            remaining -= references.size();
            if (remaining < 0 || !resolve(level, type, references, result)) {
                return Optional.empty();
            }
        }
        if (!collectColumnStarts(level, pos, filter, remaining, result)) {
            return Optional.empty();
        }
        return Optional.of(new ArrayList<>(result));
    }

    /** Native structures keep their 16-by-16 origin keys in the supporting columns. Spawn queries never request terrain. */
    private static boolean collectColumnStarts(ServerLevel level, BlockPos pos, Predicate<Structure> filter, int budget, Set<StructureStart> result) {
        if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
            return true;
        }
        var source = level.getChunkSource();
        var column = source.getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (column == null) {
            return true;
        }
        int remaining = budget;
        for (var entry : column.getAllReferences().entrySet()) {
            if (!filter.test(entry.getKey())) {
                continue;
            }
            remaining -= entry.getValue().size();
            if (remaining < 0 || !resolveColumns(source, entry.getKey(), entry.getValue(), result)) {
                return false;
            }
        }
        return true;
    }

    private static boolean resolveColumns(ServerChunkCache source, Structure type, Iterable<Long> references, Set<StructureStart> result) {
        for (long key : references) {
            var owner = source.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            var start = owner == null ? null : owner.getStartForStructure(type);
            if (start == null) {
                return false;
            }
            if (start.isValid()) {
                result.add(start);
            }
        }
        return true;
    }

    private static boolean resolve(ServerLevel level, Structure type, Iterable<Long> references, Set<StructureStart> result) {
        for (long key : references) {
            CubePos pos = CubePos.from(key);
            var owner = CubeMobBlockView.loadedCube(level, pos.asBlockPos());
            if (owner == null) {
                return false;
            }
            var start = owner.getStartForStructure(type);
            if (start == null) {
                return false;
            }
            if (start.isValid()) {
                result.add(start);
            }
        }
        return true;
    }

    public static boolean hasPiece(StructureStart start, BlockPos pos) {
        return start.getPieces().stream().anyMatch(piece -> piece.getBoundingBox().isInside(pos));
    }

    public static Optional<WeightedList<MobSpawnSettings.SpawnerData>> mobsAt(ServerLevel level, MobCategory category, BlockPos pos) {
        var fortress = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.FORTRESS);
        var starts = starts(level, pos, type -> type.spawnOverrides().containsKey(category) || category == MobCategory.MONSTER && type == fortress);
        if (starts.isEmpty()) {
            return Optional.empty();
        }
        if (fortressAt(level, category, fortress, starts.get(), pos)) {
            return Optional.of(NetherFortressStructure.FORTRESS_ENEMIES);
        }
        for (StructureStart start : starts.get()) {
            var override = start.getStructure().spawnOverrides().get(category);
            if (override != null && contains(override, start, pos)) {
                return Optional.of(override.spawns());
            }
        }
        return Optional.of(level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos).getMobsToSpawn(category));
    }

    private static boolean fortressAt(ServerLevel level, MobCategory category, Structure fortress, List<StructureStart> starts, BlockPos pos) {
        return category == MobCategory.MONSTER && level.getBlockState(pos.below()).is(Blocks.NETHER_BRICKS)
                && starts.stream().anyMatch(start -> start.getStructure() == fortress && start.getBoundingBox().isInside(pos));
    }

    private static boolean contains(StructureSpawnOverride override, StructureStart start, BlockPos pos) {
        return override.boundingBox() == StructureSpawnOverride.BoundingBoxType.PIECE ? hasPiece(start, pos) : start.getBoundingBox().isInside(pos);
    }
}
