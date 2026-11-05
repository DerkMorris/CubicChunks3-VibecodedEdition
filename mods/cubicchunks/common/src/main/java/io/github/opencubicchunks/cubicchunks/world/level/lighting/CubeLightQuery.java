package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Current bounded observations never turn missing coverage or a discarded computation into an exact light value. */
public record CubeLightQuery(State state, Optional<LightRange> range) {
    public CubeLightQuery {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(range, "range");
        if ((state == State.CURRENT) != range.isPresent()) {
            throw new IllegalArgumentException("Only CURRENT queries contain a light range");
        }
    }

    public boolean isReady() {
        return this.range.map(LightRange::isReady).orElse(false);
    }

    public static CubeLightQuery unavailable() {
        return new CubeLightQuery(State.UNAVAILABLE, Optional.empty());
    }

    public OptionalInt exactValue() {
        return this.range.map(LightRange::exactValue).orElseGet(OptionalInt::empty);
    }

    public enum State {
        CURRENT, STALE, UNAVAILABLE
    }
}
