package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/** Pure bounded propagation over detached states. Unknown cells supply bounds, never fabricated block or light data. */
public final class BoundedCubeLightSolver {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final Direction[] DIRECTIONS = Direction.values();

    private BoundedCubeLightSolver() {}

    public static CubeLightSolution solve(CubeLightBounds bounds, Map<CubePos, CubeLightBlockSnapshot> snapshots, CubeLightBoundary boundary) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(boundary, "boundary");
        Map<CubePos, CubeLightBlockSnapshot> captured = Map.copyOf(snapshots);
        captured.forEach((pos, snapshot) -> {
            if (!bounds.contains(pos) || !pos.equals(snapshot.pos())) {
                throw new IllegalArgumentException("Lighting snapshot is outside its domain or belongs to another cube: " + pos);
            }
        });
        Domain domain = new Domain(bounds, captured);
        domain.seed(boundary);
        domain.propagate(domain.blockLower, false);
        domain.propagate(domain.blockUpper, false);
        domain.propagate(domain.skyLower, true);
        domain.propagate(domain.skyUpper, true);
        return new CubeLightSolution(bounds, captured.keySet(), domain.blockLower, domain.blockUpper, domain.skyLower, domain.skyUpper);
    }

    /** Matches vanilla ordinary propagation, plus the direct-sky column fill represented as downward level-15 transfer. */
    private static int transfer(BlockState from, BlockState to, Direction direction, int level, boolean sky) {
        int dampening = to.getLightDampening();
        int result = sky && level == LightEngine.MAX_LEVEL && direction == Direction.DOWN && dampening == 0 ? LightEngine.MAX_LEVEL
                : level - Math.max(1, dampening);
        if (result <= 0 || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(from, direction),
                LightEngine.getOcclusionShape(to, direction.getOpposite()))) {
            return 0;
        }
        return result;
    }

    private static final class Domain {
        private final CubeLightBounds bounds;
        private final int width;
        private final int height;
        private final int depth;
        private final int plane;
        private final BlockState[] states;
        private final byte[] blockLower;
        private final byte[] blockUpper;
        private final byte[] skyLower;
        private final byte[] skyUpper;

        private Domain(CubeLightBounds bounds, Map<CubePos, CubeLightBlockSnapshot> snapshots) {
            this.bounds = bounds;
            this.width = bounds.sizeX();
            this.height = bounds.sizeY();
            this.depth = bounds.sizeZ();
            this.plane = this.width * this.depth;
            int volume = bounds.volume();
            this.states = new BlockState[volume];
            this.blockLower = new byte[volume];
            this.blockUpper = new byte[volume];
            this.skyLower = new byte[volume];
            this.skyUpper = new byte[volume];
            snapshots.values().forEach(this::install);
        }

        private void install(CubeLightBlockSnapshot snapshot) {
            int minX = snapshot.pos().minCubeX() - this.bounds.minBlockX();
            int minY = snapshot.pos().minCubeY() - this.bounds.minBlockY();
            int minZ = snapshot.pos().minCubeZ() - this.bounds.minBlockZ();
            for (int y = 0; y < DIAMETER; y++) {
                for (int z = 0; z < DIAMETER; z++) {
                    for (int x = 0; x < DIAMETER; x++) {
                        BlockState state = snapshot.state(x, y, z);
                        int index = index(minX + x, minY + y, minZ + z);
                        this.states[index] = state;
                        int emission = state.getLightEmission();
                        new LightRange(emission, emission);
                        this.blockLower[index] = (byte) emission;
                        this.blockUpper[index] = (byte) emission;
                    }
                }
            }
        }

        private void seed(CubeLightBoundary boundary) {
            for (int index = 0; index < this.states.length; index++) {
                if (this.states[index] != null) {
                    seedCell(index, boundary);
                }
            }
        }

        private void seedCell(int index, CubeLightBoundary boundary) {
            int x = index % this.width;
            int y = index / this.plane;
            int z = index / this.width % this.depth;
            for (Direction outward : DIRECTIONS) {
                int nx = x + outward.getStepX();
                int ny = y + outward.getStepY();
                int nz = z + outward.getStepZ();
                boolean inside = inside(nx, ny, nz);
                if (inside && this.states[index(nx, ny, nz)] != null) {
                    continue;
                }
                Direction incoming = outward.getOpposite();
                Optional<CubeLightBoundary.BoundaryLight> source = inside ? Optional.empty()
                        : Objects.requireNonNull(boundary.sample(
                                new BlockPos(this.bounds.minBlockX() + nx, this.bounds.minBlockY() + ny, this.bounds.minBlockZ() + nz), incoming),
                                "boundary sample");
                seedIncoming(index, incoming, source);
            }
        }

        private void seedIncoming(int index, Direction direction, Optional<CubeLightBoundary.BoundaryLight> observation) {
            BlockState target = this.states[index];
            if (observation.isPresent()) {
                CubeLightBoundary.BoundaryLight source = observation.get();
                int block = transfer(source.state(), target, direction, source.block(), false);
                int sky = transfer(source.state(), target, direction, source.sky(), true);
                maximize(this.blockLower, index, block);
                maximize(this.blockUpper, index, block);
                maximize(this.skyLower, index, sky);
                maximize(this.skyUpper, index, sky);
            } else {
                BlockState source = Blocks.AIR.defaultBlockState();
                maximize(this.blockUpper, index, transfer(source, target, direction, LightEngine.MAX_LEVEL, false));
                maximize(this.skyUpper, index, transfer(source, target, direction, LightEngine.MAX_LEVEL, true));
            }
        }

        private void propagate(byte[] values, boolean sky) {
            IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
            boolean[] queued = new boolean[values.length];
            for (int index = 0; index < values.length; index++) {
                if (values[index] > 0) {
                    queue.enqueue(index);
                    queued[index] = true;
                }
            }
            while (!queue.isEmpty()) {
                int index = queue.dequeueInt();
                queued[index] = false;
                spread(index, values, sky, queue, queued);
            }
        }

        private void spread(int index, byte[] values, boolean sky, IntArrayFIFOQueue queue, boolean[] queued) {
            int x = index % this.width;
            int y = index / this.plane;
            int z = index / this.width % this.depth;
            for (Direction direction : DIRECTIONS) {
                int nx = x + direction.getStepX();
                int ny = y + direction.getStepY();
                int nz = z + direction.getStepZ();
                if (!inside(nx, ny, nz)) {
                    continue;
                }
                int target = index(nx, ny, nz);
                if (this.states[target] != null && values[target] < values[index]) {
                    int next = transfer(this.states[index], this.states[target], direction, values[index], sky);
                    if (maximize(values, target, next) && !queued[target]) {
                        queue.enqueue(target);
                        queued[target] = true;
                    }
                }
            }
        }

        private boolean inside(int x, int y, int z) {
            return x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth;
        }

        private int index(int x, int y, int z) {
            return x + this.width * (z + this.depth * y);
        }

        private static boolean maximize(byte[] values, int index, int next) {
            if (next <= values[index]) {
                return false;
            }
            values[index] = (byte) next;
            return true;
        }
    }
}
