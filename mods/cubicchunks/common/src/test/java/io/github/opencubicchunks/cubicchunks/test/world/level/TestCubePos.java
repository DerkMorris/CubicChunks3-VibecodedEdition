package io.github.opencubicchunks.cubicchunks.test.world.level;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.status.CubePyramid;
import org.junit.jupiter.api.Test;

class TestCubePos extends BaseTest {
    @Test
    void generationBoundsCoverEveryAxisWithoutOverflow() throws Exception {
        // This method is supplied by Mixin because its bound depends on the game's generation pyramid.
        Method isValid = CubePos.class.getMethod("isValid");
        int max = CubePyramid.MAX_CHUNK_COORDINATE_VALUE;
        assertTrue(max > 0 && max < (1 << 20) - 1);
        assertTrue((boolean) isValid.invoke(CubePos.ZERO));
        for (int axis = 0; axis < 3; axis++) {
            for (int coordinate : new int[] { -max, max }) {
                int[] pos = new int[3];
                pos[axis] = coordinate;
                assertTrue((boolean) isValid.invoke(CubePos.of(pos[0], pos[1], pos[2])), "boundary axis " + axis);
            }
            for (int coordinate : new int[] { -max - 1, max + 1, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
                int[] pos = new int[3];
                pos[axis] = coordinate;
                assertFalse((boolean) isValid.invoke(CubePos.of(pos[0], pos[1], pos[2])), "outside axis " + axis);
            }
        }
    }
}
