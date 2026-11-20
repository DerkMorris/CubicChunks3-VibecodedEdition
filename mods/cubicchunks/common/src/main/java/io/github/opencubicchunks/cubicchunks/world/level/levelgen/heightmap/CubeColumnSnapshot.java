package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Complete native surface band plus authoritative sparse overrides. Unobserved deep terrain remains unknown. */
public record CubeColumnSnapshot(
        int cubeX, int cubeZ, int minGeneratedY, int maxGeneratedY, NavigableMap<Integer, CubeColumnBlocks> cubes, Set<Integer> unknown
) {
    public CubeColumnSnapshot {
        cubes = Collections.unmodifiableNavigableMap(new TreeMap<>(cubes));
        unknown = Set.copyOf(unknown);
    }

    public @Nullable BlockState state(int x, int y, int z) {
        if (!CubicBlockBounds.contains(x) || !CubicBlockBounds.contains(y) || !CubicBlockBounds.contains(z) || Coords.blockToCube(x) != cubeX
                || Coords.blockToCube(z) != cubeZ) {
            return null;
        }
        int cy = Coords.blockToCube(y);
        if (unknown.contains(cy)) {
            return null;
        }
        CubeColumnBlocks cube = cubes.get(cy);
        if (cube != null) {
            return cube.state(x, y, z);
        }
        // NativeBiomeTerrain.fill explicitly generates AIR above the native dimension's band.
        return y > maxGeneratedY ? Blocks.AIR.defaultBlockState() : null;
    }

    /** First free block above the highest matching state; empty means unknown, empty column, or world ceiling. */
    public OptionalInt firstAvailable(Heightmap.Types type, int x, int z) {
        int upper = CubePos.MAX_COORDINATE_VALUE;
        for (var entry : cubes.descendingMap().entrySet()) {
            int cy = entry.getKey();
            for (int unresolved : unknown) {
                if (unresolved >= cy && unresolved <= upper) {
                    return OptionalInt.empty();
                }
            }
            // Omitted air above the generated band is known. A missing generated/deep interval is not.
            if (upper > cy && Coords.cubeToMinBlock(cy + 1) <= maxGeneratedY) {
                return OptionalInt.empty();
            }
            var cube = entry.getValue();
            for (int y = cube.pos().maxCubeY(); y >= cube.pos().minCubeY(); y--) {
                if (type.isOpaque().test(cube.state(x, y, z))) {
                    return y < CubicBlockBounds.MAX ? OptionalInt.of(y + 1) : OptionalInt.empty();
                }
            }
            upper = cy - 1;
        }
        return OptionalInt.empty();
    }
}
