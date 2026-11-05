package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;

public record OreRule(int minY, int maxY, int radius, HeightFormula formula) {

    public static final int MAX_RADIUS = 8;
    public static final String GROWTH = "clamp((-64 - y) * 40 / 936, 0, 40)";
    public static final OreRule DEFAULT = new OreRule(CubicBlockBounds.MIN, CubicBlockBounds.MAX, 3, HeightFormula.parse("5"));
    private static final Codec<Integer> HEIGHT = Codec.intRange(CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    public static final Codec<OreRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(HEIGHT.fieldOf("min_y").forGetter(OreRule::minY),
            HEIGHT.fieldOf("max_y").forGetter(OreRule::maxY), Codec.intRange(1, MAX_RADIUS).fieldOf("radius").forGetter(OreRule::radius),
            HeightFormula.CODEC.fieldOf("formula").forGetter(OreRule::formula)).apply(instance, OreRule::new));

    public OreRule {
        if (minY > maxY || !CubicBlockBounds.contains(minY) || !CubicBlockBounds.contains(maxY) || radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Invalid ore height interval or vein radius");
        }
        java.util.Objects.requireNonNull(formula);
    }

    public double percent(int y) {
        return y < minY || y > maxY ? 0 : formula.percent(y);
    }
}
