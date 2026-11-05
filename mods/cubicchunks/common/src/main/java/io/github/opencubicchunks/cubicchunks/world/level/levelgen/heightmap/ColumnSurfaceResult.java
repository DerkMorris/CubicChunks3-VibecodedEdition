package io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * A surface observation within the query's inclusive cube-Y bounds, using snapshots captured for that request.
 * It is not a promise about updates made after the request or about cubes outside those bounds.
 * firstUnknownCubeY is the highest missing or provisional cube encountered when scanning downward from the upper bound.
 */
public record ColumnSurfaceResult(State state, OptionalInt highestKnownBlockY, OptionalInt firstUnknownCubeY) {
    public ColumnSurfaceResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(highestKnownBlockY, "highestKnownBlockY");
        Objects.requireNonNull(firstUnknownCubeY, "firstUnknownCubeY");
        if ((state == State.UNKNOWN) != firstUnknownCubeY.isPresent()) {
            throw new IllegalArgumentException("Only UNKNOWN surfaces must identify an unknown cube");
        }
        if (state == State.KNOWN_HEIGHT && highestKnownBlockY.isEmpty()) {
            throw new IllegalArgumentException("KNOWN_HEIGHT requires a matching block");
        }
        if (state == State.KNOWN_EMPTY && highestKnownBlockY.isPresent()) {
            throw new IllegalArgumentException("KNOWN_EMPTY cannot contain a matching block");
        }
    }

    public boolean isComplete() {
        return this.state != State.UNKNOWN;
    }

    public enum State {
        KNOWN_HEIGHT, KNOWN_EMPTY, UNKNOWN
    }
}
