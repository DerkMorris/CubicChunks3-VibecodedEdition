package io.github.opencubicchunks.cubicchunks.test.world.level.levelgen.heightmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class TestCubeHeightmap extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;

    @Test
    void lazilyPrimesEveryHorizontalAndVerticalSection() {
        Fixture fixture = new Fixture(-17);
        CubeHeightmap map = fixture.map(Heightmap.Types.WORLD_SURFACE);
        assertFalse(map.isPrimed());
        for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
            int x = Coords.indexToX(section) * 16 + Coords.indexToY(section) + 1;
            int y = Coords.indexToY(section) * 16 + 2;
            int z = Coords.indexToZ(section) * 16 + 3;
            fixture.set(x, y, z, Blocks.STONE.defaultBlockState());
            assertFalse(map.update(x, fixture.minY + y, z, Blocks.STONE.defaultBlockState()));
        }
        assertFalse(map.isPrimed());
        assertAllColumns(fixture, Heightmap.Types.WORLD_SURFACE);
        assertTrue(map.isPrimed());
    }

    @Test
    void preservesColumnBoundariesAndScansBelowRemovedTop() {
        Fixture fixture = new Fixture(-2);
        CubeHeightmap map = fixture.map(Heightmap.Types.WORLD_SURFACE);
        map.prime();
        for (int x : new int[] { 0, 15, 16, DIAMETER - 1 }) {
            for (int z : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                fixture.set(x, 0, z, Blocks.STONE.defaultBlockState());
                assertTrue(map.update(x, fixture.minY, z, Blocks.STONE.defaultBlockState()));
                fixture.set(x, 16, z, Blocks.DIAMOND_BLOCK.defaultBlockState());
                assertTrue(map.update(x, fixture.minY + 16, z, Blocks.DIAMOND_BLOCK.defaultBlockState()));
                fixture.set(x, DIAMETER - 1, z, Blocks.GOLD_BLOCK.defaultBlockState());
                assertTrue(map.update(x, fixture.minY + DIAMETER - 1, z, Blocks.GOLD_BLOCK.defaultBlockState()));
                assertEquals(fixture.minY + DIAMETER, map.getFirstAvailable(x, z));
                fixture.set(x, DIAMETER - 1, z, Blocks.AIR.defaultBlockState());
                assertTrue(map.update(x, fixture.minY + DIAMETER - 1, z, Blocks.AIR.defaultBlockState()));
                assertEquals(fixture.minY + 16, map.getHighestTaken(x, z));
                fixture.set(x, 16, z, Blocks.AIR.defaultBlockState());
                assertTrue(map.update(x, fixture.minY + 16, z, Blocks.AIR.defaultBlockState()));
                assertEquals(fixture.minY, map.getHighestTaken(x, z));
                fixture.set(x, 0, z, Blocks.AIR.defaultBlockState());
                assertTrue(map.update(x, fixture.minY, z, Blocks.AIR.defaultBlockState()));
                assertEquals(fixture.minY - 1, map.getHighestTaken(x, z));
            }
        }
        assertAllColumns(fixture, Heightmap.Types.WORLD_SURFACE);
    }

    @Test
    void honorsEveryVanillaPredicateForFluidsLeavesAndPlants() {
        Fixture fixture = new Fixture(3);
        fixture.set(0, 4, 0, Blocks.WATER.defaultBlockState());
        fixture.set(1, 7, 0, Blocks.OAK_LEAVES.defaultBlockState());
        fixture.set(2, 10, 0, Blocks.SHORT_GRASS.defaultBlockState());
        fixture.set(3, 13, 0, Blocks.LAVA.defaultBlockState());
        fixture.set(4, 1, 0, Blocks.STONE.defaultBlockState());
        fixture.set(4, 4, 0, Blocks.WATER.defaultBlockState());
        fixture.set(4, 7, 0, Blocks.OAK_LEAVES.defaultBlockState());
        fixture.set(4, 10, 0, Blocks.SHORT_GRASS.defaultBlockState());
        for (Heightmap.Types type : Heightmap.Types.values()) {
            CubeHeightmap map = fixture.map(type);
            boolean surface = type == Heightmap.Types.WORLD_SURFACE || type == Heightmap.Types.WORLD_SURFACE_WG;
            boolean floor = type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG;
            assertEquals(fixture.minY + (floor ? -1 : 4), map.getHighestTaken(0, 0), type.toString());
            assertEquals(fixture.minY + (type == Heightmap.Types.MOTION_BLOCKING_NO_LEAVES ? -1 : 7), map.getHighestTaken(1, 0), type.toString());
            assertEquals(fixture.minY + (surface ? 10 : -1), map.getHighestTaken(2, 0), type.toString());
            assertEquals(fixture.minY + (floor ? -1 : 13), map.getHighestTaken(3, 0), type.toString());
            int stackTop = surface ? 10 : type == Heightmap.Types.MOTION_BLOCKING_NO_LEAVES ? 4 : 7;
            assertEquals(fixture.minY + stackTop, map.getHighestTaken(4, 0), type.toString());
            assertAllColumns(fixture, type);
        }
    }

    @Test
    void randomizedUpdatesMatchIndependentBlockArrayOracle() {
        Fixture fixture = new Fixture(-100000);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            fixture.map(type).prime();
        }
        BlockState[] states = { Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState() };
        Random random = new Random(0xC0B1C);
        for (int change = 0; change < 6000; change++) {
            int x = random.nextInt(DIAMETER);
            int y = random.nextInt(DIAMETER);
            int z = random.nextInt(DIAMETER);
            BlockState state = states[random.nextInt(states.length)];
            fixture.set(x, y, z, state);
            for (Heightmap.Types type : Heightmap.Types.values()) {
                CubeHeightmap map = fixture.map(type);
                int previous = map.getHighestTaken(x, z);
                int expected = fixture.highest(type, x, z);
                assertEquals(previous != expected, map.update(x, fixture.minY + y, z, state));
                assertEquals(expected, map.getHighestTaken(x, z), type + " at change " + change);
            }
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            assertAllColumns(fixture, type);
        }
    }

    @Test
    void acceptsNegativeAndExtremeHeightsWithoutOverflowOrDimensionClamping() {
        for (int cubeY : new int[] { -1, 0, -1000000, 1000000, Integer.MIN_VALUE / DIAMETER + 1, Integer.MAX_VALUE / DIAMETER - 1 }) {
            Fixture fixture = new Fixture(cubeY);
            CubeHeightmap map = fixture.map(Heightmap.Types.WORLD_SURFACE);
            assertEquals(fixture.minY - 1, map.getHighestTaken(0, 0));
            fixture.set(0, 0, 0, Blocks.STONE.defaultBlockState());
            assertTrue(map.update(0, fixture.minY, 0, Blocks.STONE.defaultBlockState()));
            fixture.set(DIAMETER - 1, DIAMETER - 1, DIAMETER - 1, Blocks.STONE.defaultBlockState());
            assertTrue(map.update(DIAMETER - 1, fixture.minY + DIAMETER - 1, DIAMETER - 1, Blocks.STONE.defaultBlockState()));
            assertEquals(fixture.minY, map.getHighestTaken(0, 0));
            assertEquals(fixture.minY + DIAMETER, map.getFirstAvailable(DIAMETER - 1, DIAMETER - 1));
        }
        for (int cubeY : new int[] { Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE / DIAMETER, Integer.MAX_VALUE / DIAMETER }) {
            CubeAccess cube = mock();
            when(cube.cc_getCubePos()).thenReturn(CubePos.of(0, cubeY, 0));
            assertThrows(IllegalArgumentException.class, () -> new CubeHeightmap(cube, Heightmap.Types.WORLD_SURFACE));
        }
    }

    @Test
    void rejectsCoordinatesInsteadOfAliasingNeighborColumnsOrCubes() {
        Fixture fixture = new Fixture(-1);
        CubeHeightmap map = fixture.map(Heightmap.Types.WORLD_SURFACE);
        for (int invalid : new int[] { -1, DIAMETER, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> map.getFirstAvailable(invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> map.getHighestTaken(0, invalid));
            assertThrows(IllegalArgumentException.class, () -> map.update(invalid, fixture.minY, 0, Blocks.STONE.defaultBlockState()));
            assertThrows(IllegalArgumentException.class, () -> map.update(0, fixture.minY, invalid, Blocks.STONE.defaultBlockState()));
        }
        for (int invalidY : new int[] { fixture.minY - 1, fixture.minY + DIAMETER, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> map.update(0, invalidY, 0, Blocks.STONE.defaultBlockState()));
        }
        assertFalse(map.isPrimed());
    }

    @Test
    void rawRoundTripIsDetachedAndUsesCubeRelativeOffsets() {
        Fixture original = new Fixture(-23);
        original.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        original.set(DIAMETER - 1, DIAMETER - 1, DIAMETER - 1, Blocks.STONE.defaultBlockState());
        CubeHeightmap map = original.map(Heightmap.Types.WORLD_SURFACE);
        long[] raw = map.getRawData();
        assertTrue(map.isPrimed());
        assertEquals(CubeHeightmap.PACKED_LENGTH, raw.length);
        long[] expectedRaw = raw.clone();
        CubeHeightmap restored = new Fixture(11).map(Heightmap.Types.WORLD_SURFACE);
        restored.setRawData(raw);
        Arrays.fill(raw, -1L);
        assertArrayEquals(expectedRaw, map.getRawData());
        assertArrayEquals(expectedRaw, restored.getRawData());
        assertEquals(11 * DIAMETER, restored.getHighestTaken(0, 0));
        assertEquals(12 * DIAMETER, restored.getFirstAvailable(DIAMETER - 1, DIAMETER - 1));
        assertEquals(11 * DIAMETER - 1, restored.getHighestTaken(1, 0));
        restored.prime();
        assertArrayEquals(expectedRaw, restored.getRawData(), "Priming an already restored map must preserve its snapshot");
    }

    @Test
    void rejectsMalformedRawValuesLengthsAndPaddingWithoutReplacingMap() {
        CubeHeightmap map = new Fixture(0).map(Heightmap.Types.WORLD_SURFACE);
        long[] valid = map.getRawData();
        assertThrows(IllegalArgumentException.class, () -> map.setRawData(new long[valid.length - 1]));
        assertThrows(IllegalArgumentException.class, () -> map.setRawData(new long[valid.length + 1]));
        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(DIAMETER);
        int valuesPerLong = Long.SIZE / bits;
        long[] invalidValue = valid.clone();
        invalidValue[0] = DIAMETER + 1;
        assertThrows(IllegalArgumentException.class, () -> map.setRawData(invalidValue));
        long[] invalidPadding = valid.clone();
        invalidPadding[0] = 1L << (valuesPerLong * bits);
        assertThrows(IllegalArgumentException.class, () -> CubeHeightmap.validateRawData(invalidPadding));
        long[] invalidTail = valid.clone();
        int lastValues = DIAMETER * DIAMETER - (valid.length - 1) * valuesPerLong;
        invalidTail[invalidTail.length - 1] = 1L << (lastValues * bits);
        assertThrows(IllegalArgumentException.class, () -> map.setRawData(invalidTail));
        assertArrayEquals(valid, map.getRawData());
    }

    @Test
    void batchPrimingOnlyCreatesRequestedMaps() {
        Fixture fixture = new Fixture(0);
        fixture.set(2, 5, 3, Blocks.STONE.defaultBlockState());
        CubeHeightmap unrequested = fixture.map(Heightmap.Types.OCEAN_FLOOR);
        CubeHeightmap.primeHeightmaps(fixture.cube, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING));
        assertFalse(unrequested.isPrimed());
        assertEquals(3, fixture.maps.size());
        assertTrue(fixture.map(Heightmap.Types.WORLD_SURFACE).isPrimed());
        assertTrue(fixture.map(Heightmap.Types.MOTION_BLOCKING).isPrimed());
        assertEquals(5, fixture.map(Heightmap.Types.WORLD_SURFACE).getHighestTaken(2, 3));
    }

    private static void assertAllColumns(Fixture fixture, Heightmap.Types type) {
        CubeHeightmap map = fixture.map(type);
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                int expected = fixture.highest(type, x, z);
                assertEquals(expected, map.getHighestTaken(x, z), type + " at " + x + ", " + z);
                assertEquals(expected + 1, map.getFirstAvailable(x, z));
            }
        }
    }

    private static final class Fixture {
        private final CubeAccess cube = mock();
        private final LevelChunkSection[] sections = new LevelChunkSection[CubicConstants.SECTION_COUNT];
        private final BlockState[][][] blocks = new BlockState[DIAMETER][DIAMETER][DIAMETER];
        private final Map<Heightmap.Types, CubeHeightmap> maps = new EnumMap<>(Heightmap.Types.class);
        private final int minY;

        private Fixture(int cubeY) {
            this.minY = cubeY * DIAMETER;
            when(this.cube.cc_getCubePos()).thenReturn(CubePos.of(-7, cubeY, 9));
            var palettes = PalettedContainerFactory.create(VanillaRegistriesExtension.shared());
            Arrays.setAll(this.sections, ignored -> new LevelChunkSection(palettes));
            when(this.cube.getSections()).thenReturn(this.sections);
            when(this.cube.cc_getOrCreateHeightmapUnprimed(any())).thenAnswer(call -> map(call.getArgument(0)));
            for (BlockState[][] plane : this.blocks) {
                for (BlockState[] column : plane) {
                    Arrays.fill(column, Blocks.AIR.defaultBlockState());
                }
            }
        }

        private CubeHeightmap map(Heightmap.Types type) {
            return this.maps.computeIfAbsent(type, key -> new CubeHeightmap(this.cube, key));
        }

        private void set(int x, int y, int z, BlockState state) {
            this.blocks[x][z][y] = state;
            this.sections[Coords.blockToIndex(x, y, z)].setBlockState(x & 15, y & 15, z & 15, state);
        }

        private int highest(Heightmap.Types type, int x, int z) {
            for (int y = DIAMETER - 1; y >= 0; y--) {
                if (type.isOpaque().test(this.blocks[x][z][y])) {
                    return this.minY + y;
                }
            }
            return this.minY - 1;
        }
    }
}
