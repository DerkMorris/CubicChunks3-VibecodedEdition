package io.github.opencubicchunks.cubicchunks.world.level;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import net.minecraft.core.BlockPos;

/** Block coordinates representable by the cube key; dimension section-array sizes stay unchanged. */
public final class CubicBlockBounds {
    public static final int MIN = (-CubePos.MAX_COORDINATE_VALUE - 1) * CubicConstants.DIAMETER_IN_BLOCKS;
    public static final int MAX = (CubePos.MAX_COORDINATE_VALUE + 1) * CubicConstants.DIAMETER_IN_BLOCKS - 1;

    private CubicBlockBounds() {}

    public static boolean contains(int coordinate) {
        return coordinate >= MIN && coordinate <= MAX;
    }

    public static boolean contains(BlockPos pos) {
        return contains(pos.getX()) && contains(pos.getY()) && contains(pos.getZ());
    }

    public static BlockPos requireValid(BlockPos pos) {
        if (!contains(pos)) {
            throw new IllegalArgumentException("Block position exceeds the cube coordinate range");
        }
        return pos.immutable();
    }
}
