package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.core.BlockPos;

/** Inclusive cube bounds for one bounded lighting calculation. Limits are checked before any voxel allocation. */
public record CubeLightBounds(CubePos min, CubePos max) {
    public static final int MAX_CUBES = 27;
    public static final int MAX_CELLS = 1_048_576;
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;

    public CubeLightBounds {
        validatePosition(Objects.requireNonNull(min, "min"));
        validatePosition(Objects.requireNonNull(max, "max"));
        long x = (long) max.getX() - min.getX() + 1;
        long y = (long) max.getY() - min.getY() + 1;
        long z = (long) max.getZ() - min.getZ() + 1;
        if (x <= 0 || y <= 0 || z <= 0) {
            throw new IllegalArgumentException("Lighting cube bounds are reversed");
        }
        if (x > MAX_CUBES || y > MAX_CUBES || z > MAX_CUBES || x * y * z > MAX_CUBES) {
            throw new IllegalArgumentException("Lighting domain exceeds " + MAX_CUBES + " cubes");
        }
        if (x * y * z * DIAMETER * DIAMETER * DIAMETER > MAX_CELLS) {
            throw new IllegalArgumentException("Lighting domain exceeds " + MAX_CELLS + " cells");
        }
    }

    public List<CubePos> positions() {
        List<CubePos> result = new ArrayList<>(cubeCount());
        for (int z = this.min.getZ(); z <= this.max.getZ(); z++) {
            for (int y = this.min.getY(); y <= this.max.getY(); y++) {
                for (int x = this.min.getX(); x <= this.max.getX(); x++) {
                    result.add(CubePos.of(x, y, z));
                }
            }
        }
        return List.copyOf(result);
    }

    public boolean contains(CubePos pos) {
        return pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX() && pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
                && pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
    }

    public boolean contains(BlockPos pos) {
        return contains(CubePos.of(Coords.blockToCube(pos.getX()), Coords.blockToCube(pos.getY()), Coords.blockToCube(pos.getZ())));
    }

    public int cubeCount() {
        return (this.max.getX() - this.min.getX() + 1) * (this.max.getY() - this.min.getY() + 1) * (this.max.getZ() - this.min.getZ() + 1);
    }

    public int volume() {
        return cubeCount() * DIAMETER * DIAMETER * DIAMETER;
    }

    public int minBlockX() {
        return this.min.minCubeX();
    }

    public int minBlockY() {
        return this.min.minCubeY();
    }

    public int minBlockZ() {
        return this.min.minCubeZ();
    }

    public int sizeX() {
        return (this.max.getX() - this.min.getX() + 1) * DIAMETER;
    }

    public int sizeY() {
        return (this.max.getY() - this.min.getY() + 1) * DIAMETER;
    }

    public int sizeZ() {
        return (this.max.getZ() - this.min.getZ() + 1) * DIAMETER;
    }

    int index(int x, int y, int z) {
        return x + sizeX() * (z + sizeZ() * y);
    }

    int index(BlockPos pos) {
        return index(pos.getX() - minBlockX(), pos.getY() - minBlockY(), pos.getZ() - minBlockZ());
    }

    static void validatePosition(CubePos pos) {
        CubeSkyOcclusion.validateCoordinate(pos.getX());
        CubeSkyOcclusion.validateCoordinate(pos.getY());
        CubeSkyOcclusion.validateCoordinate(pos.getZ());
    }
}
