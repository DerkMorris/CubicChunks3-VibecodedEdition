package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.Locale;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum CaveParameter implements StringRepresentable {
    SCALE(DeepCaveSettings.MIN_SCALE, DeepCaveSettings.MAX_SCALE), OPENNESS(0, DeepCaveSettings.PERCENT);

    public static final Codec<CaveParameter> CODEC = StringRepresentable.fromEnum(CaveParameter::values);
    public final int min;
    public final int max;

    CaveParameter(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public double sample(@Nullable HeightFormula formula, int slider, int y) {
        return formula == null ? slider : formula.bounded(y, min, max, slider);
    }

    @Override public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
