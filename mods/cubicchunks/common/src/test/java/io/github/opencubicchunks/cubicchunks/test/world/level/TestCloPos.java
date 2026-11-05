package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import org.junit.jupiter.api.Test;

class TestCloPos extends BaseTest {
    @Test
    void distancesUseCubeYAndHandleMixedUnits() {
        CloPos cube = CloPos.cube(-3, 100, 5);
        assertEquals(25, cube.distanceSquared(CloPos.cube(-3, 105, 5)));
        assertEquals(25, cube.distanceSquared(CloPos.cube(-3, 105, 5).toLong()));
        CloPos column = CloPos.chunk(-3 * CubicConstants.DIAMETER_IN_SECTIONS, 5 * CubicConstants.DIAMETER_IN_SECTIONS);
        assertEquals(0, cube.distanceSquared(column));
        assertEquals(0, column.distanceSquared(cube));
        assertEquals(25, CloPos.chunk(0, 0).distanceSquared(CloPos.chunk(3, 4)));
        assertEquals(Integer.MAX_VALUE, CloPos.cube(Integer.MIN_VALUE, 0, 0).distanceSquared(CloPos.cube(Integer.MAX_VALUE, 0, 0)));
    }

    @Test
    void neighborhoodsKeepTheirPositionKindAndIncludeVerticalNeighbors() {
        CloPos cube = CloPos.cube(-2, 400, 3);
        Set<CloPos> cubes = CloPos.rangeClosed(cube, 1).collect(Collectors.toSet());
        assertEquals(27, cubes.size());
        assertTrue(cubes.stream().allMatch(CloPos::isCube));
        assertTrue(cubes.contains(CloPos.cube(-3, 399, 2)));
        assertTrue(cubes.contains(CloPos.cube(-1, 401, 4)));
        assertFalse(cubes.contains(CloPos.cube(-2, 402, 3)));
        Set<CloPos> columns = CloPos.rangeClosed(CloPos.chunk(-2, 3), 1).collect(Collectors.toSet());
        assertEquals(9, columns.size());
        assertTrue(columns.stream().allMatch(CloPos::isChunk));
        assertEquals(Set.of(cube), CloPos.rangeClosed(cube, 0).collect(Collectors.toSet()));
    }
}
