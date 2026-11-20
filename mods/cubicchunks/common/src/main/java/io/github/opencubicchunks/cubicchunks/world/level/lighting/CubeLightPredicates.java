package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/** Proves positive gameplay thresholds from current loaded emitters without claiming an exact light field or open sky. */
public final class CubeLightPredicates {
    private static final Direction[] DIRECTIONS = Direction.values();

    private CubeLightPredicates() {}

    /** A threshold of eight visits at most the 575 cells within Manhattan distance seven; missing terrain is opaque. */
    public static boolean atLeast(LevelReader reader, BlockPos origin, int threshold) {
        if (threshold < 1 || threshold > LightEngine.MAX_LEVEL) {
            throw new IllegalArgumentException("Block-light threshold must be between one and fifteen");
        }
        if (!(reader instanceof ServerLevel level) || !((CanBeCubic) level).cc_isCubic() || !level.getServer().isSameThread()
                || !CubicBlockBounds.contains(origin)) {
            return false;
        }
        return new Search(level, origin, LightEngine.MAX_LEVEL - threshold).proves(threshold);
    }

    private static final class Search {
        private final ServerLevel level;
        private final int maximumCost;
        private final PriorityQueue<Step> work = new PriorityQueue<>(Comparator.comparingInt(Step::cost));
        private final Map<BlockPos, Integer> costs = new HashMap<>();
        private final Map<CubePos, LevelCube> cubes = new HashMap<>();

        private Search(ServerLevel level, BlockPos origin, int maximumCost) {
            this.maximumCost = maximumCost;
            this.level = level;
            costs.put(origin.immutable(), 0);
            work.add(new Step(origin.immutable(), 0));
        }

        private boolean proves(int threshold) {
            while (!work.isEmpty()) {
                Step step = work.remove();
                if (step.cost != costs.get(step.pos)) {
                    continue;
                }
                BlockState current = state(step.pos);
                if (current == null) {
                    continue;
                }
                if (current.getLightEmission() >= threshold + step.cost) {
                    return true;
                }
                int nextCost = step.cost + Math.max(1, current.getLightDampening());
                if (nextCost <= maximumCost) {
                    visitNeighbors(step.pos, current, nextCost);
                }
            }
            return false;
        }

        private void visitNeighbors(BlockPos pos, BlockState current, int nextCost) {
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = pos.relative(direction);
                if (costs.getOrDefault(neighbor, Integer.MAX_VALUE) <= nextCost) {
                    continue;
                }
                BlockState candidate = state(neighbor);
                // Search runs backwards: light travels from candidate into current, paying current's attenuation.
                if (candidate != null && !Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(candidate, direction.getOpposite()),
                        LightEngine.getOcclusionShape(current, direction))) {
                    costs.put(neighbor, nextCost);
                    work.add(new Step(neighbor, nextCost));
                }
            }
        }

        private @Nullable BlockState state(BlockPos pos) {
            if (!CubicBlockBounds.contains(pos)) {
                return null;
            }
            CubePos key = CubePos.from(pos);
            if (!cubes.containsKey(key)) {
                cubes.put(key, CubeMobBlockView.loadedCube(level, pos));
            }
            LevelCube cube = cubes.get(key);
            return cube == null ? null : cube.getBlockState(pos);
        }
    }

    private record Step(BlockPos pos, int cost) {}
}
