package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Bounded, owner-thread display prediction from known neighboring light. Server publications remain authoritative. */
final class ClientCubeLightPropagation {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int MAX_CELLS = 32768;
    private static final Direction[] DIRECTIONS = Direction.values();
    private final Function<CubePos, CubeAccess> findCube;
    private final Function<CubePos, CubeLightView> findLight;
    private final Map<CubePos, LocalCube> cubes = new HashMap<>();
    private final Set<CubePos> missing = new HashSet<>();
    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private final Set<BlockPos> queued = new HashSet<>();
    private final ArrayDeque<Removed> decreasing = new ArrayDeque<>();

    private ClientCubeLightPropagation(Function<CubePos, CubeAccess> findCube, Function<CubePos, CubeLightView> findLight) {
        this.findCube = findCube;
        this.findLight = findLight;
    }

    static Map<CubePos, CubeLightView> change(
            BlockPos pos, BlockState previous, Function<CubePos, CubeAccess> findCube, Function<CubePos, CubeLightView> findLight
    ) {
        ClientCubeLightPropagation propagation = new ClientCubeLightPropagation(findCube, findLight);
        propagation.begin(pos.immutable(), previous);
        if (!propagation.drain()) {
            // Discard the whole private transaction on budget exhaustion, never publish half-cleared light.
            return Map.of();
        }
        Map<CubePos, CubeLightView> result = new HashMap<>();
        propagation.cubes.forEach((at, cube) -> {
            if (cube.block != null && !cube.before.hasLocalValues(cube.block, cube.sky)) {
                result.put(at, new CubeLightView(at, cube.block, cube.block, cube.sky, cube.sky));
            }
        });
        return result;
    }

    private void begin(BlockPos pos, BlockState previous) {
        LocalCube target = cube(pos);
        if (target == null) {
            return;
        }
        BlockState current = target.cube.getBlockState(pos);
        boolean obstructed = reducesTransmission(previous, current);
        int block = obstructed || current.getLightEmission() < previous.getLightEmission() ? target.value(pos, false) : 0;
        int sky = obstructed ? target.value(pos, true) : 0;
        remove(pos, target, previous, block, sky);
        enqueue(pos);
        // An opened face may improve its neighbor even when the changed cell's own value stays the same.
        for (Direction direction : DIRECTIONS) {
            enqueue(pos.relative(direction));
        }
    }

    private boolean drain() {
        int remaining = MAX_CELLS;
        while (!decreasing.isEmpty() && remaining-- > 0) {
            decrease(decreasing.removeFirst());
        }
        if (!decreasing.isEmpty()) {
            return false;
        }
        while (!queue.isEmpty() && remaining-- > 0) {
            BlockPos next = queue.removeFirst();
            queued.remove(next);
            update(next);
        }
        return queue.isEmpty();
    }

    private void decrease(Removed from) {
        for (Direction direction : DIRECTIONS) {
            BlockPos pos = from.pos.relative(direction);
            LocalCube target = cube(pos);
            if (target == null) {
                continue;
            }
            BlockState state = target.cube.getBlockState(pos);
            int block = dependent(target.value(pos, false), transfer(from.state, state, direction, from.block, false));
            int sky = dependent(target.value(pos, true), transfer(from.state, state, direction, from.sky, true));
            remove(pos, target, state, block, sky);
        }
    }

    private static int dependent(int value, int contribution) {
        return value <= contribution ? value : 0;
    }

    private void remove(BlockPos pos, LocalCube cube, BlockState previous, int block, int sky) {
        if (block == 0 && sky == 0) {
            return;
        }
        cube.set(pos, block > 0 ? 0 : cube.value(pos, false), sky > 0 ? 0 : cube.value(pos, true));
        decreasing.addLast(new Removed(pos, previous, block, sky));
        enqueue(pos);
    }

    private static boolean reducesTransmission(BlockState previous, BlockState current) {
        if (current.getLightDampening() > previous.getLightDampening()) {
            return true;
        }
        for (Direction direction : DIRECTIONS) {
            if (Shapes.joinIsNotEmpty(LightEngine.getOcclusionShape(current, direction), LightEngine.getOcclusionShape(previous, direction),
                    BooleanOp.ONLY_FIRST)) {
                return true;
            }
        }
        return false;
    }

    private void update(BlockPos pos) {
        LocalCube target = cube(pos);
        if (target == null) {
            return;
        }
        BlockState state = target.cube.getBlockState(pos);
        int block = state.getLightEmission();
        int sky = 0;
        for (Direction outward : DIRECTIONS) {
            BlockPos from = pos.relative(outward);
            LocalCube source = cube(from);
            if (source == null) {
                continue;
            }
            Direction incoming = outward.getOpposite();
            BlockState neighbor = source.cube.getBlockState(from);
            block = Math.max(block, transfer(neighbor, state, incoming, source.value(from, false), false));
            sky = Math.max(sky, transfer(neighbor, state, incoming, source.value(from, true), true));
        }
        if (target.set(pos, Math.max(target.value(pos, false), block), Math.max(target.value(pos, true), sky))) {
            for (Direction direction : DIRECTIONS) {
                enqueue(pos.relative(direction));
            }
        }
    }

    private void enqueue(BlockPos pos) {
        if (CubicBlockBounds.contains(pos) && queued.add(pos)) {
            queue.addLast(pos);
        }
    }

    private @Nullable LocalCube cube(BlockPos pos) {
        if (!CubicBlockBounds.contains(pos)) {
            return null;
        }
        CubePos at = CubePos.from(pos);
        LocalCube found = cubes.get(at);
        if (found != null || missing.contains(at)) {
            return found;
        }
        CubeAccess blocks = findCube.apply(at);
        CubeLightView light = findLight.apply(at);
        if (blocks == null || light == null) {
            missing.add(at);
            return null;
        }
        found = new LocalCube(blocks, light);
        cubes.put(at, found);
        return found;
    }

    private static int transfer(BlockState from, BlockState to, Direction direction, int light, boolean sky) {
        if (light == 0 || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(from, direction),
                LightEngine.getOcclusionShape(to, direction.getOpposite()))) {
            return 0;
        }
        if (sky && light == LightEngine.MAX_LEVEL && direction == Direction.DOWN && to.getLightDampening() == 0) {
            return LightEngine.MAX_LEVEL;
        }
        return Math.max(0, light - Math.max(1, to.getLightDampening()));
    }

    private record Removed(BlockPos pos, BlockState state, int block, int sky) {}

    private static final class LocalCube {
        private final CubeAccess cube;
        private final CubeLightView before;
        private @Nullable byte[] block;
        private @Nullable byte[] sky;

        private LocalCube(CubeAccess cube, CubeLightView before) {
            this.cube = cube;
            this.before = before;
        }

        private int value(BlockPos pos, boolean skyLayer) {
            int index = index(pos);
            return block == null ? before.localValue(skyLayer, index) : (skyLayer ? sky : block)[index];
        }

        private boolean set(BlockPos pos, int blockValue, int skyValue) {
            int previousBlock = value(pos, false);
            int previousSky = value(pos, true);
            if (blockValue == previousBlock && skyValue == previousSky) {
                return false;
            }
            if (block == null) {
                block = before.copyLocalValues(false);
                sky = before.copyLocalValues(true);
            }
            int index = index(pos);
            block[index] = (byte) blockValue;
            sky[index] = (byte) skyValue;
            return true;
        }

        private static int index(BlockPos pos) {
            return Math.floorMod(pos.getX(), DIAMETER)
                    + DIAMETER * (Math.floorMod(pos.getZ(), DIAMETER) + DIAMETER * Math.floorMod(pos.getY(), DIAMETER));
        }
    }
}
