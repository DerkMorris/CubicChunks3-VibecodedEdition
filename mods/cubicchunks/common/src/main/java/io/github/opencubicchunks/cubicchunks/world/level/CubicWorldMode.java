package io.github.opencubicchunks.cubicchunks.world.level;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WritableLevelData;

public final class CubicWorldMode {
    /** Set during configuration, before the client constructs a level; reset for every new connection. */
    public static volatile boolean clientOverworld;

    private CubicWorldMode() {}

    public static boolean isCubic(WritableLevelData data, ResourceKey<Level> dimension, boolean client) {
        if (CubicChunks.IS_IN_TEST) {
            return CubicChunks.config().shouldGenerateNewWorldsAsCC();
        }
        return Level.OVERWORLD.equals(dimension) && (client ? clientOverworld : data instanceof CubicWorldData world && world.cc_isCubicWorld());
    }
}
