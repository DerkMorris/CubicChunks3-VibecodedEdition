package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/** A cube-local heightmap; it makes no claim about blocks in other loaded or unloaded cubes. */
public final class CubeHeightmap implements CloHeightmap {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int COLUMN_COUNT = DIAMETER * DIAMETER;
    private static final int BITS = Integer.SIZE - Integer.numberOfLeadingZeros(DIAMETER);
    private static final int VALUES_PER_LONG = Long.SIZE / BITS;
    public static final int PACKED_LENGTH = (COLUMN_COUNT + VALUES_PER_LONG - 1) / VALUES_PER_LONG;
    private static final long VALUE_MASK = (1L << BITS) - 1;

    private final CubeAccess cube;
    private final Predicate<BlockState> isOpaque;
    private final int minY;
    private SimpleBitStorage data = new SimpleBitStorage(BITS, COLUMN_COUNT);
    private boolean primed;

    public CubeHeightmap(CubeAccess cube, Heightmap.Types type) {
        this.cube = Objects.requireNonNull(cube, "cube");
        this.isOpaque = Objects.requireNonNull(type, "type").isOpaque();
        long minimum = (long) cube.cc_getCubePos().getY() * DIAMETER;
        if (minimum <= Integer.MIN_VALUE || minimum + DIAMETER > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cube height range and its empty/top sentinels must fit signed integers: " + cube.cc_getCubePos());
        }
        this.minY = (int) minimum;
    }

    public static void primeHeightmaps(CubeAccess cube, Set<Heightmap.Types> types) {
        Objects.requireNonNull(cube, "cube");
        for (Heightmap.Types type : Objects.requireNonNull(types, "types")) {
            ((CubeHeightmap) cube.cc_getOrCreateHeightmapUnprimed(type)).prime();
        }
    }

    public boolean isPrimed() {
        return this.primed;
    }

    public void prime() {
        if (this.primed) {
            return;
        }
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                this.data.set(index(x, z), findFirstAvailableOffset(x, DIAMETER - 1, z));
            }
        }
        this.primed = true;
    }

    /** Called after the block state changed. An unprimed map remains lazy and will observe the updated sections. */
    @Override public boolean update(int localX, int globalY, int localZ, BlockState state) {
        int index = index(localX, localZ);
        long localY = (long) globalY - this.minY;
        if (localY < 0 || localY >= DIAMETER) {
            throw new IllegalArgumentException("Block Y is outside cube " + this.cube.cc_getCubePos() + ": " + globalY);
        }
        Objects.requireNonNull(state, "state");
        if (!this.primed) {
            return false;
        }
        int previous = this.data.get(index);
        int changedOffset = (int) localY + 1;
        if (changedOffset < previous) {
            return false;
        }
        int next = previous;
        if (this.isOpaque.test(state)) {
            next = Math.max(previous, changedOffset);
        } else if (changedOffset == previous) {
            next = findFirstAvailableOffset(localX, (int) localY - 1, localZ);
        }
        if (next == previous) {
            return false;
        }
        this.data.set(index, next);
        return true;
    }

    @Override public int getFirstAvailable(int localX, int localZ) {
        int index = index(localX, localZ);
        prime();
        return this.minY + this.data.get(index);
    }

    @Override public int getHighestTaken(int localX, int localZ) {
        return getFirstAvailable(localX, localZ) - 1;
    }

    @Override public long[] getRawData() {
        prime();
        return this.data.getRaw().clone();
    }

    public void setRawData(long[] raw) {
        long[] snapshot = Objects.requireNonNull(raw, "raw").clone();
        validateRawData(snapshot);
        this.data = new SimpleBitStorage(BITS, COLUMN_COUNT, snapshot);
        this.primed = true;
    }

    /** Validates a serialized map without accessing a cube, registries, or block states. */
    public static void validateRawData(long[] raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length != PACKED_LENGTH) {
            throw new IllegalArgumentException("Expected " + PACKED_LENGTH + " packed heightmap longs, got " + raw.length);
        }
        for (int word = 0; word < raw.length; word++) {
            int values = Math.min(VALUES_PER_LONG, COLUMN_COUNT - word * VALUES_PER_LONG);
            int usedBits = values * BITS;
            if (usedBits < Long.SIZE && (raw[word] >>> usedBits) != 0) {
                throw new IllegalArgumentException("Nonzero heightmap padding in word " + word);
            }
            for (int value = 0; value < values; value++) {
                long offset = (raw[word] >>> (value * BITS)) & VALUE_MASK;
                if (offset > DIAMETER) {
                    throw new IllegalArgumentException("Heightmap offset is outside cube: " + offset);
                }
            }
        }
    }

    private int findFirstAvailableOffset(int localX, int topLocalY, int localZ) {
        LevelChunkSection[] sections = this.cube.getSections();
        for (int y = topLocalY; y >= 0; y--) {
            LevelChunkSection section = sections[Coords.blockToIndex(localX, y, localZ)];
            if (this.isOpaque.test(
                    section.getBlockState(SectionPos.sectionRelative(localX), SectionPos.sectionRelative(y), SectionPos.sectionRelative(localZ)))) {
                return y + 1;
            }
        }
        return 0;
    }

    private static int index(int localX, int localZ) {
        if (localX < 0 || localX >= DIAMETER || localZ < 0 || localZ >= DIAMETER) {
            throw new IllegalArgumentException("Expected cube-local X/Z in [0, " + DIAMETER + "): " + localX + ", " + localZ);
        }
        return localX + localZ * DIAMETER;
    }
}
