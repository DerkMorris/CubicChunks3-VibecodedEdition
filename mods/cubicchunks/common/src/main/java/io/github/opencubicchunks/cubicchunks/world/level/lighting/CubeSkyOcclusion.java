package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/** Immutable cube-local skylight occlusion observations, with no assumption about either external neighbor. */
public final class CubeSkyOcclusion {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int COLUMN_COUNT = DIAMETER * DIAMETER;

    private final CubePos pos;
    private final byte[] highestInternalOffsets;
    private final BlockState[] bottomStates;
    private final BlockState[] topStates;

    private CubeSkyOcclusion(CubePos pos, byte[] highestInternalOffsets, BlockState[] bottomStates, BlockState[] topStates) {
        this.pos = pos;
        this.highestInternalOffsets = highestInternalOffsets.clone();
        this.bottomStates = bottomStates.clone();
        this.topStates = topStates.clone();
    }

    /** Capture on the cube's owning thread. Block states are immutable; no live cube or section is retained. */
    public static CubeSkyOcclusion capture(CubeAccess cube) {
        CubePos pos = Objects.requireNonNull(cube, "cube").cc_getCubePos();
        return capture(pos, cube.getSections());
    }

    public static CubeSkyOcclusion capture(CubePos pos, LevelChunkSection[] sections) {
        validateCoordinate(pos.getX());
        validateCoordinate(pos.getY());
        validateCoordinate(pos.getZ());
        byte[] edges = new byte[COLUMN_COUNT];
        BlockState[] bottom = new BlockState[COLUMN_COUNT];
        BlockState[] top = new BlockState[COLUMN_COUNT];

        if (Arrays.stream(sections).allMatch(CubeSkyOcclusion::onlyAir)) {
            Arrays.fill(bottom, Blocks.AIR.defaultBlockState());
            Arrays.fill(top, Blocks.AIR.defaultBlockState());
            return new CubeSkyOcclusion(pos, edges, bottom, top);
        }
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                int column = index(x, z);
                BlockState upper = state(sections, x, DIAMETER - 1, z);
                top[column] = upper;
                for (int y = DIAMETER - 2; y >= 0; y--) {
                    BlockState lower = state(sections, x, y, z);
                    if (edges[column] == 0 && isEdgeOccluded(upper, lower)) {
                        edges[column] = (byte) (y + 1);
                        break;
                    }
                    upper = lower;
                }
                bottom[column] = state(sections, x, 0, z);
            }
        }
        return new CubeSkyOcclusion(pos, edges, bottom, top);
    }

    private static boolean onlyAir(LevelChunkSection section) {
        return !section.maybeHas(state -> !state.is(Blocks.AIR) && !state.is(Blocks.CAVE_AIR) && !state.is(Blocks.VOID_AIR));
    }

    public CubePos pos() {
        return this.pos;
    }

    /** Absolute Y of the upper block at the highest blocking edge whose two endpoints are inside this cube. */
    public OptionalInt highestInternalEdgeY(int localX, int localZ) {
        int offset = Byte.toUnsignedInt(this.highestInternalOffsets[index(localX, localZ)]);
        return offset == 0 ? OptionalInt.empty() : OptionalInt.of(this.pos.minCubeY() + offset);
    }

    public BlockState bottomState(int localX, int localZ) {
        return this.bottomStates[index(localX, localZ)];
    }

    public BlockState topState(int localX, int localZ) {
        return this.topStates[index(localX, localZ)];
    }

    /** Exact directed-edge test used by Minecraft 26.3 ChunkSkyLightSources. */
    public static boolean isEdgeOccluded(BlockState upper, BlockState lower) {
        Objects.requireNonNull(upper, "upper");
        Objects.requireNonNull(lower, "lower");
        return lower.getLightDampening() != 0
                || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(upper, Direction.DOWN), LightEngine.getOcclusionShape(lower, Direction.UP));
    }

    static void validateCoordinate(int value) {
        if (value < -CubePos.MAX_COORDINATE_VALUE - 1 || value > CubePos.MAX_COORDINATE_VALUE) {
            throw new IllegalArgumentException("Cube coordinate exceeds the supported range: " + value);
        }
    }

    private static BlockState state(LevelChunkSection[] sections, int x, int y, int z) {
        return sections[Coords.blockToIndex(x, y, z)].getBlockState(SectionPos.sectionRelative(x), SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(z));
    }

    private static int index(int x, int z) {
        if (x < 0 || x >= DIAMETER || z < 0 || z >= DIAMETER) {
            throw new IllegalArgumentException("Expected cube-local X/Z in [0, " + DIAMETER + "): " + x + ", " + z);
        }
        return x + z * DIAMETER;
    }
}
