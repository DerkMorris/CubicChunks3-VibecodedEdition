package io.github.opencubicchunks.cubicchunks.test.world.level.levelgen.heightmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceIndex;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.ColumnSurfaceResult;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class TestColumnSurfaceIndex extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int MIN_CUBE = -CubePos.MAX_COORDINATE_VALUE - 1;
    private static final int MAX_CUBE = CubePos.MAX_COORDINATE_VALUE;
    private static final Heightmap.Types SURFACE = Heightmap.Types.WORLD_SURFACE;

    @Test
    void missingCubesRemainUnknownAcrossHugeSparseIntervals() {
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(0, 0);
        assertResult(index.query(SURFACE, 0, 0, MIN_CUBE, MAX_CUBE), ColumnSurfaceResult.State.UNKNOWN, null, MAX_CUBE);
        index.put(CubePos.of(0, MIN_CUBE, 0), ChunkStatus.FULL, maps(0, 0, 1));
        index.put(CubePos.of(0, MAX_CUBE, 0), ChunkStatus.FULL, maps(0, 0, 0));
        assertTimeout(Duration.ofSeconds(2), () -> {
            for (int repeat = 0; repeat < 1000; repeat++) {
                assertResult(index.query(SURFACE, 0, 0, MIN_CUBE, MAX_CUBE), ColumnSurfaceResult.State.UNKNOWN, Coords.cubeToMinBlock(MIN_CUBE),
                        MAX_CUBE - 1);
            }
        });
    }

    @Test
    void onlyExplicitFullCoverageCanEstablishAnEmptyRange() {
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(0, 0);
        for (int y = -2; y <= 0; y++) {
            index.put(CubePos.of(0, y, 0), ChunkStatus.FULL, maps(0, 0, 0));
        }
        assertResult(index.query(SURFACE, 0, 0, -2, 0), ColumnSurfaceResult.State.KNOWN_EMPTY, null, null);
        assertResult(index.query(SURFACE, 0, 0, -3, 0), ColumnSurfaceResult.State.UNKNOWN, null, -3);
        assertResult(index.query(SURFACE, 0, 0, -2, 1), ColumnSurfaceResult.State.UNKNOWN, null, 1);
    }

    @Test
    void gapsAndProvisionalCubesAboveTheWinnerKeepItsHeightButNotCompleteness() {
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(0, 0);
        index.put(CubePos.of(0, -2, 0), ChunkStatus.FULL, maps(0, 0, 7));
        index.put(CubePos.ZERO, ChunkStatus.FULL, maps(0, 0, 0));
        int candidate = Coords.cubeToMinBlock(-2) + 6;
        assertResult(index.query(SURFACE, 0, 0, -4, 0), ColumnSurfaceResult.State.UNKNOWN, candidate, -1);
        index.put(CubePos.of(0, -1, 0), ChunkStatus.FULL, maps(0, 0, 0));
        // Unknown cubes below the winning block cannot change the surface within this query.
        assertResult(index.query(SURFACE, 0, 0, -4, 0), ColumnSurfaceResult.State.KNOWN_HEIGHT, candidate, null);
        index.put(CubePos.of(0, -1, 0), ChunkStatus.FEATURES, null);
        assertResult(index.query(SURFACE, 0, 0, -4, 0), ColumnSurfaceResult.State.UNKNOWN, candidate, -1);
        index.put(CubePos.ZERO, ChunkStatus.EMPTY, Map.of(SURFACE, new long[0]));
        assertResult(index.query(SURFACE, 0, 0, -4, 0), ColumnSurfaceResult.State.UNKNOWN, candidate, 0);
    }

    @Test
    void anEmptyUpperCubeDoesNotBecomeAnImaginarySurface() {
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(0, 0);
        index.put(CubePos.of(0, -3, 0), ChunkStatus.FULL, maps(0, 0, 20));
        index.put(CubePos.of(0, -2, 0), ChunkStatus.FULL, maps(0, 0, 0));
        index.put(CubePos.of(0, 100, 0), ChunkStatus.FULL, maps(0, 0, 31));
        assertResult(index.query(SURFACE, 0, 0, -3, -2), ColumnSurfaceResult.State.KNOWN_HEIGHT, Coords.cubeToMinBlock(-3) + 19, null);
        index.put(CubePos.of(0, -3, 0), ChunkStatus.FULL, maps(0, 0, 0));
        assertResult(index.query(SURFACE, 0, 0, -3, -2), ColumnSurfaceResult.State.KNOWN_EMPTY, null, null);
    }

    @Test
    void allTypesAndHorizontalColumnsRemainDistinctAtCoordinateLimits() {
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(MIN_CUBE, MAX_CUBE);
        Map<Heightmap.Types, long[]> snapshot = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            SimpleBitStorage data = storage();
            for (int z : new int[] { 0, 15, 16, 31 }) {
                for (int x : new int[] { 0, 15, 16, 31 }) {
                    data.set(x + z * DIAMETER, (x + z + type.ordinal()) % DIAMETER + 1);
                }
            }
            snapshot.put(type, data.getRaw());
        }
        for (int cubeY : new int[] { MIN_CUBE, MAX_CUBE }) {
            index.put(CubePos.of(MIN_CUBE, cubeY, MAX_CUBE), ChunkStatus.FULL, snapshot);
            for (Heightmap.Types type : Heightmap.Types.values()) {
                for (int z : new int[] { 0, 15, 16, 31 }) {
                    for (int x : new int[] { 0, 15, 16, 31 }) {
                        int expected = Coords.cubeToMinBlock(cubeY) + (x + z + type.ordinal()) % DIAMETER;
                        assertResult(index.query(type, Coords.cubeToMinBlock(MIN_CUBE) + x, Coords.cubeToMinBlock(MAX_CUBE) + z, cubeY, cubeY),
                                ColumnSurfaceResult.State.KNOWN_HEIGHT, expected, null);
                    }
                }
            }
        }
    }

    @Test
    void overlaysCaptureEmptyAndProvisionalChangesWithoutSharingMutableState() {
        ColumnSurfaceIndex persisted = new ColumnSurfaceIndex(0, 0);
        persisted.put(CubePos.ZERO, ChunkStatus.FULL, maps(0, 0, 12));
        persisted.put(CubePos.of(0, 1, 0), ChunkStatus.FULL, maps(0, 0, 5));
        ColumnSurfaceIndex live = new ColumnSurfaceIndex(0, 0);
        Map<Heightmap.Types, long[]> empty = maps(0, 0, 0);
        live.put(CubePos.of(0, 1, 0), ChunkStatus.FULL, empty);
        empty.values().forEach(data -> data[0] = -1L);
        empty.clear();
        persisted.overlay(live);
        assertResult(persisted.query(SURFACE, 0, 0, 0, 1), ColumnSurfaceResult.State.KNOWN_HEIGHT, 11, null);
        live.put(CubePos.of(0, 1, 0), ChunkStatus.FULL, maps(0, 0, 31));
        assertResult(persisted.query(SURFACE, 0, 0, 0, 1), ColumnSurfaceResult.State.KNOWN_HEIGHT, 11, null);
        persisted.overlay(live);
        assertResult(persisted.query(SURFACE, 0, 0, 0, 1), ColumnSurfaceResult.State.KNOWN_HEIGHT, DIAMETER + 30, null);
        live.put(CubePos.of(0, 1, 0), ChunkStatus.FEATURES, Map.of());
        persisted.overlay(live);
        assertResult(persisted.query(SURFACE, 0, 0, 0, 1), ColumnSurfaceResult.State.UNKNOWN, 11, 1);
    }

    @Test
    void invalidFullSnapshotsCannotReplaceAnExistingObservation() {
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(0, 0);
        Map<Heightmap.Types, long[]> original = maps(0, 0, 9);
        index.put(CubePos.ZERO, ChunkStatus.FULL, original);
        original.values().forEach(data -> data[0] = -1L);
        original.clear();
        assertResult(index.query(SURFACE, 0, 0, 0, 0), ColumnSurfaceResult.State.KNOWN_HEIGHT, 8, null);
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.ZERO, ChunkStatus.FULL, Map.of()));
        Map<Heightmap.Types, long[]> malformed = maps(0, 0, 0);
        malformed.put(SURFACE, new long[CubeHeightmap.PACKED_LENGTH - 1]);
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.ZERO, ChunkStatus.FULL, malformed));
        malformed.put(SURFACE, new long[CubeHeightmap.PACKED_LENGTH]);
        malformed.get(SURFACE)[0] = 63;
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.ZERO, ChunkStatus.FULL, malformed));
        assertResult(index.query(SURFACE, 0, 0, 0, 0), ColumnSurfaceResult.State.KNOWN_HEIGHT, 8, null);
    }

    @Test
    void rejectsDifferentColumnsAndUnsupportedOrReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ColumnSurfaceIndex(MAX_CUBE + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ColumnSurfaceIndex(0, MIN_CUBE - 1));
        ColumnSurfaceIndex index = new ColumnSurfaceIndex(-3, 4);
        int x = Coords.cubeToMinBlock(-3);
        int z = Coords.cubeToMinBlock(4);
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.ZERO, ChunkStatus.EMPTY, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> index.put(CubePos.of(-3, MAX_CUBE + 1, 4), ChunkStatus.EMPTY, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> index.query(SURFACE, x - 1, z, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(SURFACE, x, z + DIAMETER, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(SURFACE, x, z, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(SURFACE, x, z, MIN_CUBE - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> index.query(SURFACE, x, z, 0, MAX_CUBE + 1));
        assertThrows(IllegalArgumentException.class, () -> index.overlay(new ColumnSurfaceIndex(-3, 5)));
    }

    private static Map<Heightmap.Types, long[]> maps(int localX, int localZ, int offset) {
        Map<Heightmap.Types, long[]> maps = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            SimpleBitStorage data = storage();
            data.set(localX + localZ * DIAMETER, offset);
            maps.put(type, data.getRaw());
        }
        return maps;
    }

    private static SimpleBitStorage storage() {
        return new SimpleBitStorage(Integer.SIZE - Integer.numberOfLeadingZeros(DIAMETER), DIAMETER * DIAMETER);
    }

    private static void assertResult(ColumnSurfaceResult result, ColumnSurfaceResult.State state, Integer height, Integer unknown) {
        assertEquals(state, result.state());
        assertEquals(height == null ? OptionalInt.empty() : OptionalInt.of(height), result.highestKnownBlockY());
        assertEquals(unknown == null ? OptionalInt.empty() : OptionalInt.of(unknown), result.firstUnknownCubeY());
        if (state == ColumnSurfaceResult.State.UNKNOWN) {
            assertFalse(result.isComplete());
        } else {
            assertTrue(result.isComplete());
        }
    }
}
