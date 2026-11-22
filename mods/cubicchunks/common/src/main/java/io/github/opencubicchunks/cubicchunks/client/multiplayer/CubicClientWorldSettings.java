package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/** Reads the connected world's settings, never the local new-world creation defaults. */
public final class CubicClientWorldSettings {
    private CubicClientWorldSettings() {}

    public static WorldHeightSettings heights(ClientLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension()) && ((CanBeCubic) level).cc_isCubic()
                ? CubicWorldMode.clientHeights : WorldHeightSettings.DEFAULT;
    }
}
