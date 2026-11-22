package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;

/** Absolute Overworld heights, saved with the world rather than read from client preferences. */
public record WorldHeightSettings(int cloudHeight, int deepslateStartY, int skyFadeStartY) {
    public static final int SKY_FADE_DISTANCE = 64;
    public static final WorldHeightSettings DEFAULT = new WorldHeightSettings(192, 8, 0);
    private static final Codec<Integer> HEIGHT = Codec.intRange(CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    public static final Codec<WorldHeightSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            HEIGHT.optionalFieldOf("cloud_height", DEFAULT.cloudHeight()).forGetter(WorldHeightSettings::cloudHeight),
            HEIGHT.optionalFieldOf("deepslate_start_y", DEFAULT.deepslateStartY()).forGetter(WorldHeightSettings::deepslateStartY),
            HEIGHT.optionalFieldOf("sky_fade_start_y", DEFAULT.skyFadeStartY()).forGetter(WorldHeightSettings::skyFadeStartY))
            .apply(instance, WorldHeightSettings::new));

    public WorldHeightSettings {
        if (!CubicBlockBounds.contains(cloudHeight) || !CubicBlockBounds.contains(deepslateStartY) || !CubicBlockBounds.contains(skyFadeStartY)) {
            throw new IllegalArgumentException("World heights exceed the cube coordinate range");
        }
    }
}
