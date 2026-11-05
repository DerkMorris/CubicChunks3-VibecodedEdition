package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.OptionalInt;

import net.minecraft.world.level.lighting.LightEngine;

/** Guaranteed lower and upper light bounds for the captured inputs. Equal bounds are exact. */
public record LightRange(int lower, int upper) {
    public LightRange {
        if (lower < 0 || upper > LightEngine.MAX_LEVEL || lower > upper) {
            throw new IllegalArgumentException("Invalid light range: " + lower + ".." + upper);
        }
    }

    public boolean isReady() {
        return this.lower == this.upper;
    }

    public OptionalInt exactValue() {
        return isReady() ? OptionalInt.of(this.lower) : OptionalInt.empty();
    }
}
