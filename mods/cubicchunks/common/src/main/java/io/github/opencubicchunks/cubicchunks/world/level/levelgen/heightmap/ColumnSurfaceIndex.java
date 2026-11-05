package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Sparse, detached observations for one horizontal cube column. Only FULL cubes establish coverage.
 * The caller merges persisted snapshots first and request-time live snapshots second; absent cubes remain unknown.
 * This index is confined to its owner thread and does not retain live cubes or perform disk I/O.
 */
public final class ColumnSurfaceIndex {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int COLUMN_COUNT = DIAMETER * DIAMETER;
    private static final int BITS = Integer.SIZE - Integer.numberOfLeadingZeros(DIAMETER);
    private static final Set<Heightmap.Types> ALL_TYPES = Set.of(Heightmap.Types.values());

    private final int cubeX;
    private final int cubeZ;
    private final NavigableMap<Integer, Map<Heightmap.Types, SimpleBitStorage>> cubes = new TreeMap<>();

    public ColumnSurfaceIndex(int cubeX, int cubeZ) {
        validateCubeCoordinate(cubeX);
        validateCubeCoordinate(cubeZ);
        this.cubeX = cubeX;
        this.cubeZ = cubeZ;
    }

    /** A non-FULL observation shadows this Y as unknown without inspecting its maps. */
    public void put(CubePos pos, ChunkStatus status, Map<Heightmap.Types, long[]> maps) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(status, "status");
        if (pos.getX() != this.cubeX || pos.getZ() != this.cubeZ) {
            throw new IllegalArgumentException("Cube is outside this horizontal column: " + pos);
        }
        validateCubeCoordinate(pos.getY());
        Map<Heightmap.Types, SimpleBitStorage> snapshot = status == ChunkStatus.FULL ? copyFullMaps(maps) : Map.of();
        this.cubes.put(pos.getY(), snapshot);
    }

    /** Overlays an independently captured index, including its provisional and explicitly empty observations. */
    public void overlay(ColumnSurfaceIndex newer) {
        Objects.requireNonNull(newer, "newer");
        if (newer.cubeX != this.cubeX || newer.cubeZ != this.cubeZ) {
            throw new IllegalArgumentException("Cannot overlay a different horizontal cube column");
        }
        newer.cubes.forEach((y, maps) -> {
            Map<Heightmap.Types, SimpleBitStorage> copy = new EnumMap<>(Heightmap.Types.class);
            maps.forEach((type, data) -> copy.put(type, new SimpleBitStorage(BITS, COLUMN_COUNT, data.getRaw().clone())));
            this.cubes.put(y, copy);
        });
    }

    /**
     * Finds a surface within whole-cube bounds. A positive offset is a candidate; offset zero contributes only coverage.
     * The scan visits stored observations rather than iterating over the possibly enormous missing vertical intervals.
     */
    public ColumnSurfaceResult query(Heightmap.Types type, int globalBlockX, int globalBlockZ, int minCubeY, int maxCubeY) {
        Objects.requireNonNull(type, "type");
        validateQuery(globalBlockX, globalBlockZ, minCubeY, maxCubeY);
        int column = Coords.blockToLocal(globalBlockX) + Coords.blockToLocal(globalBlockZ) * DIAMETER;
        Optional<Candidate> candidate = highestCandidate(type, column, minCubeY, maxCubeY);
        int coverageFloor = candidate.map(Candidate::cubeY).orElse(minCubeY);
        OptionalInt firstUnknown = firstUnknown(coverageFloor, maxCubeY);
        OptionalInt height = candidate.isPresent() ? OptionalInt.of(candidate.get().blockY()) : OptionalInt.empty();
        ColumnSurfaceResult.State state = firstUnknown.isPresent() ? ColumnSurfaceResult.State.UNKNOWN
                : height.isPresent() ? ColumnSurfaceResult.State.KNOWN_HEIGHT : ColumnSurfaceResult.State.KNOWN_EMPTY;
        return new ColumnSurfaceResult(state, height, firstUnknown);
    }

    private Optional<Candidate> highestCandidate(Heightmap.Types type, int column, int minCubeY, int maxCubeY) {
        for (var entry : this.cubes.subMap(minCubeY, true, maxCubeY, true).descendingMap().entrySet()) {
            SimpleBitStorage map = entry.getValue().get(type);
            if (map != null) {
                int offset = map.get(column);
                if (offset > 0) {
                    int cubeY = entry.getKey();
                    return Optional.of(new Candidate(cubeY, Coords.cubeToMinBlock(cubeY) + offset - 1));
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt firstUnknown(int minCubeY, int maxCubeY) {
        int expectedY = maxCubeY;
        for (var entry : this.cubes.subMap(minCubeY, true, maxCubeY, true).descendingMap().entrySet()) {
            int cubeY = entry.getKey();
            if (cubeY < expectedY) {
                return OptionalInt.of(expectedY);
            }
            if (entry.getValue().isEmpty()) {
                return OptionalInt.of(cubeY);
            }
            expectedY = cubeY - 1;
        }
        return expectedY >= minCubeY ? OptionalInt.of(expectedY) : OptionalInt.empty();
    }

    private void validateQuery(int globalBlockX, int globalBlockZ, int minCubeY, int maxCubeY) {
        if (Coords.blockToCube(globalBlockX) != this.cubeX || Coords.blockToCube(globalBlockZ) != this.cubeZ) {
            throw new IllegalArgumentException("Block X/Z is outside this horizontal cube column");
        }
        validateCubeCoordinate(minCubeY);
        validateCubeCoordinate(maxCubeY);
        if (minCubeY > maxCubeY) {
            throw new IllegalArgumentException("Cube-Y query bounds are reversed");
        }
    }

    private static Map<Heightmap.Types, SimpleBitStorage> copyFullMaps(Map<Heightmap.Types, long[]> maps) {
        Objects.requireNonNull(maps, "maps");
        if (!maps.keySet().equals(ALL_TYPES)) {
            throw new IllegalArgumentException("A FULL surface snapshot must contain every heightmap type");
        }
        Map<Heightmap.Types, SimpleBitStorage> result = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            long[] packed = Objects.requireNonNull(maps.get(type), type.getSerializationKey()).clone();
            CubeHeightmap.validateRawData(packed);
            result.put(type, new SimpleBitStorage(BITS, COLUMN_COUNT, packed));
        }
        return result;
    }

    private static void validateCubeCoordinate(int value) {
        if (value < -CubePos.MAX_COORDINATE_VALUE - 1 || value > CubePos.MAX_COORDINATE_VALUE) {
            throw new IllegalArgumentException("Cube coordinate exceeds the supported range: " + value);
        }
    }

    private record Candidate(int cubeY, int blockY) {}
}
