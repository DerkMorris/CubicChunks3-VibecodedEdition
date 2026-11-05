package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.ToIntBiFunction;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/** A complete cube and a 15-block halo: attenuated external light cannot reach the result's interior. */
public final class CubeWorldLightSolver {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int HALO = LightEngine.MAX_LEVEL;
    private static final int SIDE = DIAMETER + 2 * HALO;
    private static final int PLANE = SIDE * SIDE;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int REUSABLE_WORKSPACES = 8;
    private static final ArrayBlockingQueue<CubeWorldLightSolver> POOL = new ArrayBlockingQueue<>(REUSABLE_WORKSPACES);
    private final BlockState[] states = new BlockState[SIDE * PLANE];
    private final byte[] block = new byte[states.length];
    private final byte[] sky = new byte[states.length];

    private final IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
    private final boolean[] queued = new boolean[states.length];

    private CubeWorldLightSolver() {}

    public static CubeLightView solve(CubePos center, Map<CubePos, CubeLightBlockSnapshot> cubes, ToIntBiFunction<Integer, Integer> skyEdge) {
        CubeWorldLightSolver solver = POOL.poll();
        if (solver == null) {
            solver = new CubeWorldLightSolver();
        }
        CubeLightView view = solver.calculate(center, cubes, skyEdge);
        // The extracted arrays belong to the view. All scratch cells are overwritten before reuse.
        POOL.offer(solver);
        return view;
    }

    private CubeLightView calculate(CubePos center, Map<CubePos, CubeLightBlockSnapshot> cubes, ToIntBiFunction<Integer, Integer> skyEdge) {
        fillStates(center, cubes);
        boolean hasEmitter = false;
        for (int i = 0; i < states.length; i++) {
            block[i] = (byte) states[i].getLightEmission();
            hasEmitter |= block[i] != 0;
        }
        if (hasEmitter) {
            spread(block);
        }
        if (fillSky(center, skyEdge)) {
            spread(sky);
        }
        byte[] blockResult = extract(block);
        byte[] skyResult = extract(sky);
        return new CubeLightView(center, blockResult, blockResult, skyResult, skyResult);
    }

    private boolean fillSky(CubePos center, ToIntBiFunction<Integer, Integer> skyEdge) {
        int bottom = center.minCubeY() - HALO;
        boolean lit = false;
        boolean dark = false;
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                int edge = skyEdge.applyAsInt(center.minCubeX() + x - HALO, center.minCubeZ() + z - HALO);
                lit |= edge <= bottom + SIDE - 1;
                dark |= edge > bottom;
                for (int y = 0; y < SIDE; y++) {
                    sky[x + SIDE * z + PLANE * y] = (byte) (bottom + y >= edge ? LightEngine.MAX_LEVEL : 0);
                }
            }
        }
        return lit && dark;
    }

    private void fillStates(CubePos center, Map<CubePos, CubeLightBlockSnapshot> cubes) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    CubePos pos = CubePos.of(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    copyCube(cubes.get(pos), pos, dx * DIAMETER + HALO, dy * DIAMETER + HALO, dz * DIAMETER + HALO);
                }
            }
        }
    }

    private void copyCube(CubeLightBlockSnapshot cube, CubePos pos, int ox, int oy, int oz) {
        int minX = Math.max(0, ox);
        int width = Math.min(SIDE, ox + DIAMETER) - minX;
        BlockState fallback = cube == null ? outside(pos) : null;
        for (int y = Math.max(0, oy); y < Math.min(SIDE, oy + DIAMETER); y++) {
            for (int z = Math.max(0, oz); z < Math.min(SIDE, oz + DIAMETER); z++) {
                int offset = minX + SIDE * z + PLANE * y;
                if (cube == null) {
                    Arrays.fill(states, offset, offset + width, fallback);
                } else {
                    cube.copyRow(minX - ox, y - oy, z - oz, states, offset, width);
                }
            }
        }
    }

    private static BlockState outside(CubePos pos) {
        if (pos.getX() >= -CubePos.MAX_COORDINATE_VALUE - 1 && pos.getX() <= CubePos.MAX_COORDINATE_VALUE
                && pos.getY() >= -CubePos.MAX_COORDINATE_VALUE - 1 && pos.getY() <= CubePos.MAX_COORDINATE_VALUE
                && pos.getZ() >= -CubePos.MAX_COORDINATE_VALUE - 1 && pos.getZ() <= CubePos.MAX_COORDINATE_VALUE) {
            throw new IllegalArgumentException("Missing lighting halo cube: " + pos);
        }
        return Blocks.AIR.defaultBlockState();
    }

    private void spread(byte[] light) {
        for (int i = 0; i < light.length; i++) {
            if (light[i] > 1 && hasLowerNeighbor(i, light)) {
                queue.enqueue(i);
                queued[i] = true;
            }
        }
        while (!queue.isEmpty()) {
            int from = queue.dequeueInt();
            queued[from] = false;
            for (Direction direction : DIRECTIONS) {
                int to = neighbor(from, direction);
                if (to >= 0 && light[to] < light[from] - 1 && transfer(from, to, direction, light) && !queued[to]) {
                    queue.enqueue(to);
                    queued[to] = true;
                }
            }
        }
    }

    /** Uniform direct-sky interiors cannot improve any neighbor; only the boundary seeds propagation. */
    private static boolean hasLowerNeighbor(int index, byte[] light) {
        for (Direction direction : DIRECTIONS) {
            int to = neighbor(index, direction);
            if (to >= 0 && light[to] < light[index] - 1) {
                return true;
            }
        }
        return false;
    }

    private boolean transfer(int from, int to, Direction direction, byte[] light) {
        int value = light[from] - Math.max(1, states[to].getLightDampening());
        if (value <= light[to] || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(states[from], direction),
                LightEngine.getOcclusionShape(states[to], direction.getOpposite()))) {
            return false;
        }
        light[to] = (byte) value;
        return true;
    }

    private static int neighbor(int index, Direction direction) {
        int x = index % SIDE + direction.getStepX();
        int y = index / PLANE + direction.getStepY();
        int z = index / SIDE % SIDE + direction.getStepZ();
        return x < 0 || x >= SIDE || y < 0 || y >= SIDE || z < 0 || z >= SIDE ? -1 : x + SIDE * z + PLANE * y;
    }

    private byte[] extract(byte[] light) {
        byte[] result = new byte[DIAMETER * DIAMETER * DIAMETER];
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                System.arraycopy(light, HALO + SIDE * (z + HALO) + PLANE * (y + HALO), result, DIAMETER * (z + DIAMETER * y), DIAMETER);
            }
        }
        return result;
    }
}
