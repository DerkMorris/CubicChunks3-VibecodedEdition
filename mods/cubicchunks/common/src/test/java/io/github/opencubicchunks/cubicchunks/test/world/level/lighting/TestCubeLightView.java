package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.BoundedCubeLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightSolution;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.LightRange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class TestCubeLightView extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;

    @Test
    void exactImportAndExportPreserveEveryCellAndDetachAllMutableArrays() {
        CubePos pos = CubePos.of(-4, -100000, 3);
        byte[][] block = patterned(0);
        byte[][] sky = patterned(7);
        CubeLightView view = CubeLightView.fromExact(pos, block, sky);
        assertEquals(pos, view.pos());
        assertTrue(view.isReady());
        assertPattern(view, LightLayer.BLOCK, 0);
        assertPattern(view, LightLayer.SKY, 7);
        for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
            assertArrayEquals(block[section], view.packedLayer(LightLayer.BLOCK, section).orElseThrow());
            assertArrayEquals(sky[section], view.sectionData(LightLayer.SKY, section).orElseThrow().getData());
        }
        Arrays.fill(block[0], (byte) -1);
        sky[0] = new byte[DataLayer.SIZE];
        view.packedLayer(LightLayer.BLOCK, 0).orElseThrow()[0] = -1;
        DataLayer exported = view.sectionData(LightLayer.SKY, 0).orElseThrow();
        exported.fill(0);
        exported.getData()[0] = -1;
        assertEquals(new LightRange(0, 0), view.sample(LightLayer.BLOCK, pos.asBlockPos()).orElseThrow());
        assertEquals(new LightRange(7, 7), view.sample(LightLayer.SKY, pos.asBlockPos()).orElseThrow());
        assertEquals(7, view.sectionData(LightLayer.SKY, 0).orElseThrow().get(0, 0, 0));
    }

    @Test
    void incompleteCubeExportsOnlyFullyExactSections() {
        ProtoCube cube = TestBoundedCubeLightSolver.cube(CubePos.ZERO);
        CubeLightBoundary boundary = (pos, incoming) -> incoming == Direction.EAST ? Optional.empty()
                : Optional.of(new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), 0, 0));
        CubeLightView view = fromCube(cube, boundary);
        assertFalse(view.isReady());
        for (LightLayer layer : LightLayer.values()) {
            assertEquals(new LightRange(0, 14), view.sample(layer, BlockPos.ZERO).orElseThrow());
            assertEquals(new LightRange(0, 0), view.sample(layer, new BlockPos(16, 0, 0)).orElseThrow());
            for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                assertEquals(Coords.indexToX(section) == 1, view.sectionData(layer, section).isPresent());
                assertEquals(Coords.indexToX(section) == 1, view.packedLayer(layer, section).isPresent());
            }
        }
        int right = Coords.sectionToIndex(1, 0, 0);
        view.sectionData(LightLayer.BLOCK, right).orElseThrow().set(0, 0, 0, 15);
        assertEquals(0, view.sectionData(LightLayer.BLOCK, right).orElseThrow().get(0, 0, 0));
    }

    @Test
    void combinedBrightnessCanBeExactWhenOneLayerStillHasAnUncertainRange() {
        ProtoCube cube = TestBoundedCubeLightSolver.cube(CubePos.ZERO);
        TestBoundedCubeLightSolver.set(cube, 16, 16, 16, Blocks.GLOWSTONE.defaultBlockState());
        CubeLightView view = fromCube(cube, CubeLightBoundary.UNKNOWN);
        BlockPos adjacent = new BlockPos(16, 16, 15);
        assertEquals(new LightRange(14, 14), view.sample(LightLayer.BLOCK, adjacent).orElseThrow());
        assertEquals(new LightRange(0, 15), view.sample(LightLayer.SKY, adjacent).orElseThrow());
        assertTrue(view.rawBrightness(adjacent, 0).isEmpty());
        assertEquals(OptionalInt.of(14), view.rawBrightness(adjacent, 1));
        assertEquals(OptionalInt.of(14), view.rawBrightness(adjacent, 15));
        assertEquals(OptionalInt.of(14), view.rawBrightness(adjacent, 0, true, false));
        assertEquals(OptionalInt.of(0), view.rawBrightness(adjacent, 15, false, true));
        assertEquals(OptionalInt.of(0), view.rawBrightness(adjacent, -5, false, false));
    }

    @Test
    void exactImportsRejectIncompleteSectionsAndUnsupportedCoordinates() {
        byte[][] valid = filled(3);
        assertThrows(IllegalArgumentException.class, () -> CubeLightView.fromExact(CubePos.ZERO, new byte[0][], valid));
        byte[][] malformed = filled(4);
        malformed[1] = new byte[DataLayer.SIZE - 1];
        assertThrows(IllegalArgumentException.class, () -> CubeLightView.fromExact(CubePos.ZERO, valid, malformed));
        malformed[1] = null;
        assertThrows(IllegalArgumentException.class, () -> CubeLightView.fromExact(CubePos.ZERO, malformed, valid));
        assertThrows(IllegalArgumentException.class, () -> CubeLightView.fromExact(CubePos.of(0, CubePos.MAX_COORDINATE_VALUE + 1, 0), valid, valid));
        CubeLightView view = CubeLightView.fromExact(CubePos.ZERO, valid, valid);
        assertThrows(IllegalArgumentException.class, () -> view.sectionData(LightLayer.BLOCK, -1));
        assertThrows(IllegalArgumentException.class, () -> view.packedLayer(LightLayer.SKY, CubicConstants.SECTION_COUNT));
        assertTrue(view.sample(LightLayer.BLOCK, new BlockPos(DIAMETER, 0, 0)).isEmpty());
        assertTrue(view.rawBrightness(new BlockPos(-1, 0, 0), 0).isEmpty());
    }

    @Test
    void missingAndOutsideSolutionCubesDoNotProduceViews() {
        CubeLightBounds bounds = new CubeLightBounds(CubePos.ZERO, CubePos.ZERO);
        CubeLightSolution missing = BoundedCubeLightSolver.solve(bounds, Map.of(), CubeLightBoundary.UNKNOWN);
        assertTrue(missing.cubeView(CubePos.ZERO).isEmpty());
        assertTrue(CubeLightView.fromSolution(missing, CubePos.ZERO).isEmpty());
        ProtoCube cube = TestBoundedCubeLightSolver.cube(CubePos.ZERO);
        CubeLightSolution solution = BoundedCubeLightSolver.solve(bounds, Map.of(CubePos.ZERO, CubeLightBlockSnapshot.capture(cube)),
                CubeLightBoundary.UNKNOWN);
        assertTrue(solution.cubeView(CubePos.of(1, 0, 0)).isEmpty());
        assertFalse(solution.cubeView(CubePos.ZERO).orElseThrow().isReady());
    }

    static byte[][] filled(int value) {
        byte[][] sections = new byte[CubicConstants.SECTION_COUNT][];
        for (int section = 0; section < sections.length; section++) {
            sections[section] = new DataLayer(value).getData();
        }
        return sections;
    }

    static CubeLightView fromCube(ProtoCube cube, CubeLightBoundary boundary) {
        CubePos pos = cube.cc_getCubePos();
        CubeLightSolution solution = BoundedCubeLightSolver.solve(new CubeLightBounds(pos, pos), Map.of(pos, CubeLightBlockSnapshot.capture(cube)),
                boundary);
        return CubeLightView.fromSolution(solution, pos).orElseThrow();
    }

    private static byte[][] patterned(int offset) {
        byte[][] result = new byte[CubicConstants.SECTION_COUNT][];
        for (int section = 0; section < result.length; section++) {
            DataLayer layer = new DataLayer();
            for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
                for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                    for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                        layer.set(x, y, z, value(section, x, y, z, offset));
                    }
                }
            }
            result[section] = layer.getData();
        }
        return result;
    }

    private static void assertPattern(CubeLightView view, LightLayer layer, int offset) {
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    int section = Coords.blockToIndex(x, y, z);
                    int expected = value(section, SectionPos.sectionRelative(x), SectionPos.sectionRelative(y), SectionPos.sectionRelative(z),
                            offset);
                    assertEquals(new LightRange(expected, expected), view.sample(layer, view.pos().asBlockPos(x, y, z)).orElseThrow());
                }
            }
        }
    }

    private static int value(int section, int x, int y, int z, int offset) {
        return (section * 3 + x + y * 2 + z * 5 + offset) % 16;
    }
}
