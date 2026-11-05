package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(CubicChunks.MODID)
public final class CubicChunksNeoForge {
    public CubicChunksNeoForge(IEventBus bus) {
        new CubicChunks().initialize();
        NeoForgePlatform.SERIALIZERS.register(bus);
        bus.addListener(NeoForgeNetwork::register);
        bus.addListener(NeoForgeNetwork::configure);
    }
}
