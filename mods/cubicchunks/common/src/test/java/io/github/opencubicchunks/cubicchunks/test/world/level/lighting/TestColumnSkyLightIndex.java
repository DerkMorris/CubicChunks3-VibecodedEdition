package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.test.world.level.lighting.TestCubeSkyOcclusion.newCube;
import static io.github.opencubicchunks.cubicchunks.test.world.level.lighting.TestCubeSkyOcclusion.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightIndex;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;

class TestColumnSkyLightIndex extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int MIN_CUBE = -CubePos.MAX_COORDINATE_VALUE - 1;
    private static final int MAX_CUBE = CubePos.MAX_COORDINATE_VALUE;

    @Test
    void fullyCoveredClearRangesDoNotClaimKnowledgeBeyondTheirBounds() {
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(0, 0);
        for (int y = -2; y <= 0; y++) {
            put(index, newCube(CubePos.of(0, y, 0)));
        }
        assertResult(index.query(0, 0, -2, 0), ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, null, null);
        assertResult(index.query(0, 0, -2, 1), ColumnSkyLightResult.State.UNKNOWN, null, 1);
        assertResult(index.query(0, 0, -3, 0), ColumnSkyLightResult.State.UNKNOWN, null, -3);
    }

    @Test
    void scansSparseObservationsWithoutTraversingHugeUnknownGaps() {
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(0, 0);
        assertResult(index.query(0, 0, MIN_CUBE, MAX_CUBE), ColumnSkyLightResult.State.UNKNOWN, null, MAX_CUBE);
        ProtoCube lower = newCube(CubePos.of(0, MIN_CUBE, 0));
        set(lower, 0, 3, 0, Blocks.STONE.defaultBlockState());
        put(index, lower);
        put(index, newCube(CubePos.of(0, MAX_CUBE, 0)));
        assertTimeout(Duration.ofSeconds(2), () -> {
            for (int repeat = 0; repeat < 1000; repeat++) {
                assertResult(index.query(0, 0, MIN_CUBE, MAX_CUBE), ColumnSkyLightResult.State.UNKNOWN, lower.cc_getCubePos().minCubeY() + 4,
                        MAX_CUBE - 1);
            }
        });
    }

    @Test
    void missingAndProvisionalCubesAboveTheWinnerRetainTheKnownCandidate() {
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(0, 0);
        ProtoCube lower = newCube(CubePos.of(0, -2, 0));
        set(lower, 0, 6, 0, Blocks.STONE.defaultBlockState());
        put(index, lower);
        put(index, newCube(CubePos.ZERO));
        int edge = lower.cc_getCubePos().minCubeY() + 7;
        assertResult(index.query(0, 0, -4, 0), ColumnSkyLightResult.State.UNKNOWN, edge, -1);
        put(index, newCube(CubePos.of(0, -1, 0)));
        assertResult(index.query(0, 0, -4, 0), ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, edge, null);
        index.put(CubePos.of(0, -1, 0), ChunkStatus.FEATURES, null);
        assertResult(index.query(0, 0, -4, 0), ColumnSkyLightResult.State.UNKNOWN, edge, -1);
        index.put(CubePos.ZERO, ChunkStatus.EMPTY, CubeSkyOcclusion.capture(lower));
        assertResult(index.query(0, 0, -4, 0), ColumnSkyLightResult.State.UNKNOWN, edge, 0);
    }

    @Test
    void onlyAdjacentFullCubesContributeBoundaryEdgesInsideTheQuery() {
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(0, 0);
        ProtoCube lower = newCube(CubePos.of(0, -1, 0));
        ProtoCube upper = newCube(CubePos.ZERO);
        set(upper, 0, 0, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        put(index, upper);
        assertResult(index.query(0, 0, 0, 0), ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, null, null);
        assertResult(index.query(0, 0, -1, 0), ColumnSkyLightResult.State.UNKNOWN, null, -1);
        put(index, lower);
        assertResult(index.query(0, 0, -3, 0), ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, 0, null);
        assertResult(index.query(0, 0, -1, -1), ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, null, null);
        index.put(lower.cc_getCubePos(), ChunkStatus.FEATURES, null);
        assertResult(index.query(0, 0, -1, 0), ColumnSkyLightResult.State.UNKNOWN, null, -1);

        set(upper, 0, 0, 0, Blocks.AIR.defaultBlockState());
        set(lower, 0, DIAMETER - 1, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        put(index, upper);
        put(index, lower);
        assertResult(index.query(0, 0, -1, 0), ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, 0, null);
        assertResult(index.query(0, 0, -1, -1), ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, null, null);
    }

    @Test
    void internalEdgesOutrankTheLowerBoundaryAndPreserveExtremeHorizontalCoordinates() {
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(MIN_CUBE, MAX_CUBE);
        for (int y : new int[] { MIN_CUBE, MAX_CUBE }) {
            CubePos pos = CubePos.of(MIN_CUBE, y, MAX_CUBE);
            ProtoCube cube = newCube(pos);
            for (int z : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                for (int x : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                    set(cube, x, (x + z) % (DIAMETER - 1), z, Blocks.STONE.defaultBlockState());
                }
            }
            put(index, cube);
            for (int z : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                for (int x : new int[] { 0, 15, 16, DIAMETER - 1 }) {
                    assertResult(index.query(pos.minCubeX() + x, pos.minCubeZ() + z, y, y), ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE,
                            pos.minCubeY() + (x + z) % (DIAMETER - 1) + 1, null);
                }
            }
        }
        ColumnSkyLightIndex zero = new ColumnSkyLightIndex(0, 0);
        ProtoCube lower = newCube(CubePos.of(0, -1, 0));
        ProtoCube upper = newCube(CubePos.ZERO);
        set(lower, 0, DIAMETER - 1, 0, Blocks.STONE.defaultBlockState());
        set(upper, 0, 8, 0, Blocks.STONE.defaultBlockState());
        put(zero, lower);
        put(zero, upper);
        assertResult(zero.query(0, 0, -1, 0), ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, 9, null);
    }

    @Test
    void overlaysRetainImmutableSnapshotsAndIncludeClearAndProvisionalChanges() {
        ColumnSkyLightIndex persisted = new ColumnSkyLightIndex(0, 0);
        ProtoCube cube = newCube(CubePos.ZERO);
        set(cube, 0, 9, 0, Blocks.STONE.defaultBlockState());
        put(persisted, cube);
        ColumnSkyLightIndex live = new ColumnSkyLightIndex(0, 0);
        set(cube, 0, 9, 0, Blocks.AIR.defaultBlockState());
        put(live, cube);
        persisted.overlay(live);
        set(cube, 0, 29, 0, Blocks.STONE.defaultBlockState());
        put(live, cube);
        assertResult(persisted.query(0, 0, 0, 0), ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, null, null);
        persisted.overlay(live);
        assertResult(persisted.query(0, 0, 0, 0), ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, 30, null);
        live.put(CubePos.ZERO, ChunkStatus.EMPTY, null);
        persisted.overlay(live);
        assertResult(persisted.query(0, 0, 0, 0), ColumnSkyLightResult.State.UNKNOWN, null, 0);
    }

    @Test
    void rejectsInvalidCoordinatesAndForeignSnapshotsBeforeReplacingValidData() {
        assertThrows(IllegalArgumentException.class, () -> new ColumnSkyLightIndex(MIN_CUBE - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ColumnSkyLightIndex(0, MAX_CUBE + 1));
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(-3, 4);
        CubePos pos = CubePos.of(-3, 0, 4);
        CubeSkyOcclusion snapshot = CubeSkyOcclusion.capture(newCube(pos));
        index.put(pos, ChunkStatus.FULL, snapshot);
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.ZERO, ChunkStatus.FULL, snapshot));
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.of(-3, 1, 4), ChunkStatus.FULL, snapshot));
        assertThrows(NullPointerException.class, () -> index.put(pos, ChunkStatus.FULL, null));
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.of(-3, MAX_CUBE + 1, 4), ChunkStatus.EMPTY, null));
        int x = Coords.cubeToMinBlock(-3);
        int z = Coords.cubeToMinBlock(4);
        assertResult(index.query(x, z, 0, 0), ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE, null, null);
        assertThrows(IllegalArgumentException.class, () -> index.query(x - 1, z, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(x, z + DIAMETER, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(x, z, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(x, z, MIN_CUBE - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(x, z, 0, MAX_CUBE + 1));
        assertThrows(IllegalArgumentException.class, () -> index.overlay(new ColumnSkyLightIndex(-3, 5)));
    }

    private static void put(ColumnSkyLightIndex index, ProtoCube cube) {
        index.put(cube.cc_getCubePos(), ChunkStatus.FULL, CubeSkyOcclusion.capture(cube));
    }

    private static void assertResult(ColumnSkyLightResult result, ColumnSkyLightResult.State state, Integer edge, Integer unknown) {
        assertEquals(state, result.state());
        assertEquals(edge == null ? OptionalInt.empty() : OptionalInt.of(edge), result.highestKnownBlockingY());
        assertEquals(unknown == null ? OptionalInt.empty() : OptionalInt.of(unknown), result.firstUnknownCubeY());
        if (state == ColumnSkyLightResult.State.UNKNOWN) {
            assertFalse(result.isComplete());
        } else {
            assertTrue(result.isComplete());
        }
    }
}
