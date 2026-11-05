package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LightEngine;

/** Immutable cube-sized lighting publication. Uncertain cells retain their bounds and never become exact section data. */
public final class CubeLightView {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int VOLUME = DIAMETER * DIAMETER * DIAMETER;
    private final CubePos pos;
    private final byte[] blockLower;
    private final byte[] blockUpper;
    private final byte[] skyLower;
    private final byte[] skyUpper;
    private final boolean ready;

    CubeLightView(CubePos pos, byte[] blockLower, byte[] blockUpper, byte[] skyLower, byte[] skyUpper) {
        CubeLightBounds.validatePosition(Objects.requireNonNull(pos, "pos"));
        this.pos = pos;
        this.blockLower = copyValues(blockLower);
        this.blockUpper = blockLower == blockUpper ? this.blockLower : copyValues(blockUpper);
        this.skyLower = copyValues(skyLower);
        this.skyUpper = skyLower == skyUpper ? this.skyLower : copyValues(skyUpper);
        validateOrder(this.blockLower, this.blockUpper);
        validateOrder(this.skyLower, this.skyUpper);
        this.ready = Arrays.equals(this.blockLower, this.blockUpper) && Arrays.equals(this.skyLower, this.skyUpper);
    }

    public static Optional<CubeLightView> fromSolution(CubeLightSolution solution, CubePos pos) {
        return Objects.requireNonNull(solution, "solution").cubeView(pos);
    }

    /** Imports complete exact section arrays in Cubic Chunks' local XYZ section order, not vanilla column order. */
    public static CubeLightView fromExact(CubePos pos, byte[][] block, byte[][] sky) {
        CubeLightBounds.validatePosition(Objects.requireNonNull(pos, "pos"));
        byte[] blockValues = unpack(copyPacked(block));
        byte[] skyValues = unpack(copyPacked(sky));
        return new CubeLightView(pos, blockValues, blockValues, skyValues, skyValues);
    }

    public CubePos pos() {
        return this.pos;
    }

    public Optional<LightRange> sample(LightLayer layer, BlockPos blockPos) {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(blockPos, "blockPos");
        if (!contains(blockPos)) {
            return Optional.empty();
        }
        int index = localIndex(blockPos);
        return Optional.of(layer == LightLayer.BLOCK ? new LightRange(this.blockLower[index], this.blockUpper[index])
                : new LightRange(this.skyLower[index], this.skyUpper[index]));
    }

    /** Combines both ranges before testing exactness; one exact dominating layer can determine the final brightness. */
    public OptionalInt rawBrightness(BlockPos blockPos, int darkening) {
        return rawBrightness(blockPos, darkening, true, true);
    }

    /** Disabled engine layers contribute exact zero, including a disabled sky layer before any darkening is applied. */
    public OptionalInt rawBrightness(BlockPos blockPos, int darkening, boolean blockEnabled, boolean skyEnabled) {
        Objects.requireNonNull(blockPos, "blockPos");
        if (!contains(blockPos)) {
            return OptionalInt.empty();
        }
        int index = localIndex(blockPos);
        long lower = Math.max(blockEnabled ? this.blockLower[index] : 0, skyEnabled ? (long) this.skyLower[index] - darkening : 0);
        long upper = Math.max(blockEnabled ? this.blockUpper[index] : 0, skyEnabled ? (long) this.skyUpper[index] - darkening : 0);
        return lower == upper ? OptionalInt.of(Math.toIntExact(lower)) : OptionalInt.empty();
    }

    /** Returns a detached vanilla layer only if all 4096 values in this section and layer are exact. */
    public Optional<DataLayer> sectionData(LightLayer layer, int localSection) {
        Objects.requireNonNull(layer, "layer");
        validateSection(localSection);
        byte[] lower = layer == LightLayer.BLOCK ? this.blockLower : this.skyLower;
        byte[] upper = layer == LightLayer.BLOCK ? this.blockUpper : this.skyUpper;
        int minX = Coords.indexToX(localSection) * SectionPos.SECTION_SIZE;
        int minY = Coords.indexToY(localSection) * SectionPos.SECTION_SIZE;
        int minZ = Coords.indexToZ(localSection) * SectionPos.SECTION_SIZE;
        DataLayer result = new DataLayer();
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    int index = index(minX + x, minY + y, minZ + z);
                    if (lower[index] != upper[index]) {
                        return Optional.empty();
                    }
                    result.set(x, y, z, lower[index]);
                }
            }
        }
        return Optional.of(result);
    }

    public Optional<byte[]> packedLayer(LightLayer layer, int localSection) {
        return sectionData(layer, localSection).map(data -> data.getData().clone());
    }

    public boolean isReady() {
        return this.ready;
    }

    int localValue(boolean sky, int index) {
        return (sky ? skyLower : blockLower)[index];
    }

    byte[] copyLocalValues(boolean sky) {
        return (sky ? skyLower : blockLower).clone();
    }

    boolean hasLocalValues(byte[] block, byte[] sky) {
        return Arrays.equals(blockLower, block) && Arrays.equals(skyLower, sky);
    }

    private boolean contains(BlockPos blockPos) {
        return Coords.blockToCube(blockPos.getX()) == this.pos.getX() && Coords.blockToCube(blockPos.getY()) == this.pos.getY()
                && Coords.blockToCube(blockPos.getZ()) == this.pos.getZ();
    }

    private int localIndex(BlockPos blockPos) {
        return index(blockPos.getX() - this.pos.minCubeX(), blockPos.getY() - this.pos.minCubeY(), blockPos.getZ() - this.pos.minCubeZ());
    }

    private static int index(int x, int y, int z) {
        return x + DIAMETER * (z + DIAMETER * y);
    }

    private static byte[] copyValues(byte[] values) {
        if (Objects.requireNonNull(values, "values").length != VOLUME) {
            throw new IllegalArgumentException("Expected " + VOLUME + " cube light values");
        }
        byte[] copy = values.clone();
        for (byte value : copy) {
            if (value < 0 || value > LightEngine.MAX_LEVEL) {
                throw new IllegalArgumentException("Invalid light value: " + value);
            }
        }
        return copy;
    }

    private static void validateOrder(byte[] lower, byte[] upper) {
        for (int index = 0; index < lower.length; index++) {
            if (lower[index] > upper[index]) {
                throw new IllegalArgumentException("Lower light bound exceeds upper bound");
            }
        }
    }

    private static void validateSection(int localSection) {
        if (localSection < 0 || localSection >= CubicConstants.SECTION_COUNT) {
            throw new IllegalArgumentException("Local cube section index is outside bounds: " + localSection);
        }
    }

    private static byte[][] copyPacked(byte[][] sections) {
        if (Objects.requireNonNull(sections, "sections").length != CubicConstants.SECTION_COUNT) {
            throw new IllegalArgumentException("Expected " + CubicConstants.SECTION_COUNT + " light sections");
        }
        byte[][] copy = new byte[sections.length][];
        for (int index = 0; index < sections.length; index++) {
            if (sections[index] == null || sections[index].length != DataLayer.SIZE) {
                throw new IllegalArgumentException("Expected " + DataLayer.SIZE + " packed light bytes in section " + index);
            }
            copy[index] = sections[index].clone();
        }
        return copy;
    }

    private static byte[] unpack(byte[][] sections) {
        byte[] values = new byte[VOLUME];
        for (int section = 0; section < sections.length; section++) {
            DataLayer data = new DataLayer(sections[section]);
            int minX = Coords.indexToX(section) * SectionPos.SECTION_SIZE;
            int minY = Coords.indexToY(section) * SectionPos.SECTION_SIZE;
            int minZ = Coords.indexToZ(section) * SectionPos.SECTION_SIZE;
            for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
                for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                    for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                        values[index(minX + x, minY + y, minZ + z)] = (byte) data.get(x, y, z);
                    }
                }
            }
        }
        return values;
    }
}
