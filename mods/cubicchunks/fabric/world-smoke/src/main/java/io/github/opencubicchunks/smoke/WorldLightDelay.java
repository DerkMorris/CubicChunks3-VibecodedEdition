package io.github.opencubicchunks.smoke;

/** Development-only delay of queued client light application; never included in the mod JAR. */
public final class WorldLightDelay {
    private static long until;

    private WorldLightDelay() {}

    public static void arm() {
        until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    }

    public static boolean active() {
        return until != 0 && System.nanoTime() < until;
    }
}
