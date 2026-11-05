package io.github.opencubicchunks.cubicchunks.platform.fabric;

import io.github.opencubicchunks.cubicchunks.client.gui.render.pip.WorldLoadingCubeStatusesRenderer;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeLightRefresh;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEntityDataPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEventPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLevelEventPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightInvalidatePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightRevokePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeOpenSignEditorPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundForgetLevelCloPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelChunkPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundSetCubeCacheCenterPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeLightRefreshPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;

/** Registers receivers only on a physical client, after common payload registration. */
@Environment(EnvType.CLIENT)
public final class CubicChunksFabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.INIT
                .register((handler, client) -> io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode.clientOverworld = false);
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
                io.github.opencubicchunks.cubicchunks.network.CCClientboundWorldModePacket.TYPE,
                (packet, context) -> io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode.clientOverworld = packet.cubicOverworld());
        new EntityDiagnostics().register();
        PictureInPictureRendererRegistry.register(context -> new WorldLoadingCubeStatusesRenderer());
        // Fabric invokes these handlers on the render thread, where client world access is safe.
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundLevelCubeWithLightPacket.TYPE,
                (packet, context) -> new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeBlockChangesPacket.TYPE,
                (packet, context) -> new CCClientboundCubeBlockChangesPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeBlockEventPacket.TYPE,
                (packet, context) -> new CCClientboundCubeBlockEventPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeLevelEventPacket.TYPE,
                (packet, context) -> new CCClientboundCubeLevelEventPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeOpenSignEditorPacket.TYPE,
                (packet, context) -> new CCClientboundCubeOpenSignEditorPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeBlockEntityDataPacket.TYPE,
                (packet, context) -> new CCClientboundCubeBlockEntityDataPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeLightInvalidatePacket.TYPE,
                (packet, context) -> new CCClientboundCubeLightInvalidatePacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundCubeLightRevokePacket.TYPE,
                (packet, context) -> new CCClientboundCubeLightRevokePacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundLevelChunkPacket.TYPE,
                (packet, context) -> new CCClientboundLevelChunkPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundForgetLevelCloPacket.TYPE,
                (packet, context) -> new CCClientboundForgetLevelCloPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientPlayNetworking.registerGlobalReceiver(CCClientboundSetCubeCacheCenterPacket.TYPE,
                (packet, context) -> new CCClientboundSetCubeCacheCenterPacket.Handler().receive(packet,
                        new io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext(context.client(), context.player())));
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientCubeLightRefresh.tick(client,
                () -> ClientPlayNetworking.canSend(CCServerboundCubeLightRefreshPacket.TYPE), ClientPlayNetworking::send));
    }
}
