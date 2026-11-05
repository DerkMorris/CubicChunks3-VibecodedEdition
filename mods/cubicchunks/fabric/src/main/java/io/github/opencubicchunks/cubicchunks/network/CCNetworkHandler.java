package io.github.opencubicchunks.cubicchunks.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class CCNetworkHandler {
    private CCNetworkHandler() {}

    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(CCClientboundWorldModePacket.TYPE, CCClientboundWorldModePacket.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            boolean cubic = ((io.github.opencubicchunks.cubicchunks.CanBeCubic) server.overworld()).cc_isCubic();
            if (net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking.canSend(handler, CCClientboundWorldModePacket.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking.send(handler, new CCClientboundWorldModePacket(cubic));
            } else if (cubic) {
                handler.disconnect(net.minecraft.network.chat.Component.literal("This cubic world requires Cubic Chunks for Minecraft 26.3."));
            }
        });
        // Register codecs on both sides before the client initializer installs its receivers.
        // Keep client-only handlers out of this path so dedicated servers never load them.
        final var registrar = PayloadTypeRegistry.clientboundPlay();

        registrar.register(CCClientboundLevelCubeWithLightPacket.TYPE, CCClientboundLevelCubeWithLightPacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeLightInvalidatePacket.TYPE, CCClientboundCubeLightInvalidatePacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeLightRevokePacket.TYPE, CCClientboundCubeLightRevokePacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeBlockChangesPacket.TYPE, CCClientboundCubeBlockChangesPacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeOpenSignEditorPacket.TYPE, CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeBlockEntityDataPacket.TYPE, CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeBlockEventPacket.TYPE, CCClientboundCubeBlockEventPacket.STREAM_CODEC);
        registrar.register(CCClientboundCubeLevelEventPacket.TYPE, CCClientboundCubeLevelEventPacket.STREAM_CODEC);
        registrar.register(CCClientboundLevelChunkPacket.TYPE, CCClientboundLevelChunkPacket.STREAM_CODEC);
        registrar.register(CCClientboundForgetLevelCloPacket.TYPE, CCClientboundForgetLevelCloPacket.STREAM_CODEC);
        registrar.register(CCClientboundSetCubeCacheCenterPacket.TYPE, CCClientboundSetCubeCacheCenterPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CCServerboundCubeLightRefreshPacket.TYPE, CCServerboundCubeLightRefreshPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CCServerboundCubeLightRefreshPacket.TYPE,
                (packet, context) -> new CCServerboundCubeLightRefreshPacket.Handler().receive(packet, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(CCServerboundCubePlayerActionPacket.TYPE, CCServerboundCubePlayerActionPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CCServerboundCubeUseItemOnPacket.TYPE, CCServerboundCubeUseItemOnPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CCServerboundCubePlayerActionPacket.TYPE,
                (packet, context) -> new CCServerboundCubePlayerActionPacket.Handler().receive(packet, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(CCServerboundCubeUseItemOnPacket.TYPE,
                (packet, context) -> new CCServerboundCubeUseItemOnPacket.Handler().receive(packet, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(CCServerboundCubeSignUpdatePacket.TYPE, CCServerboundCubeSignUpdatePacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CCServerboundCubeSetCommandBlockPacket.TYPE,
                CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CCServerboundCubeSetStructureBlockPacket.TYPE,
                CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CCServerboundCubeSignUpdatePacket.TYPE,
                (packet, context) -> new CCServerboundCubeSignUpdatePacket.Handler().receive(packet, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(CCServerboundCubeSetCommandBlockPacket.TYPE,
                (packet, context) -> new CCServerboundCubeSetCommandBlockPacket.Handler().receive(packet, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(CCServerboundCubeSetStructureBlockPacket.TYPE,
                (packet, context) -> new CCServerboundCubeSetStructureBlockPacket.Handler().receive(packet, context.player()));
    }
}
