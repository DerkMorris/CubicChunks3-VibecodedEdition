package io.github.opencubicchunks.cubicchunks.platform.fabric;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.network.CCNetworkHandler;
import net.fabricmc.api.ModInitializer;

public final class CubicChunksFabric implements ModInitializer {
    @Override public void onInitialize() {
        new CubicChunks().initialize();
        CCNetworkHandler.register();
    }
}
