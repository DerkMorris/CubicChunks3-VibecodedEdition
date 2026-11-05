package io.github.opencubicchunks.cubicchunks.platform.forge;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldMode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraftforge.network.config.SimpleConfigurationTask;
import net.minecraftforge.server.ServerLifecycleHooks;

final class ForgeNetwork {
    static Channel<CustomPacketPayload> CHANNEL;
    // Installed only on physical clients. Server registration never resolves Minecraft client classes.
    static java.util.function.Consumer<CustomPacketPayload> clientHandler = packet -> {
        throw new IllegalStateException("Client handler not installed");
    };

    private ForgeNetwork() {}

    static void register() {
        var builder = ChannelBuilder.named("cubicchunks:main").networkProtocolVersion(1).optional().payloadChannel();
        var configuration = builder.protocol(NetworkProtocol.CONFIGURATION).clientbound();
        configuration.addMain(CCClientboundWorldModePacket.TYPE, CCClientboundWorldModePacket.STREAM_CODEC,
                (packet, context) -> CubicWorldMode.clientOverworld = packet.cubicOverworld());
        var toClient = builder.protocol(NetworkProtocol.PLAY).clientbound();
        toClient.addMain(CCClientboundLevelCubeWithLightPacket.TYPE, CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeLightInvalidatePacket.TYPE, CCClientboundCubeLightInvalidatePacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeLightRevokePacket.TYPE, CCClientboundCubeLightRevokePacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeBlockChangesPacket.TYPE, CCClientboundCubeBlockChangesPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeOpenSignEditorPacket.TYPE, CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeBlockEntityDataPacket.TYPE, CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeBlockEventPacket.TYPE, CCClientboundCubeBlockEventPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundCubeLevelEventPacket.TYPE, CCClientboundCubeLevelEventPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundLevelChunkPacket.TYPE, CCClientboundLevelChunkPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundForgetLevelCloPacket.TYPE, CCClientboundForgetLevelCloPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        toClient.addMain(CCClientboundSetCubeCacheCenterPacket.TYPE, CCClientboundSetCubeCacheCenterPacket.STREAM_CODEC.cast(),
                (packet, context) -> clientHandler.accept(packet));
        var toServer = builder.protocol(NetworkProtocol.PLAY).serverbound();
        toServer.addMain(CCServerboundCubeLightRefreshPacket.TYPE, CCServerboundCubeLightRefreshPacket.STREAM_CODEC.cast(),
                (packet, context) -> new CCServerboundCubeLightRefreshPacket.Handler().receive(packet, context.getSender()));
        toServer.addMain(CCServerboundCubePlayerActionPacket.TYPE, CCServerboundCubePlayerActionPacket.STREAM_CODEC.cast(),
                (packet, context) -> new CCServerboundCubePlayerActionPacket.Handler().receive(packet, context.getSender()));
        toServer.addMain(CCServerboundCubeUseItemOnPacket.TYPE, CCServerboundCubeUseItemOnPacket.STREAM_CODEC.cast(),
                (packet, context) -> new CCServerboundCubeUseItemOnPacket.Handler().receive(packet, context.getSender()));
        toServer.addMain(CCServerboundCubeSignUpdatePacket.TYPE, CCServerboundCubeSignUpdatePacket.STREAM_CODEC.cast(),
                (packet, context) -> new CCServerboundCubeSignUpdatePacket.Handler().receive(packet, context.getSender()));
        toServer.addMain(CCServerboundCubeSetCommandBlockPacket.TYPE, CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC.cast(),
                (packet, context) -> new CCServerboundCubeSetCommandBlockPacket.Handler().receive(packet, context.getSender()));
        toServer.addMain(CCServerboundCubeSetStructureBlockPacket.TYPE, CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC.cast(),
                (packet, context) -> new CCServerboundCubeSetStructureBlockPacket.Handler().receive(packet, context.getSender()));
        CHANNEL = toServer.build();
    }

    static void configure(GatherLoginConfigurationTasksEvent event) {
        event.addTask(new SimpleConfigurationTask(new ConfigurationTask.Type("cubicchunks:world_mode"), context -> {
            boolean cubic = ((CanBeCubic) ServerLifecycleHooks.getCurrentServer().overworld()).cc_isCubic();
            // Integrated-server connections share the mod set and skip Forge's remote channel negotiation.
            if (context.getConnection().isMemoryConnection() || CHANNEL.isRemotePresent(context.getConnection())) {
                CHANNEL.send(new CCClientboundWorldModePacket(cubic), context.getConnection());
            } else if (cubic) {
                ((net.minecraft.server.network.ServerConfigurationPacketListenerImpl) context.getConnection().getPacketListener())
                        .disconnect(Component.literal("This cubic world requires Cubic Chunks for Minecraft 26.3."));
            }
        }));
    }
}
