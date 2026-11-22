package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Detached immutable block states captured on the cube's owning thread. */
public final class CubeLightBlockSnapshot {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private final CubePos pos;
    private final @Nullable BlockState[] states;
    private final @Nullable PalettedContainer<BlockState>[] sections;
    private final @Nullable BlockState[] uniformSections;

    private CubeLightBlockSnapshot(CubePos pos, BlockState[] states) {
        this.pos = pos;
        this.states = states;
        this.sections = null;
        this.uniformSections = null;
    }

    private CubeLightBlockSnapshot(CubePos pos, PalettedContainer<BlockState>[] sections, BlockState[] uniformSections) {
        this.pos = pos;
        this.states = null;
        this.sections = sections;
        this.uniformSections = uniformSections;
    }

    public static CubeLightBlockSnapshot capture(CubeAccess cube) {
        CubePos pos = Objects.requireNonNull(cube, "cube").cc_getCubePos();
        return capture(pos, cube.getSections());
    }

    @SuppressWarnings("unchecked")
    public static CubeLightBlockSnapshot capture(CubePos pos, LevelChunkSection[] sections) {
        CubeLightBounds.validatePosition(pos);
        if (sections.length != CubicConstants.SECTION_COUNT) {
            throw new IllegalArgumentException("Expected " + CubicConstants.SECTION_COUNT + " cube sections, got " + sections.length);
        }
        var packed = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[sections.length];
        var uniform = new BlockState[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = Objects.requireNonNull(sections[i], "section");
            BlockState first = section.getBlockState(0, 0, 0);
            if (!section.maybeHas(state -> state != first)) {
                uniform[i] = first;
            } else {
                // copy() detaches both packed storage and its palette; no mutable section or biome data is retained.
                packed[i] = section.getStates().copy();
            }
        }
        return new CubeLightBlockSnapshot(pos, packed, uniform);
    }

    public static CubeLightBlockSnapshot uniform(CubePos pos, BlockState state) {
        CubeLightBounds.validatePosition(pos);
        return new CubeLightBlockSnapshot(pos, new BlockState[] { Objects.requireNonNull(state, "state") });
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
        if (states != null) {
            if (states.length == 1) {
                Arrays.fill(target, offset, offset + length, states[0]);
            } else {
                System.arraycopy(states, index(x, y, z), target, offset, length);
            }
            return;
        }
        int cursorX = x;
        int cursorOffset = offset;
        int remaining = length;
        while (remaining > 0) {
            int sectionIndex = Coords.blockToIndex(cursorX, y, z);
            int localX = SectionPos.sectionRelative(cursorX);
            int count = Math.min(remaining, SectionPos.SECTION_SIZE - localX);
            PalettedContainer<BlockState> section = sections[sectionIndex];
            if (section == null) {
                Arrays.fill(target, cursorOffset, cursorOffset + count, uniformSections[sectionIndex]);
            } else {
                for (int i = 0; i < count; i++) {
                    target[cursorOffset + i] = section.get(localX + i, SectionPos.sectionRelative(y), SectionPos.sectionRelative(z));
                }
            }
            cursorX += count;
            cursorOffset += count;
            remaining -= count;
        }
    }

    public CubePos pos() {
        return this.pos;
    }

    /** Exact immutable block-state equality, used when a live source replaces an already resolved fallback. */
    public boolean sameStates(CubeLightBlockSnapshot other) {
        if (!pos.equals(other.pos)) {
            return false;
        }
        if (states != null && other.states != null && states.length == other.states.length) {
            return Arrays.equals(states, other.states);
        }
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    if (state(x, y, z) != other.state(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
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
        if (states != null) {
            return states.length == 1 ? states[0] : states[index(x, y, z)];
        }
        int sectionIndex = Coords.blockToIndex(x, y, z);
        PalettedContainer<BlockState> section = sections[sectionIndex];
        return section == null ? uniformSections[sectionIndex]
                : section.get(SectionPos.sectionRelative(x), SectionPos.sectionRelative(y), SectionPos.sectionRelative(z));
    }

    private static int index(int x, int y, int z) {
        return x + DIAMETER * (z + DIAMETER * y);
    }
}
