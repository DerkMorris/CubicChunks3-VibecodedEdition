package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Creation defaults are copied to level.dat; a saved world owns its generation settings. */
public record DeepCaveSettings(
        boolean enabled, int scale, int openness, int oreFrequency, Map<OreKind, OreRule> oreRules, Map<CaveParameter, HeightFormula> caveCurves
) {

    public static final int MIN_SCALE = 24;
    public static final int MAX_SCALE = 160;
    public static final int PERCENT = 100;
    public static final DeepCaveSettings CLASSIC = new DeepCaveSettings(false, 64, 45, 35);
    public static final Codec<DeepCaveSettings> CODEC = RecordCodecBuilder
            .create(instance -> instance.group(Codec.BOOL.fieldOf("enabled").forGetter(DeepCaveSettings::enabled),
                    Codec.intRange(MIN_SCALE, MAX_SCALE).fieldOf("scale").forGetter(DeepCaveSettings::scale),
                    Codec.intRange(0, PERCENT).fieldOf("openness").forGetter(DeepCaveSettings::openness),
                    Codec.intRange(0, PERCENT).fieldOf("ore_frequency").forGetter(DeepCaveSettings::oreFrequency),
                    Codec.unboundedMap(OreKind.CODEC, OreRule.CODEC).optionalFieldOf("ore_rules", Map.of()).forGetter(DeepCaveSettings::oreRules),
                    Codec.unboundedMap(CaveParameter.CODEC, HeightFormula.CODEC).optionalFieldOf("cave_curves", Map.of())
                            .forGetter(DeepCaveSettings::caveCurves))
                    .apply(instance, DeepCaveSettings::new));

    public DeepCaveSettings(boolean enabled, int scale, int openness, int oreFrequency) {
        this(enabled, scale, openness, oreFrequency, Map.of());
    }

    public DeepCaveSettings(boolean enabled, int scale, int openness, int oreFrequency, Map<OreKind, OreRule> oreRules) {
        this(enabled, scale, openness, oreFrequency, oreRules, Map.of());
    }

    public DeepCaveSettings {
        var ordered = new EnumMap<OreKind, OreRule>(OreKind.class);
        oreRules.forEach((kind, rule) -> ordered.put(kind, java.util.Objects.requireNonNull(rule)));
        oreRules = Collections.unmodifiableMap(ordered);
        caveCurves = Map.copyOf(caveCurves);
        if (scale < MIN_SCALE || scale > MAX_SCALE || openness < 0 || openness > PERCENT || oreFrequency < 0 || oreFrequency > PERCENT) {
            throw new IllegalArgumentException("Deep cave settings outside the supported range");
        }
    }
}
