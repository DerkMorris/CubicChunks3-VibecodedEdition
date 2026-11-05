package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Objects;

import javax.annotation.Nullable;

/** Owner-thread scheduling status. Notification failures are independent from calculation failures and readiness. */
public record CubeLightStatus(
        Phase phase, @Nullable CubeLightBounds bounds, int consecutiveFailures, int retryInTicks, @Nullable String lastFailure,
        boolean notificationPending, @Nullable String notificationFailure
) {
    public CubeLightStatus {
        Objects.requireNonNull(phase, "phase");
        if (consecutiveFailures < 0 || retryInTicks < 0) {
            throw new IllegalArgumentException("Failure counts and retry delays cannot be negative");
        }
    }

    public enum Phase {
        IDLE, RUNNING, CURRENT, DIRTY, RETRY_WAIT, FAILED, CLOSED
    }
}
