package io.github.opencubicchunks.cubicchunks.platform.forge;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CubicChunks.MODID)
public final class CubicChunksForge {
    public CubicChunksForge(FMLJavaModLoadingContext context) {
        new CubicChunks().initialize();
        ForgePlatform.SERIALIZERS.register(context.getModBusGroup());
        ForgeNetwork.register();
        // Check channel availability after Forge's registration/version negotiation tasks.
        GatherLoginConfigurationTasksEvent.BUS.addListener(net.minecraftforge.eventbus.api.listener.Priority.LOWEST, ForgeNetwork::configure);
    }
}
