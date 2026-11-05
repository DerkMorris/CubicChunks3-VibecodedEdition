package io.github.opencubicchunks.cubicchunks.world.level;

import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;

/** Persisted world mode; changing the new-world preference must never convert an existing save. */
public interface CubicWorldData {
    String FORMAT_KEY = "cubicchunks:world_format";
    String DEEP_CAVES_KEY = "cubicchunks:deep_caves_v1";
    int CURRENT_FORMAT = 1;

    boolean cc_isCubicWorld();

    DeepCaveSettings cc_deepCaveSettings();

    void cc_setDeepCaveSettings(DeepCaveSettings settings);

    void cc_setCubicWorld(boolean cubic);
}
