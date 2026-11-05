package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.BoundedCubeLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightSolution;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.LightRange;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestCubeLightData extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int MIN_CUBE = -CubePos.MAX_COORDINATE_VALUE - 1;
    private static final int MAX_CUBE = CubePos.MAX_COORDINATE_VALUE;

    @Test
    void validatesBoundsBeforeDomainAllocationAndEnumeratesTheInclusiveCubeBox() {
        CubeLightBounds bounds = new CubeLightBounds(CubePos.of(-1, -1, -1), CubePos.of(1, 1, 1));
        assertEquals(CubeLightBounds.MAX_CUBES, bounds.cubeCount());
        assertEquals(bounds.cubeCount(), bounds.positions().size());
        assertEquals(bounds.positions().size(), bounds.positions().stream().distinct().count());
        assertEquals(bounds.sizeX() * bounds.sizeY() * bounds.sizeZ(), bounds.volume());
        assertTrue(bounds.volume() <= CubeLightBounds.MAX_CELLS);
        assertTrue(bounds.contains(new BlockPos(-DIAMETER, -DIAMETER, -DIAMETER)));
        assertTrue(bounds.contains(new BlockPos(2 * DIAMETER - 1, 2 * DIAMETER - 1, 2 * DIAMETER - 1)));
        assertFalse(bounds.contains(new BlockPos(2 * DIAMETER, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> bounds.positions().clear());
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBounds(CubePos.of(1, 0, 0), CubePos.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBounds(CubePos.ZERO, CubePos.of(CubeLightBounds.MAX_CUBES, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBounds(CubePos.ZERO, CubePos.of(3, 3, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> new CubeLightBounds(CubePos.of(MIN_CUBE, MIN_CUBE, MIN_CUBE), CubePos.of(MAX_CUBE, MAX_CUBE, MAX_CUBE)));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBounds(CubePos.of(MIN_CUBE - 1, 0, 0), CubePos.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBounds(CubePos.ZERO, CubePos.of(0, MAX_CUBE + 1, 0)));
    }

    @Test
    void snapshotsDetachAllSectionsAndRejectLocalCoordinateAliasing() {
        ProtoCube cube = TestBoundedCubeLightSolver.cube(CubePos.of(-3, -11, 2));
        for (int x : new int[] { 0, 15, 16, DIAMETER - 1 }) {
            for (int y : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                for (int z : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                    TestBoundedCubeLightSolver.set(cube, x, y, z, Blocks.GLOWSTONE.defaultBlockState());
                }
            }
        }
        CubeLightBlockSnapshot snapshot = CubeLightBlockSnapshot.capture(cube);
        assertEquals(cube.cc_getCubePos(), snapshot.pos());
        for (int x : new int[] { 0, 15, 16, DIAMETER - 1 }) {
            for (int y : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                for (int z : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                    TestBoundedCubeLightSolver.set(cube, x, y, z, Blocks.AIR.defaultBlockState());
                    assertSame(Blocks.GLOWSTONE.defaultBlockState(), snapshot.state(x, y, z));
                }
            }
        }
        assertSame(Blocks.AIR.defaultBlockState(), snapshot.state(1, 1, 1));
        for (int invalid : new int[] { -1, DIAMETER, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> snapshot.state(invalid, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> snapshot.state(0, invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> snapshot.state(0, 0, invalid));
        }
    }

    @Test
    void missingCubesAreUnsampledAndForeignSnapshotsAreRejected() {
        CubeLightBounds bounds = new CubeLightBounds(CubePos.ZERO, CubePos.ZERO);
        CubeLightSolution empty = BoundedCubeLightSolver.solve(bounds, Map.of(), (pos, direction) -> {
            throw new AssertionError("An entirely missing domain must not query external boundaries");
        });
        assertSame(bounds, empty.bounds());
        assertTrue(empty.sample(LightLayer.BLOCK, BlockPos.ZERO).isEmpty());
        assertTrue(empty.sample(LightLayer.SKY, new BlockPos(-1, 0, 0)).isEmpty());
        assertFalse(empty.isReady(CubePos.ZERO));
        assertFalse(empty.isReady(CubePos.ZERO, LightLayer.BLOCK));
        assertFalse(empty.isReady(CubePos.of(1, 0, 0), LightLayer.SKY));
        CubePos other = CubePos.of(1, 0, 0);
        CubeLightBlockSnapshot snapshot = CubeLightBlockSnapshot.capture(TestBoundedCubeLightSolver.cube(other));
        assertThrows(IllegalArgumentException.class, () -> BoundedCubeLightSolver.solve(bounds, Map.of(other, snapshot), CubeLightBoundary.UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCubeLightSolver.solve(bounds, Map.of(CubePos.ZERO, snapshot), CubeLightBoundary.UNKNOWN));
    }

    @Test
    void lightRangesAndBoundaryObservationsRejectInvalidLevels() {
        LightRange unknown = new LightRange(0, 15);
        assertFalse(unknown.isReady());
        assertTrue(unknown.exactValue().isEmpty());
        LightRange exact = new LightRange(7, 7);
        assertTrue(exact.isReady());
        assertEquals(OptionalInt.of(7), exact.exactValue());
        assertThrows(IllegalArgumentException.class, () -> new LightRange(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LightRange(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new LightRange(8, 7));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), 0, 16));
        assertThrows(NullPointerException.class, () -> new CubeLightBoundary.BoundaryLight(null, 0, 0));
    }
}
