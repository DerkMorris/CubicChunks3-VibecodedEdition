package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Complete sky-source index for the current hills generator, with saved and live cubes overriding generated terrain. */
public final class CubeWorldSkyColumn {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private final int[] edges = new int[DIAMETER * DIAMETER];

    public CubeWorldSkyColumn(long seed, int cubeX, int cubeZ, Map<Integer, CubeSkyOcclusion> overrides) {
        this((x, z) -> CubeTerrainGenerator.surface(seed, x, z), cubeX, cubeZ, overrides);
    }

    CubeWorldSkyColumn(IntBinaryOperator floor, int cubeX, int cubeZ, Map<Integer, CubeSkyOcclusion> overrides) {
        this(floor, cubeX, cubeZ, overrides, null);
    }

    CubeWorldSkyColumn(
            IntBinaryOperator floor, int cubeX, int cubeZ, Map<Integer, CubeSkyOcclusion> overrides, @Nullable IntFunction<CubeSkyOcclusion> deep
    ) {
        var resolved = deep == null ? overrides : new HashMap<>(overrides);
        Set<Integer> candidates = new TreeSet<>();
        overrides.keySet().forEach(y -> addNeighbors(candidates, y));
        int[] heights = new int[DIAMETER * DIAMETER];
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                int height = floor.applyAsInt(Coords.cubeToMinBlock(cubeX) + x, Coords.cubeToMinBlock(cubeZ) + z);
                heights[x + DIAMETER * z] = height;
                addNeighbors(candidates, Coords.blockToCube(height));
            }
        }
        int[] descending = candidates.stream().sorted(java.util.Comparator.reverseOrder()).mapToInt(Integer::intValue).toArray();
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                edges[x + DIAMETER * z] = highest(heights[x + DIAMETER * z], x, z, resolved, descending, deep);
            }
        }
    }

    public int edge(int x, int z) {
        return edges[Math.floorMod(x, DIAMETER) + DIAMETER * Math.floorMod(z, DIAMETER)];
    }

    private static void addNeighbors(Set<Integer> candidates, int y) {
        candidates.add(y);
        if (y > -CubePos.MAX_COORDINATE_VALUE - 1) {
            candidates.add(y - 1);
        }
        if (y < CubePos.MAX_COORDINATE_VALUE) {
            candidates.add(y + 1);
        }
    }

    private static int highest(
            int height, int x, int z, Map<Integer, CubeSkyOcclusion> overrides, int[] candidates, @Nullable IntFunction<CubeSkyOcclusion> deep
    ) {
        for (int y : candidates) {
            if (deep != null && y <= Coords.blockToCube(height)) {
                return deepEdge(height, x, z, y, overrides, deep);
            }
            CubeSkyOcclusion cube = overrides.get(y);
            int min = Coords.cubeToMinBlock(y);
            int top = min + DIAMETER - 1;
            BlockState upper = face(height, x, z, y + 1, false, overrides);
            BlockState lower = face(height, x, z, y, true, overrides);
            if (CubeSkyOcclusion.isEdgeOccluded(upper, lower)) {
                return top + 1;
            }
            if (cube != null) {
                var edge = cube.highestInternalEdgeY(x, z);
                if (edge.isPresent()) {
                    return edge.getAsInt();
                }
            } else {
                int solid = Math.min(height, top - 1);
                if (solid >= min) {
                    return solid + 1;
                }
            }
        }
        return CubicBlockBounds.MIN;
    }

    /** Only exposed shafts reach this path. Stop at the first obstruction, including in not-yet-loaded caves. */
    private static int deepEdge(int height, int x, int z, int start, Map<Integer, CubeSkyOcclusion> resolved, IntFunction<CubeSkyOcclusion> deep) {
        BlockState upper = face(height, x, z, start + 1, false, resolved);
        for (int y = start; y >= -CubePos.MAX_COORDINATE_VALUE - 1; y--) {
            var cube = resolved.computeIfAbsent(y, deep::apply);
            if (CubeSkyOcclusion.isEdgeOccluded(upper, cube.topState(x, z))) {
                return Coords.cubeToMinBlock(y) + DIAMETER;
            }
            var edge = cube.highestInternalEdgeY(x, z);
            if (edge.isPresent()) {
                return edge.getAsInt();
            }
            upper = cube.bottomState(x, z);
        }
        return CubicBlockBounds.MIN;
    }

    private static BlockState face(int height, int x, int z, int y, boolean top, Map<Integer, CubeSkyOcclusion> overrides) {
        if (y > CubePos.MAX_COORDINATE_VALUE) {
            return Blocks.AIR.defaultBlockState();
        }
        CubeSkyOcclusion cube = overrides.get(y);
        if (cube == null) {
            return CubeTerrainGenerator.stateAtHeight(height, Coords.cubeToMinBlock(y) + (top ? DIAMETER - 1 : 0));
        }
        int lx = Math.floorMod(x, DIAMETER);
        int lz = Math.floorMod(z, DIAMETER);
        return top ? cube.topState(lx, lz) : cube.bottomState(lx, lz);
    }
}
