package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Sparse, owner-thread-confined skylight observations. Only FULL cubes establish coverage; absent cubes remain unknown. */
public final class ColumnSkyLightIndex {
    private final int cubeX;
    private final int cubeZ;
    private final NavigableMap<Integer, CubeSkyOcclusion> cubes = new TreeMap<>();

    public ColumnSkyLightIndex(int cubeX, int cubeZ) {
        CubeSkyOcclusion.validateCoordinate(cubeX);
        CubeSkyOcclusion.validateCoordinate(cubeZ);
        this.cubeX = cubeX;
        this.cubeZ = cubeZ;
    }

    /** Non-FULL observations shadow this Y as unknown without inspecting the supplied snapshot. */
    public void put(CubePos pos, ChunkStatus status, @Nullable CubeSkyOcclusion snapshot) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(status, "status");
        if (pos.getX() != this.cubeX || pos.getZ() != this.cubeZ) {
            throw new IllegalArgumentException("Cube is outside this horizontal column: " + pos);
        }
        CubeSkyOcclusion.validateCoordinate(pos.getY());
        if (status == ChunkStatus.FULL && !Objects.requireNonNull(snapshot, "snapshot").pos().equals(pos)) {
            throw new IllegalArgumentException("Skylight snapshot belongs to a different cube: " + snapshot.pos());
        }
        this.cubes.put(pos.getY(), status == ChunkStatus.FULL ? snapshot : null);
    }

    /** Snapshot objects are immutable; overlaying never shares a mutable index or live cube. */
    public void overlay(ColumnSkyLightIndex newer) {
        Objects.requireNonNull(newer, "newer");
        if (newer.cubeX != this.cubeX || newer.cubeZ != this.cubeZ) {
            throw new IllegalArgumentException("Cannot overlay a different horizontal cube column");
        }
        this.cubes.putAll(newer.cubes);
    }

    /** Examines only edges with both endpoints inside the inclusive whole-cube bounds. */
    public ColumnSkyLightResult query(int globalX, int globalZ, int minCubeY, int maxCubeY) {
        validateQuery(globalX, globalZ, minCubeY, maxCubeY);
        int x = Coords.blockToLocal(globalX);
        int z = Coords.blockToLocal(globalZ);
        Optional<Candidate> candidate = highestCandidate(x, z, minCubeY, maxCubeY);
        int floor = candidate.map(Candidate::cubeY).orElse(minCubeY);
        OptionalInt unknown = firstUnknown(floor, maxCubeY);
        OptionalInt edge = candidate.isPresent() ? OptionalInt.of(candidate.get().edgeY()) : OptionalInt.empty();
        ColumnSkyLightResult.State state = unknown.isPresent() ? ColumnSkyLightResult.State.UNKNOWN
                : edge.isPresent() ? ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE : ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE;
        return new ColumnSkyLightResult(state, edge, unknown);
    }

    private Optional<Candidate> highestCandidate(int x, int z, int minY, int maxY) {
        for (var entry : this.cubes.subMap(minY, true, maxY, true).descendingMap().entrySet()) {
            CubeSkyOcclusion upper = entry.getValue();
            if (upper == null) {
                continue;
            }
            int cubeY = entry.getKey();
            OptionalInt internal = upper.highestInternalEdgeY(x, z);
            if (internal.isPresent()) {
                return Optional.of(new Candidate(cubeY, internal.getAsInt()));
            }
            CubeSkyOcclusion lower = cubeY > minY ? this.cubes.get(cubeY - 1) : null;
            if (lower != null && CubeSkyOcclusion.isEdgeOccluded(upper.bottomState(x, z), lower.topState(x, z))) {
                return Optional.of(new Candidate(cubeY, upper.pos().minCubeY()));
            }
        }
        return Optional.empty();
    }

    private OptionalInt firstUnknown(int minY, int maxY) {
        int expected = maxY;
        for (var entry : this.cubes.subMap(minY, true, maxY, true).descendingMap().entrySet()) {
            int cubeY = entry.getKey();
            if (cubeY < expected) {
                return OptionalInt.of(expected);
            }
            if (entry.getValue() == null) {
                return OptionalInt.of(cubeY);
            }
            expected = cubeY - 1;
        }
        return expected >= minY ? OptionalInt.of(expected) : OptionalInt.empty();
    }

    private void validateQuery(int globalX, int globalZ, int minY, int maxY) {
        if (Coords.blockToCube(globalX) != this.cubeX || Coords.blockToCube(globalZ) != this.cubeZ) {
            throw new IllegalArgumentException("Block X/Z is outside this horizontal cube column");
        }
        CubeSkyOcclusion.validateCoordinate(minY);
        CubeSkyOcclusion.validateCoordinate(maxY);
        if (minY > maxY) {
            throw new IllegalArgumentException("Cube-Y query bounds are reversed");
        }
    }

    private record Candidate(int cubeY, int edgeY) {}
}
