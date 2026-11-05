package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.*;
import io.github.opencubicchunks.cubicchunks.platform.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class NeoForgeNetwork {
    private NeoForgeNetwork() {}

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.configurationToClient(CCClientboundWorldModePacket.TYPE, CCClientboundWorldModePacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundLevelCubeWithLightPacket.TYPE, CCClientboundLevelCubeWithLightPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeLightInvalidatePacket.TYPE, CCClientboundCubeLightInvalidatePacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeLightRevokePacket.TYPE, CCClientboundCubeLightRevokePacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeBlockChangesPacket.TYPE, CCClientboundCubeBlockChangesPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeOpenSignEditorPacket.TYPE, CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeBlockEntityDataPacket.TYPE, CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeBlockEventPacket.TYPE, CCClientboundCubeBlockEventPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundCubeLevelEventPacket.TYPE, CCClientboundCubeLevelEventPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundLevelChunkPacket.TYPE, CCClientboundLevelChunkPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundForgetLevelCloPacket.TYPE, CCClientboundForgetLevelCloPacket.STREAM_CODEC);
        registrar.playToClient(CCClientboundSetCubeCacheCenterPacket.TYPE, CCClientboundSetCubeCacheCenterPacket.STREAM_CODEC);
        registrar.playToServer(CCServerboundCubeLightRefreshPacket.TYPE, CCServerboundCubeLightRefreshPacket.STREAM_CODEC,
                (packet, context) -> new CCServerboundCubeLightRefreshPacket.Handler().receive(packet, (ServerPlayer) context.player()));
        registrar.playToServer(CCServerboundCubePlayerActionPacket.TYPE, CCServerboundCubePlayerActionPacket.STREAM_CODEC,
                (packet, context) -> new CCServerboundCubePlayerActionPacket.Handler().receive(packet, (ServerPlayer) context.player()));
        registrar.playToServer(CCServerboundCubeUseItemOnPacket.TYPE, CCServerboundCubeUseItemOnPacket.STREAM_CODEC,
                (packet, context) -> new CCServerboundCubeUseItemOnPacket.Handler().receive(packet, (ServerPlayer) context.player()));
        registrar.playToServer(CCServerboundCubeSignUpdatePacket.TYPE, CCServerboundCubeSignUpdatePacket.STREAM_CODEC,
                (packet, context) -> new CCServerboundCubeSignUpdatePacket.Handler().receive(packet, (ServerPlayer) context.player()));
        registrar.playToServer(CCServerboundCubeSetCommandBlockPacket.TYPE, CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC,
                (packet, context) -> new CCServerboundCubeSetCommandBlockPacket.Handler().receive(packet, (ServerPlayer) context.player()));
        registrar.playToServer(CCServerboundCubeSetStructureBlockPacket.TYPE, CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC,
                (packet, context) -> new CCServerboundCubeSetStructureBlockPacket.Handler().receive(packet, (ServerPlayer) context.player()));
    }

    static void configure(RegisterConfigurationTasksEvent event) {
        var listener = event.getListener();
        event.register(new ICustomConfigurationTask() {
            private static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("cubicchunks:world_mode");

            @Override public ConfigurationTask.Type type() {
                return TYPE;
            }

            @Override public void run(Consumer<CustomPacketPayload> sender) {
                boolean cubic = ((CanBeCubic) ServerLifecycleHooks.getCurrentServer().overworld()).cc_isCubic();
                if (listener.hasChannel(CCClientboundWorldModePacket.TYPE)) {
                    sender.accept(new CCClientboundWorldModePacket(cubic));
                } else if (cubic) {
                    listener.disconnect(Component.literal("This cubic world requires Cubic Chunks for Minecraft 26.3."));
                    return;
                }
                listener.finishCurrentTask(TYPE);
            }
        });
    }
}
