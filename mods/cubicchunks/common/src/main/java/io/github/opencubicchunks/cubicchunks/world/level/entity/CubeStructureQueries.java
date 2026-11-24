package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Gameplay queries read cube metadata, resolving distant deep owner graphs without loading their terrain. */
public final class CubeStructureQueries {
    private CubeStructureQueries() {}

    public static boolean handles(LevelAccessor level, int y) {
        return level instanceof ServerLevel server && ((CanBeCubic) server).cc_isCubic() && y < server.getMinY();
    }

    public static StructureStart at(ServerLevel level, BlockPos pos, Predicate<Structure> filter, boolean requirePiece) {
        for (StructureStart start : CubeStructureSpawns.starts(level, pos, filter).orElse(List.of())) {
            if (requirePiece ? CubeStructureSpawns.hasPiece(start, pos) : start.getBoundingBox().isInside(pos)) {
                return start;
            }
        }
        return StructureStart.INVALID_START;
    }

    public static Predicate<Structure> matching(ServerLevel level, Predicate<Holder<Structure>> filter) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        return structure -> registry.get(registry.getId(structure)).map(filter::test).orElse(false);
    }

    /** Values are full CubePos owner keys, including Y. As in vanilla, this reports references rather than geometry. */
    public static Map<Structure, LongSet> references(ServerLevel level, BlockPos pos) {
        var cube = CubeMobBlockView.loadedCube(level, pos);
        if (cube == null) {
            return Map.of();
        }
        Map<Structure, LongSet> result = new LinkedHashMap<>();
        cube.getAllReferences().forEach((type, refs) -> result.put(type, LongSets.unmodifiable(new LongOpenHashSet(refs))));
        return Map.copyOf(result);
    }
}
