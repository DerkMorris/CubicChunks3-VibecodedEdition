package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Initial cubic terrain: seeded hills with grass, dirt and stone, evaluated in absolute block coordinates. */
public final class CubeTerrainGenerator {
    private static final int BASE_HEIGHT = 80;
    private static final int LARGE_SCALE = 128;
    private static final int SMALL_SCALE = 32;
    private static final int LARGE_AMPLITUDE = 20;
    private static final int SMALL_AMPLITUDE = 4;
    private static final int SOIL_DEPTH = 3;

    private static final int SMOOTH_COEFFICIENT = 3;
    private static final long X_SEED = 0x9E3779B97F4A7C15L;
    private static final long Z_SEED = 0xD1B54A32D192ED03L;
    private static final long MIX_FIRST = 0xBF58476D1CE4E5B9L;
    private static final long MIX_SECOND = 0x94D049BB133111EBL;
    private static final int SHIFT_FIRST = 30;
    private static final int SHIFT_SECOND = 27;
    private static final int SHIFT_FINAL = 31;
    private static final int MANTISSA_SHIFT = 11;
    private static final double SIGNED_UNIT_SCALE = 0x1.0p-52;

    private CubeTerrainGenerator() {}

    public static int surface(long seed, int x, int z) {
        return BASE_HEIGHT + (int) Math.round(noise(seed, x, z, LARGE_SCALE) * LARGE_AMPLITUDE + noise(~seed, x, z, SMALL_SCALE) * SMALL_AMPLITUDE);
    }

    /** Authoritative state of an as-yet ungenerated cube in this port's terrain generator. */
    public static BlockState state(long seed, int x, int y, int z) {
        return stateAtHeight(surface(seed, x, z), y);
    }

    public static BlockState stateAtHeight(int height, int y) {
        int depth = height - y;
        return depth < 0 ? Blocks.AIR.defaultBlockState() : material(depth);
    }

    public static void fill(long seed, CubeAccess cube) {
        CubePos pos = cube.cc_getCubePos();
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        for (int x = 0; x < CubicConstants.DIAMETER_IN_BLOCKS; x++) {
            for (int z = 0; z < CubicConstants.DIAMETER_IN_BLOCKS; z++) {
                int worldX = pos.minCubeX() + x;
                int worldZ = pos.minCubeZ() + z;
                int height = surface(seed, worldX, worldZ);
                int top = Math.min(height, pos.maxCubeY());
                for (int y = pos.minCubeY(); y <= top; y++) {
                    cube.setBlockState(block.set(worldX, y, worldZ), material(height - y));
                }
            }
        }
    }

    private static BlockState material(int depth) {
        return (depth == 0 ? Blocks.GRASS_BLOCK : depth <= SOIL_DEPTH ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
    }

    private static double noise(long seed, int x, int z, int scale) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double dx = smooth((double) Math.floorMod(x, scale) / scale);
        double dz = smooth((double) Math.floorMod(z, scale) / scale);
        double north = mix(value(seed, cellX, cellZ), value(seed, cellX + 1, cellZ), dx);
        double south = mix(value(seed, cellX, cellZ + 1), value(seed, cellX + 1, cellZ + 1), dx);
        return mix(north, south, dz);
    }

    private static double smooth(double value) {
        return value * value * (SMOOTH_COEFFICIENT - 2 * value);
    }

    private static double mix(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static double value(long seed, int x, int z) {
        long hash = seed ^ (long) x * X_SEED ^ (long) z * Z_SEED;
        hash = (hash ^ hash >>> SHIFT_FIRST) * MIX_FIRST;
        hash = (hash ^ hash >>> SHIFT_SECOND) * MIX_SECOND;
        hash ^= hash >>> SHIFT_FINAL;
        return (hash >>> MANTISSA_SHIFT) * SIGNED_UNIT_SCALE - 1;
    }
}
