package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import javax.annotation.Nullable;

/** Transient network publication. Absence of a complete view explicitly revokes readiness rather than supplying zero light. */
public record CubeLightPacketSnapshot(long epoch, @Nullable CubeLightView light, boolean scoped) {
    public CubeLightPacketSnapshot(long epoch, @Nullable CubeLightView light) {
        this(epoch, light, false);
    }

    public CubeLightPacketSnapshot {
        if (epoch < 0 || light != null && !light.isReady()) {
            throw new IllegalArgumentException("Cube light packets require a nonnegative epoch and a wholly exact view");
        }
    }
}
