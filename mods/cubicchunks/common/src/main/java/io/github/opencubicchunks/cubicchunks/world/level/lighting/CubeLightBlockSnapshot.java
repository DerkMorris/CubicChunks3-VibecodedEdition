package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Detached immutable block states captured on the cube's owning thread. */
public final class CubeLightBlockSnapshot {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private final CubePos pos;
    private final BlockState[] states;

    private CubeLightBlockSnapshot(CubePos pos, BlockState[] states) {
        this.pos = pos;
        this.states = states;
    }

    public static CubeLightBlockSnapshot capture(CubeAccess cube) {
        CubePos pos = Objects.requireNonNull(cube, "cube").cc_getCubePos();
        return capture(pos, cube.getSections());
    }

    public static CubeLightBlockSnapshot capture(CubePos pos, LevelChunkSection[] sections) {
        CubeLightBounds.validatePosition(pos);
        BlockState[] states = new BlockState[DIAMETER * DIAMETER * DIAMETER];

        for (int i = 0; i < sections.length; i++) {
            copySection(sections[i], Coords.indexToX(i) * SectionPos.SECTION_SIZE, Coords.indexToY(i) * SectionPos.SECTION_SIZE,
                    Coords.indexToZ(i) * SectionPos.SECTION_SIZE, states);
        }
        return new CubeLightBlockSnapshot(pos, states);
    }

    public static CubeLightBlockSnapshot uniform(CubePos pos, BlockState state) {
        CubeLightBounds.validatePosition(pos);
        BlockState[] states = new BlockState[DIAMETER * DIAMETER * DIAMETER];
        Arrays.fill(states, state);
        return new CubeLightBlockSnapshot(pos, states);
    }

    private static void copySection(LevelChunkSection section, int ox, int oy, int oz, BlockState[] states) {
        BlockState first = section.getBlockState(0, 0, 0);
        boolean uniform = !section.maybeHas(state -> state != first);
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                int offset = index(ox, oy + y, oz + z);
                if (uniform) {
                    Arrays.fill(states, offset, offset + SectionPos.SECTION_SIZE, first);
                } else {
                    for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                        states[offset + x] = section.getBlockState(x, y, z);
                    }
                }
            }
        }
    }

    /** Evaluate the generator's horizontal noise once per column rather than once per block. */
    public static CubeLightBlockSnapshot generated(CubePos pos, long seed) {
        CubeLightBounds.validatePosition(pos);
        BlockState[] states = new BlockState[DIAMETER * DIAMETER * DIAMETER];
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                int height = CubeTerrainGenerator.surface(seed, pos.minCubeX() + x, pos.minCubeZ() + z);
                for (int y = 0; y < DIAMETER; y++) {
                    states[index(x, y, z)] = CubeTerrainGenerator.stateAtHeight(height, pos.minCubeY() + y);
                }
            }
        }
        return new CubeLightBlockSnapshot(pos, states);
    }

    void copyRow(int x, int y, int z, BlockState[] target, int offset, int length) {
        System.arraycopy(states, index(x, y, z), target, offset, length);
    }

    public CubePos pos() {
        return this.pos;
    }

    /** Pure source, used for the authoritative generator fallback and detached solver fixtures. */
    public static CubeLightBlockSnapshot create(CubePos pos, Function<BlockPos, BlockState> source) {
        CubeLightBounds.validatePosition(pos);
        BlockState[] states = new BlockState[DIAMETER * DIAMETER * DIAMETER];
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    states[index(x, y, z)] = Objects
                            .requireNonNull(source.apply(block.set(pos.minCubeX() + x, pos.minCubeY() + y, pos.minCubeZ() + z)));
                }
            }
        }
        return new CubeLightBlockSnapshot(pos, states);
    }

    public BlockState state(int x, int y, int z) {
        if (x < 0 || x >= DIAMETER || y < 0 || y >= DIAMETER || z < 0 || z >= DIAMETER) {
            throw new IllegalArgumentException("Block coordinates are outside the cube snapshot: " + x + ", " + y + ", " + z);
        }
        return this.states[index(x, y, z)];
    }

    private static int index(int x, int y, int z) {
        return x + DIAMETER * (z + DIAMETER * y);
    }
}
