package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Non-loading reads for movement and pathfinding. Missing terrain is solid, never an invitation to walk into an unloaded cube. */
public final class CubeMobBlockView implements BlockGetter {
    public static final int MAX_PATH_LENGTH = 96;
    private final Level level;
    private final BlockPos min;
    private final BlockPos max;
    private final @Nullable Map<CubePos, LevelCube> cubes;

    public CubeMobBlockView(Level level) {
        this(level, new BlockPos(CubicBlockBounds.MIN, CubicBlockBounds.MIN, CubicBlockBounds.MIN),
                new BlockPos(CubicBlockBounds.MAX, CubicBlockBounds.MAX, CubicBlockBounds.MAX), false);
    }

    public CubeMobBlockView(Level level, BlockPos min, BlockPos max, boolean cache) {
        this.level = level;
        this.min = new BlockPos(Math.max(min.getX(), CubicBlockBounds.MIN), Math.max(min.getY(), CubicBlockBounds.MIN),
                Math.max(min.getZ(), CubicBlockBounds.MIN));
        this.max = new BlockPos(Math.min(max.getX(), CubicBlockBounds.MAX), Math.min(max.getY(), CubicBlockBounds.MAX),
                Math.min(max.getZ(), CubicBlockBounds.MAX));
        cubes = cache ? new HashMap<>() : null;
    }

    public static @Nullable LevelCube loadedCube(Level level, BlockPos pos) {
        if (!CubicBlockBounds.contains(pos)) {
            return null;
        }
        CubePos key = CubePos.from(pos);
        LevelCube cube = ((CubeSource) level.getChunkSource()).cc_getCubeNow(key.getX(), key.getY(), key.getZ());
        return cube != null && cube.getLevel() == level && cube.cc_getCubePos().equals(key) && cube.getFullStatus().isOrAfter(FullChunkStatus.FULL)
                ? cube
                : null;
    }

    private @Nullable LevelCube cube(BlockPos pos) {
        if (pos.getX() < min.getX() || pos.getX() > max.getX() || pos.getY() < min.getY() || pos.getY() > max.getY() || pos.getZ() < min.getZ()
                || pos.getZ() > max.getZ()) {
            return null;
        }
        if (cubes == null) {
            return loadedCube(level, pos);
        }
        CubePos key = CubePos.from(pos);
        if (!cubes.containsKey(key)) {
            cubes.put(key, loadedCube(level, pos));
        }
        return cubes.get(key);
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        LevelCube cube = cube(pos);
        return cube == null ? Blocks.BARRIER.defaultBlockState() : cube.getBlockState(pos);
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        LevelCube cube = cube(pos);
        return cube == null ? Fluids.EMPTY.defaultFluidState() : cube.getFluidState(pos);
    }

    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        LevelCube cube = cube(pos);
        return cube == null ? null : cube.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    @Override public int getMinY() {
        return min.getY();
    }

    @Override public int getHeight() {
        return Math.max(0, max.getY() - min.getY() + 1);
    }
}
