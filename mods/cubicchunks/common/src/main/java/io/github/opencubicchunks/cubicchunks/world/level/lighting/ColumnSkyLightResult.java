package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * A bounded observation of direct skylight obstacles. CLEAR_WITHIN_RANGE does not establish access to the sky.
 * Blocking Y is the upper endpoint of an occluded edge. Unknown coverage is reported from the query's top downward.
 */
public record ColumnSkyLightResult(State state, OptionalInt highestKnownBlockingY, OptionalInt firstUnknownCubeY) {
    public ColumnSkyLightResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(highestKnownBlockingY, "highestKnownBlockingY");
        Objects.requireNonNull(firstUnknownCubeY, "firstUnknownCubeY");
        if ((state == State.UNKNOWN) != firstUnknownCubeY.isPresent()) {
            throw new IllegalArgumentException("Only UNKNOWN observations must identify an unknown cube");
        }
        if (state == State.KNOWN_BLOCKING_EDGE && highestKnownBlockingY.isEmpty()) {
            throw new IllegalArgumentException("KNOWN_BLOCKING_EDGE requires a blocking edge");
        }
        if (state == State.CLEAR_WITHIN_RANGE && highestKnownBlockingY.isPresent()) {
            throw new IllegalArgumentException("CLEAR_WITHIN_RANGE cannot contain a blocking edge");
        }
    }

    public boolean isComplete() {
        return this.state != State.UNKNOWN;
    }

    public enum State {
        KNOWN_BLOCKING_EDGE, CLEAR_WITHIN_RANGE, UNKNOWN
    }
}
